package geosync.architecture;

import geosync.settings.SettingsController;

import geosync.database.DbSetupController;

import geosync.database.DbSetupService;

import geosync.database.TargetTableNameService;

import geosync.database.TargetDbService;

import geosync.settings.RuntimeSettingsService;

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

    private final JavaClasses classes = new ClassFileImporter().importPackages("geosync");

    @Test
    void commonAndConfigurationClassesAreFeatureOwned() {
        assertPackage("CoordTransformer", "geosync.common.geo");
        assertPackage("XmlUtil", "geosync.common.xml");
        assertPackage("DatabaseConfig", "geosync.configuration");
        assertPackage("KrasGpkiProperties", "geosync.configuration");
        assertPackage("TargetDb", "geosync.configuration");
        assertPackage("DatabaseSettings", "geosync.settings");
    }

    @Test
    void settingsAndDatabaseClassesAreFeatureOwned() {
        assertPackage("RuntimeSettingsService", "geosync.settings");
        assertPackage("SettingsController", "geosync.settings");
        assertPackage("TargetDbService", "geosync.database");
        assertPackage("DatabaseConnectionService", "geosync.database");
        assertPackage("TargetTableNameService", "geosync.database");
        assertPackage("DbSetupService", "geosync.database");
        assertPackage("DbSetupController", "geosync.database");
    }

    @Test
    void odsClassesAreFeatureOwnedAndHelpersRemainPackagePrivate() {
        assertPackage("OdsRepository", "geosync.ods");
        assertPackage("OdsTableDdl", "geosync.ods");
        assertPackage("OdsController", "geosync.ods");
        assertPackage("UsezoneCodeService", "geosync.ods");
        assertThat(findClass("OdsTableDdl").getModifiers()).doesNotContain(JavaModifier.PUBLIC);
    }

    @Test
    void krasClassesAreFeatureOwned() {
        assertPackage("KrasApiClient", "geosync.kras");
        assertPackage("KorepsApiClient", "geosync.kras");
        assertPackage("KrasGpkiService", "geosync.kras");
        assertPackage("KrasFileReader", "geosync.kras");
        assertPackage("KrasFileWriter", "geosync.kras");
        assertPackage("KrasWorkspaceScanner", "geosync.kras");
        assertPackage("KrasFileDownloadService", "geosync.kras");
        assertPackage("KrasTxtLoaderService", "geosync.kras");
        assertPackage("KrasCatalogStatusService", "geosync.kras");
        assertPackage("FileDownloadController", "geosync.kras");
        assertPackage("MockGatewayController", "geosync.kras");
    }

    @Test
    void gatewayClassesAreFeatureOwnedAndHelpersRemainPackagePrivate() {
        assertPackage("ConnRequestLogService", "geosync.gateway");
        assertPackage("KrasConnController", "geosync.gateway");
        assertPackage("KrasGmxController", "geosync.gateway");
        assertPackage("GatewayPaths", "geosync.gateway");
        assertPackage("ConnStatsController", "geosync.gateway");
        assertPackage("ApiTestProxyController", "geosync.gateway");
        assertThat(findClass("GatewayPaths").getModifiers()).doesNotContain(JavaModifier.PUBLIC);
    }

    @Test
    void synchronizationClassesAreFeatureOwned() {
        assertPackage("ColumnDef", "geosync.synchronization.model");
        assertPackage("SyncTableDef", "geosync.synchronization.model");
        assertPackage("SyncHistory", "geosync.synchronization.model");
        assertPackage("TableMapper", "geosync.synchronization");
        assertPackage("KrasWorker", "geosync.synchronization");
        assertPackage("SyncStatusService", "geosync.synchronization");
        assertPackage("SyncExecutionLogService", "geosync.synchronization");
        assertPackage("SyncScheduler", "geosync.synchronization");
        assertPackage("DynamicScheduleManager", "geosync.synchronization");
        assertPackage("SyncController", "geosync.synchronization");
        assertPackage("ScheduleController", "geosync.synchronization");
    }

    @Test
    void monitoringClassesAreFeatureOwned() {
        assertPackage("CatalogFileStatus", "geosync.monitoring");
        assertPackage("DashboardController", "geosync.monitoring");
        assertPackage("LogController", "geosync.monitoring");
        assertPackage("ApiTestController", "geosync.monitoring");
    }

    @Test
    void noProductionClassRemainsInLegacyLayerPackages() {
        assertThat(classes.stream()
                .filter(javaClass -> !javaClass.getName().startsWith("geosync.architecture."))
                .map(JavaClass::getPackageName)
                .filter(PackageArchitectureTest::isLegacyPackage)
                .toList())
                .isEmpty();
    }

    @Test
    void commonDoesNotDependOnFeaturePackages() {
        assertThat(classes.stream()
                .filter(javaClass -> javaClass.getPackageName().startsWith("geosync.common"))
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
                "geosync.web",
                "geosync.service",
                "geosync.worker",
                "geosync.repository",
                "geosync.scheduler",
                "geosync.model",
                "geosync.mapper",
                "geosync.geo",
                "geosync.util",
                "geosync.config"
        ).contains(packageName);
    }

    private static boolean isFeaturePackage(String packageName) {
        return Set.of(
                "geosync.settings",
                "geosync.database",
                "geosync.ods",
                "geosync.kras",
                "geosync.gateway",
                "geosync.synchronization",
                "geosync.monitoring"
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
