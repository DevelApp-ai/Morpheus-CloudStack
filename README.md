# Apache CloudStack Plugin for Morpheus Data

A Morpheus Data cloud provider plugin that integrates [Apache CloudStack](https://cloudstack.apache.org/) as a first-class cloud in the Morpheus platform. It enables VM provisioning, lifecycle management, resource synchronisation and real-time telemetry — all backed by CloudStack's HMAC-SHA1-signed REST API.

---

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Configuration](#configuration)
- [Building](#building)
- [Testing](#testing)
- [Installation](#installation)
- [Sync Services](#sync-services)
- [API Client](#api-client)
- [CI Pipeline](#ci-pipeline)

---

## Features

| Category | Detail |
|---|---|
| **Cloud setup** | Dynamic Zone dropdown populated live from CloudStack credentials |
| **Validation** | Connectivity check (`listZones`) before saving the cloud |
| **VM lifecycle** | Deploy, start, stop, restart, destroy (async job polling with exponential backoff) |
| **Sync — daily** | Zones → `CloudRegion`, Service Offerings → `ServicePlan`, Templates → `VirtualImage`, Disk Offerings → `StorageVolumeType` |
| **Sync — frequent** | Networks → `Network`, Virtual Machines → `ComputeServer` |
| **Telemetry** | CPU %, used/free memory via `listVirtualMachinesMetrics` |
| **User data** | Cloud-init payload Base64-encoded and injected at deploy time |
| **Tagging** | Morpheus tags propagated to CloudStack VM resources via `createTags` |
| **Multi-tenancy** | Optional Domain ID scopes every API call for multi-tenant CloudStack deployments |

---

## Architecture

```
CloudStackPlugin
├── CloudStackCloudProvider          (CloudProvider)
│   ├── getOptionTypes()             API URL / API Key / Secret Key / Domain ID
│   ├── validate()                   Calls listZones to verify connectivity
│   ├── refresh()                    NetworkSyncService + VirtualMachineSyncService
│   ├── refreshDaily()               ZoneSyncService + ServicePlanSyncService +
│   │                                VirtualImageSyncService + DiskOfferingSyncService
│   ├── getServerStats()             listVirtualMachinesMetrics → ServerStatsData
│   └── startServer / stopServer / deleteServer
│
├── CloudStackProvisionProvider      (WorkloadProvisionProvider)
│   ├── runWorkload()                deployVirtualMachine + async job polling
│   ├── stopWorkload / startWorkload / restartWorkload / removeWorkload
│   └── getServerDetails()           listVirtualMachines by externalId
│
├── CloudStackZoneDatasetProvider    (DatasetProvider)
│   └── list()                       listZones → Zone select dropdown
│
└── CloudStackApiClient              (signed HTTP REST client)
    ├── HMAC-SHA1 request signing
    └── 18 CloudStack API commands
```

---

## Prerequisites

| Requirement | Version |
|---|---|
| Java | 17 |
| Gradle (wrapper provided) | 8.11 |
| Morpheus Appliance | 6.x or later (Plugin API 1.3.0) |
| CloudStack | 4.x or later |

---

## Configuration

When adding an Apache CloudStack cloud in Morpheus (**Infrastructure → Clouds → + Add**), provide:

| Field | Description | Required |
|---|---|---|
| **API URL** | CloudStack API endpoint, e.g. `http://cloudstack-host/client/api` | ✅ |
| **API Key** | CloudStack user API key | ✅ |
| **Secret Key** | CloudStack user secret key | ✅ |
| **Domain ID** | CloudStack Domain ID to scope resources (multi-tenant deployments) | ✗ |

After entering credentials the **Zone** field populates automatically via `CloudStackZoneDatasetProvider`.

---

## Building

```bash
# Clone the repository
git clone https://github.com/DevelApp-ai/Morpheus-CloudStack.git
cd Morpheus-CloudStack

# Build the fat JAR (includes all runtime dependencies)
./gradlew shadowJar
```

The output JAR is written to `build/libs/cloudstack-plugin-1.0.0-all.jar`.

---

## Testing

```bash
# Run all unit tests (Spock framework, JUnit Platform)
./gradlew test

# HTML test report is generated at:
#   build/reports/tests/test/index.html
```

The test suite contains **172 tests** across six specification classes:

| Specification | Tests | Coverage |
|---|---|---|
| `CloudStackPluginSpec` | 8 | Plugin code/name, provider registration, `onDestroy` |
| `CloudStackApiClientSpec` | 26 | All 18 API commands, HMAC signing, special chars, error paths |
| `CloudStackCloudProviderSpec` | 51 | Option types, capability flags, `validate()`, server lifecycle, `getServerStats` |
| `CloudStackProvisionProviderSpec` | 35 | `runWorkload`, async job polling, keypair/userdata/tag handling, `getServerDetails` |
| `CloudStackZoneDatasetProviderSpec` | 20 | Dataset metadata, `list()` all paths, `listOptions`, item helpers |
| `VirtualMachineSyncServiceSpec` | 32 | `mapVmStatus` all states, `buildComputeServer` field mapping, `execute()` error handling |

---

## Installation

1. Build the fat JAR as described above (or download a release artifact).
2. In the Morpheus UI go to **Administration → Plugins**.
3. Click **+ Upload Plugin** and select `cloudstack-plugin-1.0.0-all.jar`.
4. The plugin is activated immediately — **Apache CloudStack** appears as a cloud type.

---

## Sync Services

| Service | CloudStack command | Morpheus model | Schedule |
|---|---|---|---|
| `ZoneSyncService` | `listZones` | `CloudRegion` | Daily |
| `ServicePlanSyncService` | `listServiceOfferings` | `ServicePlan` | Daily |
| `VirtualImageSyncService` | `listTemplates` | `VirtualImage` | Daily |
| `DiskOfferingSyncService` | `listDiskOfferings` | `StorageVolumeType` | Daily |
| `NetworkSyncService` | `listNetworks` + `listZones` | `Network` | Every refresh |
| `VirtualMachineSyncService` | `listVirtualMachines` | `ComputeServer` | Every refresh |

All sync services propagate the configured `domainId` to every API call for correct multi-tenant scoping.

---

## API Client

`CloudStackApiClient` executes all CloudStack API calls using HMAC-SHA1 request signing:

1. Collect parameters, add `apikey` and `response=json`.
2. Sort parameters alphabetically by lowercase key.
3. Build query string with lowercase-encoded keys and URL-encoded values.
4. Compute `HMAC-SHA1(queryString, secretKey)`, Base64-encode, then URL-encode.
5. Append `&signature=<value>` and execute an HTTP GET.

Supported commands:

`listZones` · `listServiceOfferings` · `listTemplates` · `listNetworks` · `listVirtualMachines` · `deployVirtualMachine` · `startVirtualMachine` · `stopVirtualMachine` · `destroyVirtualMachine` · `queryAsyncJobResult` · `listDiskOfferings` · `createVolume` · `attachVolume` · `listVolumes` · `listVirtualMachinesMetrics` · `createTags` · `listTags`

### Async job polling

Long-running operations (deploy / start / stop / destroy) return a `jobid`. The client polls `queryAsyncJobResult` using **exponential backoff with jitter** (initial 2 s, cap 30 s, max 60 attempts).

---

## CI Pipeline

Every push and pull request triggers the GitHub Actions workflow defined in `.github/workflows/ci.yml`:

1. Check out code
2. Set up Java 17 (Temurin)
3. Configure Gradle via `gradle/actions/setup-gradle`
4. Run `./gradlew test`
5. Upload HTML test report as a build artifact (retained 7 days)