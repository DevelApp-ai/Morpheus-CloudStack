package com.morpheusdata.cloudstack.sync

import com.morpheusdata.cloudstack.CloudStackApiClient
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.StorageVolumeType
import groovy.util.logging.Slf4j

/**
 * Syncs CloudStack Disk Offerings to Morpheus StorageVolumeType entries.
 * Uses listAll + create/save pattern since StorageVolumeType has no
 * separate IdentityProjection in the Plugin API 1.3.0.
 */
@Slf4j
class DiskOfferingSyncService {

    MorpheusContext morpheusContext
    CloudStackApiClient apiClient
    Cloud cloud

    DiskOfferingSyncService(MorpheusContext morpheusContext, CloudStackApiClient apiClient, Cloud cloud) {
        this.morpheusContext = morpheusContext
        this.apiClient = apiClient
        this.cloud = cloud
    }

    void execute() {
        log.debug("Syncing CloudStack disk offerings for cloud: ${cloud.name}")
        try {
            def config = cloud.configMap
            def domainParams = config.domainId ? [domainid: config.domainId] : [:]
            def result = apiClient.listDiskOfferings(config.apiUrl, config.apiKey, config.secretKey, domainParams)
            if (!result.success) {
                log.error("Failed to list disk offerings: ${result.error}")
                return
            }

            def diskOfferings = result.data?.listdiskofferingsresponse?.diskoffering ?: []
            log.debug("Found ${diskOfferings.size()} disk offerings from CloudStack")

            // Fetch existing volume types to reconcile
            List<StorageVolumeType> existingTypes = morpheusContext.async.storageVolume.storageVolumeType
                .listAll()
                .filter { it.code?.startsWith("cloudstack.disk.${cloud.id}.") }
                .toList()
                .blockingGet()

            Map<String, StorageVolumeType> existingByExternalId = existingTypes.collectEntries {
                [(it.externalId): it]
            }

            def toAdd = []
            def toUpdate = []
            def seenIds = [] as Set

            diskOfferings.each { offering ->
                def externalId = offering.id?.toString()
                seenIds << externalId
                def existing = existingByExternalId[externalId]
                if (existing) {
                    def doUpdate = false
                    if (existing.name != offering.name) {
                        existing.name = offering.name
                        doUpdate = true
                    }
                    if (offering.disksize) {
                        def newSize = (offering.disksize as Long) * 1024L * 1024L * 1024L
                        if (existing.minStorageSize != newSize) {
                            existing.minStorageSize = newSize
                            existing.maxStorageSize = newSize
                            doUpdate = true
                        }
                    }
                    if (doUpdate) toUpdate << existing
                } else {
                    toAdd << buildStorageVolumeType(offering)
                }
            }

            // Remove stale types no longer present in CloudStack
            def toRemove = existingTypes.findAll { !(it.externalId in seenIds) }

            if (toAdd) {
                morpheusContext.async.storageVolume.storageVolumeType.create(toAdd).blockingGet()
                log.debug("Created ${toAdd.size()} disk offering types")
            }
            if (toUpdate) {
                morpheusContext.async.storageVolume.storageVolumeType.save(toUpdate).blockingGet()
                log.debug("Updated ${toUpdate.size()} disk offering types")
            }
            if (toRemove) {
                morpheusContext.async.storageVolume.storageVolumeType.remove(toRemove).blockingGet()
                log.debug("Removed ${toRemove.size()} stale disk offering types")
            }

        } catch (Exception e) {
            log.error("Error syncing disk offerings: ${e.message}", e)
        }
    }

    private StorageVolumeType buildStorageVolumeType(Map offering) {
        def volumeType = new StorageVolumeType(
            code: "cloudstack.disk.${cloud.id}.${offering.id}",
            externalId: offering.id?.toString(),
            name: offering.name,
            description: offering.displaytext ?: offering.name,
            displayOrder: 0,
            defaultType: false,
            enabled: true,
            resizable: true,
            provisionTypeCode: 'cloudstack-provision'
        )
        // Map disk size (GB → bytes)
        if (offering.disksize) {
            def sizeBytes = (offering.disksize as Long) * 1024L * 1024L * 1024L
            volumeType.minStorageSize = sizeBytes
            volumeType.maxStorageSize = sizeBytes
        }
        // Custom IOPS support
        if (offering.iscustomizediops?.toString() == 'true') {
            volumeType.customizable = true
        }
        return volumeType
    }
}


