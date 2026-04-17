package com.morpheusdata.cloudstack.sync

import com.morpheusdata.cloudstack.CloudStackApiClient
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class VirtualMachineSyncService {

    MorpheusContext morpheusContext
    CloudStackApiClient apiClient
    Cloud cloud

    VirtualMachineSyncService(MorpheusContext morpheusContext, CloudStackApiClient apiClient, Cloud cloud) {
        this.morpheusContext = morpheusContext
        this.apiClient = apiClient
        this.cloud = cloud
    }

    void execute() {
        log.debug("Syncing CloudStack VMs for cloud: ${cloud.name}")
        try {
            def config = cloud.configMap
            def domainParams = config.domainId ? [domainid: config.domainId] : [:]
            def result = apiClient.listVirtualMachines(config.apiUrl, config.apiKey, config.secretKey, domainParams)
            if (!result.success) {
                log.error("Failed to list virtual machines: ${result.error}")
                return
            }

            def cloudVms = result.data?.listvirtualmachinesresponse?.virtualmachine ?: []
            log.debug("Found ${cloudVms.size()} VMs from CloudStack")

            Observable<ComputeServerIdentityProjection> existingServers =
                morpheusContext.async.computeServer.listIdentityProjections(cloud.id, null)

            SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask =
                new SyncTask<>(existingServers, cloudVms as Collection<Map>)

            syncTask.addMatchFunction { ComputeServerIdentityProjection existingItem, Map cloudItem ->
                existingItem.externalId == cloudItem.id?.toString()
            }.onDelete { List<ComputeServerIdentityProjection> removeItems ->
                morpheusContext.async.computeServer.remove(removeItems).blockingGet()
            }.onAdd { List<Map> addItems ->
                def newServers = addItems.collect { vm -> buildComputeServer(vm) }
                morpheusContext.async.computeServer.create(newServers).blockingGet()
            }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
                Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap =
                    updateItems.collectEntries { [(it.existingItem.id): it] }
                morpheusContext.async.computeServer.listById(updateItemMap.keySet().toList()).map { ComputeServer server ->
                    SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map> matchedItem = updateItemMap[server.id]
                    return new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: matchedItem.masterItem)
                }
            }.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
                def toUpdate = []
                updateItems.each { updateItem ->
                    def server = updateItem.existingItem
                    def vm = updateItem.masterItem
                    def doUpdate = false

                    def newStatus = mapVmStatus(vm.state)
                    if (server.status != newStatus) {
                        server.status = newStatus
                        doUpdate = true
                    }

                    def primaryIp = vm.nic?.find { it.isdefault }?.ipaddress
                    if (primaryIp && server.externalIp != primaryIp) {
                        server.externalIp = primaryIp
                        server.internalIp = primaryIp
                        doUpdate = true
                    }

                    if (server.name != vm.name) {
                        server.name = vm.name
                        doUpdate = true
                    }

                    if (doUpdate) {
                        toUpdate << server
                    }
                }
                if (toUpdate) {
                    morpheusContext.async.computeServer.save(toUpdate).blockingGet()
                }
            }.start()

        } catch (Exception e) {
            log.error("Error syncing VMs: ${e.message}", e)
        }
    }

    private ComputeServer buildComputeServer(Map vm) {
        def server = new ComputeServer(
            cloud: cloud,
            externalId: vm.id?.toString(),
            name: vm.name,
            status: mapVmStatus(vm.state),
            provision: false,
            managed: false,
            discovered: true
        )

        def primaryNic = vm.nic?.find { it.isdefault }
        if (primaryNic) {
            server.externalIp = primaryNic.ipaddress
            server.internalIp = primaryNic.ipaddress
        }

        if (vm.cpunumber) server.maxCpu = vm.cpunumber as Integer
        if (vm.memory) server.maxMemory = (vm.memory as Long) * 1024L

        return server
    }

    private String mapVmStatus(String vmState) {
        switch (vmState?.toLowerCase()) {
            case 'running': return 'running'
            case 'stopped': return 'stopped'
            case 'starting': return 'starting'
            case 'stopping': return 'stopping'
            case 'destroyed': return 'terminated'
            case 'expunging': return 'terminated'
            case 'migrating': return 'running'
            case 'error': return 'failed'
            default: return 'unknown'
        }
    }
}
