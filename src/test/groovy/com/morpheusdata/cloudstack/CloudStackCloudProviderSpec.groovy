package com.morpheusdata.cloudstack

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ServerStatsData
import com.morpheusdata.request.ValidateCloudRequest
import spock.lang.Specification
import spock.lang.Subject

class CloudStackCloudProviderSpec extends Specification {

    MorpheusContext mockMorpheus = Mock(MorpheusContext)

    // Create a minimal concrete stub of the abstract Plugin class
    Plugin mockPlugin = Stub(Plugin) {
        getCode() >> 'cloudstack'
        getName() >> 'Apache CloudStack'
    }

    @Subject
    CloudStackCloudProvider provider = new CloudStackCloudProvider(mockPlugin, mockMorpheus)

    private ValidateCloudRequest dummyRequest() {
        new ValidateCloudRequest('', '', '', [:])
    }

    private Cloud cloudWithConfig(Map config) {
        Stub(Cloud) {
            getConfigMap() >> config
        }
    }

    private ComputeServer serverWithCloud(Cloud cloud, String externalId = 'vm-1') {
        Stub(ComputeServer) {
            getCloud() >> cloud
            getExternalId() >> externalId
            getMaxMemory() >> 2048L * 1024L * 1024L
        }
    }

    def "getCode returns cloudstack"() {
        expect:
        provider.getCode() == 'cloudstack'
    }

    def "getName returns Apache CloudStack"() {
        expect:
        provider.getName() == 'Apache CloudStack'
    }

    def "hasNetworks returns true"() {
        expect:
        provider.hasNetworks() == true
    }

    def "canCreateCloudPools returns false"() {
        expect:
        provider.canCreateCloudPools() == false
    }

    def "canCreateNetworks returns false"() {
        expect:
        provider.canCreateNetworks() == false
    }

    def "hasComputeZonePools returns false"() {
        expect:
        provider.hasComputeZonePools() == false
    }

    def "hasDatastores returns false"() {
        expect:
        provider.hasDatastores() == false
    }

    def "hasBareMetal returns false"() {
        expect:
        provider.hasBareMetal() == false
    }

    def "getOptionTypes returns four option types"() {
        when:
        def options = provider.getOptionTypes()

        then:
        options.size() == 4
    }

    def "getOptionTypes contains apiUrl option"() {
        when:
        def options = provider.getOptionTypes()
        def apiUrlOption = options.find { it.fieldName == 'apiUrl' }

        then:
        apiUrlOption != null
        apiUrlOption.required == true
        apiUrlOption.fieldContext == 'config'
    }

    def "getOptionTypes contains apiKey option"() {
        when:
        def options = provider.getOptionTypes()
        def apiKeyOption = options.find { it.fieldName == 'apiKey' }

        then:
        apiKeyOption != null
        apiKeyOption.required == true
        apiKeyOption.fieldContext == 'config'
    }

    def "getOptionTypes contains secretKey option as password"() {
        when:
        def options = provider.getOptionTypes()
        def secretKeyOption = options.find { it.fieldName == 'secretKey' }

        then:
        secretKeyOption != null
        secretKeyOption.required == true
        secretKeyOption.inputType == OptionType.InputType.PASSWORD
        secretKeyOption.fieldContext == 'config'
    }

    def "getOptionTypes contains domainId option as optional"() {
        when:
        def options = provider.getOptionTypes()
        def domainIdOption = options.find { it.fieldName == 'domainId' }

        then:
        domainIdOption != null
        domainIdOption.required == false
        domainIdOption.fieldContext == 'config'
    }

    def "getOptionTypes returns options with ascending displayOrder"() {
        when:
        def options = provider.getOptionTypes()
        def sortedOrders = options*.displayOrder

        then:
        sortedOrders == sortedOrders.sort()
    }

    def "getOptionTypes all have non-null codes"() {
        when:
        def options = provider.getOptionTypes()

        then:
        options.every { it.code != null }
    }

    def "getDescription is not empty"() {
        expect:
        provider.getDescription() != null
        provider.getDescription().length() > 0
    }

    def "getDefaultProvisionTypeCode returns cloudstack-provision"() {
        expect:
        provider.getDefaultProvisionTypeCode() == 'cloudstack-provision'
    }

    def "getMorpheus returns the injected context"() {
        expect:
        provider.getMorpheus() == mockMorpheus
    }

    def "getPlugin returns the injected plugin"() {
        expect:
        provider.getPlugin() == mockPlugin
    }

    def "getAvailableProvisionProviders returns at least one provider"() {
        when:
        def providers = provider.getAvailableProvisionProviders()

        then:
        providers != null
        providers.size() > 0
    }

    def "getAvailableProvisionProviders contains cloudstack-provision"() {
        when:
        def providers = provider.getAvailableProvisionProviders()

        then:
        providers.any { it.code == 'cloudstack-provision' }
    }

    def "getComputeServerTypes returns at least one type"() {
        when:
        def types = provider.getComputeServerTypes()

        then:
        types != null
        types.size() > 0
        types.any { it.code == 'cloudstack-vm' }
    }

    def "getNetworkTypes returns isolated and shared network types"() {
        when:
        def types = provider.getNetworkTypes()

        then:
        types != null
        types.size() >= 2
        types.any { it.code == 'cloudstack-isolated' }
        types.any { it.code == 'cloudstack-shared' }
    }

    def "getStorageVolumeTypes returns at least one type"() {
        when:
        def types = provider.getStorageVolumeTypes()

        then:
        types != null
        types.size() > 0
        types.any { it.code == 'cloudstack-disk' }
    }

    // ---- Capability flags not yet tested ----

    def "hasFolders returns false"() {
        expect:
        provider.hasFolders() == false
    }

    def "hasCloudInit returns false"() {
        expect:
        provider.hasCloudInit() == false
    }

    def "supportsDistributedWorker returns false"() {
        expect:
        provider.supportsDistributedWorker() == false
    }

    // ---- Icon methods ----

    def "getIcon returns non-null Icon"() {
        when:
        def icon = provider.getIcon()

        then:
        icon != null
        icon instanceof Icon
        icon.path == 'cloudstack.svg'
    }

    def "getCircularIcon returns non-null Icon"() {
        when:
        def icon = provider.getCircularIcon()

        then:
        icon != null
        icon instanceof Icon
        icon.path == 'cloudstack-circular.svg'
    }

    // ---- Empty collections ----

    def "getStorageControllerTypes returns empty collection"() {
        expect:
        provider.getStorageControllerTypes().isEmpty()
    }

    def "getAvailableBackupProviders returns empty collection"() {
        expect:
        provider.getAvailableBackupProviders().isEmpty()
    }

    def "getSubnetTypes returns empty collection"() {
        expect:
        provider.getSubnetTypes().isEmpty()
    }

    // ---- ProvisionProvider lookup ----

    def "getProvisionProvider by code returns matching provider"() {
        when:
        def pp = provider.getProvisionProvider('cloudstack-provision')

        then:
        pp != null
        pp.code == 'cloudstack-provision'
    }

    def "getProvisionProvider with unknown code returns null"() {
        expect:
        provider.getProvisionProvider('nonexistent-code') == null
    }

    // ---- validate() ----

    def "validate returns error when apiUrl is missing"() {
        given:
        def cloud = cloudWithConfig([apiKey: 'key', secretKey: 'secret'])

        when:
        def result = provider.validate(cloud, dummyRequest())

        then:
        !result.success
    }

    def "validate returns error when apiKey is missing"() {
        given:
        def cloud = cloudWithConfig([apiUrl: 'http://host/api', secretKey: 'secret'])

        when:
        def result = provider.validate(cloud, dummyRequest())

        then:
        !result.success
    }

    def "validate returns error when secretKey is missing"() {
        given:
        def cloud = cloudWithConfig([apiUrl: 'http://host/api', apiKey: 'key'])

        when:
        def result = provider.validate(cloud, dummyRequest())

        then:
        !result.success
    }

    def "validate returns success when API call succeeds"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            listZones(_, _, _, _) >> [success: true, data: [listzonesresponse: [zone: []]]]
        }
        provider.apiClient = mockApiClient
        def cloud = cloudWithConfig([apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret'])

        when:
        def result = provider.validate(cloud, dummyRequest())

        then:
        result.success
    }

    def "validate returns error when API call fails"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            listZones(_, _, _, _) >> [success: false, error: 'Connection refused']
        }
        provider.apiClient = mockApiClient
        def cloud = cloudWithConfig([apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret'])

        when:
        def result = provider.validate(cloud, dummyRequest())

        then:
        !result.success
    }

    def "validate handles unexpected exception gracefully"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            listZones(_, _, _, _) >> { throw new RuntimeException('Network error') }
        }
        provider.apiClient = mockApiClient
        def cloud = cloudWithConfig([apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret'])

        when:
        def result = provider.validate(cloud, dummyRequest())

        then:
        !result.success
    }

    // ---- deleteCloud() ----

    def "deleteCloud returns success"() {
        given:
        def cloud = cloudWithConfig([:])

        when:
        def result = provider.deleteCloud(cloud)

        then:
        result.success
    }

    // ---- startServer() ----

    def "startServer returns success when API succeeds"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            startVirtualMachine(_, _, _, _) >> [success: true, data: [:]]
        }
        provider.apiClient = mockApiClient
        def cloud = cloudWithConfig([apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret'])
        def server = serverWithCloud(cloud)

        when:
        def result = provider.startServer(server)

        then:
        result.success
    }

    def "startServer returns error when API fails"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            startVirtualMachine(_, _, _, _) >> [success: false, error: 'VM not found']
        }
        provider.apiClient = mockApiClient
        def cloud = cloudWithConfig([apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret'])
        def server = serverWithCloud(cloud)

        when:
        def result = provider.startServer(server)

        then:
        !result.success
    }

    // ---- stopServer() ----

    def "stopServer returns success when API succeeds"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            stopVirtualMachine(_, _, _, _) >> [success: true, data: [:]]
        }
        provider.apiClient = mockApiClient
        def cloud = cloudWithConfig([apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret'])
        def server = serverWithCloud(cloud)

        when:
        def result = provider.stopServer(server)

        then:
        result.success
    }

    def "stopServer returns error when API fails"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            stopVirtualMachine(_, _, _, _) >> [success: false, error: 'Stop failed']
        }
        provider.apiClient = mockApiClient
        def cloud = cloudWithConfig([apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret'])
        def server = serverWithCloud(cloud)

        when:
        def result = provider.stopServer(server)

        then:
        !result.success
    }

    // ---- deleteServer() ----

    def "deleteServer returns success when API succeeds"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            destroyVirtualMachine(_, _, _, _) >> [success: true, data: [:]]
        }
        provider.apiClient = mockApiClient
        def cloud = cloudWithConfig([apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret'])
        def server = serverWithCloud(cloud)

        when:
        def result = provider.deleteServer(server)

        then:
        result.success
    }

    def "deleteServer returns error when API fails"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            destroyVirtualMachine(_, _, _, _) >> [success: false, error: 'Destroy failed']
        }
        provider.apiClient = mockApiClient
        def cloud = cloudWithConfig([apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret'])
        def server = serverWithCloud(cloud)

        when:
        def result = provider.deleteServer(server)

        then:
        !result.success
    }

    // ---- getServerStats() ----

    def "getServerStats returns ServerStatsData on API success"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            listVirtualMachinesMetrics(_, _, _, _) >> [
                success: true,
                data: [listvirtualmachinesmetricsresponse: [virtualmachine: [[
                    id: 'vm-1', state: 'Running',
                    memorykbs: 1048576L,   // 1 GB in KB
                    cpuused: '25.5%'
                ]]]]
            ]
        }
        provider.apiClient = mockApiClient
        def cloud = cloudWithConfig([apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret'])
        def server = serverWithCloud(cloud)

        when:
        def statsList = provider.getServerStats(server, [:])

        then:
        statsList.size() == 1
        statsList[0] instanceof ServerStatsData
        statsList[0].usedMemory == 1048576L * 1024L
        statsList[0].cpuUsage > 0.0f
        statsList[0].running == true
    }

    def "getServerStats returns empty list when API returns no VM"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            listVirtualMachinesMetrics(_, _, _, _) >> [
                success: true,
                data: [listvirtualmachinesmetricsresponse: [virtualmachine: []]]
            ]
        }
        provider.apiClient = mockApiClient
        def cloud = cloudWithConfig([apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret'])
        def server = serverWithCloud(cloud)

        when:
        def statsList = provider.getServerStats(server, [:])

        then:
        statsList.isEmpty()
    }

    def "getServerStats returns empty list on API failure"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            listVirtualMachinesMetrics(_, _, _, _) >> [success: false, error: 'Not authorized']
        }
        provider.apiClient = mockApiClient
        def cloud = cloudWithConfig([apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret'])
        def server = serverWithCloud(cloud)

        when:
        def statsList = provider.getServerStats(server, [:])

        then:
        statsList.isEmpty()
    }

    def "getServerStats marks running=false when VM is Stopped"() {
        given:
        def mockApiClient = Spy(CloudStackApiClient) {
            listVirtualMachinesMetrics(_, _, _, _) >> [
                success: true,
                data: [listvirtualmachinesmetricsresponse: [virtualmachine: [[
                    id: 'vm-1', state: 'Stopped', memorykbs: 0L, cpuused: '0%'
                ]]]]
            ]
        }
        provider.apiClient = mockApiClient
        def cloud = cloudWithConfig([apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret'])
        def server = serverWithCloud(cloud)

        when:
        def statsList = provider.getServerStats(server, [:])

        then:
        statsList.size() == 1
        statsList[0].running == false
    }
}
