# Repository and Package Reorganization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve every external runtime contract while reorganizing Java code by feature and moving loose reference documents under `docs/`.

**Architecture:** Keep `geosync.SyncApplication` as the component-scan root and relocate existing classes into feature-owned packages without changing their implementation. Add characterization and architecture tests before each move so endpoint mappings, operational paths, and target package placement remain executable contracts.

**Tech Stack:** Java 17, Spring Boot 3.3.5, Spring MVC, Gradle, JUnit Jupiter, AssertJ, ArchUnit 1.3.0

**Spec:** `docs/superpowers/specs/2026-09-10-repository-package-reorganization-design.md`

## Global Constraints

- Do not change HTTP URLs, HTTP methods, request parameter names/defaults/required flags, path-variable names, response bodies, or Thymeleaf view names.
- Do not change YAML keys, default operational paths, `conf/`, Windows service behavior, or the distribution ZIP layout.
- Do not split classes, fix known bugs, or refactor method bodies in this change.
- Production source edits are limited to file moves, `package` declarations, imports, and package-private test access required by the moves.
- Do not commit any change unless the user explicitly approves that specific commit.
- Do not claim completion until tests and `bootJar` run with Java 17 or newer.
- Preserve pre-existing user changes and do not use destructive Git reset or checkout commands.

---

## File Map

### New test files

- `src/test/java/geosync/architecture/EndpointContractTest.java`: snapshots Spring MVC route and parameter annotations independently of package names.
- `src/test/java/geosync/architecture/PackageArchitectureTest.java`: asserts the approved destination of every production class and the small set of dependency/access rules.
- `src/test/java/geosync/architecture/RepositoryLayoutTest.java`: protects operational paths and the final documentation layout.
- `src/test/resources/contracts/http-endpoints.txt`: reviewed, deterministic endpoint-contract snapshot captured before package moves.

### Build file

- `build.gradle`: adds `com.tngtech.archunit:archunit-junit5:1.3.0` as a test-only dependency.

### Production package moves

| Current package | Classes | Destination |
|---|---|---|
| `geo` | `CoordTransformer` | `common.geo` |
| `util` | `XmlUtil` | `common.xml` |
| `config` | `DatabaseConfig`, `KrasGpkiProperties`, `TargetDb`, `TargetDbProperties` | `configuration` |
| `service` | `RuntimeSettingsService` | `settings` |
| `web` | `SettingsController` | `settings` |
| `service` | `TargetDbService`, `TargetTableNameService`, `DbSetupService` | `database` |
| `web` | `DbSetupController` | `database` |
| `repository` | `OdsRepository`, `OdsTableDdl` | `ods` |
| `service` | `UsezoneCodeService` | `ods` |
| `web` | `OdsController` | `ods` |
| `worker` | `KrasApiClient`, `KorepsApiClient`, `KrasFileReader`, `KrasFileWriter`, `KrasWorkspaceScanner` | `kras` |
| `service` | `KrasGpkiService`, `KrasFileDownloadService`, `KrasTxtLoaderService`, `KrasCatalogStatusService` | `kras` |
| `web` | `FileDownloadController`, `MockGatewayController` | `kras` |
| `service` | `ConnRequestLogService` | `gateway` |
| `web` | `KrasConnController`, `KrasGmxController`, `GatewayPaths`, `ConnStatsController`, `ApiTestProxyController` | `gateway` |
| `model` | `ColumnDef`, `SyncTableDef`, `SyncHistory` | `synchronization.model` |
| `mapper` | `TableMapper` | `synchronization` |
| `worker` | `KrasWorker` | `synchronization` |
| `service` | `SyncStatusService`, `SyncExecutionLogService` | `synchronization` |
| `scheduler` | `SyncScheduler`, `DynamicScheduleManager` | `synchronization` |
| `web` | `SyncController`, `ScheduleController` | `synchronization` |
| `model` | `CatalogFileStatus` | `monitoring` |
| `web` | `DashboardController`, `LogController`, `ApiTestController` | `monitoring` |

### Document moves

- `46870_DATA_CATALOG.md` → `docs/reference/46870-data-catalog.md`
- `lt_c_uzone_plan.txt` → `docs/reference/lt-c-uzone-plan.md`
- `mock/KRAS_GEOSYNC_SYNC_STRUCTURE.md` → `docs/reference/kras-geosync-structure.md`
- `IMPROVEMENTS.md` → `docs/reviews/improvements.md`

---

### Task 1: Establish the Java baseline and endpoint contract

**Files:**

- Create: `src/test/java/geosync/architecture/EndpointContractTest.java`
- Modify: `build.gradle`

**Interfaces:**

- Consumes: Spring MVC controller annotations already present in production code.
- Produces: a package-name-independent `Set<String>` contract with records shaped as `VERB path | consumes | produces | parameters`.

- [ ] **Step 1: Locate a usable Java runtime**

Run:

```powershell
Get-Command java -ErrorAction SilentlyContinue
Get-ChildItem 'C:\Program Files\Java','C:\Program Files\Eclipse Adoptium','C:\Program Files\Microsoft' -Directory -ErrorAction SilentlyContinue
```

Expected: either `java.exe` is available or a Java 17+ installation directory can be assigned to `$env:JAVA_HOME` for this terminal. If neither exists, stop implementation and report the environment blocker.

- [ ] **Step 2: Run the existing tests before changing source packages**

Run: `.\gradlew.bat test`

Expected: `BUILD SUCCESSFUL`. Any existing failure must be diagnosed before package moves begin.

- [ ] **Step 3: Add the test-only architecture dependency**

Add inside `dependencies` in `build.gradle`:

```groovy
testImplementation 'com.tngtech.archunit:archunit-junit5:1.3.0'
```

- [ ] **Step 4: Write the endpoint characterization test**

Create `EndpointContractTest` with:

```java
package geosync.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointContractTest {
    private static final Class<?>[] CONTROLLERS = {
        geosync.web.ApiTestController.class,
        geosync.web.ApiTestProxyController.class,
        geosync.web.ConnStatsController.class,
        geosync.web.DashboardController.class,
        geosync.web.DbSetupController.class,
        geosync.web.FileDownloadController.class,
        geosync.web.KrasConnController.class,
        geosync.web.KrasGmxController.class,
        geosync.web.LogController.class,
        geosync.web.MockGatewayController.class,
        geosync.web.OdsController.class,
        geosync.web.ScheduleController.class,
        geosync.web.SettingsController.class,
        geosync.web.SyncController.class
    };

    @Test
    void preservesEveryHttpMapping() {
        assertThat(snapshot()).containsExactlyInAnyOrderElementsOf(expectedContract());
    }

    private static Set<String> snapshot() {
        Set<String> result = new LinkedHashSet<>();
        for (Class<?> controller : CONTROLLERS) {
            RequestMapping type = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String prefix = type == null || type.path().length == 0 ? "" : type.path()[0];
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                String path = prefix + (mapping.path().length == 0 ? "" : mapping.path()[0]);
                String verbs = mapping.method().length == 0 ? "ANY" :
                    Arrays.stream(mapping.method()).map(RequestMethod::name).sorted().toList().toString();
                String params = Arrays.stream(method.getParameters())
                    .map(EndpointContractTest::parameterContract).sorted().toList().toString();
                result.add(controller.getSimpleName() + " | " + verbs + " " +
                    (path.isEmpty() ? "/" : path) + " | " +
                    Arrays.toString(mapping.consumes()) + " | " +
                    Arrays.toString(mapping.produces()) + " | " + params);
            }
        }
        return result;
    }

    private static String parameterContract(Parameter parameter) {
        var requestParam = parameter.getAnnotation(org.springframework.web.bind.annotation.RequestParam.class);
        if (requestParam != null) {
            String name = requestParam.name().isBlank() ? parameter.getName() : requestParam.name();
            return "request:" + name + ":" + requestParam.required() + ":" + requestParam.defaultValue();
        }
        var pathVariable = parameter.getAnnotation(org.springframework.web.bind.annotation.PathVariable.class);
        if (pathVariable != null) {
            String name = pathVariable.name().isBlank() ? parameter.getName() : pathVariable.name();
            return "path:" + name + ":" + pathVariable.required();
        }
        return "infrastructure:" + parameter.getType().getSimpleName();
    }

    private static Set<String> expectedContract() throws Exception {
        var resource = EndpointContractTest.class.getResourceAsStream("/contracts/http-endpoints.txt");
        assertThat(resource).as("recorded HTTP contract").isNotNull();
        try (resource) {
            return new LinkedHashSet<>(new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .lines().filter(line -> !line.isBlank()).toList());
        }
    }
}
```

Add `throws Exception` to `preservesEveryHttpMapping`. Before creating the resource, temporarily add this method to the test:

```java
@Test
void printContractForInitialRecording() {
    snapshot().stream().sorted().forEach(System.out::println);
}
```

- [ ] **Step 5: Run the endpoint contract test**

Run: `.\gradlew.bat test --tests geosync.architecture.EndpointContractTest`

Expected: FAIL because `src/test/resources/contracts/http-endpoints.txt` does not exist, while the Gradle test report contains the sorted contract lines emitted by `printContractForInitialRecording`.

- [ ] **Step 6: Freeze and review the endpoint contract**

Create `src/test/resources/contracts/http-endpoints.txt` from the emitted sorted lines, one contract per line. Remove `printContractForInitialRecording`, then compare the resource with:

```powershell
rg -n '@(Get|Post|Put|Delete|Patch|Request)Mapping|@RequestParam|@PathVariable' src/main/java/geosync/web
.\gradlew.bat test --tests geosync.architecture.EndpointContractTest
```

Expected: every source mapping and bound parameter appears in the resource and the test succeeds. The resource is never regenerated during subsequent package moves.

- [ ] **Step 7: Review checkpoint without committing**

Run: `git diff --check; git status --short`

Expected: only `build.gradle`, `EndpointContractTest.java`, and `http-endpoints.txt` are changed. Do not commit.

### Task 2: Add the package architecture test and move common/configuration

**Files:**

- Create: `src/test/java/geosync/architecture/PackageArchitectureTest.java`
- Move: `CoordTransformer`, `XmlUtil`, and all four current `config` classes according to the File Map.
- Modify: all production and test imports referring to those six classes.

**Interfaces:**

- Consumes: existing public class APIs unchanged.
- Produces: `geosync.common.geo`, `geosync.common.xml`, and `geosync.configuration` packages.

- [ ] **Step 1: Write failing package placement rules**

Create the test with explicit class-to-package assertions:

```java
package geosync.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class PackageArchitectureTest {
    private final JavaClasses classes = new ClassFileImporter().importPackages("geosync");

    @Test
    void commonAndConfigurationClassesAreFeatureOwned() {
        classes().that().haveSimpleName("CoordTransformer").should().resideInAPackage("..common.geo")
            .andShould().haveSimpleName("CoordTransformer").check(classes);
        classes().that().haveSimpleName("XmlUtil").should().resideInAPackage("..common.xml")
            .andShould().haveSimpleName("XmlUtil").check(classes);
        classes().that().haveSimpleNameMatching("DatabaseConfig|KrasGpkiProperties|TargetDb|TargetDbProperties")
            .should().resideInAPackage("..configuration").check(classes);
    }

    @Test
    void commonDoesNotDependOnFeatures() {
        noClasses().that().resideInAPackage("..common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..database..", "..ods..", "..kras..", "..gateway..",
                "..synchronization..", "..monitoring..", "..settings..")
            .check(classes);
    }
}
```

- [ ] **Step 2: Verify the placement test fails**

Run: `.\gradlew.bat test --tests geosync.architecture.PackageArchitectureTest`

Expected: FAIL because the six classes still reside in `geo`, `util`, and `config`.

- [ ] **Step 3: Move the six source files and matching tests**

Resolve and verify every source and destination is inside the repository, then use PowerShell `Move-Item -LiteralPath` for each file. Use `apply_patch` to change package declarations and imports. Move `CoordTransformerTest` to `src/test/java/geosync/common/geo/CoordTransformerTest.java`.

- [ ] **Step 4: Run focused and full tests**

Run:

```powershell
.\gradlew.bat test --tests geosync.architecture.PackageArchitectureTest --tests geosync.common.geo.CoordTransformerTest
.\gradlew.bat test
```

Expected: both commands succeed.

- [ ] **Step 5: Review checkpoint without committing**

Run: `git diff --check; git status --short`

Expected: only the planned moves, package/import edits, build change, and tests are present. Do not commit.

### Task 3: Move settings and database features

**Files:**

- Move: `RuntimeSettingsService`, `SettingsController` → `settings`.
- Move: `TargetDbService`, `TargetTableNameService`, `DbSetupService`, `DbSetupController` → `database`.
- Modify: `PackageArchitectureTest` and all affected imports.

**Interfaces:** Existing constructors and public methods remain byte-for-source equivalent except for owning package names.

- [ ] **Step 1: Add failing explicit placement assertions**

Append rules using `haveSimpleNameMatching` so the two settings classes must reside in `..settings` and the four database classes in `..database`.

- [ ] **Step 2: Run the new rules and verify failure**

Run: `.\gradlew.bat test --tests geosync.architecture.PackageArchitectureTest`

Expected: FAIL listing the six classes in their old packages.

- [ ] **Step 3: Move files and update only package/import declarations**

Use verified `Move-Item -LiteralPath` operations, then `apply_patch` for declarations/imports. Keep `@RequestMapping("/settings")`, `@RequestMapping("/db")`, method annotations and method bodies unchanged.

- [ ] **Step 4: Run endpoint, architecture, and full tests**

Run: `.\gradlew.bat test --tests 'geosync.architecture.*'; .\gradlew.bat test`

Expected: both commands succeed.

- [ ] **Step 5: Review checkpoint without committing**

Run: `git diff --check; git status --short`. Do not commit.

### Task 4: Move ODS feature

**Files:**

- Move: `OdsRepository`, `OdsTableDdl`, `OdsController`, `UsezoneCodeService` → `ods`.
- Move tests: all current repository tests → `src/test/java/geosync/ods/`.
- Modify: `PackageArchitectureTest` and affected imports.

**Interfaces:** `OdsTableDdl` remains package-private beside `OdsRepository`; repository overloads and SQL remain unchanged.

- [ ] **Step 1: Add failing ODS placement and access assertions**

Require all four class names in `..ods` and use AssertJ reflection to assert `Modifier.isPublic(OdsTableDdl.class.getModifiers())` is false.

- [ ] **Step 2: Verify failure, then move the four classes and two tests**

Run the architecture test before moving and expect failure. Perform verified moves and package/import edits only.

- [ ] **Step 3: Run ODS and contract tests**

Run:

```powershell
.\gradlew.bat test --tests 'geosync.ods.*'
.\gradlew.bat test --tests 'geosync.architecture.*'
.\gradlew.bat test
```

Expected: all commands succeed with SQL assertions unchanged.

- [ ] **Step 4: Review checkpoint without committing**

Run: `git diff --check; git status --short`. Do not commit.

### Task 5: Move KRAS feature

**Files:**

- Move the eleven KRAS classes listed in the File Map → `kras`.
- Move: `KrasUzoneMockLoadTest` → `src/test/java/geosync/kras/KrasUzoneMockLoadTest.java`.
- Modify: `PackageArchitectureTest` and all affected imports.

**Interfaces:** Preserve KRAS API requests, file names, classpath resource paths, synchronization behavior, and `/file-download` plus `/mock/estateGateway` endpoints.

- [ ] **Step 1: Add a failing rule for the exact eleven KRAS class names**

Use `haveSimpleNameMatching` with all names from the File Map and require `..kras`.

- [ ] **Step 2: Verify failure, move files, and patch declarations/imports**

Do not alter constants, method bodies, annotations, XML paths, or file names.

- [ ] **Step 3: Run KRAS, endpoint, architecture, and full tests**

Run:

```powershell
.\gradlew.bat test --tests 'geosync.kras.*'
.\gradlew.bat test --tests 'geosync.architecture.*'
.\gradlew.bat test
```

Expected: all commands succeed.

- [ ] **Step 4: Review checkpoint without committing**

Use `git diff --word-diff=porcelain` to verify production changes are only moves, packages, and imports. Do not commit.

### Task 6: Move gateway feature

**Files:**

- Move the six gateway classes listed in the File Map → `gateway`.
- Modify: `PackageArchitectureTest` and affected imports.

**Interfaces:** Preserve `/conn/**`, `/svc/**`, `/conn-stats/**`, upstream KRAS/KOREPS calls, XML responses, logging, and `GatewayPaths` package-private access.

- [ ] **Step 1: Add failing gateway placement and package-private assertions**

Require the six names in `..gateway`; assert `GatewayPaths` is not public.

- [ ] **Step 2: Verify failure, move files, and update declarations/imports**

Keep `GatewayPaths` with both gateway controllers. Do not edit endpoint or XML logic.

- [ ] **Step 3: Run endpoint, architecture, and full tests**

Run: `.\gradlew.bat test --tests 'geosync.architecture.*'; .\gradlew.bat test`

Expected: both commands succeed.

- [ ] **Step 4: Review checkpoint without committing**

Run: `git diff --check; git status --short`. Do not commit.

### Task 7: Move synchronization feature and models

**Files:**

- Move: `ColumnDef`, `SyncTableDef`, `SyncHistory` → `synchronization/model`.
- Move: the eight synchronization classes listed in the File Map → `synchronization`.
- Move: `TableMapperTest` → `src/test/java/geosync/synchronization/TableMapperTest.java`.
- Modify: `PackageArchitectureTest` and all affected imports.

**Interfaces:** Preserve all worker overloads, scheduler entry points, status keys, `/sync/**`, and `/schedule`.

- [ ] **Step 1: Add failing model and synchronization placement assertions**

Require the three model names in `..synchronization.model` and the eight orchestration names in `..synchronization`.

- [ ] **Step 2: Verify failure, move files, and update declarations/imports**

Keep every constructor, overload, annotation, and method body unchanged.

- [ ] **Step 3: Run mapper, endpoint, architecture, and full tests**

Run:

```powershell
.\gradlew.bat test --tests geosync.synchronization.TableMapperTest
.\gradlew.bat test --tests 'geosync.architecture.*'
.\gradlew.bat test
```

Expected: all commands succeed.

- [ ] **Step 4: Review checkpoint without committing**

Run: `git diff --check; git status --short`. Do not commit.

### Task 8: Move monitoring feature and prohibit legacy packages

**Files:**

- Move the four monitoring classes listed in the File Map → `monitoring`.
- Modify: `EndpointContractTest`, `PackageArchitectureTest`, and affected imports.

**Interfaces:** Preserve `/`, `/logs`, `/api-test`, `/api-test/call`, `/api/sync-stats`, and `/api/gateway-status`.

- [ ] **Step 1: Add failing monitoring placement and final legacy-package rules**

Add exact placement assertions and:

```java
@Test
void noProductionClassRemainsInLegacyLayerPackages() {
    noClasses().should().resideInAnyPackage(
        "geosync.web..", "geosync.service..", "geosync.worker..",
        "geosync.repository..", "geosync.scheduler..", "geosync.model..",
        "geosync.mapper..", "geosync.geo..", "geosync.util..",
        "geosync.config.."
    ).check(classes);
}
```

- [ ] **Step 2: Verify failure, move monitoring files, and update the controller class list**

Update `EndpointContractTest.CONTROLLERS` to the new package names only. Its expected literal contract must remain byte-for-byte unchanged.

- [ ] **Step 3: Run architecture and full tests**

Run: `.\gradlew.bat test --tests 'geosync.architecture.*'; .\gradlew.bat test`

Expected: all tests succeed and no old package contains a `.java` file.

- [ ] **Step 4: Search for stale imports and packages**

Run:

```powershell
rg -n 'geosync\.sync\.(web|service|worker|repository|scheduler|model|mapper|geo|util|config)' src
rg --files src/main/java/geosync
```

Expected: the first command returns no stale references; the second lists only approved packages.

- [ ] **Step 5: Review checkpoint without committing**

Run: `git diff --check; git status --short`. Do not commit.

### Task 9: Reorganize reference documents with a layout contract

**Files:**

- Create: `src/test/java/geosync/architecture/RepositoryLayoutTest.java`
- Move the four documents listed in the File Map.
- Modify: Markdown references to moved documents, if found.

**Interfaces:** Runtime directories and distribution structure remain unchanged.

- [ ] **Step 1: Write a failing final-layout test**

Create:

```java
package geosync.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryLayoutTest {
    private final Path root = Path.of("").toAbsolutePath();

    @Test
    void preservesOperationalPaths() {
        assertThat(root.resolve("conf/kras/base-tables.xml")).exists();
        assertThat(root.resolve("conf/sql/sync_public_tables.sql")).exists();
        assertThat(root.resolve("conf/sql/mt_usezone_cd.sql")).exists();
        assertThat(root.resolve("scripts/run.bat")).exists();
        assertThat(root.resolve("scripts/install-service.bat")).exists();
        assertThat(root.resolve("scripts/uninstall-service.bat")).exists();
        assertThat(root.resolve("mock")).isDirectory();
        assertThat(root.resolve("api-tests")).isDirectory();
    }

    @Test
    void referenceDocumentsHaveApprovedLocations() {
        assertThat(root.resolve("docs/reference/46870-data-catalog.md")).exists();
        assertThat(root.resolve("docs/reference/lt-c-uzone-plan.md")).exists();
        assertThat(root.resolve("docs/reference/kras-geosync-structure.md")).exists();
        assertThat(root.resolve("docs/reviews/improvements.md")).exists();
    }

    @Test
    void distributionTaskKeepsItsExternalLayout() throws Exception {
        String build = Files.readString(root.resolve("build.gradle"));
        assertThat(build).contains("into 'lib'", "into 'jre'", "into 'bin'", "into 'conf'", "into('KAIS_WORK')", "into('logs')");
    }
}
```

- [ ] **Step 2: Verify the document-location test fails**

Run: `.\gradlew.bat test --tests geosync.architecture.RepositoryLayoutTest`

Expected: FAIL because the four documents are still in their old locations.

- [ ] **Step 3: Verify paths and move the four documents**

Resolve every source/destination under the repository, create `docs/reference` and `docs/reviews`, then use literal PowerShell moves. Update only references found by:

```powershell
rg -n '46870_DATA_CATALOG|lt_c_uzone_plan|KRAS_GEOSYNC_SYNC_STRUCTURE|IMPROVEMENTS' -g '!build/**' -g '!.gradle/**'
```

- [ ] **Step 4: Run layout and full tests**

Run: `.\gradlew.bat test --tests geosync.architecture.RepositoryLayoutTest; .\gradlew.bat test`

Expected: both commands succeed.

- [ ] **Step 5: Review checkpoint without committing**

Run: `git diff --check; git status --short`. Do not commit.

### Task 10: Final verification and user review

**Files:** All files changed by Tasks 1–9.

**Interfaces:** Produces a verified, uncommitted working tree ready for user inspection.

- [ ] **Step 1: Run the full unit test suite from a clean Gradle process**

Run: `.\gradlew.bat --stop; .\gradlew.bat clean test`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Build the executable JAR**

Run: `.\gradlew.bat bootJar`

Expected: `build/libs/geosync.jar` exists.

- [ ] **Step 3: Build the distribution when a JLink-capable JDK is available**

Run: `.\gradlew.bat jlinkZip`

Expected: `build/distributions/geosync-HOME-1.0.0.zip` exists and contains the unchanged external layout. If the local JDK lacks `jlink`, record this single verification as unavailable rather than changing the build.

- [ ] **Step 4: Confirm no runtime contract changed**

Run:

```powershell
.\gradlew.bat test --tests geosync.architecture.EndpointContractTest
git diff --check
git diff --find-renames --stat
git status --short
```

Expected: endpoint test succeeds, diff checks are clean, and production Java changes are recognized as moves plus package/import edits.

- [ ] **Step 5: Present the uncommitted result for review**

Report tests, `bootJar`, optional `jlinkZip`, all moved areas, and any verification limitation. Do not stage or commit. Ask the user whether they want changes or a commit.
