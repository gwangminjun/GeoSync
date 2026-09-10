package geomex.sync.settings;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsFileServiceTest {

    @Test
    void savesCanonicalDatabaseAndPreservesUnmanagedYamlKeys() throws Exception {
        Path config = Files.createTempFile("geomex-settings", ".yml");
        Files.writeString(config, """
                server:
                  port: 18080
                spring:
                  datasource:
                    hikari:
                      maximum-pool-size: 5
                koreps:
                  url: "https://example.invalid"
                targets:
                  - name: "legacy"
                """, StandardCharsets.UTF_8);

        SettingsFileService service = new SettingsFileService();
        service.saveDatabase(config, new DatabaseSettings(
                "운영 DB", "jdbc:postgresql://db-host:5432/geomex", "db-user", "db-password",
                DatabaseSettings.Source.CANONICAL));

        Map<String, Object> root = new Yaml().load(Files.readString(config, StandardCharsets.UTF_8));
        Map<String, Object> server = (Map<String, Object>) root.get("server");
        Map<String, Object> spring = (Map<String, Object>) root.get("spring");
        Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
        Map<String, Object> geomex = (Map<String, Object>) root.get("geomex");
        Map<String, Object> database = (Map<String, Object>) geomex.get("database");

        assertThat(server.get("port")).isEqualTo(18080);
        assertThat(((Map<?, ?>) datasource.get("hikari")).get("maximum-pool-size")).isEqualTo(5);
        assertThat(((Map<?, ?>) root.get("koreps")).get("url")).isEqualTo("https://example.invalid");
        assertThat(datasource.get("url")).isEqualTo("jdbc:postgresql://db-host:5432/geomex");
        assertThat(datasource.get("username")).isEqualTo("db-user");
        assertThat(database.get("display-name")).isEqualTo("운영 DB");
        assertThat(root).doesNotContainKey("targets");
    }
}
