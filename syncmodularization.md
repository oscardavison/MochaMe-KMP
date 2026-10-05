Specification: :sync Subsystem Modularization & Relocation1. Scope & ObjectiveRearrange and namespace all sync-related modules under the :sync tree in settings.gradle.kts. This phase focuses strictly on directory relocations, convention plugin assignments, and dependency wiring. Test execution and runtime verification are explicitly deferred.2. Target Hierarchy & Conventions2.1 Module MappingPrevious Gradle PathNew Gradle PathTarget DirectoryApplied Convention Plugin(New):sync:protocolsync/protocolmocha.convention.logic:core:sync-api:sync:apisync/apimocha.convention.provider:node:sync:nodesync/node(No change to existing plugins):server:sync:serversync/server(No change to existing plugins):sync:engine:sync:enginesync/engine(No change to existing plugins)2.2 Direct Dependency Graph           :sync:server               :sync:engine
│                          │
│                          ├──────────► :sync:api
│                          │
▼                          ├──────────► :sync:node
:sync:protocol ◄─────────────────┘
3. Execution PlanStep 1: Update settings.gradle.ktsRemove the old top-level module declarations and register the consolidated :sync hierarchy:Kotlin// Remove deprecated includes:
   // include(":node")
   // include(":server")
   // include(":core:sync-api")

// Register consolidated :sync subprojects:
include(":sync:protocol")
include(":sync:api")
include(":sync:node")
include(":sync:server")
include(":sync:engine")
Step 2: Filesystem RelocationsMove :core:sync-api $\rightarrow$ :sync:api:Move directory core/sync-api to sync/api.Move :node $\rightarrow$ :sync:node:Move directory node to sync/node.Move :server $\rightarrow$ :sync:server:Move directory server to sync/server.Create Directory Structure for :sync:protocol:Create sync/protocol/src/commonMain/kotlin/com/mochame/sync/protocolCreate sync/protocol/build.gradle.ktsStep 3: WireFrame Source Relocation to :sync:protocolMove WireFrame sealed interfaces, opcodes, frame models, and existing internal byte-packing functions into:sync/protocol/src/commonMain/kotlin/com/mochame/sync/protocol/Update the package declaration:Kotlinpackage com.mochame.sync.protocol
Update imports in :sync:server and :sync:engine to reference com.mochame.sync.protocol.*.Step 4: Build Script Configurations (build.gradle.kts)1. sync/protocol/build.gradle.ktsKotlinplugins {
id("mocha.convention.logic")
}
2. sync/api/build.gradle.ktsKotlinplugins {
   id("mocha.convention.provider")
   }
3. sync/server/build.gradle.ktsRetain all existing convention plugins.Update dependencies:Kotlindependencies {
   // Replace :core:sync-api / :sync:api references with:
   implementation(project(":sync:protocol"))
   }
4. sync/engine/build.gradle.ktsRetain all existing convention plugins.Update dependencies:Kotlindependencies {
   implementation(project(":sync:api"))
   implementation(project(":sync:protocol"))
   implementation(project(":sync:node"))
   }
   Step 5: External Consumer Path UpdatesAudit and update external module references across the codebase:Features (feature/*/build.gradle.kts):Replace project(":core:sync-api") with project(":sync:api").Any modules referencing :node:Replace project(":node") with project(":sync:node").Any modules referencing :server:Replace project(":server") with project(":sync:server").


## Summary of Changes Made

### Step 1: Update settings.gradle.kts
- Removed deprecated top-level module inclusions: `include(":core:sync-api")`, `include(":node")`, `include(":sync-engine")`, `include(":server")`.
- Registered consolidated `:sync` subproject hierarchy:
  - `include(":sync:protocol")`
  - `include(":sync:api")`
  - `include(":sync:node")`
  - `include(":sync:server")`
  - `include(":sync:engine")`

### Step 2: Filesystem Relocations
- Moved `core/sync-api` -> `sync/api`
- Moved `node` -> `sync/node`
- Moved `server` -> `sync/server`
- Moved `sync-engine` -> `sync/engine`
- Created directory structures:
  - `sync/protocol/src/commonMain/kotlin/com/mochame/sync/protocol`
  - `sync/protocol/src/commonTest/kotlin/com/mochame/sync/protocol`

### Step 3: WireFrame Source Relocation to :sync:protocol
- Moved `WireFrame.kt` to `sync/protocol/src/commonMain/kotlin/com/mochame/sync/protocol/WireFrame.kt`.
- Updated package declaration to `package com.mochame.sync.protocol`.
- Moved `WireFrameTest.kt` to `sync/protocol/src/commonTest/kotlin/com/mochame/sync/protocol/WireFrameTest.kt` with package `com.mochame.sync.protocol`.
- Updated WireFrame imports across `:sync:server` (`DatabaseActor.kt`, `SyncRelayRoute.kt`, `DatabaseActorTest.kt`, `SyncRelayRouteTest.kt`) and `:sync:engine` (`ClientWebSocketTransport.kt`, `ClientWebSocketTransportTest.kt`) to reference `com.mochame.sync.protocol.*`.

### Step 4: Build Script Configurations (build.gradle.kts)
- Created `sync/protocol/build.gradle.kts` with `id("mocha.convention.logic")`.
- Updated `sync/api/build.gradle.kts` to use `id("mocha.convention.provider")`.
- Updated `sync/server/build.gradle.kts` dependency on `:core:sync-api` to `implementation(project(":sync:protocol"))`.
- Updated `sync/engine/build.gradle.kts` dependencies to explicitly include `implementation(project(":sync:api"))`, `implementation(project(":sync:protocol"))`, and `implementation(project(":sync:node"))`.

### Step 5: External Consumer Path Updates
- Audited and updated all build scripts across app, core, features, and build-logic conventions:
  - `app/assembly/build.gradle.kts` (`:sync:api`, `:sync:node`, `:sync:engine`)
  - `app/entry/androidApp/build.gradle.kts` (`:sync:api`)
  - `app/ui/build.gradle.kts` (`:sync:api`, `:sync:node`)
  - `build-logic/src/main/kotlin/mocha.convention.assembler.gradle.kts` (`:sync:api`)
  - `build-logic/src/main/kotlin/mocha.convention.feature.gradle.kts` (`:sync:api`)
  - `core/platform/build.gradle.kts` (`:sync:api`)
  - `core/test/fixtures-node/build.gradle.kts` (`:sync:node`, `:sync:api`)
  - `core/test/fixtures-platform/build.gradle.kts` (`:sync:api`)
  - `core/test/fixtures-sync-api/build.gradle.kts` (`:sync:api`)
  - `core/test/fixtures-utils/build.gradle.kts` (`:sync:api`)
  - `core/test/support/build.gradle.kts` (`:sync:api`)
  - `feature/bio/ui/build.gradle.kts` (`:sync:api`)