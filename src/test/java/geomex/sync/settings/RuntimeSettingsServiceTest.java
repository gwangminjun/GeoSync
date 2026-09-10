package geomex.sync.settings;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeSettingsServiceTest {

    @Test
    void resolvesCanonicalDatasourcePlaceholdersAsOneCompleteSetting() throws Exception {
        Path config = Files.createTempFile("geomex-settings", ".yml");
        Files.writeString(config, """
                geomex:
                  database:
                    display-name: "운영 DB"
                spring:
                  datasource:
                    url: "${DB_URL:jdbc:postgresql://fallback/db}"
                    username: "${DB_USER:fallback-user}"
                    password: "file-password"
                targets:
                  - host: "legacy-host"
                    username: "legacy-user"
                    enabled: true
                """, StandardCharsets.UTF_8);

        MockEnvironment environment = new MockEnvironment()
                .withProperty("DB_URL", "jdbc:postgresql://env-host:5432/envdb")
                .withProperty("DB_USER", "env-user");
        RuntimeSettingsService service = new RuntimeSettingsService(
                environment, config.toString(), "jdbc:postgresql://startup/db", "startup-user", "startup-password");

        DatabaseSettings result = service.database();

        assertThat(result.source()).isEqualTo(DatabaseSettings.Source.CANONICAL);
        assertThat(result.displayName()).isEqualTo("운영 DB");
        assertThat(result.url()).isEqualTo("jdbc:postgresql://env-host:5432/envdb");
        assertThat(result.username()).isEqualTo("env-user");
        assertThat(result.password()).isEqualTo("file-password");
    }

    @Test
    void selectsFirstEnabledLegacyTargetWhenCanonicalDatasourceIsAbsent() throws Exception {
        Path config = Files.createTempFile("geomex-settings", ".yml");
        Files.writeString(config, """
                targets:
                  - name: "비활성"
                    host: "disabled-host"
                    port: 5432
                    dbname: "disabled"
                    username: "disabled-user"
                    enabled: false
                  - name: "활성 DB"
                    host: "active-host"
                    port: 5433
                    dbname: "active"
                    username: "active-user"
                    password: "active-password"
                    enabled: true
                """, StandardCharsets.UTF_8);

        RuntimeSettingsService service = new RuntimeSettingsService(
                new MockEnvironment(), config.toString(), "jdbc:postgresql://startup/db", "startup-user", "startup-password");

        DatabaseSettings result = service.database();

        assertThat(result.source()).isEqualTo(DatabaseSettings.Source.LEGACY);
        assertThat(result.displayName()).isEqualTo("활성 DB");
        assertThat(result.url()).isEqualTo("jdbc:postgresql://active-host:5433/active");
        assertThat(result.username()).isEqualTo("active-user");
        assertThat(result.password()).isEqualTo("active-password");
    }

    @Test
    void fallsBackToStartupDatasourceWhenNoCompleteExternalSettingExists() throws Exception {
        Path config = Files.createTempFile("geomex-settings", ".yml");
        Files.writeString(config, """
                spring:
                  datasource:
                    url: ""
                    username: ""
                targets: []
                """, StandardCharsets.UTF_8);

        RuntimeSettingsService service = new RuntimeSettingsService(
                new MockEnvironment(), config.toString(), "jdbc:postgresql://startup/db", "startup-user", "startup-password");

        DatabaseSettings result = service.database();

        assertThat(result.source()).isEqualTo(DatabaseSettings.Source.STARTUP);
        assertThat(result.displayName()).isEqualTo("db");
        assertThat(result.url()).isEqualTo("jdbc:postgresql://startup/db");
        assertThat(result.username()).isEqualTo("startup-user");
    }
}
