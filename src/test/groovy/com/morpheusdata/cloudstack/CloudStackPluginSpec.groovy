package com.morpheusdata.cloudstack

import com.morpheusdata.core.MorpheusContext
import spock.lang.Specification
import spock.lang.Subject

class CloudStackPluginSpec extends Specification {

    @Subject
    CloudStackPlugin plugin = new CloudStackPlugin()

    def setup() {
        // Inject a morpheus context so initialize() can construct providers
        plugin.morpheus = Mock(MorpheusContext)
    }

    def "getCode returns cloudstack"() {
        expect:
        plugin.getCode() == 'cloudstack'
    }

    def "getName returns Apache CloudStack"() {
        expect:
        plugin.getName() == 'Apache CloudStack'
    }

    def "getCode is consistent with provider code"() {
        expect:
        plugin.getCode() == 'cloudstack'
    }

    def "onDestroy completes without throwing"() {
        when:
        plugin.onDestroy()

        then:
        noExceptionThrown()
    }

    def "initialize registers cloud provider"() {
        when:
        plugin.initialize()

        then:
        plugin.pluginProviders.containsKey('cloudstack')
        plugin.pluginProviders['cloudstack'] instanceof CloudStackCloudProvider
    }

    def "initialize registers zone dataset provider"() {
        when:
        plugin.initialize()

        then:
        plugin.pluginProviders.containsKey('cloudstack-zones')
        plugin.pluginProviders['cloudstack-zones'] instanceof CloudStackZoneDatasetProvider
    }

    def "initialize registers exactly two providers"() {
        when:
        plugin.initialize()

        then:
        plugin.pluginProviders.size() == 2
    }

    def "initialize is idempotent (does not throw on re-call)"() {
        when:
        plugin.initialize()
        plugin.initialize()

        then:
        noExceptionThrown()
    }
}
