package geosync.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 차세대 설정을 kras 밖(kras-nextgen)에 둔 이유 — 설정 화면 저장이 kras 블록만 통째로 바꾼다. */
class SettingsFileServiceIncrementalKeysTest {

    @Test
    @SuppressWarnings("unchecked")
    void settingsSaveKeepsTopLevelIncrementalBlockButReplacesKrasBlock(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("application.yml");
        Files.writeString(file, """
            kras:
              url: "http://old"
              land-change:
                service-id: "IN-KRAS-BLOCK"
            kras-nextgen:
              land-change:
                service-id: "KRAS000099"
              schedules:
                land-change-daily: "0 0 6 * * *"
            """, StandardCharsets.UTF_8);

        new SettingsFileService().save(file, new DatabaseSettings("db", "jdbc:postgresql://h/db", "u", "p",
                        DatabaseSettings.Source.CANONICAL),
                Map.of("kras", Map.of("url", "http://new")));

        Map<String, Object> root = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
        Map<String, Object> incremental = (Map<String, Object>) root.get("kras-nextgen");
        assertThat((Map<String, Object>) incremental.get("land-change")).containsEntry("service-id", "KRAS000099");
        assertThat((Map<String, Object>) incremental.get("schedules")).containsEntry("land-change-daily", "0 0 6 * * *");
        assertThat((Map<String, Object>) root.get("kras")).containsEntry("url", "http://new")
                .doesNotContainKey("land-change");
    }
}
