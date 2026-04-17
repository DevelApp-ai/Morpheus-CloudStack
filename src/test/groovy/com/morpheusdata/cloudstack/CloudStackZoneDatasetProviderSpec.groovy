package com.morpheusdata.cloudstack

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DatasetInfo
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.core.util.ApiParameterMap
import spock.lang.Specification
import spock.lang.Subject

class CloudStackZoneDatasetProviderSpec extends Specification {

    def mockPlugin = Stub(com.morpheusdata.core.Plugin) {
        getCode() >> 'cloudstack'
        getName() >> 'Apache CloudStack'
    }
    def mockMorpheus = Mock(MorpheusContext)

    @Subject
    CloudStackZoneDatasetProvider provider = new CloudStackZoneDatasetProvider(mockPlugin, mockMorpheus)

    // Helper to build a DatasetQuery with inline credentials
    private DatasetQuery queryWithCreds(String apiUrl, String apiKey, String secretKey) {
        def query = new DatasetQuery()
        def params = new ApiParameterMap<String, Object>()
        params.put('apiUrl', apiUrl)
        params.put('apiKey', apiKey)
        params.put('secretKey', secretKey)
        query.parameters = params
        return query
    }

    def "getCode returns cloudstack-zones"() {
        expect:
        provider.getCode() == 'cloudstack-zones'
    }

    def "getName returns CloudStack Zones"() {
        expect:
        provider.getName() == 'CloudStack Zones'
    }

    def "getKey returns cloudstackZones"() {
        expect:
        provider.getKey() == 'cloudstackZones'
    }

    def "getItemType returns Map class"() {
        expect:
        provider.getItemType() == Map.class
    }

    def "getMorpheus returns injected context"() {
        expect:
        provider.getMorpheus() == mockMorpheus
    }

    def "getPlugin returns injected plugin"() {
        expect:
        provider.getPlugin() == mockPlugin
    }

    def "getInfo returns correct DatasetInfo"() {
        when:
        def info = provider.getInfo()

        then:
        info instanceof DatasetInfo
        info.key == 'cloudstackZones'
        info.name == 'CloudStack Zones'
    }

    def "list with null query returns empty Observable"() {
        when:
        def result = provider.list(null).toList().blockingGet()

        then:
        result.isEmpty()
    }

    def "list with no credentials in query returns empty Observable"() {
        given:
        def query = new DatasetQuery()
        query.parameters = new ApiParameterMap<>()

        when:
        def result = provider.list(query).toList().blockingGet()

        then:
        result.isEmpty()
    }

    def "list with valid credentials returns zones from API"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            listZones(_, _, _, _) >> [
                success: true,
                data: [listzonesresponse: [zone: [
                    [id: 'zone-1', name: 'Zone One'],
                    [id: 'zone-2', name: 'Zone Two']
                ]]]
            ]
        }
        provider.apiClient = mockApiClient
        def query = queryWithCreds('http://host/api', 'mykey', 'mysecret')

        when:
        def result = provider.list(query).toList().blockingGet()

        then:
        result.size() == 2
        result[0].id == 'zone-1'
        result[0].name == 'Zone One'
        result[0].value == 'zone-1'
        result[0].text == 'Zone One'
        result[1].id == 'zone-2'
    }

    def "list returns empty when API call fails"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            listZones(_, _, _, _) >> [success: false, error: 'Connection refused']
        }
        provider.apiClient = mockApiClient
        def query = queryWithCreds('http://host/api', 'key', 'secret')

        when:
        def result = provider.list(query).toList().blockingGet()

        then:
        result.isEmpty()
    }

    def "list returns empty when API throws exception"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            listZones(_, _, _, _) >> { throw new RuntimeException('Network timeout') }
        }
        provider.apiClient = mockApiClient
        def query = queryWithCreds('http://host/api', 'key', 'secret')

        when:
        def result = provider.list(query).toList().blockingGet()

        then:
        result.isEmpty()
    }

    def "list supports config-prefixed credential keys"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            listZones(_, _, _, _) >> [
                success: true,
                data: [listzonesresponse: [zone: [[id: 'z-1', name: 'Zone 1']]]]
            ]
        }
        provider.apiClient = mockApiClient
        def query = new DatasetQuery()
        def params = new ApiParameterMap<String, Object>()
        params.put('config.apiUrl', 'http://host/api')
        params.put('config.apiKey', 'key')
        params.put('config.secretKey', 'secret')
        query.parameters = params

        when:
        def result = provider.list(query).toList().blockingGet()

        then:
        result.size() == 1
        result[0].id == 'z-1'
    }

    def "listOptions delegates to list"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            listZones(_, _, _, _) >> [
                success: true,
                data: [listzonesresponse: [zone: [[id: 'zone-1', name: 'Zone One']]]]
            ]
        }
        provider.apiClient = mockApiClient
        def query = queryWithCreds('http://host/api', 'key', 'secret')

        when:
        def result = provider.listOptions(query).toList().blockingGet()

        then:
        result.size() == 1
        result[0].id == 'zone-1'
    }

    def "fetchItem returns map with id name value text"() {
        when:
        def item = provider.fetchItem('zone-abc')

        then:
        item.id == 'zone-abc'
        item.name == 'zone-abc'
        item.value == 'zone-abc'
        item.text == 'zone-abc'
    }

    def "item returns map with id name value text"() {
        when:
        def item = provider.item('zone-xyz')

        then:
        item.id == 'zone-xyz'
        item.name == 'zone-xyz'
        item.value == 'zone-xyz'
        item.text == 'zone-xyz'
    }

    def "itemName returns name from item"() {
        when:
        def name = provider.itemName([id: 'z-1', name: 'My Zone', text: 'My Zone'])

        then:
        name == 'My Zone'
    }

    def "itemName falls back to text when name is null"() {
        when:
        def name = provider.itemName([id: 'z-1', name: null, text: 'Fallback Zone'])

        then:
        name == 'Fallback Zone'
    }

    def "itemValue returns id from item"() {
        when:
        def value = provider.itemValue([id: 'zone-99', name: 'Zone 99'])

        then:
        value == 'zone-99'
    }

    def "itemValue falls back to value field when id is null"() {
        when:
        def value = provider.itemValue([id: null, value: 'fallback-id', name: 'Zone X'])

        then:
        value == 'fallback-id'
    }
}
