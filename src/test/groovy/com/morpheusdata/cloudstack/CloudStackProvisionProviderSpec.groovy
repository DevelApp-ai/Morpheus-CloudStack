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
}
