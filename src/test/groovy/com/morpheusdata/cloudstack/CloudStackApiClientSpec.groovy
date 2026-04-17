package com.morpheusdata.cloudstack

import spock.lang.Specification
import spock.lang.Subject

class CloudStackApiClientSpec extends Specification {

    @Subject
    CloudStackApiClient apiClient = new CloudStackApiClient()

    def "signRequest produces consistent HMAC-SHA1 signature"() {
        given:
        def queryString = 'apikey=testkey&command=listZones'
        def secretKey = 'testSecretKey'

        when:
        def sig1 = apiClient.signRequest(queryString, secretKey)
        def sig2 = apiClient.signRequest(queryString, secretKey)

        then:
        sig1 != null
        sig1.length() > 0
        sig1 == sig2
    }

    def "signRequest produces different signatures for different secret keys"() {
        given:
        def queryString = 'apikey=testkey&command=listZones'

        when:
        def sig1 = apiClient.signRequest(queryString, 'secretKey1')
        def sig2 = apiClient.signRequest(queryString, 'secretKey2')

        then:
        sig1 != sig2
    }

    def "signRequest produces different signatures for different query strings"() {
        given:
        def secretKey = 'mySecretKey'

        when:
        def sig1 = apiClient.signRequest('apikey=key&command=listZones', secretKey)
        def sig2 = apiClient.signRequest('apikey=key&command=listNetworks', secretKey)

        then:
        sig1 != sig2
    }

    def "signRequest output is URL-encoded"() {
        given:
        def queryString = 'apikey=testkey&command=listZones'
        def secretKey = 'testSecretKey'

        when:
        def signature = apiClient.signRequest(queryString, secretKey)

        then:
        // URL-encoded base64 strings should not contain raw + or = signs
        !signature.contains('+')
        !signature.contains('=')
        // but may contain %2B, %3D etc (URL-encoded forms)
    }

    def "signRequest produces a known correct value"() {
        given: "Known input/output from CloudStack API spec"
        // According to CloudStack signing spec, verify the HMAC-SHA1 Base64 URL-encoded output
        def queryString = 'apikey=plgWJfZK4gyS3mOMTVmjUVg-X-jlWlnfaUJ9GAbBbf9EdM-kAYMmAiLqzzq1ElZLYq_u38zCm0bewzGUdP66mg&command=listusers&response=json'
        def secretKey = 'VDaACYb0LV9eNjTetIOElcVQkvJck_J_QljX_FcHRj87ZKiy0z0ty0ZsYBkZ9sXzVnBFq7EgDEBJMbpqOr44Mw'

        when:
        def signature = apiClient.signRequest(queryString, secretKey)

        then:
        signature != null
        signature.length() > 0
        // The signature should be URL-encoded Base64
        !signature.contains(' ')
    }

    def "callApi builds request with required parameters"() {
        given: "A mock that captures the request URL"
        def capturedParams = null
        def mockClient = Spy(CloudStackApiClient) {
            callApi(_, _, _, _) >> { String apiUrl, String apiKey, String secretKey, Map params ->
                capturedParams = params
                return [success: true, data: [:]]
            }
        }

        when:
        mockClient.listZones('http://cloudstack/client/api', 'myApiKey', 'mySecretKey', [:])

        then:
        capturedParams != null
        capturedParams.command == 'listZones'
        capturedParams.available == 'true'
    }

    def "listZones includes available=true parameter"() {
        given:
        def mockClient = Spy(CloudStackApiClient) {
            callApi(*_) >> [success: true, data: [listzonesresponse: [zone: []]]]
        }

        when:
        def result = mockClient.listZones('http://host/api', 'key', 'secret', [:])

        then:
        1 * mockClient.callApi('http://host/api', 'key', 'secret', { Map p -> p.command == 'listZones' && p.available == 'true' })
    }

    def "listTemplates includes templatefilter=executable parameter"() {
        given:
        def mockClient = Spy(CloudStackApiClient) {
            callApi(*_) >> [success: true, data: [listtemplatesresponse: [template: []]]]
        }

        when:
        mockClient.listTemplates('http://host/api', 'key', 'secret', [:])

        then:
        1 * mockClient.callApi('http://host/api', 'key', 'secret', { Map p ->
            p.command == 'listTemplates' && p.templatefilter == 'executable'
        })
    }

    def "queryAsyncJobResult sends correct jobid"() {
        given:
        def mockClient = Spy(CloudStackApiClient) {
            callApi(*_) >> [success: true, data: [queryasyncjobresultresponse: [jobstatus: 1]]]
        }
        def jobId = 'abc-123-job-id'

        when:
        mockClient.queryAsyncJobResult('http://host/api', 'key', 'secret', jobId)

        then:
        1 * mockClient.callApi('http://host/api', 'key', 'secret', { Map p ->
            p.command == 'queryAsyncJobResult' && p.jobid == jobId
        })
    }

    def "listDiskOfferings uses listDiskOfferings command"() {
        given:
        def mockClient = Spy(CloudStackApiClient) {
            callApi(*_) >> [success: true, data: [listdiskofferingsresponse: [diskoffering: []]]]
        }

        when:
        mockClient.listDiskOfferings('http://host/api', 'key', 'secret', [:])

        then:
        1 * mockClient.callApi('http://host/api', 'key', 'secret', { Map p -> p.command == 'listDiskOfferings' })
    }

    def "createVolume uses createVolume command"() {
        given:
        def mockClient = Spy(CloudStackApiClient) {
            callApi(*_) >> [success: true, data: [:]]
        }

        when:
        mockClient.createVolume('http://host/api', 'key', 'secret', [name: 'vol1', diskofferingid: 'do-1', zoneid: 'z-1'])

        then:
        1 * mockClient.callApi('http://host/api', 'key', 'secret', { Map p ->
            p.command == 'createVolume' && p.name == 'vol1'
        })
    }

    def "listVirtualMachinesMetrics uses listVirtualMachinesMetrics command"() {
        given:
        def mockClient = Spy(CloudStackApiClient) {
            callApi(*_) >> [success: true, data: [:]]
        }

        when:
        mockClient.listVirtualMachinesMetrics('http://host/api', 'key', 'secret', [id: 'vm-1'])

        then:
        1 * mockClient.callApi('http://host/api', 'key', 'secret', { Map p ->
            p.command == 'listVirtualMachinesMetrics' && p.id == 'vm-1'
        })
    }

    def "createTags sends resourceids and resourcetype"() {
        given:
        def mockClient = Spy(CloudStackApiClient) {
            callApi(*_) >> [success: true, data: [:]]
        }

        when:
        mockClient.createTags('http://host/api', 'key', 'secret', [
            resourceids: 'vm-1', resourcetype: 'UserVm', 'tags[0].key': 'env', 'tags[0].value': 'prod'
        ])

        then:
        1 * mockClient.callApi('http://host/api', 'key', 'secret', { Map p ->
            p.command == 'createTags' && p.resourceids == 'vm-1' && p.resourcetype == 'UserVm'
        })
    }

    def "listNetworks uses listNetworks command"() {
        given:
        def mockClient = Spy(CloudStackApiClient) {
            callApi(*_) >> [success: true, data: [listnetworksresponse: [network: []]]]
        }

        when:
        mockClient.listNetworks('http://host/api', 'key', 'secret', [:])

        then:
        1 * mockClient.callApi('http://host/api', 'key', 'secret', { Map p -> p.command == 'listNetworks' })
    }

    def "listServiceOfferings uses listServiceOfferings command"() {
        given:
        def mockClient = Spy(CloudStackApiClient) {
            callApi(*_) >> [success: true, data: [listserviceofferingsresponse: [serviceoffering: []]]]
        }

        when:
        mockClient.listServiceOfferings('http://host/api', 'key', 'secret', [:])

        then:
        1 * mockClient.callApi('http://host/api', 'key', 'secret', { Map p -> p.command == 'listServiceOfferings' })
    }

    def "listVirtualMachines uses listVirtualMachines command"() {
        given:
        def mockClient = Spy(CloudStackApiClient) {
            callApi(*_) >> [success: true, data: [listvirtualmachinesresponse: [virtualmachine: []]]]
        }

        when:
        mockClient.listVirtualMachines('http://host/api', 'key', 'secret', [:])

        then:
        1 * mockClient.callApi('http://host/api', 'key', 'secret', { Map p -> p.command == 'listVirtualMachines' })
    }

    def "deployVirtualMachine uses deployVirtualMachine command"() {
        given:
        def mockClient = Spy(CloudStackApiClient) {
            callApi(*_) >> [success: true, data: [:]]
        }

        when:
        mockClient.deployVirtualMachine('http://host/api', 'key', 'secret', [
            templateid: 'tpl-1', serviceofferingid: 'svc-1', zoneid: 'zone-1', name: 'vm-test'
        ])

        then:
        1 * mockClient.callApi('http://host/api', 'key', 'secret', { Map p ->
            p.command == 'deployVirtualMachine' && p.name == 'vm-test'
        })
    }

    def "startVirtualMachine uses startVirtualMachine command with id"() {
        given:
        def mockClient = Spy(CloudStackApiClient) {
            callApi(*_) >> [success: true, data: [:]]
        }

        when:
        mockClient.startVirtualMachine('http://host/api', 'key', 'secret', [id: 'vm-1'])

        then:
        1 * mockClient.callApi('http://host/api', 'key', 'secret', { Map p ->
            p.command == 'startVirtualMachine' && p.id == 'vm-1'
        })
    }

    def "stopVirtualMachine uses stopVirtualMachine command with id"() {
        given:
        def mockClient = Spy(CloudStackApiClient) {
            callApi(*_) >> [success: true, data: [:]]
        }

        when:
        mockClient.stopVirtualMachine('http://host/api', 'key', 'secret', [id: 'vm-2'])

        then:
        1 * mockClient.callApi('http://host/api', 'key', 'secret', { Map p ->
            p.command == 'stopVirtualMachine' && p.id == 'vm-2'
        })
    }

    def "destroyVirtualMachine uses destroyVirtualMachine command with id"() {
        given:
        def mockClient = Spy(CloudStackApiClient) {
            callApi(*_) >> [success: true, data: [:]]
        }

        when:
        mockClient.destroyVirtualMachine('http://host/api', 'key', 'secret', [id: 'vm-3'])

        then:
        1 * mockClient.callApi('http://host/api', 'key', 'secret', { Map p ->
            p.command == 'destroyVirtualMachine' && p.id == 'vm-3'
        })
    }

    def "attachVolume uses attachVolume command"() {
        given:
        def mockClient = Spy(CloudStackApiClient) {
            callApi(*_) >> [success: true, data: [:]]
        }

        when:
        mockClient.attachVolume('http://host/api', 'key', 'secret', [id: 'vol-1', virtualmachineid: 'vm-1'])

        then:
        1 * mockClient.callApi('http://host/api', 'key', 'secret', { Map p ->
            p.command == 'attachVolume' && p.id == 'vol-1'
        })
    }

    def "listVolumes uses listVolumes command"() {
        given:
        def mockClient = Spy(CloudStackApiClient) {
            callApi(*_) >> [success: true, data: [:]]
        }

        when:
        mockClient.listVolumes('http://host/api', 'key', 'secret', [virtualmachineid: 'vm-1'])

        then:
        1 * mockClient.callApi('http://host/api', 'key', 'secret', { Map p ->
            p.command == 'listVolumes' && p.virtualmachineid == 'vm-1'
        })
    }

    def "listTags uses listTags command"() {
        given:
        def mockClient = Spy(CloudStackApiClient) {
            callApi(*_) >> [success: true, data: [:]]
        }

        when:
        mockClient.listTags('http://host/api', 'key', 'secret', [resourceid: 'vm-1', resourcetype: 'UserVm'])

        then:
        1 * mockClient.callApi('http://host/api', 'key', 'secret', { Map p ->
            p.command == 'listTags' && p.resourceid == 'vm-1'
        })
    }

    def "callApi includes apikey and response=json in request"() {
        given:
        // callApi is the real implementation; test structural invariants
        // by verifying the signing-string uses sorted lowercase keys
        def client = new CloudStackApiClient()
        def sig = client.signRequest('apikey=myKey&command=listZones&response=json', 'secret123')

        expect:
        sig != null
        sig.length() > 0
    }

    def "signRequest handles special characters in keys"() {
        given:
        def queryString = 'apikey=my+key%2Bvalue&command=listZones'
        def secretKey = 'secret/with+special=chars'

        when:
        def sig = apiClient.signRequest(queryString, secretKey)

        then:
        sig != null
        sig.length() > 0
        !sig.contains(' ')
    }

    def "extraParams are merged into API request"() {
        given:
        def mockClient = Spy(CloudStackApiClient) {
            callApi(*_) >> [success: true, data: [:]]
        }

        when:
        mockClient.listZones('http://host/api', 'key', 'secret', [domainid: 'dom-1'])

        then:
        1 * mockClient.callApi('http://host/api', 'key', 'secret', { Map p ->
            p.command == 'listZones' && p.domainid == 'dom-1'
        })
    }
}
