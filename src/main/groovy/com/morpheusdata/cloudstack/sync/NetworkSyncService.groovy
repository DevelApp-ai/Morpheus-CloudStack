package com.morpheusdata.cloudstack.sync

import com.morpheusdata.cloudstack.CloudStackApiClient
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Network
import com.morpheusdata.model.projection.NetworkIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class NetworkSyncService {

    MorpheusContext morpheusContext
    CloudStackApiClient apiClient
    Cloud cloud

    NetworkSyncService(MorpheusContext morpheusContext, CloudStackApiClient apiClient, Cloud cloud) {
        this.morpheusContext = morpheusContext
        this.apiClient = apiClient
        this.cloud = cloud
    }

    void execute() {
        log.debug("Syncing CloudStack networks for cloud: ${cloud.name}")
        try {
            def config = cloud.configMap
            def domainParams = config.domainId ? [domainid: config.domainId] : [:]
            def result = apiClient.listNetworks(config.apiUrl, config.apiKey, config.secretKey, domainParams)
            if (!result.success) {
                log.error("Failed to list networks: ${result.error}")
                return
            }

            def cloudNetworks = result.data?.listnetworksresponse?.network ?: []
            log.debug("Found ${cloudNetworks.size()} networks from CloudStack")

            // Fetch zones to determine DHCP availability (internaldns1/internaldns2 presence)
            def zoneResult = apiClient.listZones(config.apiUrl, config.apiKey, config.secretKey, domainParams)
            Map<String, Boolean> zoneDhcpMap = [:]
            if (zoneResult.success) {
                zoneResult.data?.listzonesresponse?.zone?.each { zone ->
                    // If the zone provides internal DNS, its Virtual Router is handling DHCP
                    boolean hasDhcp = zone.internaldns1 || zone.internaldns2
                    zoneDhcpMap[zone.id?.toString()] = hasDhcp
                }
            }

            Observable<NetworkIdentityProjection> existingNetworks =
                morpheusContext.async.network.listIdentityProjections(cloud)

            SyncTask<NetworkIdentityProjection, Map, Network> syncTask =
                new SyncTask<>(existingNetworks, cloudNetworks as Collection<Map>)

            syncTask.addMatchFunction { NetworkIdentityProjection existingItem, Map cloudItem ->
                existingItem.externalId == cloudItem.id?.toString()
            }.onDelete { List<NetworkIdentityProjection> removeItems ->
                morpheusContext.async.network.remove(removeItems).blockingGet()
            }.onAdd { List<Map> addItems ->
                def newNetworks = addItems.collect { net ->
                    buildNetwork(net, zoneDhcpMap)
                }
                morpheusContext.async.network.create(newNetworks).blockingGet()
            }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkIdentityProjection, Map>> updateItems ->
                Map<Long, SyncTask.UpdateItemDto<NetworkIdentityProjection, Map>> updateItemMap =
                    updateItems.collectEntries { [(it.existingItem.id): it] }
                morpheusContext.async.network.listById(updateItemMap.keySet().toList()).map { Network network ->
                    SyncTask.UpdateItemDto<NetworkIdentityProjection, Map> matchedItem = updateItemMap[network.id]
                    return new SyncTask.UpdateItem<Network, Map>(existingItem: network, masterItem: matchedItem.masterItem)
                }
            }.onUpdate { List<SyncTask.UpdateItem<Network, Map>> updateItems ->
                def toUpdate = []
                updateItems.each { updateItem ->
                    def network = updateItem.existingItem
                    def net = updateItem.masterItem
                    def doUpdate = false
                    if (network.name != net.name) {
                        network.name = net.name
                        doUpdate = true
                    }
                    if (net.cidr && network.cidr != net.cidr) {
                        network.cidr = net.cidr
                        doUpdate = true
                    }
                    // Re-evaluate DHCP flag in case zone DNS config changed
                    def newDhcp = zoneDhcpMap[net.zoneid?.toString()] ?: false
                    if (network.dhcpServer != newDhcp) {
                        network.dhcpServer = newDhcp
                        doUpdate = true
                    }
                    if (doUpdate) {
                        toUpdate << network
                    }
                }
                if (toUpdate) {
                    morpheusContext.async.network.save(toUpdate).blockingGet()
                }
            }.start()

        } catch (Exception e) {
            log.error("Error syncing networks: ${e.message}", e)
        }
    }

    private Network buildNetwork(Map net, Map<String, Boolean> zoneDhcpMap) {
        // Determine if this network's zone has internal DHCP/DNS via the Virtual Router
        boolean dhcpEnabled = zoneDhcpMap[net.zoneid?.toString()] ?: false
        // Determine isolation type: 'Isolated' vs 'Shared'
        boolean isIsolated = (net.type?.toLowerCase() == 'isolated')

        return new Network(
            cloud: cloud,
            code: "cloudstack.network.${cloud.id}.${net.id}",
            externalId: net.id?.toString(),
            name: net.name,
            displayName: net.displaytext ?: net.name,
            cidr: net.cidr,
            active: true,
            description: net.networkofferingname ?: '',
            dhcpServer: dhcpEnabled,
            typeCode: isIsolated ? 'cloudstack-isolated' : 'cloudstack-shared'
        )
    }
}
