# MochaMe-KMP
## v0.2.0

<p align="center">
  <img src="/docs/images/readme_applogo.webp" alt="app logo" width="220" height="288">
</p>

---

<details>
<summary><b> Local First Architecture </b></summary>

<br>

Needs Updating 

<p align="center">
  <img width="220" height="288" alt="logo" src="/docs/images/readme_applogo.webp" />
</p>

</details>

---

<details>
<summary><b> Approach to Gradle Build Design </b></summary>

#### Gradle Build-Logic Configs

###### Core builds to plug in, and what they provide.

##### 1. `mocha.provider` (Lightweight Infrastructure)

**Purpose:** Configures pure structural dependencies and targets for modules that act as APIs or expect/actual providers for simple requirements. Avoids all test runner configuration.

- **Targets Declared:** `jvm()`, `linuxX64()`, `android()`.
- **Source Sets Declared:** None explicitly.
- **Test Runners:** None.
- **Koin Compiler:** Applied.
- **Room Scope:** None.

##### 2. `mocha.logic` (Pure Logic)

**Purpose:** Configures the standard execution environment for pure Kotlin modules that do not define persistence schemas. Essentially just tools.

- **Targets Declared:** `jvm()`, `linuxX64()`, `android()`.
- **Source Sets Declared:** `androidHostTest`, `androidDeviceTest`, `jvmTest`
- **Test Runners:** Standard test runners (`androidHostTest`, `jvmTest`, `linuxX64Test`, iOS) injected - _unit tests_.
- **Koin Compiler:** Applied.
- **Room Scope:** None.

##### 3. `mocha.feature` (Heavy Components)

**Purpose:** Designed exclusively for features that require isolated micro-schemas for testing their integration logic across platforms.

- **Targets Declared:** `jvm()`, `linuxX64()`, `android()`.
- **Source Sets Declared:** `commonMain`: Injects `implementation(libs.room.runtime)` to allow `@Dao` and `@Entity` compilation.
- **Test Runners:** Standard test runners (`androidHostTest`, `jvmTest`, `linuxX64Test`) injected.
- **Koin Compiler:** Applied.
- **Room Scope:** Runtime: Provided to `commonMain`.
- **KSP (Compiler):** Applied strictly to test configurations purely for Room, not Koin (e.g., `kspAndroidHostTest`, `kspJvmTest`). Keeps main targets free of generation overhead - no `@Database` in main, purely in test.

##### 4. `mocha.assembler` (Aggregator)

**Purpose:** Configures the final assembly points where domain DAOs are aggregated into production code.

- **Targets Declared:** `jvm()`, `linuxX64()`, `android()`.
- **Source Sets Declared:** `commonMain`: Injects `implementation(libs.room.runtime)`.
- **Test Runners:** Fully configured.
- **Koin Compiler:** Applied.
- **Room Scope:** Runtime: Provided to `commonMain`.
- **KSP (Compiler):** Applied to standard main configurations (e.g., `kspAndroid`, `kspJvm`). Expect `@Database` in main.

<br>

<img src="docs/images/gradle-arch.webp" alt="gradle architecture mermaid diagram">

</details>

---

<details>
<summary><b> Serialization Architecture </b></summary>

<br>

## Outbound

<img src="docs/images/outbound2.webp" alt="mermaid diagram of outbound data flow">

## Inbound

<img src="docs/images/inbound2.webp" alt="mermaid diagram of inbound data flow">

</details>

---

<details>
<summary><b> Data Model </b></summary>

<br>

Simply to provide a model for the local-first testing. Only DailyContext is implemented:

The model below is changing, and now includes sync metadata. 
```mermaid
classDiagram
    %% --- BIO MODULE (The Context) ---
    class DailyContext {
        <<Entity>>
        +String id PK "UUID"
        +Long epochDay "Unique Index"
        +Double sleepHours
        +Int readinessScore
        +Bool isNapped "The Outlier Flag"
        +Long lastModified "Sync Timestamp"
    }

    %% --- TELEMETRY MODULE (The Work) ---
    class Domain {
        <<Entity>>
        +String id PK "UUID"
        +String name
        +String hexColor
        +String iconKey
        +Boolean isActive
        +Long lastModified "Sync Timestamp"
    }

    class Topic {
        <<Entity>>
        +String id PK "UUID"
        +String domainId FK "UUID"
        +String name
        +Boolean isActive
        +Long lastModified "Sync Timestamp"
    }

    class Moment {
        <<Entity>>
        +String id PK "UUID"
        +String domainId FK "fka categoryId"
        +String? topicId FK
        +String? spaceId FK
        +Int satisfactionScore "1-10"
        +Int mood
        +Int energyDelta "-5 to +5"
        +Int intensityScale "1-10"
        +Int? entryEnergy "1-10"
        +String? note
        +Boolean? isFocusTime
        +Int? socialScale "fka isSocial"
        +Int? biophiliaScale "1-5"
        +Int? durationMinutes
        +Boolean? isDaylight
        +Int? cloudDensity
        +Boolean? isPrecipitating
        +Long timestamp "ms"
        +Long associatedEpochDay "4am Rule"
        +Long lastModified "Sync Timestamp"
    }

    class Space {
        <<Entity>>
        +String id PK "UUID"
        +String name "Unique"
        +String iconKey
        +Int? defaultBiophilia "1-5"
        +Boolean isControlled "Private vs Public"
        +Boolean isActive
        +Long lastModified
    }

    %% --- SIGNAL MODULE (The Archive) ---
    class Author {
        <<Entity>>
        +String id PK "UUID"
        +String name
        +Boolean isActive
        +Long lastModified "Sync Timestamp"
    }

    class Book {
        <<Entity>>
        +String id PK "UUID"
        +String authorId FK "UUID"
        +String title
        +Long dateAdded
        +Long dateFinished
        +Boolean isActive
        +Long lastModified "Sync Timestamp"
    }

    class Quote {
        <<Entity>>
        +String id PK "UUID"
        +String bookId FK "UUID"
        +String content
        +Resonance resonance
        +Int viewCount
        +Long lastModified "Sync Timestamp"
    }

    class Resonance {
        <<Enumeration>>
        WONDER
        LOGIC
        SADNESS
        JOY
    }

    class Mood {
        <<Enumeration (PAD MODEL)>>
        FOCUS
        WONDER
        ENERGIZED
        CALM
        NEUTRAL
        BORED
        TIRED
        SAD
        FRUSTRATED
        +pleasure: Int
        +energy: Int
        +agency: Int
        +fromName(name: String) Mood$
    }


    %% --- RELATIONSHIPS ---
    Moment "*" --> "1" Space : occurs in
    Domain "1" -- "*" Topic : contains
    Domain "1" -- "*" Moment : anchors
    Topic "0..1" -- "*" Moment : refines
    DailyContext .. Moment : Analytic Link (Join on epochDay)
    Author "1" -- "*" Book : writes
    Book "1" -- "*" Quote : contains
    Quote ..> Resonance : matches
    Moment ..> Mood : holds
```

</details>

---

<details>
<summary><b> Testing Architecture & Commands </b></summary>

<br>

### Testing 

| Command                        | Target              | Dependencies                   |
|:-------------------------------|:--------------------|:-------------------------------|
| **allTests**                   | `commonTest`        | `kotlin.test`, Turbine         |
| **jvmTest**                    | `jvmTest`           | JUnit 5                        |
| **testAndroidHost**            | `androidHostTest`   | Robolectric, JUnit 4 (Vintage) |
| **connectedAndroidDeviceTest** | `androidDeviceTest` | AndroidJUnitRunner             |
| **linuxX64Test**               | `linuxX64Test`      | Parity with `commonTest`       |

Server tested with `Kotest`, which I would probably integrate into future testing.

</details>

---