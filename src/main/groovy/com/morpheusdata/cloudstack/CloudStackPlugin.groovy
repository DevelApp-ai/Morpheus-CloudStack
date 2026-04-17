package com.morpheusdata.cloudstack

import com.morpheusdata.core.Plugin

class CloudStackPlugin extends Plugin {

    @Override
    String getCode() {
        return 'cloudstack'
    }

    @Override
    void initialize() {
        CloudStackCloudProvider cloudProvider = new CloudStackCloudProvider(this, this.morpheus)
        this.pluginProviders.put(cloudProvider.code, cloudProvider)
        registerProvider(cloudProvider)

        CloudStackZoneDatasetProvider zoneDataset = new CloudStackZoneDatasetProvider(this, this.morpheus)
        this.pluginProviders.put(zoneDataset.code, zoneDataset)
        registerProvider(zoneDataset)
    }

    @Override
    void onDestroy() {}

    @Override
    String getName() {
        return 'Apache CloudStack'
    }
}
