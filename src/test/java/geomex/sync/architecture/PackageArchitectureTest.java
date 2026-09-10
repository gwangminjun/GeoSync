package geomex.sync.architecture;

import geomex.sync.settings.SettingsController;

import geomex.sync.database.DbSetupController;

import geomex.sync.database.DbSetupService;

import geomex.sync.database.TargetTableNameService;

import geomex.sync.database.TargetDbService;

import geomex.sync.settings.RuntimeSettingsService;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PackageArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter().importPackages("geomex.sync");

    @Test
    void commonAndConfigurationClassesAreFeatureOwned() {
        assertPackage("CoordTransformer", "geomex.sync.common.geo");
        assertPackage("XmlUtil", "geomex.sync.common.xml");
        assertPackage("DatabaseConfig", "geomex.sync.configuration");
        assertPackage("KrasGpkiProperties", "geomex.sync.configuration");
        assertPackage("TargetDb", "geomex.sync.configuration");
        assertPackage("DatabaseSettings", "geomex.sync.settings");
    }

    @Test
    void settingsAndDatabaseClassesAreFeatureOwned() {
        assertPackage("RuntimeSettingsService", "geomex.sync.settings");
        assertPackage("SettingsController", "geomex.sync.settings");
        assertPackage("TargetDbService", "geomex.sync.database");
        assertPackage("DatabaseConnectionService", "geomex.sync.database");
        assertPackage("TargetTableNameService", "geomex.sync.database");
        assertPackage("DbSetupService", "geomex.sync.database");
        assertPackage("DbSetupController", "geomex.sync.database");
    }

    @Test
    void odsClassesAreFeatureOwnedAndHelpersRemainPackagePrivate() {
        assertPackage("OdsRepository", "geomex.sync.ods");
        assertPackage("OdsTableDdl", "geomex.sync.ods");
        assertPackage("OdsController", "geomex.sync.ods");
        assertPackage("UsezoneCodeService", "geomex.sync.ods");
        assertThat(findClass("OdsTableDdl").getModifiers()).doesNotContain(JavaModifier.PUBLIC);
    }

    @Test
    void krasClassesAreFeatureOwned() {
        assertPackage("KrasApiClient", "geomex.sync.kras");
        assertPackage("KorepsApiClient", "geomex.sync.kras");
        assertPackage("KrasGpkiService", "geomex.sync.kras");
        assertPackage("KrasFileReader", "geomex.sync.kras");
        assertPackage("KrasFileWriter", "geomex.sync.kras");
        assertPackage("KrasWorkspaceScanner", "geomex.sync.kras");
        assertPackage("KrasFileDownloadService", "geomex.sync.kras");
        assertPackage("KrasTxtLoaderService", "geomex.sync.kras");
        assertPackage("KrasCatalogStatusService", "geomex.sync.kras");
        assertPackage("FileDownloadController", "geomex.sync.kras");
        assertPackage("MockGatewayController", "geomex.sync.kras");
    }

    @Test
    void gatewayClassesAreFeatureOwnedAndHelpersRemainPackagePrivate() {
        assertPackage("ConnRequestLogService", "geomex.sync.gateway");
        assertPackage("KrasConnController", "geomex.sync.gateway");
        assertPackage("KrasGmxController", "geomex.sync.gateway");
        assertPackage("GatewayPaths", "geomex.sync.gateway");
        assertPackage("ConnStatsController", "geomex.sync.gateway");
        assertPackage("ApiTestProxyController", "geomex.sync.gateway");
        assertThat(findClass("GatewayPaths").getModifiers()).doesNotContain(JavaModifier.PUBLIC);
    }

    @Test
    void synchronizationClassesAreFeatureOwned() {
        assertPackage("ColumnDef", "geomex.sync.synchronization.model");
        assertPackage("SyncTableDef", "geomex.sync.synchronization.model");
        assertPackage("SyncHistory", "geomex.sync.synchronization.model");
        assertPackage("TableMapper", "geomex.sync.synchronization");
        assertPackage("KrasWorker", "geomex.sync.synchronization");
        assertPackage("SyncStatusService", "geomex.sync.synchronization");
        assertPackage("SyncExecutionLogService", "geomex.sync.synchronization");
        assertPackage("SyncScheduler", "geomex.sync.synchronization");
        assertPackage("DynamicScheduleManager", "geomex.sync.synchronization");
        assertPackage("SyncController", "geomex.sync.synchronization");
        assertPackage("ScheduleController", "geomex.sync.synchronization");
    }

    @Test
    void monitoringClassesAreFeatureOwned() {
        assertPackage("CatalogFileStatus", "geomex.sync.monitoring");
        assertPackage("DashboardController", "geomex.sync.monitoring");
        assertPackage("LogController", "geomex.sync.monitoring");
        assertPackage("ApiTestController", "geomex.sync.monitoring");
    }

    @Test
    void noProductionClassRemainsInLegacyLayerPackages() {
        assertThat(classes.stream()
                .filter(javaClass -> !javaClass.getName().startsWith("geomex.sync.architecture."))
                .map(JavaClass::getPackageName)
                .filter(PackageArchitectureTest::isLegacyPackage)
                .toList())
                .isEmpty();
    }

    @Test
    void commonDoesNotDependOnFeaturePackages() {
        assertThat(classes.stream()
                .filter(javaClass -> javaClass.getPackageName().startsWith("geomex.sync.common"))
                .flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
                .map(dependency -> dependency.getTargetClass().getPackageName())
                .filter(PackageArchitectureTest::isFeaturePackage)
                .toList())
                .isEmpty();
    }

    @Test
    void springMvcControllersUseControllerSuffix() {
        assertThat(classes.stream()
                .filter(javaClass -> javaClass.isAnnotatedWith(Controller.class)
                        || javaClass.isAnnotatedWith(RestController.class))
                .map(JavaClass::getSimpleName)
                .filter(simpleName -> !simpleName.endsWith("Controller"))
                .toList())
                .isEmpty();
    }

    private static boolean isLegacyPackage(String packageName) {
        return Set.of(
                "geomex.sync.web",
                "geomex.sync.service",
                "geomex.sync.worker",
                "geomex.sync.repository",
                "geomex.sync.scheduler",
                "geomex.sync.model",
                "geomex.sync.mapper",
                "geomex.sync.geo",
                "geomex.sync.util",
                "geomex.sync.config"
        ).contains(packageName);
    }

    private static boolean isFeaturePackage(String packageName) {
        return Set.of(
                "geomex.sync.settings",
                "geomex.sync.database",
                "geomex.sync.ods",
                "geomex.sync.kras",
                "geomex.sync.gateway",
                "geomex.sync.synchronization",
                "geomex.sync.monitoring"
        ).stream().anyMatch(packageName::startsWith);
    }

    private void assertPackage(String simpleName, String expectedPackage) {
        JavaClass matchingClass = findClass(simpleName);

        assertThat(matchingClass.getPackageName())
                .as(simpleName + " package")
                .isEqualTo(expectedPackage);
    }

    private JavaClass findClass(String simpleName) {
        return classes.stream()
                .filter(javaClass -> javaClass.getSimpleName().equals(simpleName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + simpleName));
    }
}
