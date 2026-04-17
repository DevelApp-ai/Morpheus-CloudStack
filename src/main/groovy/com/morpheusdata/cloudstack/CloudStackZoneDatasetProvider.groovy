package com.morpheusdata.cloudstack

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DatasetInfo
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.core.providers.DatasetProvider
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

/**
 * DatasetProvider that populates the Zone dropdown dynamically during cloud setup.
 * When an admin enters API credentials, this provider queries CloudStack and lists
 * available Zones for selection (wired via OptionType.optionSource = 'cloudstackZones').
 */
@Slf4j
class CloudStackZoneDatasetProvider implements DatasetProvider<Map, String> {

    Plugin plugin
    MorpheusContext morpheusContext
    CloudStackApiClient apiClient

    CloudStackZoneDatasetProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.plugin = plugin
        this.morpheusContext = morpheusContext
        this.apiClient = new CloudStackApiClient()
    }

    @Override
    String getCode() {
        return 'cloudstack-zones'
    }

    @Override
    String getName() {
        return 'CloudStack Zones'
    }

    @Override
    MorpheusContext getMorpheus() {
        return morpheusContext
    }

    @Override
    Plugin getPlugin() {
        return plugin
    }

    @Override
    DatasetInfo getInfo() {
        return new DatasetInfo('cloudstack', 'cloudstackZones', 'CloudStack Zones',
            'Available CloudStack availability zones')
    }

    @Override
    String getKey() {
        return 'cloudstackZones'
    }

    @Override
    Class<Map> getItemType() {
        return Map.class
    }

    /**
     * Returns zone list as Map items. DatasetQuery params contain cloud config with
     * apiUrl/apiKey/secretKey from the partially-filled cloud form.
     */
    @Override
    Observable<Map> list(DatasetQuery query) {
        def params = query?.parameters
        def apiUrl = params?.get('config.apiUrl') ?: params?.get('apiUrl')
        def apiKey = params?.get('config.apiKey') ?: params?.get('apiKey')
        def secretKey = params?.get('config.secretKey') ?: params?.get('secretKey')

        if (!apiUrl || !apiKey || !secretKey) {
            return Observable.empty()
        }

        try {
            def result = apiClient.listZones(apiUrl as String, apiKey as String, secretKey as String, [:])
            if (!result.success) {
                log.warn("Failed to fetch zones for dataset: ${result.error}")
                return Observable.empty()
            }
            def zones = (result.data?.listzonesresponse?.zone ?: []) as List<Map>
            return Observable.fromIterable(zones.collect { zone ->
                [id: zone.id, name: zone.name, value: zone.id, text: zone.name] as Map
            })
        } catch (Exception e) {
            log.error("Error listing zones for dataset: ${e.message}", e)
            return Observable.empty()
        }
    }

    @Override
    Observable<Map> listOptions(DatasetQuery query) {
        return list(query)
    }

    @Override
    Map fetchItem(Object value) {
        return [id: value, name: value, value: value, text: value]
    }

    @Override
    Map item(String value) {
        return [id: value, name: value, value: value, text: value]
    }

    @Override
    String itemName(Map item) {
        return item?.name?.toString() ?: item?.text?.toString()
    }

    @Override
    String itemValue(Map item) {
        return item?.id?.toString() ?: item?.value?.toString()
    }
}

