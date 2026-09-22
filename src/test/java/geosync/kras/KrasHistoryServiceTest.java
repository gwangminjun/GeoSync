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
        when(jdbc.queryForList(contains("si.item_id=? AND si.org_cd=?"), eq(44L), eq("46870")))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.preview(jdbc, "46870", 44L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbc, never()).query(contains("stage_"), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));
    }

    @Test
    void listsOnlyCurrentOrganizationWithBoundedLimit() {
        when(jdbc.queryForObject(contains("to_regclass"), eq(String.class))).thenReturn(null);
        service.history(jdbc, "46870", "land_info", 9999);
        verify(jdbc).queryForList(contains("si.org_cd=?"), eq("46870"), eq("land_info"), eq("land_info"), eq(100));
    }

    @Test
    void failedItemCannotBeResumed() {
        when(jdbc.queryForList(contains("si.item_id=? AND si.org_cd=?"), eq(44L), eq("46870")))
                .thenReturn(List.of(Map.of("item_id", 44L, "dataset_code", "land_info", "status", "FAILED")));
        when(jdbc.queryForList(contains("sync_dataset"), eq("land_info"))).thenReturn(List.of(
                Map.of("contract_status", "VERIFIED", "enabled", true, "service_code", "KRAS000002")));
        assertThat(service.preview(jdbc, "46870", 44L)).containsEntry("promotable", false);
    }

    @Test
    void rejectsPnuFromDifferentOrganizationBeforeGatewayCall() {
        assertThatThrownBy(() -> service.requirePnuInput("46870", "1111025021100010000", "land_info", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("기관");
    }

    @Test
    void requiresBuildingIdentifierForDrilldown() {
        assertThatThrownBy(() -> service.requirePnuInput("46870", "4687025021100010000", "bldg_ho_info", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bldg_gbn_no");
    }

    @Test
    void requiresNineteenDigitPnu() {
        assertThatThrownBy(() -> service.requirePnuInput("46870", "not-a-pnu", "land_info", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("19");
    }
}
