package com.morpheusdata.cloudstack

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Workload
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class CloudStackProvisionProvider implements WorkloadProvisionProvider {

    Plugin plugin
    MorpheusContext morpheusContext
    CloudStackApiClient apiClient

    private static final int ASYNC_MAX_RETRIES = 60
    private static final long ASYNC_INITIAL_DELAY_MS = 2000L
    private static final long ASYNC_MAX_DELAY_MS = 30000L

    CloudStackProvisionProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.plugin = plugin
        this.morpheusContext = morpheusContext
        this.apiClient = new CloudStackApiClient()
    }

    @Override
    String getCode() {
        return 'cloudstack-provision'
    }

    @Override
    String getName() {
        return 'Apache CloudStack'
    }

    @Override
    MorpheusContext getMorpheus() {
        return this.morpheusContext
    }

    @Override
    Plugin getPlugin() {
        return this.plugin
    }

    @Override
    Boolean hasDatastores() {
        return false
    }

    @Override
    Boolean hasNetworks() {
        return true
    }

    @Override
    Boolean hasPlanTagMatch() {
        return false
    }

    @Override
    Integer getMaxNetworks() {
        return 1
    }

    @Override
    Collection<OptionType> getOptionTypes() {
        def options = []

        options << new OptionType(
            name: 'Zone',
            code: 'cloudstack-provision-zone',
            fieldName: 'zoneId',
            displayOrder: 0,
            fieldContext: 'config',
            fieldLabel: 'Zone',
            required: true,
            inputType: OptionType.InputType.SELECT,
            optionSource: 'cloudstackZones'
        )

        options << new OptionType(
            name: 'Network',
            code: 'cloudstack-provision-network',
            fieldName: 'networkId',
            displayOrder: 1,
            fieldContext: 'config',
            fieldLabel: 'Network',
            required: true,
            inputType: OptionType.InputType.SELECT,
            optionSource: 'cloudstackNetworks'
        )

        options << new OptionType(
            name: 'Template',
            code: 'cloudstack-provision-template',
            fieldName: 'templateId',
            displayOrder: 2,
            fieldContext: 'config',
            fieldLabel: 'Template',
            required: true,
            inputType: OptionType.InputType.SELECT,
            optionSource: 'cloudstackTemplates'
        )

        options << new OptionType(
            name: 'Service Offering',
            code: 'cloudstack-provision-service-offering',
            fieldName: 'serviceOfferingId',
            displayOrder: 3,
            fieldContext: 'config',
            fieldLabel: 'Service Offering',
            required: true,
            inputType: OptionType.InputType.SELECT,
            optionSource: 'cloudstackServiceOfferings'
        )

        options << new OptionType(
            name: 'Keypair',
            code: 'cloudstack-provision-keypair',
            fieldName: 'keypair',
            displayOrder: 4,
            fieldContext: 'config',
            fieldLabel: 'SSH Keypair',
            required: false,
            inputType: OptionType.InputType.TEXT
        )

        return options
    }

    @Override
    Collection<OptionType> getNodeOptionTypes() {
        return []
    }

    @Override
    ServiceResponse validateWorkload(Map opts) {
        return ServiceResponse.success()
    }

    @Override
    ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
        log.debug("Deploying VM via CloudStack for workload: ${workload?.id}")
        def provisionResponse = new ProvisionResponse()
        try {
            ComputeServer server = workload.server
            Cloud cloud = server.cloud
            def config = cloud.configMap
            def serverConfig = server.configMap

            def deployParams = [
                serviceofferingid: serverConfig?.serviceOfferingId,
                templateid       : serverConfig?.templateId,
                zoneid           : serverConfig?.zoneId,
                name             : server.name,
                displayname      : server.name
            ]

            // Scope to domain when configured
            if (config.domainId) {
                deployParams.domainid = config.domainId
            }

            if (serverConfig?.networkId) {
                deployParams.networkids = serverConfig.networkId
            }

            if (serverConfig?.keypair) {
                deployParams.keypair = serverConfig.keypair
            }

            // Inject cloud-init / user-data (Base64-encoded, max 32 KB per CloudStack spec)
            def userData = workloadRequest?.cloudConfigUser ?: opts?.userdata
            if (userData) {
                def encoded = Base64.encoder.encodeToString(userData.toString().getBytes('UTF-8'))
                deployParams.userdata = encoded
            }

            def result = apiClient.deployVirtualMachine(config.apiUrl, config.apiKey, config.secretKey, deployParams)
            if (!result.success) {
                provisionResponse.success = false
                provisionResponse.message = "Failed to deploy VM: ${result.error}"
                return ServiceResponse.error(provisionResponse.message, null, provisionResponse)
            }

            def jobId = result.data?.deployvirtualmachineresponse?.jobid
            if (jobId) {
                def jobResult = pollAsyncJobWithBackoff(config.apiUrl, config.apiKey, config.secretKey, jobId)
                if (jobResult.success) {
                    def vmData = jobResult.data?.jobresult?.virtualmachine
                    provisionResponse.success = true
                    provisionResponse.externalId = vmData?.id
                    provisionResponse.publicIp = vmData?.nic?.find { it.isdefault }?.ipaddress
                    provisionResponse.hostname = vmData?.name

                    // Apply Morpheus tags to the provisioned VM
                    if (vmData?.id) {
                        applyTags(config, vmData.id, server.name, opts?.tags as Map)
                    }
                } else {
                    provisionResponse.success = false
                    provisionResponse.message = "VM deployment job failed: ${jobResult.error}"
                    return ServiceResponse.error(provisionResponse.message, null, provisionResponse)
                }
            } else {
                def vmData = result.data?.deployvirtualmachineresponse
                provisionResponse.success = true
                provisionResponse.externalId = vmData?.id
            }
            return ServiceResponse.success(provisionResponse)
        } catch (Exception e) {
            log.error("Error deploying VM: ${e.message}", e)
            provisionResponse.success = false
            provisionResponse.message = "Deployment failed: ${e.message}"
            return ServiceResponse.error(e.message, null, provisionResponse)
        }
    }

    @Override
    ServiceResponse finalizeWorkload(Workload workload) {
        return ServiceResponse.success()
    }

    @Override
    ServiceResponse stopWorkload(Workload workload) {
        log.debug("Stopping VM for workload: ${workload?.id}")
        try {
            ComputeServer server = workload.server
            Cloud cloud = server.cloud
            def config = cloud.configMap
            def result = apiClient.stopVirtualMachine(config.apiUrl, config.apiKey, config.secretKey, [id: server.externalId])
            if (!result.success) {
                return ServiceResponse.error("Failed to stop VM: ${result.error}")
            }
            def jobId = result.data?.stopvirtualmachineresponse?.jobid
            if (jobId) {
                def jobResult = pollAsyncJobWithBackoff(config.apiUrl, config.apiKey, config.secretKey, jobId)
                return jobResult.success ? ServiceResponse.success() : ServiceResponse.error("Stop job failed: ${jobResult.error}")
            }
            return ServiceResponse.success()
        } catch (Exception e) {
            log.error("Error stopping VM: ${e.message}", e)
            return ServiceResponse.error("Stop failed: ${e.message}")
        }
    }

    @Override
    ServiceResponse startWorkload(Workload workload) {
        log.debug("Starting VM for workload: ${workload?.id}")
        try {
            ComputeServer server = workload.server
            Cloud cloud = server.cloud
            def config = cloud.configMap
            def result = apiClient.startVirtualMachine(config.apiUrl, config.apiKey, config.secretKey, [id: server.externalId])
            if (!result.success) {
                return ServiceResponse.error("Failed to start VM: ${result.error}")
            }
            def jobId = result.data?.startvirtualmachineresponse?.jobid
            if (jobId) {
                def jobResult = pollAsyncJobWithBackoff(config.apiUrl, config.apiKey, config.secretKey, jobId)
                return jobResult.success ? ServiceResponse.success() : ServiceResponse.error("Start job failed: ${jobResult.error}")
            }
            return ServiceResponse.success()
        } catch (Exception e) {
            log.error("Error starting VM: ${e.message}", e)
            return ServiceResponse.error("Start failed: ${e.message}")
        }
    }

    @Override
    ServiceResponse restartWorkload(Workload workload) {
        def stopResult = stopWorkload(workload)
        if (!stopResult.success) return stopResult
        return startWorkload(workload)
    }

    @Override
    ServiceResponse removeWorkload(Workload workload, Map opts) {
        log.debug("Destroying VM for workload: ${workload?.id}")
        try {
            ComputeServer server = workload.server
            Cloud cloud = server.cloud
            def config = cloud.configMap
            def result = apiClient.destroyVirtualMachine(config.apiUrl, config.apiKey, config.secretKey, [id: server.externalId])
            if (!result.success) {
                return ServiceResponse.error("Failed to destroy VM: ${result.error}")
            }
            def jobId = result.data?.destroyvirtualmachineresponse?.jobid
            if (jobId) {
                def jobResult = pollAsyncJobWithBackoff(config.apiUrl, config.apiKey, config.secretKey, jobId)
                return jobResult.success ? ServiceResponse.success() : ServiceResponse.error("Destroy job failed: ${jobResult.error}")
            }
            return ServiceResponse.success()
        } catch (Exception e) {
            log.error("Error destroying VM: ${e.message}", e)
            return ServiceResponse.error("Delete failed: ${e.message}")
        }
    }

    @Override
    ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
        log.debug("Getting server details for: ${server?.externalId}")
        try {
            Cloud cloud = server.cloud
            def config = cloud.configMap
            def domainParams = config.domainId ? [domainid: config.domainId] : [:]
            def result = apiClient.listVirtualMachines(
                config.apiUrl, config.apiKey, config.secretKey,
                [id: server.externalId] + domainParams
            )
            if (result.success && result.data?.listvirtualmachinesresponse?.virtualmachine) {
                def vm = result.data.listvirtualmachinesresponse.virtualmachine[0]
                def provisionResponse = new ProvisionResponse(success: true, externalId: vm.id, hostname: vm.name)
                return ServiceResponse.success(provisionResponse)
            }
            return ServiceResponse.error("VM not found: ${server.externalId}")
        } catch (Exception e) {
            log.error("Error getting server details: ${e.message}", e)
            return ServiceResponse.error("Failed to get server details: ${e.message}")
        }
    }

    @Override
    ServiceResponse createWorkloadResources(Workload workload, Map opts) {
        return ServiceResponse.success()
    }

    @Override
    ServiceResponse stopServer(ComputeServer computeServer) {
        log.debug("Stopping server: ${computeServer?.externalId}")
        try {
            Cloud cloud = computeServer.cloud
            def config = cloud.configMap
            def result = apiClient.stopVirtualMachine(
                config.apiUrl, config.apiKey, config.secretKey,
                [id: computeServer.externalId]
            )
            return result.success ? ServiceResponse.success() : ServiceResponse.error("Failed to stop: ${result.error}")
        } catch (Exception e) {
            log.error("Error stopping server: ${e.message}", e)
            return ServiceResponse.error("Stop failed: ${e.message}")
        }
    }

    @Override
    ServiceResponse startServer(ComputeServer computeServer) {
        log.debug("Starting server: ${computeServer?.externalId}")
        try {
            Cloud cloud = computeServer.cloud
            def config = cloud.configMap
            def result = apiClient.startVirtualMachine(
                config.apiUrl, config.apiKey, config.secretKey,
                [id: computeServer.externalId]
            )
            return result.success ? ServiceResponse.success() : ServiceResponse.error("Failed to start: ${result.error}")
        } catch (Exception e) {
            log.error("Error starting server: ${e.message}", e)
            return ServiceResponse.error("Start failed: ${e.message}")
        }
    }

    /**
     * Inject Morpheus tags into CloudStack as resource tags on the VM.
     */
    private void applyTags(Map config, String vmId, String vmName, Map tags) {
        try {
            def allTags = [morpheus_name: vmName] + (tags ?: [:])
            if (!allTags) return
            def tagParams = [
                resourceids : vmId,
                resourcetype: 'UserVm',
            ]
            allTags.eachWithIndex { entry, idx ->
                tagParams["tags[${idx}].key"]   = entry.key.toString()
                tagParams["tags[${idx}].value"] = entry.value.toString()
            }
            if (config.domainId) {
                tagParams.domainid = config.domainId
            }
            def result = apiClient.createTags(config.apiUrl, config.apiKey, config.secretKey, tagParams)
            if (!result.success) {
                log.warn("Failed to apply tags to VM ${vmId}: ${result.error}")
            }
        } catch (Exception e) {
            log.warn("Error applying tags to VM ${vmId}: ${e.message}", e)
        }
    }

    /**
     * Poll an async CloudStack job with exponential backoff.
     * jobstatus: 0 = PENDING, 1 = SUCCESS, 2 = ERROR
     */
    private Map pollAsyncJobWithBackoff(String apiUrl, String apiKey, String secretKey, String jobId) {
        long delay = ASYNC_INITIAL_DELAY_MS
        int attempts = 0
        while (attempts < ASYNC_MAX_RETRIES) {
            def result = apiClient.queryAsyncJobResult(apiUrl, apiKey, secretKey, jobId)
            if (!result.success) {
                return [success: false, error: result.error]
            }
            def jobStatus = result.data?.queryasyncjobresultresponse?.jobstatus
            if (jobStatus == 1) {
                return [success: true, data: result.data?.queryasyncjobresultresponse]
            } else if (jobStatus == 2) {
                def errorText = result.data?.queryasyncjobresultresponse?.jobresult?.errortext ?: 'Job failed'
                return [success: false, error: errorText]
            }
            log.debug("Async job ${jobId} still pending (attempt ${attempts + 1}/${ASYNC_MAX_RETRIES}), waiting ${delay}ms")
            Thread.sleep(delay)
            // Exponential backoff with jitter, capped at max delay
            delay = Math.min((long)(delay * 1.5 + (Math.random() * 1000).toLong()), ASYNC_MAX_DELAY_MS)
            attempts++
        }
        return [success: false, error: "Async job ${jobId} timed out after ${ASYNC_MAX_RETRIES} attempts"]
    }
}
