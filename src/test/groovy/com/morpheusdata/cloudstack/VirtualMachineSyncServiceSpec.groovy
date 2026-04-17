package com.morpheusdata.cloudstack

import com.morpheusdata.cloudstack.sync.VirtualMachineSyncService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

class VirtualMachineSyncServiceSpec extends Specification {

    MorpheusContext mockMorpheus = Mock(MorpheusContext)
    CloudStackApiClient mockApiClient = Mock(CloudStackApiClient)
    Cloud cloud = Stub(Cloud) {
        getName() >> 'test-cloud'
        getId() >> 42L
        getConfigMap() >> [apiUrl: 'http://host/api', apiKey: 'key', secretKey: 'secret']
    }

    @Subject
    VirtualMachineSyncService service = new VirtualMachineSyncService(mockMorpheus, mockApiClient, cloud)

    // ---- mapVmStatus tests ----

    @Unroll
    def "mapVmStatus maps '#input' to '#expected'"() {
        when:
        def result = service.invokeMethod('mapVmStatus', [input])

        then:
        result == expected

        where:
        input        | expected
        'running'    | 'running'
        'Running'    | 'running'
        'RUNNING'    | 'running'
        'stopped'    | 'stopped'
        'Stopped'    | 'stopped'
        'starting'   | 'starting'
        'stopping'   | 'stopping'
        'destroyed'  | 'terminated'
        'Destroyed'  | 'terminated'
        'expunging'  | 'terminated'
        'Expunging'  | 'terminated'
        'migrating'  | 'running'
        'Migrating'  | 'running'
        'error'      | 'failed'
        'Error'      | 'failed'
        'unknown_st' | 'unknown'
        'halted'     | 'unknown'
    }

    def "mapVmStatus maps null to unknown"() {
        when:
        def result = service.invokeMethod('mapVmStatus', [null])

        then:
        result == 'unknown'
    }

    def "mapVmStatus maps empty string to unknown"() {
        when:
        def result = service.invokeMethod('mapVmStatus', [''])

        then:
        result == 'unknown'
    }

    // ---- buildComputeServer tests ----

    def "buildComputeServer sets externalId from vm.id"() {
        given:
        def vm = [id: 'abc-123', name: 'my-vm', state: 'Running']

        when:
        ComputeServer server = service.invokeMethod('buildComputeServer', [vm])

        then:
        server.externalId == 'abc-123'
    }

    def "buildComputeServer sets name from vm.name"() {
        given:
        def vm = [id: 'vm-1', name: 'web-server-01', state: 'Running']

        when:
        ComputeServer server = service.invokeMethod('buildComputeServer', [vm])

        then:
        server.name == 'web-server-01'
    }

    def "buildComputeServer maps status via mapVmStatus"() {
        given:
        def vm = [id: 'vm-1', name: 'vm', state: 'Stopped']

        when:
        ComputeServer server = service.invokeMethod('buildComputeServer', [vm])

        then:
        server.status == 'stopped'
    }

    def "buildComputeServer sets provision=false, managed=false, discovered=true"() {
        given:
        def vm = [id: 'vm-1', name: 'vm', state: 'Running']

        when:
        ComputeServer server = service.invokeMethod('buildComputeServer', [vm])

        then:
        server.provision == false
        server.managed == false
        server.discovered == true
    }

    def "buildComputeServer sets cloud reference"() {
        given:
        def vm = [id: 'vm-1', name: 'vm', state: 'Running']

        when:
        ComputeServer server = service.invokeMethod('buildComputeServer', [vm])

        then:
        server.cloud == cloud
    }

    def "buildComputeServer sets IP from default NIC"() {
        given:
        def vm = [
            id: 'vm-1', name: 'vm', state: 'Running',
            nic: [
                [isdefault: false, ipaddress: '10.0.0.2'],
                [isdefault: true,  ipaddress: '10.0.0.1']
            ]
        ]

        when:
        ComputeServer server = service.invokeMethod('buildComputeServer', [vm])

        then:
        server.externalIp == '10.0.0.1'
        server.internalIp == '10.0.0.1'
    }

    def "buildComputeServer does not set IP when no NIC present"() {
        given:
        def vm = [id: 'vm-1', name: 'vm', state: 'Running', nic: null]

        when:
        ComputeServer server = service.invokeMethod('buildComputeServer', [vm])

        then:
        server.externalIp == null
        server.internalIp == null
    }

    def "buildComputeServer does not set IP when no default NIC"() {
        given:
        def vm = [
            id: 'vm-1', name: 'vm', state: 'Running',
            nic: [[isdefault: false, ipaddress: '192.168.1.5']]
        ]

        when:
        ComputeServer server = service.invokeMethod('buildComputeServer', [vm])

        then:
        server.externalIp == null
        server.internalIp == null
    }

    def "buildComputeServer sets maxCpu from cpunumber"() {
        given:
        def vm = [id: 'vm-1', name: 'vm', state: 'Running', cpunumber: 4]

        when:
        ComputeServer server = service.invokeMethod('buildComputeServer', [vm])

        then:
        server.maxCpu == 4
    }

    def "buildComputeServer sets maxMemory from memory (MB to bytes)"() {
        given:
        // CloudStack memory is in MB; stored in ComputeServer as bytes (* 1024)
        def vm = [id: 'vm-1', name: 'vm', state: 'Running', memory: 2048L]

        when:
        ComputeServer server = service.invokeMethod('buildComputeServer', [vm])

        then:
        server.maxMemory == 2048L * 1024L
    }

    def "buildComputeServer does not set maxCpu when cpunumber absent"() {
        given:
        def vm = [id: 'vm-1', name: 'vm', state: 'Running']

        when:
        ComputeServer server = service.invokeMethod('buildComputeServer', [vm])

        then:
        server.maxCpu == null
    }

    def "buildComputeServer does not set maxMemory when memory absent"() {
        given:
        def vm = [id: 'vm-1', name: 'vm', state: 'Running']

        when:
        ComputeServer server = service.invokeMethod('buildComputeServer', [vm])

        then:
        server.maxMemory == null
    }

    def "execute gracefully handles API error"() {
        given:
        mockApiClient.listVirtualMachines(_, _, _, _) >> [success: false, error: 'Unauthorized']

        when:
        service.execute()

        then:
        // Should complete without throwing
        noExceptionThrown()
    }
}
