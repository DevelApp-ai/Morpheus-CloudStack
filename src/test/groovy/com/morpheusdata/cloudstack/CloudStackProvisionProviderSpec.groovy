package com.morpheusdata.cloudstack

import spock.lang.Specification
import spock.lang.Subject

class CloudStackProvisionProviderSpec extends Specification {

    def mockPlugin = Stub(com.morpheusdata.core.Plugin) {
        getCode() >> 'cloudstack'
        getName() >> 'Apache CloudStack'
    }
    def mockMorpheus = Mock(com.morpheusdata.core.MorpheusContext)

    @Subject
    CloudStackProvisionProvider provider = new CloudStackProvisionProvider(mockPlugin, mockMorpheus)

    def "getCode returns cloudstack-provision"() {
        expect:
        provider.getCode() == 'cloudstack-provision'
    }

    def "getName returns Apache CloudStack"() {
        expect:
        provider.getName() == 'Apache CloudStack'
    }

    def "hasNetworks returns true"() {
        expect:
        provider.hasNetworks() == true
    }

    def "hasDatastores returns false"() {
        expect:
        provider.hasDatastores() == false
    }

    def "getOptionTypes contains zone, network, template, serviceOffering, keypair"() {
        when:
        def options = provider.getOptionTypes()
        def fieldNames = options*.fieldName

        then:
        fieldNames.contains('zoneId')
        fieldNames.contains('networkId')
        fieldNames.contains('templateId')
        fieldNames.contains('serviceOfferingId')
        fieldNames.contains('keypair')
    }

    def "getOptionTypes zone uses cloudstackZones optionSource"() {
        when:
        def zoneOption = provider.getOptionTypes().find { it.fieldName == 'zoneId' }

        then:
        zoneOption.optionSource == 'cloudstackZones'
        zoneOption.required == true
    }

    def "userdata is Base64-encoded before being sent to CloudStack"() {
        given:
        def capturedParams = null
        def mockApiClient = Spy(CloudStackApiClient) {
            deployVirtualMachine(_, _, _, _) >> { String url, String key, String secret, Map params ->
                capturedParams = params
                return [success: true, data: [deployvirtualmachineresponse: [jobid: null, id: 'vm-1']]]
            }
        }
        provider.apiClient = mockApiClient

        def cloud = Stub(com.morpheusdata.model.Cloud) {
            getConfigMap() >> [apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret', domainId: null]
        }
        def server = Stub(com.morpheusdata.model.ComputeServer) {
            getCloud() >> cloud
            getName() >> 'test-vm'
            getConfigMap() >> [serviceOfferingId: 'svc-1', templateId: 'tpl-1', zoneId: 'zone-1', networkId: 'net-1']
        }
        def workload = Stub(com.morpheusdata.model.Workload) {
            getServer() >> server
        }
        def workloadRequest = new com.morpheusdata.model.provisioning.WorkloadRequest()
        workloadRequest.cloudConfigUser = '#!/bin/bash\necho hello'

        when:
        provider.runWorkload(workload, workloadRequest, [:])

        then:
        capturedParams?.userdata != null
        new String(Base64.decoder.decode(capturedParams.userdata as String), 'UTF-8') == '#!/bin/bash\necho hello'
    }

    def "domainId is included in deployVirtualMachine params when cloud has domainId"() {
        given:
        def capturedParams = null
        def mockApiClient = Spy(CloudStackApiClient) {
            deployVirtualMachine(_, _, _, _) >> { String url, String key, String secret, Map params ->
                capturedParams = params
                return [success: true, data: [deployvirtualmachineresponse: [jobid: null, id: 'vm-1']]]
            }
        }
        provider.apiClient = mockApiClient

        def cloud = Stub(com.morpheusdata.model.Cloud) {
            getConfigMap() >> [apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret', domainId: 'domain-abc']
        }
        def server = Stub(com.morpheusdata.model.ComputeServer) {
            getCloud() >> cloud
            getName() >> 'test-vm'
            getConfigMap() >> [serviceOfferingId: 'svc-1', templateId: 'tpl-1', zoneId: 'zone-1', networkId: 'net-1']
        }
        def workload = Stub(com.morpheusdata.model.Workload) {
            getServer() >> server
        }
        def workloadRequest = new com.morpheusdata.model.provisioning.WorkloadRequest()

        when:
        provider.runWorkload(workload, workloadRequest, [:])

        then:
        capturedParams?.domainid == 'domain-abc'
    }

    def "pollAsyncJobWithBackoff returns success on jobstatus 1"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            queryAsyncJobResult(_, _, _, _) >> [
                success: true,
                data: [queryasyncjobresultresponse: [jobstatus: 1, jobresult: [virtualmachine: [id: 'vm-1']]]]
            ]
        }
        provider.apiClient = mockApiClient

        when:
        def result = provider.invokeMethod('pollAsyncJobWithBackoff', ['http://host/api', 'key', 'secret', 'job-1'])

        then:
        result.success == true
        result.data.jobresult.virtualmachine.id == 'vm-1'
    }

    def "pollAsyncJobWithBackoff returns error on jobstatus 2"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            queryAsyncJobResult(_, _, _, _) >> [
                success: true,
                data: [queryasyncjobresultresponse: [
                    jobstatus: 2,
                    jobresult: [errortext: 'Insufficient capacity']
                ]]
            ]
        }
        provider.apiClient = mockApiClient

        when:
        def result = provider.invokeMethod('pollAsyncJobWithBackoff', ['http://host/api', 'key', 'secret', 'job-1'])

        then:
        result.success == false
        result.error == 'Insufficient capacity'
    }

    // ---- Capability flag methods ----

    def "getMaxNetworks returns 1"() {
        expect:
        provider.getMaxNetworks() == 1
    }

    def "hasPlanTagMatch returns false"() {
        expect:
        provider.hasPlanTagMatch() == false
    }

    def "getMorpheus returns injected context"() {
        expect:
        provider.getMorpheus() == mockMorpheus
    }

    def "getPlugin returns injected plugin"() {
        expect:
        provider.getPlugin() == mockPlugin
    }

    def "getNodeOptionTypes returns empty collection"() {
        expect:
        provider.getNodeOptionTypes().isEmpty()
    }

    // ---- Lifecycle methods that are trivially success ----

    def "validateWorkload returns success"() {
        when:
        def result = provider.validateWorkload([:])

        then:
        result.success
    }

    def "finalizeWorkload returns success"() {
        given:
        def workload = Stub(com.morpheusdata.model.Workload)

        when:
        def result = provider.finalizeWorkload(workload)

        then:
        result.success
    }

    def "createWorkloadResources returns success"() {
        given:
        def workload = Stub(com.morpheusdata.model.Workload)

        when:
        def result = provider.createWorkloadResources(workload, [:])

        then:
        result.success
    }

    // ---- Helper to build workload stub ----

    private com.morpheusdata.model.Workload makeWorkload(String vmId = 'vm-1', Map cloudConfig = [:]) {
        def config = [apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret'] + cloudConfig
        def cloud = Stub(com.morpheusdata.model.Cloud) { getConfigMap() >> config }
        def server = Stub(com.morpheusdata.model.ComputeServer) {
            getCloud() >> cloud
            getExternalId() >> vmId
        }
        return Stub(com.morpheusdata.model.Workload) { getServer() >> server }
    }

    // ---- stopWorkload ----

    def "stopWorkload returns success when stop API returns without jobid"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            stopVirtualMachine(_, _, _, _) >> [success: true, data: [stopvirtualmachineresponse: [:]]]
        }
        provider.apiClient = mockApiClient

        when:
        def result = provider.stopWorkload(makeWorkload())

        then:
        result.success
    }

    def "stopWorkload returns success when stop job completes"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            stopVirtualMachine(_, _, _, _) >> [success: true, data: [stopvirtualmachineresponse: [jobid: 'job-1']]]
            queryAsyncJobResult(_, _, _, 'job-1') >> [
                success: true,
                data: [queryasyncjobresultresponse: [jobstatus: 1, jobresult: [:]]]
            ]
        }
        provider.apiClient = mockApiClient

        when:
        def result = provider.stopWorkload(makeWorkload())

        then:
        result.success
    }

    def "stopWorkload returns error when stop API fails"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            stopVirtualMachine(_, _, _, _) >> [success: false, error: 'VM not found']
        }
        provider.apiClient = mockApiClient

        when:
        def result = provider.stopWorkload(makeWorkload())

        then:
        !result.success
    }

    // ---- startWorkload ----

    def "startWorkload returns success when start API returns without jobid"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            startVirtualMachine(_, _, _, _) >> [success: true, data: [startvirtualmachineresponse: [:]]]
        }
        provider.apiClient = mockApiClient

        when:
        def result = provider.startWorkload(makeWorkload())

        then:
        result.success
    }

    def "startWorkload returns error when start API fails"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            startVirtualMachine(_, _, _, _) >> [success: false, error: 'Already running']
        }
        provider.apiClient = mockApiClient

        when:
        def result = provider.startWorkload(makeWorkload())

        then:
        !result.success
    }

    // ---- removeWorkload ----

    def "removeWorkload returns success when destroy API returns without jobid"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            destroyVirtualMachine(_, _, _, _) >> [success: true, data: [destroyvirtualmachineresponse: [:]]]
        }
        provider.apiClient = mockApiClient

        when:
        def result = provider.removeWorkload(makeWorkload(), [:])

        then:
        result.success
    }

    def "removeWorkload returns success when destroy job completes"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            destroyVirtualMachine(_, _, _, _) >> [success: true, data: [destroyvirtualmachineresponse: [jobid: 'job-2']]]
            queryAsyncJobResult(_, _, _, 'job-2') >> [
                success: true,
                data: [queryasyncjobresultresponse: [jobstatus: 1, jobresult: [:]]]
            ]
        }
        provider.apiClient = mockApiClient

        when:
        def result = provider.removeWorkload(makeWorkload(), [:])

        then:
        result.success
    }

    def "removeWorkload returns error when destroy API fails"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            destroyVirtualMachine(_, _, _, _) >> [success: false, error: 'Not found']
        }
        provider.apiClient = mockApiClient

        when:
        def result = provider.removeWorkload(makeWorkload(), [:])

        then:
        !result.success
    }

    // ---- restartWorkload ----

    def "restartWorkload returns error if stopWorkload fails"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            stopVirtualMachine(_, _, _, _) >> [success: false, error: 'Stop failed']
        }
        provider.apiClient = mockApiClient

        when:
        def result = provider.restartWorkload(makeWorkload())

        then:
        !result.success
    }

    def "restartWorkload returns success if stop and start both succeed"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            stopVirtualMachine(_, _, _, _) >> [success: true, data: [stopvirtualmachineresponse: [:]]]
            startVirtualMachine(_, _, _, _) >> [success: true, data: [startvirtualmachineresponse: [:]]]
        }
        provider.apiClient = mockApiClient

        when:
        def result = provider.restartWorkload(makeWorkload())

        then:
        result.success
    }

    // ---- runWorkload error path ----

    def "runWorkload returns error response when deploy API fails"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            deployVirtualMachine(_, _, _, _) >> [success: false, error: 'Insufficient capacity']
        }
        provider.apiClient = mockApiClient

        def cloud = Stub(com.morpheusdata.model.Cloud) {
            getConfigMap() >> [apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret']
        }
        def server = Stub(com.morpheusdata.model.ComputeServer) {
            getCloud() >> cloud
            getName() >> 'test-vm'
            getConfigMap() >> [serviceOfferingId: 'svc-1', templateId: 'tpl-1', zoneId: 'zone-1']
        }
        def workload = Stub(com.morpheusdata.model.Workload) { getServer() >> server }
        def workloadRequest = new com.morpheusdata.model.provisioning.WorkloadRequest()

        when:
        def result = provider.runWorkload(workload, workloadRequest, [:])

        then:
        !result.success
    }

    // ---- getServerDetails ----

    def "getServerDetails returns ProvisionResponse when VM found"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            listVirtualMachines(_, _, _, _) >> [
                success: true,
                data: [listvirtualmachinesresponse: [virtualmachine: [
                    [id: 'vm-1', name: 'my-vm']
                ]]]
            ]
        }
        provider.apiClient = mockApiClient

        def cloud = Stub(com.morpheusdata.model.Cloud) {
            getConfigMap() >> [apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret']
        }
        def server = Stub(com.morpheusdata.model.ComputeServer) {
            getCloud() >> cloud
            getExternalId() >> 'vm-1'
        }

        when:
        def result = provider.getServerDetails(server)

        then:
        result.success
        result.data.externalId == 'vm-1'
        result.data.hostname == 'my-vm'
    }

    def "getServerDetails returns error when VM not found"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            listVirtualMachines(_, _, _, _) >> [
                success: true,
                data: [listvirtualmachinesresponse: [virtualmachine: []]]
            ]
        }
        provider.apiClient = mockApiClient

        def cloud = Stub(com.morpheusdata.model.Cloud) {
            getConfigMap() >> [apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret']
        }
        def server = Stub(com.morpheusdata.model.ComputeServer) {
            getCloud() >> cloud
            getExternalId() >> 'vm-missing'
        }

        when:
        def result = provider.getServerDetails(server)

        then:
        !result.success
    }

    // ---- stopServer / startServer on ComputeServer ----

    def "stopServer on ComputeServer returns success"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            stopVirtualMachine(_, _, _, _) >> [success: true, data: [:]]
        }
        provider.apiClient = mockApiClient
        def cloud = Stub(com.morpheusdata.model.Cloud) {
            getConfigMap() >> [apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret']
        }
        def server = Stub(com.morpheusdata.model.ComputeServer) {
            getCloud() >> cloud
            getExternalId() >> 'vm-1'
        }

        when:
        def result = provider.stopServer(server)

        then:
        result.success
    }

    def "startServer on ComputeServer returns success"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            startVirtualMachine(_, _, _, _) >> [success: true, data: [:]]
        }
        provider.apiClient = mockApiClient
        def cloud = Stub(com.morpheusdata.model.Cloud) {
            getConfigMap() >> [apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret']
        }
        def server = Stub(com.morpheusdata.model.ComputeServer) {
            getCloud() >> cloud
            getExternalId() >> 'vm-1'
        }

        when:
        def result = provider.startServer(server)

        then:
        result.success
    }

    // ---- keypair not included when absent ----

    def "runWorkload does not include keypair param when not configured"() {
        given:
        def capturedParams = null
        def mockApiClient = Spy(CloudStackApiClient) {
            deployVirtualMachine(_, _, _, _) >> { String url, String key, String secret, Map params ->
                capturedParams = params
                return [success: true, data: [deployvirtualmachineresponse: [jobid: null, id: 'vm-1']]]
            }
        }
        provider.apiClient = mockApiClient

        def cloud = Stub(com.morpheusdata.model.Cloud) {
            getConfigMap() >> [apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret']
        }
        def server = Stub(com.morpheusdata.model.ComputeServer) {
            getCloud() >> cloud
            getName() >> 'test-vm'
            getConfigMap() >> [serviceOfferingId: 'svc-1', templateId: 'tpl-1', zoneId: 'zone-1']
        }
        def workload = Stub(com.morpheusdata.model.Workload) { getServer() >> server }
        def workloadRequest = new com.morpheusdata.model.provisioning.WorkloadRequest()

        when:
        provider.runWorkload(workload, workloadRequest, [:])

        then:
        !capturedParams?.containsKey('keypair')
    }

    def "runWorkload includes keypair param when configured"() {
        given:
        def capturedParams = null
        def mockApiClient = Spy(CloudStackApiClient) {
            deployVirtualMachine(_, _, _, _) >> { String url, String key, String secret, Map params ->
                capturedParams = params
                return [success: true, data: [deployvirtualmachineresponse: [jobid: null, id: 'vm-1']]]
            }
        }
        provider.apiClient = mockApiClient

        def cloud = Stub(com.morpheusdata.model.Cloud) {
            getConfigMap() >> [apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret']
        }
        def server = Stub(com.morpheusdata.model.ComputeServer) {
            getCloud() >> cloud
            getName() >> 'test-vm'
            getConfigMap() >> [serviceOfferingId: 'svc-1', templateId: 'tpl-1', zoneId: 'zone-1', keypair: 'my-keypair']
        }
        def workload = Stub(com.morpheusdata.model.Workload) { getServer() >> server }
        def workloadRequest = new com.morpheusdata.model.provisioning.WorkloadRequest()

        when:
        provider.runWorkload(workload, workloadRequest, [:])

        then:
        capturedParams?.keypair == 'my-keypair'
    }
}
