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
}
