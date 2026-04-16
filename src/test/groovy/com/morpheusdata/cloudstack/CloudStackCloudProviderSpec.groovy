package com.morpheusdata.cloudstack

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
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
}
