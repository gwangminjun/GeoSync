package geosync.kras;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KrasHistoryServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final KrasHistoryService service = new KrasHistoryService(List.of(new LandInfoMapper()), List.of());

    @Test
    void blocksUnverifiedDatasetBeforeExecution() {
        when(jdbc.queryForList(contains("sync_dataset"), eq("land_info"))).thenReturn(List.of(
                Map.of("contract_status", "UNVERIFIED", "enabled", false, "service_code", "KRAS000002")));
        assertThat(service.readiness(jdbc, "land_info")).isNotEmpty();
        assertThatThrownBy(() -> service.requireReady(jdbc, "land_info")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsItemOutsideCurrentOrganization() {
        when(jdbc.queryForList(contains("si.item_id=? AND si.org_cd=?"), eq(44L), eq("12830")))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.preview(jdbc, "12830", 44L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbc, never()).query(contains("stage_"), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));
    }

    @Test
    void listsOnlyCurrentOrganizationWithBoundedLimit() {
        when(jdbc.queryForObject(contains("to_regclass"), eq(String.class))).thenReturn(null);
        service.history(jdbc, "12830", "land_info", 9999);
        verify(jdbc).queryForList(contains("si.org_cd=?"), eq("12830"), eq("land_info"), eq("land_info"), eq(100));
    }

    @Test
    void failedItemCannotBeResumed() {
        when(jdbc.queryForList(contains("si.item_id=? AND si.org_cd=?"), eq(44L), eq("12830")))
                .thenReturn(List.of(Map.of("item_id", 44L, "dataset_code", "land_info", "status", "FAILED")));
        when(jdbc.queryForList(contains("sync_dataset"), eq("land_info"))).thenReturn(List.of(
                Map.of("contract_status", "VERIFIED", "enabled", true, "service_code", "KRAS000002")));
        assertThat(service.preview(jdbc, "12830", 44L)).containsEntry("promotable", false);
    }

    @Test
    void rejectsPnuFromDifferentOrganizationBeforeGatewayCall() {
        assertThatThrownBy(() -> service.requirePnuInput("12830", "1111025021100010000", "land_info", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("기관");
    }

    @Test
    void requiresBuildingIdentifierForDrilldown() {
        assertThatThrownBy(() -> service.requirePnuInput("12830", "1283025021100010000", "bldg_ho_info", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bldg_gbn_no");
    }

    @Test
    void requiresNineteenDigitPnu() {
        assertThatThrownBy(() -> service.requirePnuInput("12830", "not-a-pnu", "land_info", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("19");
    }

    @Test
    void previewIncludesComparisonNotingExistingRowWillBeUpdated() {
        when(jdbc.queryForList(contains("si.item_id=? AND si.org_cd=?"), eq(44L), eq("12830")))
                .thenReturn(List.of(Map.of("item_id", 44L, "dataset_code", "land_info", "status", "SUCCESS")));
        when(jdbc.queryForList(contains("sync_dataset"), eq("land_info"))).thenReturn(List.of(
                Map.of("contract_status", "VERIFIED", "enabled", true, "service_code", "KRAS000002")));
        when(jdbc.query(contains("row_to_json"), any(org.springframework.jdbc.core.RowMapper.class), eq(44L)))
                .thenReturn(List.of());
        when(jdbc.queryForObject(contains("count(*) FROM kras.stage_parcel"), eq(Long.class), eq(44L))).thenReturn(1L);
        when(jdbc.queryForMap(contains("kras.stage_parcel"), eq(44L))).thenReturn(Map.of("pnu", "1283025021100010000"));
        when(jdbc.queryForObject(contains("count(*) FROM kras.parcel"), eq(Long.class), eq("1283025021100010000")))
                .thenReturn(1L);
        // 나머지 두 spec(land_register/land_owner)은 아무 값이나 반환해도 이 테스트 목적(첫 spec 검증)엔 무관하다
        when(jdbc.queryForObject(contains("count(*) FROM kras.stage_land_register"), eq(Long.class), eq(44L))).thenReturn(1L);
        when(jdbc.queryForMap(contains("kras.stage_land_register"), eq(44L))).thenReturn(Map.of("pnu", "1283025021100010000"));
        when(jdbc.queryForObject(contains("count(*) FROM kras.land_register"), eq(Long.class), eq("1283025021100010000")))
                .thenReturn(0L);
        when(jdbc.queryForObject(contains("count(*) FROM kras.stage_land_owner"), eq(Long.class), eq(44L))).thenReturn(1L);
        when(jdbc.queryForMap(contains("kras.stage_land_owner"), eq(44L))).thenReturn(Map.of("pnu", "1283025021100010000"));
        when(jdbc.queryForObject(contains("count(*) FROM kras.land_owner"), eq(Long.class), eq("1283025021100010000")))
                .thenReturn(0L);

        Map<String, Object> result = service.preview(jdbc, "12830", 44L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comparison = (List<Map<String, Object>>) result.get("comparison");
        assertThat(comparison).hasSize(3);
        assertThat(comparison.get(0)).containsEntry("businessTable", "kras.parcel")
                .containsEntry("note", "기존 행 UPDATE 예상(자연키 일치)");
        assertThat(comparison.get(1)).containsEntry("note", "신규 행 INSERT 예상(자연키 불일치)");
    }

    @Test
    void comparisonFailureDoesNotBreakPreview() {
        when(jdbc.queryForList(contains("si.item_id=? AND si.org_cd=?"), eq(44L), eq("12830")))
                .thenReturn(List.of(Map.of("item_id", 44L, "dataset_code", "land_info", "status", "SUCCESS")));
        when(jdbc.queryForList(contains("sync_dataset"), eq("land_info"))).thenReturn(List.of(
                Map.of("contract_status", "VERIFIED", "enabled", true, "service_code", "KRAS000002")));
        when(jdbc.query(contains("row_to_json"), any(org.springframework.jdbc.core.RowMapper.class), eq(44L)))
                .thenReturn(List.of());
        when(jdbc.queryForObject(contains("count(*) FROM kras.stage_parcel"), eq(Long.class), eq(44L)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("연결 끊김"));

        Map<String, Object> result = service.preview(jdbc, "12830", 44L);

        assertThat(result).containsEntry("promotable", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comparison = (List<Map<String, Object>>) result.get("comparison");
        assertThat(comparison.get(0).get("note").toString()).contains("계산할 수 없습니다");
    }
}
