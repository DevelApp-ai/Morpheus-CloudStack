package com.morpheusdata.cloudstack.sync

import com.morpheusdata.cloudstack.CloudStackApiClient
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudRegion
import com.morpheusdata.model.projection.CloudRegionIdentity
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class ZoneSyncService {

    MorpheusContext morpheusContext
    CloudStackApiClient apiClient
    Cloud cloud

    ZoneSyncService(MorpheusContext morpheusContext, CloudStackApiClient apiClient, Cloud cloud) {
        this.morpheusContext = morpheusContext
        this.apiClient = apiClient
        this.cloud = cloud
    }

    void execute() {
        log.debug("Syncing CloudStack zones for cloud: ${cloud.name}")
        try {
            def config = cloud.configMap
            def domainParams = config.domainId ? [domainid: config.domainId] : [:]
            def result = apiClient.listZones(config.apiUrl, config.apiKey, config.secretKey, domainParams)
            if (!result.success) {
                log.error("Failed to list zones: ${result.error}")
                return
            }

            def cloudZones = result.data?.listzonesresponse?.zone ?: []
            log.debug("Found ${cloudZones.size()} zones from CloudStack")

            Observable<CloudRegionIdentity> existingRegions =
                morpheusContext.async.cloud.region.listIdentityProjections(cloud.id)

            SyncTask<CloudRegionIdentity, Map, CloudRegion> syncTask =
                new SyncTask<>(existingRegions, cloudZones as Collection<Map>)

            syncTask.addMatchFunction { CloudRegionIdentity existingItem, Map cloudItem ->
                existingItem.externalId == cloudItem.id?.toString()
            }.onDelete { List<CloudRegionIdentity> removeItems ->
                morpheusContext.async.cloud.region.remove(removeItems).blockingGet()
            }.onAdd { List<Map> addItems ->
                def newRegions = addItems.collect { zone ->
                    new CloudRegion(
                        cloud: cloud,
                        code: "cloudstack.zone.${cloud.id}.${zone.id}",
                        externalId: zone.id?.toString(),
                        name: zone.name,
                        regionCode: zone.id?.toString(),
                        internalId: zone.id?.toString()
                    )
                }
                morpheusContext.async.cloud.region.create(newRegions).blockingGet()
            }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<CloudRegionIdentity, Map>> updateItems ->
                Map<Long, SyncTask.UpdateItemDto<CloudRegionIdentity, Map>> updateItemMap =
                    updateItems.collectEntries { [(it.existingItem.id): it] }
                morpheusContext.async.cloud.region.listById(updateItemMap.keySet().toList()).map { CloudRegion region ->
                    SyncTask.UpdateItemDto<CloudRegionIdentity, Map> matchedItem = updateItemMap[region.id]
                    return new SyncTask.UpdateItem<CloudRegion, Map>(existingItem: region, masterItem: matchedItem.masterItem)
                }
            }.onUpdate { List<SyncTask.UpdateItem<CloudRegion, Map>> updateItems ->
                def toUpdate = []
                updateItems.each { updateItem ->
                    def region = updateItem.existingItem
                    def zone = updateItem.masterItem
                    if (region.name != zone.name) {
                        region.name = zone.name
                        toUpdate << region
                    }
                }
                if (toUpdate) {
                    morpheusContext.async.cloud.region.save(toUpdate).blockingGet()
                }
            }.start()

        } catch (Exception e) {
            log.error("Error syncing zones: ${e.message}", e)
        }
    }
}
