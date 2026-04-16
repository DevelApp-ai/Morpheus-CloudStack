package com.morpheusdata.cloudstack

import com.morpheusdata.core.CloudProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.ProvisionProvider as ProvisionProviderInterface
import com.morpheusdata.model.BackupProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkSubnetType
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ServerStatsData
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.cloudstack.sync.DiskOfferingSyncService
import com.morpheusdata.cloudstack.sync.NetworkSyncService
import com.morpheusdata.cloudstack.sync.ServicePlanSyncService
import com.morpheusdata.cloudstack.sync.VirtualImageSyncService
import com.morpheusdata.cloudstack.sync.VirtualMachineSyncService
import com.morpheusdata.cloudstack.sync.ZoneSyncService
import groovy.util.logging.Slf4j

@Slf4j
class CloudStackCloudProvider implements CloudProvider {

    Plugin plugin
    MorpheusContext morpheusContext
    CloudStackApiClient apiClient

    CloudStackCloudProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.plugin = plugin
        this.morpheusContext = morpheusContext
        this.apiClient = new CloudStackApiClient()
    }

    @Override
    String getCode() {
        return 'cloudstack'
    }

    @Override
    String getName() {
        return 'Apache CloudStack'
    }

    @Override
    String getDescription() {
        return 'Apache CloudStack cloud provider for Morpheus'
    }

    @Override
    Icon getIcon() {
        return new Icon(path: 'cloudstack.svg', darkPath: 'cloudstack-dark.svg')
    }

    @Override
    Icon getCircularIcon() {
        return new Icon(path: 'cloudstack-circular.svg', darkPath: 'cloudstack-circular-dark.svg')
    }

    @Override
    String getDefaultProvisionTypeCode() {
        return 'cloudstack-provision'
    }

    @Override
    Boolean hasNetworks() {
        return true
    }

    @Override
    Boolean hasFolders() {
        return false
    }

    @Override
    Boolean hasDatastores() {
        return false
    }

    @Override
    Boolean hasBareMetal() {
        return false
    }

    @Override
    Boolean hasCloudInit() {
        return false
    }

    @Override
    Boolean supportsDistributedWorker() {
        return false
    }

    @Override
    Boolean hasComputeZonePools() {
        return false
    }

    @Override
    Boolean canCreateCloudPools() {
        return false
    }

    @Override
    Boolean canCreateNetworks() {
        return false
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
    Collection<OptionType> getOptionTypes() {
        def options = []

        options << new OptionType(
            name: 'API URL',
            code: 'cloudstack-api-url',
            fieldName: 'apiUrl',
            displayOrder: 0,
            fieldContext: 'config',
            fieldLabel: 'API URL',
            helpText: 'CloudStack API endpoint URL (e.g. http://cloudstack-host/client/api)',
            required: true,
            inputType: OptionType.InputType.TEXT
        )

        options << new OptionType(
            name: 'API Key',
            code: 'cloudstack-api-key',
            fieldName: 'apiKey',
            displayOrder: 1,
            fieldContext: 'config',
            fieldLabel: 'API Key',
            required: true,
            inputType: OptionType.InputType.TEXT
        )

        options << new OptionType(
            name: 'Secret Key',
            code: 'cloudstack-secret-key',
            fieldName: 'secretKey',
            displayOrder: 2,
            fieldContext: 'config',
            fieldLabel: 'Secret Key',
            required: true,
            inputType: OptionType.InputType.PASSWORD
        )

        options << new OptionType(
            name: 'Domain ID',
            code: 'cloudstack-domain-id',
            fieldName: 'domainId',
            displayOrder: 3,
            fieldContext: 'config',
            fieldLabel: 'Domain ID',
            helpText: 'Optional CloudStack Domain ID to scope resources',
            required: false,
            inputType: OptionType.InputType.TEXT
        )

        return options
    }

    @Override
    Collection<ComputeServerType> getComputeServerTypes() {
        return [
            new ComputeServerType(
                code: 'cloudstack-vm',
                name: 'CloudStack VM',
                description: 'CloudStack virtual machine',
                platform: 'linux',
                nodeType: 'morpheus-vm',
                managed: true,
                reconfigureSupported: true,
                hasAutomation: true,
                vmHypervisor: false,
                controlPower: true,
                controlSuspend: false,
                enabled: true,
                provisionTypeCode: 'cloudstack-provision'
            )
        ]
    }

    @Override
    Collection<NetworkType> getNetworkTypes() {
        return [
            new NetworkType(
                code: 'cloudstack-isolated',
                name: 'CloudStack Isolated Network',
                description: 'CloudStack isolated network',
                overlay: false,
                creatable: false,
                nameEditable: false
            ),
            new NetworkType(
                code: 'cloudstack-shared',
                name: 'CloudStack Shared Network',
                description: 'CloudStack shared network',
                overlay: false,
                creatable: false,
                nameEditable: false
            )
        ]
    }

    @Override
    Collection<NetworkSubnetType> getSubnetTypes() {
        return []
    }

    @Override
    Collection<StorageVolumeType> getStorageVolumeTypes() {
        return [
            new StorageVolumeType(
                code: 'cloudstack-disk',
                name: 'CloudStack Disk',
                description: 'CloudStack disk volume',
                displayOrder: 0,
                defaultType: true,
                enabled: true,
                resizable: true
            )
        ]
    }

    @Override
    Collection<StorageControllerType> getStorageControllerTypes() {
        return []
    }

    @Override
    Collection<BackupProvider> getAvailableBackupProviders() {
        return []
    }

    @Override
    Collection<ProvisionProviderInterface> getAvailableProvisionProviders() {
        return [new CloudStackProvisionProvider(this.plugin, this.morpheusContext)]
    }

    @Override
    ProvisionProviderInterface getProvisionProvider(String providerCode) {
        return getAvailableProvisionProviders().find { it.code == providerCode }
    }

    @Override
    ServiceResponse validate(Cloud cloud, ValidateCloudRequest validateCloudRequest) {
        log.debug("Validating CloudStack cloud: ${cloud?.name}")
        try {
            def config = cloud.configMap
            if (!config?.apiUrl) {
                return ServiceResponse.error('API URL is required')
            }
            if (!config?.apiKey) {
                return ServiceResponse.error('API Key is required')
            }
            if (!config?.secretKey) {
                return ServiceResponse.error('Secret Key is required')
            }
            def result = apiClient.listZones(config.apiUrl, config.apiKey, config.secretKey, [:])
            if (result.success) {
                return ServiceResponse.success()
            } else {
                return ServiceResponse.error("Unable to connect to CloudStack API: ${result.error}")
            }
        } catch (Exception e) {
            log.error("Error validating CloudStack cloud: ${e.message}", e)
            return ServiceResponse.error("Validation failed: ${e.message}")
        }
    }

    @Override
    ServiceResponse initializeCloud(Cloud cloud) {
        log.debug("Initializing CloudStack cloud: ${cloud?.name}")
        try {
            refreshDaily(cloud)
            return ServiceResponse.success()
        } catch (Exception e) {
            log.error("Error initializing CloudStack cloud: ${e.message}", e)
            return ServiceResponse.error("Initialization failed: ${e.message}")
        }
    }

    @Override
    ServiceResponse refresh(Cloud cloud) {
        log.debug("Refreshing CloudStack cloud: ${cloud?.name}")
        try {
            new NetworkSyncService(morpheusContext, apiClient, cloud).execute()
            new VirtualMachineSyncService(morpheusContext, apiClient, cloud).execute()
            return ServiceResponse.success()
        } catch (Exception e) {
            log.error("Error refreshing CloudStack cloud: ${e.message}", e)
            return ServiceResponse.error("Refresh failed: ${e.message}")
        }
    }

    @Override
    void refreshDaily(Cloud cloud) {
        log.debug("Daily refresh of CloudStack cloud: ${cloud?.name}")
        try {
            new ZoneSyncService(morpheusContext, apiClient, cloud).execute()
            new ServicePlanSyncService(morpheusContext, apiClient, cloud).execute()
            new VirtualImageSyncService(morpheusContext, apiClient, cloud).execute()
            new DiskOfferingSyncService(morpheusContext, apiClient, cloud).execute()
        } catch (Exception e) {
            log.error("Error in daily refresh of CloudStack cloud: ${e.message}", e)
        }
    }

    @Override
    ServiceResponse deleteCloud(Cloud cloud) {
        log.debug("Deleting CloudStack cloud: ${cloud?.name}")
        return ServiceResponse.success()
    }

    ServiceResponse getServerDetails(ComputeServer computeServer) {
        log.debug("Getting server details for: ${computeServer?.externalId}")
        try {
            def cloud = computeServer.cloud
            def config = cloud.configMap
            def domainParams = config.domainId ? [domainid: config.domainId] : [:]
            def result = apiClient.listVirtualMachines(
                config.apiUrl, config.apiKey, config.secretKey,
                [id: computeServer.externalId] + domainParams
            )
            if (result.success && result.data?.listvirtualmachinesresponse?.virtualmachine) {
                return ServiceResponse.success(result.data.listvirtualmachinesresponse.virtualmachine[0])
            }
            return ServiceResponse.error("VM not found: ${computeServer.externalId}")
        } catch (Exception e) {
            log.error("Error getting server details: ${e.message}", e)
            return ServiceResponse.error("Failed to get server details: ${e.message}")
        }
    }

    @Override
    List<ServerStatsData> getServerStats(ComputeServer computeServer, Map<String, Object> opts) {
        log.debug("Getting server stats for: ${computeServer?.externalId}")
        try {
            def cloud = computeServer.cloud
            def config = cloud.configMap
            def domainParams = config.domainId ? [domainid: config.domainId] : [:]
            def result = apiClient.listVirtualMachinesMetrics(
                config.apiUrl, config.apiKey, config.secretKey,
                [id: computeServer.externalId] + domainParams
            )
            if (result.success && result.data?.listvirtualmachinesmetricsresponse?.virtualmachine) {
                def vm = result.data.listvirtualmachinesmetricsresponse.virtualmachine[0]
                def stats = new ServerStatsData(
                    maxMemory : computeServer.maxMemory,
                    usedMemory: vm.memorykbs ? (vm.memorykbs as Long) * 1024L : 0L,
                    freeMemory: computeServer.maxMemory ? computeServer.maxMemory - (vm.memorykbs ? (vm.memorykbs as Long) * 1024L : 0L) : 0L,
                    cpuUsage  : vm.cpuused ? (vm.cpuused as String).replace('%', '').toFloat() / 100.0f : 0.0f,
                    running   : vm.state?.toLowerCase() == 'running',
                    date      : new Date()
                )
                return [stats]
            }
            return []
        } catch (Exception e) {
            log.error("Error getting server stats: ${e.message}", e)
            return []
        }
    }

    @Override
    ServiceResponse startServer(ComputeServer computeServer) {
        log.debug("Starting server: ${computeServer?.externalId}")
        try {
            def cloud = computeServer.cloud
            def config = cloud.configMap
            def result = apiClient.startVirtualMachine(
                config.apiUrl, config.apiKey, config.secretKey,
                [id: computeServer.externalId]
            )
            return result.success ? ServiceResponse.success() : ServiceResponse.error("Failed to start server: ${result.error}")
        } catch (Exception e) {
            log.error("Error starting server: ${e.message}", e)
            return ServiceResponse.error("Start failed: ${e.message}")
        }
    }

    @Override
    ServiceResponse stopServer(ComputeServer computeServer) {
        log.debug("Stopping server: ${computeServer?.externalId}")
        try {
            def cloud = computeServer.cloud
            def config = cloud.configMap
            def result = apiClient.stopVirtualMachine(
                config.apiUrl, config.apiKey, config.secretKey,
                [id: computeServer.externalId]
            )
            return result.success ? ServiceResponse.success() : ServiceResponse.error("Failed to stop server: ${result.error}")
        } catch (Exception e) {
            log.error("Error stopping server: ${e.message}", e)
            return ServiceResponse.error("Stop failed: ${e.message}")
        }
    }

    @Override
    ServiceResponse deleteServer(ComputeServer computeServer) {
        log.debug("Deleting server: ${computeServer?.externalId}")
        try {
            def cloud = computeServer.cloud
            def config = cloud.configMap
            def result = apiClient.destroyVirtualMachine(
                config.apiUrl, config.apiKey, config.secretKey,
                [id: computeServer.externalId]
            )
            return result.success ? ServiceResponse.success() : ServiceResponse.error("Failed to delete server: ${result.error}")
        } catch (Exception e) {
            log.error("Error deleting server: ${e.message}", e)
            return ServiceResponse.error("Delete failed: ${e.message}")
        }
    }
}

