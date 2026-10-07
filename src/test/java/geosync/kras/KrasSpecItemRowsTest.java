package geosync.kras;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KrasSpecItemRowsTest {

    private static Map<String, Object> status(String contract, boolean enabled) {
        return Map.of("contract_status", contract, "enabled", enabled);
    }

    private static Map<String, Map<String, Object>> statuses(Object... pairs) {
        Map<String, Map<String, Object>> byCode = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            byCode.put((String) pairs[i], (Map<String, Object>) pairs[i + 1]);
        }
        return byCode;
    }

    private static Map<String, Object> rowOf(List<Map<String, Object>> rows, int specNo) {
        return rows.stream().filter(r -> r.get("specNo").equals(specNo)).findFirst().orElseThrow();
    }

    @Test
    void listsAllSixteenItemsWithNoStatusRows() {
        List<Map<String, Object>> rows = KrasSchemaController.toSpecItemRows(Map.of());

        assertThat(rows).hasSize(16);
        assertThat(rows.stream().filter(r -> Boolean.TRUE.equals(r.get("callable")))).isEmpty();
        assertThat(rows.stream().filter(r -> Boolean.TRUE.equals(r.get("docError")))).hasSize(2);
        assertThat(rows.stream().filter(r -> "없음".equals(r.get("serviceId"))
                && !Boolean.TRUE.equals(r.get("docError")))).hasSize(7);
    }

    @Test
    void docErrorItemsAreNotCallableEvenWithStatus() {
        List<Map<String, Object>> rows = KrasSchemaController.toSpecItemRows(Map.of());

        assertThat(rowOf(rows, 11).get("state")).isEqualTo("규격서 오류 · 미구현");
        assertThat(rowOf(rows, 12).get("state")).isEqualTo("규격서 오류 · 미구현");
        assertThat(rowOf(rows, 11).get("callable")).isEqualTo(false);
    }

    @Test
    void itemWithoutServiceIdStaysNotCallableEvenWhenVerifiedAndEnabled() {
        // 토지이동내역(§10)은 계약이 검증돼도 서비스 ID가 없으면 호출할 수 없다.
        Map<String, Map<String, Object>> byCode = statuses("land_change", status("VERIFIED", true));

        List<Map<String, Object>> rows = KrasSchemaController.toSpecItemRows(byCode);

        assertThat(rowOf(rows, 10).get("state")).isEqualTo("서비스 ID 없음 · 호출 불가");
        assertThat(rowOf(rows, 10).get("callable")).isEqualTo(false);
    }

    @Test
    void itemWithIdIsCallableOnlyWhenVerifiedAndEnabled() {
        List<Map<String, Object>> verifiedOn = KrasSchemaController.toSpecItemRows(
                statuses("land_info", status("VERIFIED", true)));
        assertThat(rowOf(verifiedOn, 1).get("callable")).isEqualTo(true);
        assertThat(rowOf(verifiedOn, 1).get("state")).isEqualTo("계약 VERIFIED");

        List<Map<String, Object>> verifiedOff = KrasSchemaController.toSpecItemRows(
                statuses("land_info", status("VERIFIED", false)));
        assertThat(rowOf(verifiedOff, 1).get("callable")).isEqualTo(false);

        List<Map<String, Object>> unverifiedOn = KrasSchemaController.toSpecItemRows(
                statuses("land_info", status("UNVERIFIED", true)));
        assertThat(rowOf(unverifiedOn, 1).get("callable")).isEqualTo(false);
        assertThat(rowOf(unverifiedOn, 1).get("state")).isEqualTo("계약 UNVERIFIED");
    }

    @Test
    void runInfoShowsManualScheduleAndLastRunFromSyncItem() {
        List<Map<String, Object>> rows = KrasSchemaController.toSpecItemRows(Map.of());
        Map<String, Map<String, Object>> last = Map.of("land_info", Map.of(
                "status", "SUCCESS",
                "window_start", java.sql.Date.valueOf("2026-10-01"),
                "window_end_exclusive", java.sql.Date.valueOf("2026-10-06"),
                "requested_at", java.sql.Timestamp.valueOf("2026-10-06 04:31:00")));

        KrasSchemaController.withRunInfo(rows, last);

        assertThat(rowOf(rows, 1).get("schedule")).isEqualTo("수동");
        assertThat(rowOf(rows, 1).get("nextRun")).isEqualTo("—");
        assertThat(rowOf(rows, 1).get("lastRun")).isEqualTo("완료 · 2026-10-06 04:31 · 기간 2026-10-01 ~ 2026-10-06");
        assertThat(rowOf(rows, 2).get("lastRun")).isEqualTo("실행 이력 없음");
        assertThat(rowOf(rows, 11).get("schedule")).isEqualTo("—");
    }

    @Test
    void apiTestIdOnlyForDatasetsInApiTestCatalog() {
        List<Map<String, Object>> rows = KrasSchemaController.toSpecItemRows(Map.of());
        KrasSchemaController.withRunInfo(rows, Map.of());

        assertThat(rowOf(rows, 1).get("apiId")).isEqualTo("conn/land_info");
        assertThat(rowOf(rows, 7).get("apiId")).isEqualTo("conn/land_mov_hist");
        assertThat(rowOf(rows, 4).get("apiId")).isNull();
        assertThat(rowOf(rows, 10).get("apiId")).isNull();
        assertThat(rowOf(rows, 15).get("apiId")).isNull();
    }

    @Test
    void missingDatasetRowDefaultsToUnverifiedAndNotCallable() {
        List<Map<String, Object>> rows = KrasSchemaController.toSpecItemRows(Map.of());

        assertThat(rowOf(rows, 16).get("state")).isEqualTo("계약 UNVERIFIED");
        assertThat(rowOf(rows, 16).get("callable")).isEqualTo(false);
    }
}
