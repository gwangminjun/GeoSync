package geosync.kras;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class KrasNextgenSettingsServiceTest {

    private static KrasNextgenSettingsService.Settings settings(String serviceId, String start, String end,
                                                                  Map<String, String> schedules) {
        return new KrasNextgenSettingsService.Settings(serviceId, start, end, "yyyyMMdd", schedules);
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveReplacesOnlyNextgenBlockAndDropsOldIncrementalBlock(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("application.yml");
        Files.writeString(file, """
            spring:
              datasource:
                password: "keep-me"
            kras:
              url: "http://kras"
            kras-incremental:
              land-change:
                service-id: "OLD"
            """, StandardCharsets.UTF_8);
        var service = new KrasNextgenSettingsService("file:" + file);

        service.save(settings("KRAS000099", "ST", "ED", Map.of("correction", "0 0 1 * * *")));

        Map<String, Object> root = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
        assertThat(root).containsKeys("spring", "kras", "kras-nextgen").doesNotContainKey("kras-incremental");
        assertThat(((Map<String, Object>) ((Map<String, Object>) root.get("spring")).get("datasource")))
                .containsEntry("password", "keep-me");
        Map<String, Object> pnu = (Map<String, Object>) ((Map<String, Object>) root.get("kras-nextgen")).get("pnu");
        assertThat(pnu).containsEntry("parallelism", 8).containsEntry("stop-at", "07:00");
        var current = service.current();
        assertThat(current.serviceId()).isEqualTo("KRAS000099");
        assertThat(current.schedule(KrasNextgenGroup.CORRECTION)).isEqualTo("0 0 1 * * *");
        assertThat(current.schedule(KrasNextgenGroup.FULL_LOAD)).isEmpty();
        assertThat(current.pnu().effectiveDatasets()).isEqualTo(KrasPnuQueueService.DEFAULT_DATASETS)
                .doesNotContain("bldg_ho_info", "cbldg_dfhs_info");
    }

    @Test
    @SuppressWarnings("unchecked")
    void oldPerJobScheduleKeysMoveToGroupsOnReadAndAreDroppedOnSave(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("application.yml");
        Files.writeString(file, """
            kras-nextgen:
              schedules:
                land-change-daily: "0 0 6 * * *"
                cadastral-full: ""
                usezone-full: "0 30 2 * * *"
            """, StandardCharsets.UTF_8);
        var service = new KrasNextgenSettingsService(file.toString());
        var s = service.current();
        assertThat(s.schedule(KrasNextgenGroup.CORRECTION)).isEqualTo("0 0 6 * * *");
        assertThat(s.schedule(KrasNextgenGroup.FULL_LOAD)).isEqualTo("0 30 2 * * *");
        // 레이어 적재(2026-10-16 신설)는 이전 키를 옮기지 않는다 — 키가 없으면 꺼진 채로 시작.
        assertThat(s.schedule(KrasNextgenGroup.LAYER)).isEmpty();

        service.save(s);
        Map<String, Object> root = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
        Map<String, Object> schedules = (Map<String, Object>) ((Map<String, Object>) root.get("kras-nextgen"))
                .get("schedules");
        assertThat(schedules).containsOnlyKeys("full-load", "correction", "layer");
    }

    @Test
    void layerScheduleIsReadAndSaved(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("application.yml");
        Files.writeString(file, "kras-nextgen:\n  schedules:\n    layer: \"0 30 7 * * *\"\n", StandardCharsets.UTF_8);
        var service = new KrasNextgenSettingsService(file.toString());
        assertThat(service.current().schedule(KrasNextgenGroup.LAYER)).isEqualTo("0 30 7 * * *");
        service.save(service.current());
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertThat(service.current().schedule(KrasNextgenGroup.LAYER)).isEqualTo("0 30 7 * * *");
        assertThat(service.current().schedule(KrasNextgenGroup.FULL_LOAD)).isEmpty();
    }

    @Test
    void directFileEditIsPickedUpWithoutRestart(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("application.yml");
        Files.writeString(file, "kras-nextgen:\n  schedules:\n    full-load: \"0 30 2 * * *\"\n"
                + "  pnu:\n    parallelism: 4\n    datasets: [land_info, bldg_dong_info]\n");
        var service = new KrasNextgenSettingsService(file.toString());
        assertThat(service.current().schedule(KrasNextgenGroup.FULL_LOAD)).isEqualTo("0 30 2 * * *");
        assertThat(service.current().pnu().parallelism()).isEqualTo(4);
        assertThat(service.current().pnu().effectiveDatasets()).containsExactly("land_info", "bldg_dong_info");

        Files.writeString(file, "kras-nextgen:\n  schedules:\n    full-load: \"\"\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertThat(service.current().schedule(KrasNextgenGroup.FULL_LOAD)).isEmpty();
        assertThat(service.current().pnu().parallelism()).isEqualTo(8);
    }

    @Test
    void missingFileOrBlockMeansEverythingOff(@TempDir Path dir) {
        var current = new KrasNextgenSettingsService(dir.resolve("none.yml").toString()).current();
        assertThat(current.serviceId()).isEmpty();
        assertThat(current.dateFormat()).isEqualTo("yyyyMMdd");
        for (KrasNextgenGroup g : KrasNextgenGroup.values()) assertThat(current.schedule(g)).isEmpty();
    }

    @Test
    void validationRejectsBadCronHalfFilledDateParamsAndBadQueueValues(@TempDir Path dir) {
        var bad = new KrasNextgenSettingsService.Settings("", "ST", "", "yyyyMMdd", Map.of("full-load", "매일 두시"),
                new KrasNextgenSettingsService.Pnu(List.of("nope"), 0, -1, 0, "25:00", 20, 90, 0, "13-40", List.of()));
        assertThat(KrasNextgenSettingsService.validate(bad))
                .anyMatch(e -> e.contains("둘 다"))
                .anyMatch(e -> e.contains("전체 적재"))
                .anyMatch(e -> e.contains("동시 호출 수"))
                .anyMatch(e -> e.contains("하루 호출 한도"))
                .anyMatch(e -> e.contains("종료 시각"))
                .anyMatch(e -> e.contains("가격 갱신 기준일"))
                .anyMatch(e -> e.contains("nope"));
        var service = new KrasNextgenSettingsService(dir.resolve("a.yml").toString());
        assertThatThrownBy(() -> service.save(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThat(dir.resolve("a.yml")).doesNotExist();
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshDaysByDatasetDefaultsToRecommendedAndIsOmittedWhenUnchanged(@TempDir Path dir) throws Exception {
        // 기본: 소유자 계열 14일, 총괄표제부 90일, 나머지는 refresh-days(30)
        var d = KrasNextgenSettingsService.Pnu.defaults();
        assertThat(d.refreshDays("land_info")).isEqualTo(14);
        assertThat(d.refreshDays("bldg_ledg_gen_hds_info")).isEqualTo(90);
        assertThat(d.refreshDays("land_bldg_check")).isEqualTo(30);

        Path file = dir.resolve("application.yml");
        Files.writeString(file, "kras-nextgen:\n  pnu:\n    refresh-days: 20\n", StandardCharsets.UTF_8);
        var service = new KrasNextgenSettingsService(file.toString());
        var p = service.current().pnu();
        assertThat(p.refreshDaysByDataset()).isEqualTo(d.refreshDaysByDataset());   // 키가 없으면 권장값
        assertThat(p.refreshDays("land_mov_hist")).isEqualTo(20);

        service.save(service.current());
        Map<String, Object> pnu = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) new Yaml()
                .load(Files.readString(file, StandardCharsets.UTF_8))).get("kras-nextgen")).get("pnu");
        assertThat(pnu).doesNotContainKey("refresh-days-by-dataset");   // 권장값과 같으면 쓰지 않는다

        // {}는 "전부 기본 일수", 값을 적으면 그 값 — 저장·다시 읽기 왕복
        Files.writeString(file, "kras-nextgen:\n  pnu:\n    refresh-days-by-dataset: {}\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertThat(service.current().pnu().refreshDays("land_info")).isEqualTo(30);
        Files.writeString(file, "kras-nextgen:\n  pnu:\n    refresh-days-by-dataset:\n      land_info: 7\n",
                StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 10000));
        service.save(service.current());
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 15000));
        assertThat(service.current().pnu().refreshDaysByDataset()).containsExactly(entry("land_info", 7));
    }

    @Test
    void refreshDaysByDatasetRejectsPriceUnknownAndNonPositiveValues() {
        var p = new KrasNextgenSettingsService.Pnu(List.of(), 8, 0, 0, "07:00", 20, 30, 200000, "06-01", List.of(),
                Map.of("land_jiga", 30, "nope", 10, "land_info", 0));
        assertThat(KrasNextgenSettingsService.validate(
                new KrasNextgenSettingsService.Settings("", "", "", "yyyyMMdd", Map.of(), p)))
                .anyMatch(e -> e.contains("land_jiga") && e.contains("가격"))
                .anyMatch(e -> e.contains("nope"))
                .anyMatch(e -> e.contains("land_info") && e.contains("1일 이상"));
    }
}
