package com.morpheusdata.cloudstack.sync

import com.morpheusdata.cloudstack.CloudStackApiClient
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.projection.ServicePlanIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class ServicePlanSyncService {

    MorpheusContext morpheusContext
    CloudStackApiClient apiClient
    Cloud cloud

    ServicePlanSyncService(MorpheusContext morpheusContext, CloudStackApiClient apiClient, Cloud cloud) {
        this.morpheusContext = morpheusContext
        this.apiClient = apiClient
        this.cloud = cloud
    }

    void execute() {
        log.debug("Syncing CloudStack service offerings for cloud: ${cloud.name}")
        try {
            def config = cloud.configMap
            def domainParams = config.domainId ? [domainid: config.domainId] : [:]
            def result = apiClient.listServiceOfferings(config.apiUrl, config.apiKey, config.secretKey, domainParams)
            if (!result.success) {
                log.error("Failed to list service offerings: ${result.error}")
                return
            }

            def offerings = result.data?.listserviceofferingsresponse?.serviceoffering ?: []
            log.debug("Found ${offerings.size()} service offerings from CloudStack")

            Observable<ServicePlanIdentityProjection> existingPlans =
                morpheusContext.async.servicePlan.listIdentityProjections(cloud.id)

            SyncTask<ServicePlanIdentityProjection, Map, ServicePlan> syncTask =
                new SyncTask<>(existingPlans, offerings as Collection<Map>)

            syncTask.addMatchFunction { ServicePlanIdentityProjection existingItem, Map cloudItem ->
                existingItem.externalId == cloudItem.id?.toString()
            }.onDelete { List<ServicePlanIdentityProjection> removeItems ->
                morpheusContext.async.servicePlan.remove(removeItems).blockingGet()
            }.onAdd { List<Map> addItems ->
                def newPlans = addItems.collect { offering ->
                    def plan = new ServicePlan(
                        code: "cloudstack.service.plan.${cloud.id}.${offering.id}",
                        externalId: offering.id?.toString(),
                        name: offering.name,
                        description: offering.displaytext ?: offering.name,
                        active: true,
                        provisionTypeCode: 'cloudstack-provision'
                    )
                    if (offering.cpunumber) plan.maxCpu = offering.cpunumber as Integer
                    if (offering.memory) plan.maxMemory = (offering.memory as Long) * 1024L * 1024L
                    return plan
                }
                morpheusContext.async.servicePlan.create(newPlans).blockingGet()
            }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ServicePlanIdentityProjection, Map>> updateItems ->
                Map<Long, SyncTask.UpdateItemDto<ServicePlanIdentityProjection, Map>> updateItemMap =
                    updateItems.collectEntries { [(it.existingItem.id): it] }
                morpheusContext.async.servicePlan.listById(updateItemMap.keySet().toList()).map { ServicePlan plan ->
                    SyncTask.UpdateItemDto<ServicePlanIdentityProjection, Map> matchedItem = updateItemMap[plan.id]
                    return new SyncTask.UpdateItem<ServicePlan, Map>(existingItem: plan, masterItem: matchedItem.masterItem)
                }
            }.onUpdate { List<SyncTask.UpdateItem<ServicePlan, Map>> updateItems ->
                def toUpdate = []
                updateItems.each { updateItem ->
                    def plan = updateItem.existingItem
                    def offering = updateItem.masterItem
                    def doUpdate = false
                    if (plan.name != offering.name) {
                        plan.name = offering.name
                        doUpdate = true
                    }
                    if (offering.memory) {
                        def newMemory = (offering.memory as Long) * 1024L * 1024L
                        if (plan.maxMemory != newMemory) {
                            plan.maxMemory = newMemory
                            doUpdate = true
                        }
                    }
                    if (doUpdate) {
                        toUpdate << plan
                    }
                }
                if (toUpdate) {
                    morpheusContext.async.servicePlan.save(toUpdate).blockingGet()
                }
            }.start()

        } catch (Exception e) {
            log.error("Error syncing service offerings: ${e.message}", e)
        }
    }
}
