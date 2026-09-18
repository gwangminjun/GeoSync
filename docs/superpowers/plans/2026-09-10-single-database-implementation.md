# Single Database Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace multi-target runtime behavior with one reloadable database connection while preserving the existing HTTP boundary and legacy configuration migration.

**Architecture:** Resolve one `DatabaseSettings` snapshot from canonical `spring.datasource`, legacy `targets[]`, or Spring startup defaults. A lease-based `DatabaseConnectionService` publishes validated connection sessions atomically; synchronization jobs pin one session for their full lifetime, while controllers adapt legacy index parameters only at the HTTP edge. YAML writes merge managed keys into the existing tree and replace the file atomically.

**Tech Stack:** Java 17, Spring Boot 3.3, Spring JDBC/HikariCP, SnakeYAML, Thymeleaf, JUnit 5, AssertJ, MockMvc, Gradle 8.10.

**Spec:** `docs/superpowers/specs/2026-09-10-single-database-design.md`

## Global Constraints

- Do not commit implementation changes until the user separately approves a commit.
- Preserve `targetIdx`, `target_index`, ODS `idx`, and `schema_<index>` request parameters at HTTP boundaries.
- Use `schema_0`, then the smallest numeric non-empty `schema_<index>`, then `ods.schema`.
- Never print or commit database passwords; `application.yml` and deployment assets contain no real credentials.
- Use a failing test before each production behavior change; run the focused test red, then green, then the relevant suite.
- Keep the user's existing `conf/application.yml` outside Git; copy it only into ignored test worktrees when integration tests require it.

### Task 1: Canonical settings resolution and atomic YAML persistence

**Files:**
- Create: `src/main/java/geosync/settings/DatabaseSettings.java`
- Create: `src/main/java/geosync/settings/SettingsFileService.java`
- Modify: `src/main/java/geosync/settings/RuntimeSettingsService.java`
- Modify: `src/main/java/geosync/settings/SettingsController.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/geosync/settings/RuntimeSettingsServiceTest.java`
- Create: `src/test/java/geosync/settings/SettingsFileServiceTest.java`

**Interfaces:**
- `DatabaseSettings` exposes `displayName()`, `url()`, `username()`, `password()`, and `source()`.
- `RuntimeSettingsService.database()` returns one resolved `DatabaseSettings`; it resolves Spring placeholders through `Environment`, never mixes fields between sources, and falls back to startup datasource values.
- `SettingsFileService.save(Map<String,String>)` merges managed YAML keys, removes only `targets`, and atomically replaces the file.

- [ ] **Step 1: Write failing tests** for canonical settings, legacy enabled/first-valid fallback, placeholder resolution, display-name fallback, preservation of unknown YAML keys, query parameters, and failed atomic writes.
- [ ] **Step 2: Run focused tests and verify RED** with `...GradleWrapperMain test --tests 'geosync.settings.*'`.
- [ ] **Step 3: Implement the settings record, resolver, and YAML merge writer** with one complete source selected at a time and password-safe logging.
- [ ] **Step 4: Run focused tests and verify GREEN**, then refactor `SettingsController` to delegate persistence.
- [ ] **Step 5: Add canonical settings defaults without real credentials** and rerun `DatabaseDefaultsTest`.

### Task 2: Lease-based single connection service and database-change lifecycle

**Files:**
- Create: `src/main/java/geosync/database/DatabaseSession.java`
- Create: `src/main/java/geosync/database/DatabaseConnectionService.java`
- Create: `src/main/java/geosync/database/DatabaseChangedEvent.java`
- Modify/Delete: `src/main/java/geosync/database/TargetDbService.java`
- Modify: `src/main/java/geosync/configuration/DatabaseConfig.java`
- Modify: `src/main/java/geosync/settings/SettingsController.java`
- Create: `src/test/java/geosync/database/DatabaseConnectionServiceTest.java`

**Interfaces:**
- `DatabaseConnectionService.acquire()` returns an `AutoCloseable DatabaseSession`.
- `DatabaseConnectionService.refresh()` validates `SELECT 1`, publishes a new holder atomically, retires the old holder, and preserves the old holder on failure.
- `DatabaseSession.jdbc()`, `url()`, `displayName()`, and `fingerprint()` are immutable for the lease lifetime.

- [ ] **Step 1: Write failing service tests** for reuse, successful replacement, failed replacement, active-lease draining, and shutdown.
- [ ] **Step 2: Run the focused service test and verify RED**.
- [ ] **Step 3: Implement the holder/lease state machine** with synchronized acquisition and retirement; retain Spring's startup `JdbcTemplate` as the fallback holder.
- [ ] **Step 4: Run the focused test and verify GREEN**; publish `DatabaseChangedEvent` only after the new connection is validated.
- [ ] **Step 5: Replace all Spring injections of `TargetDbService` with `DatabaseConnectionService`** and run compilation plus architecture tests.

### Task 3: Single-session synchronization, repositories, logs, and cache invalidation

**Files:**
- Modify: `src/main/java/geosync/synchronization/KrasWorker.java`
- Modify: `src/main/java/geosync/synchronization/SyncScheduler.java`
- Modify: `src/main/java/geosync/synchronization/SyncExecutionLogService.java`
- Modify: `src/main/java/geosync/gateway/ConnRequestLogService.java`
- Modify: `src/main/java/geosync/ods/UsezoneCodeService.java`
- Modify: `src/main/java/geosync/kras/KrasCatalogStatusService.java`
- Modify: `src/main/java/geosync/ods/OdsRepository.java`
- Create: `src/test/java/geosync/synchronization/SingleSessionWorkerTest.java`
- Create: `src/test/java/geosync/synchronization/DatabaseChangeLifecycleTest.java`

**Interfaces:**
- Worker entry points acquire one `DatabaseSession` and pass its `JdbcTemplate` to every write and log operation.
- `SyncExecutionLogService.start(session, ...)` and `finish(session, ...)` use the same session; connection-request logs capture a session fingerprint per queued record.
- A `DatabaseChangedEvent` clears DB-dependent caches and retries idempotent log-table initialization on the next access when setup failed.

- [ ] **Step 1: Write failing tests** proving one session spans start/load/finish, no target-list loop remains, old-session logs remain on the old DB, and caches clear on an event.
- [ ] **Step 2: Run focused tests and verify RED**.
- [ ] **Step 3: Collapse worker target/schema mapping to one schema and table filter**, preserving deprecated method overloads only as adapters that ignore target indices.
- [ ] **Step 4: Implement session-aware logging and event listeners**, then run synchronization and ODS tests.
- [ ] **Step 5: Run a mutation-oriented search** for `TargetDbService`, `TargetWithSchema`, `getActiveTargets()`, and target loops; remove remaining production references.

### Task 4: HTTP compatibility and UI simplification

**Files:**
- Modify: `src/main/java/geosync/database/DbSetupService.java`
- Modify: `src/main/java/geosync/database/DbSetupController.java`
- Modify: `src/main/java/geosync/ods/OdsController.java`
- Modify: `src/main/java/geosync/synchronization/SyncController.java`
- Modify: `src/main/resources/templates/settings.html`
- Modify: `src/main/resources/templates/dashboard.html`
- Modify: `src/main/resources/templates/ods.html`
- Modify: `src/main/resources/templates/schedule.html`
- Create: `src/test/java/geosync/architecture/SingleDatabaseHttpCompatibilityTest.java`

**Interfaces:**
- `/db/status` returns the existing array shape with exactly one status item.
- `/sync/kras-direct-load-options` returns the existing object shape with `targets[0]` only.
- `targetIdx`, `target_index`, and `idx` are accepted and ignored; schema selection follows the deterministic rule in the spec.

- [ ] **Step 1: Write failing MockMvc tests** for response shapes, out-of-range/negative indices, schema precedence, and table creation through the compatibility parameter.
- [ ] **Step 2: Run the focused compatibility tests and verify RED**.
- [ ] **Step 3: Implement controller adapters and single-DB service calls**; retain endpoint mappings unchanged.
- [ ] **Step 4: Replace settings, dashboard, ODS, and schedule target selectors with one DB card/status display** while keeping submitted legacy field names where required.
- [ ] **Step 5: Run `EndpointContractTest` plus the new MockMvc tests and verify GREEN**.

### Task 5: Full verification and delivery review

**Files:**
- Review: `src/test/java/geosync/architecture/PackageArchitectureTest.java`
- Review: `src/test/java/geosync/configuration/DatabaseDefaultsTest.java`
- Review: `src/main/resources/application.yml`, `conf/application.yml`, `scripts/`, and `build.gradle`

- [ ] **Step 1: Run all unit and integration tests** with the registered ignored `conf/application.yml` copied into the worktree.
- [ ] **Step 2: Run `bootJar` and `jlinkZip`** using the available JDK 17 path; record any deployment prerequisite failures separately.
- [ ] **Step 3: Run `git diff --check`, endpoint contract checks, and a credential scan that reports only whether secrets are present, never their values.
- [ ] **Step 4: Inspect `git status` and ensure no ignored credentials, build output, or user-owned `application.yml` changes are staged.
- [ ] **Step 5: Stop before committing and report the implementation diff and verification evidence for user approval.**
