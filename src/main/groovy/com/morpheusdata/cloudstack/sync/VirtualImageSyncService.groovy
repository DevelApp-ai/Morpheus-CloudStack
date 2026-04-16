package com.morpheusdata.cloudstack.sync

import com.morpheusdata.cloudstack.CloudStackApiClient
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class VirtualImageSyncService {

    MorpheusContext morpheusContext
    CloudStackApiClient apiClient
    Cloud cloud

    VirtualImageSyncService(MorpheusContext morpheusContext, CloudStackApiClient apiClient, Cloud cloud) {
        this.morpheusContext = morpheusContext
        this.apiClient = apiClient
        this.cloud = cloud
    }

    void execute() {
        log.debug("Syncing CloudStack templates for cloud: ${cloud.name}")
        try {
            def config = cloud.configMap
            def result = apiClient.listTemplates(config.apiUrl, config.apiKey, config.secretKey, [:])
            if (!result.success) {
                log.error("Failed to list templates: ${result.error}")
                return
            }

            def templates = result.data?.listtemplatesresponse?.template ?: []
            log.debug("Found ${templates.size()} templates from CloudStack")

            Observable<VirtualImageIdentityProjection> existingImages =
                morpheusContext.async.virtualImage.listIdentityProjections(cloud.id)

            SyncTask<VirtualImageIdentityProjection, Map, VirtualImage> syncTask =
                new SyncTask<>(existingImages, templates as Collection<Map>)

            syncTask.addMatchFunction { VirtualImageIdentityProjection existingItem, Map cloudItem ->
                existingItem.externalId == cloudItem.id?.toString()
            }.onDelete { List<VirtualImageIdentityProjection> removeItems ->
                morpheusContext.async.virtualImage.remove(removeItems, cloud).blockingGet()
            }.onAdd { List<Map> addItems ->
                def newImages = addItems.collect { template ->
                    new VirtualImage(
                        code: "cloudstack.template.${cloud.id}.${template.id}",
                        externalId: template.id?.toString(),
                        name: template.name,
                        description: template.displaytext ?: template.name,
                        imageType: 'disk',
                        remotePath: template.url,
                        status: 'Active',
                        isPublic: template.ispublic ?: false,
                        active: true
                    )
                }
                morpheusContext.async.virtualImage.create(newImages, cloud).blockingGet()
            }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map>> updateItems ->
                Map<Long, SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map>> updateItemMap =
                    updateItems.collectEntries { [(it.existingItem.id): it] }
                morpheusContext.async.virtualImage.listById(updateItemMap.keySet().toList()).map { VirtualImage image ->
                    SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map> matchedItem = updateItemMap[image.id]
                    return new SyncTask.UpdateItem<VirtualImage, Map>(existingItem: image, masterItem: matchedItem.masterItem)
                }
            }.onUpdate { List<SyncTask.UpdateItem<VirtualImage, Map>> updateItems ->
                def toUpdate = []
                updateItems.each { updateItem ->
                    def image = updateItem.existingItem
                    def template = updateItem.masterItem
                    if (image.name != template.name) {
                        image.name = template.name
                        toUpdate << image
                    }
                }
                if (toUpdate) {
                    morpheusContext.async.virtualImage.save(toUpdate, cloud).blockingGet()
                }
            }.start()

        } catch (Exception e) {
            log.error("Error syncing templates: ${e.message}", e)
        }
    }
}

