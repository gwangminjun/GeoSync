package geosync.kras;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KrasTableBrowserTest {

    @Test
    void splitsSpecTargetTableTextIntoTableNames() {
        assertThat(KrasTableBrowser.splitTables("kras.land_change_event → kras.parcel, kras.land_basic"))
                .containsExactly("kras.land_change_event", "kras.parcel", "kras.land_basic");
        assertThat(KrasTableBrowser.splitTables(null)).isEmpty();
        assertThat(KrasSchemaController.BROWSABLE_TABLES)
                .contains("kras.land_owner", "kras.parcel", "public.lp_pa_cbnd")
                .doesNotContain("kras.sync_dataset", "kras.pnu_work");
        // 지도 미리보기는 SHAPE 다운로드(§15) 테이블만
        assertThat(KrasSchemaController.PREVIEW_TABLES).containsExactlyInAnyOrder("public.lp_pa_cbnd", "public.lt_c_uzone");
        assertThat(KrasSchemaController.mapConfig("http://gs/geoserver/YG-Space"))
                .containsEntry("wmsUrl", "http://gs/geoserver/YG-Space/wms");
        assertThat(KrasSchemaController.mapConfig("")).containsEntry("wmsUrl", "");
    }

    @Test
    void rejectsTablesOutsideTheAllowListBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> KrasTableBrowser.views(null, Set.of("kras.parcel"), "kras.sync_dataset", "12830"))
                .isInstanceOf(KrasTableBrowser.BrowseException.class);
        assertThatThrownBy(() -> KrasTableBrowser.browse(null, Set.of("kras.parcel"), "kras.parcel; DELETE", "12830",
                null, null, 0)).isInstanceOf(KrasTableBrowser.BrowseException.class);
        assertThatThrownBy(() -> KrasTableBrowser.browse(null, Set.of("kras.parcel"), "kras.parcel", "12830",
                null, null, KrasTableBrowser.MAX_PAGE + 1)).isInstanceOf(KrasTableBrowser.BrowseException.class);
    }

    @Test
    void buildsBoundConditionsPerColumnTypeAndEscapesLikeWildcards() {
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        KrasTableBrowser.addCondition(KrasTableBrowser.column("pnu", "varchar"), "12830_%", where, args);
        KrasTableBrowser.addCondition(KrasTableBrowser.column("jimok_nm", "text"), "대", where, args);
        KrasTableBrowser.addCondition(KrasTableBrowser.column("source_item_id", "int8"), "4411", where, args);
        KrasTableBrowser.addCondition(KrasTableBrowser.column("observed_at", "timestamptz"), "2026-10-15", where, args);

        assertThat(where).containsExactly("\"pnu\" LIKE ? ESCAPE '\\'", "\"jimok_nm\" ILIKE ? ESCAPE '\\'",
                "\"source_item_id\" = ?", "\"observed_at\" >= ? AND \"observed_at\" < ?");
        assertThat(args).containsExactly("12830\\_\\%%", "%대%", new BigDecimal("4411"),
                java.sql.Date.valueOf("2026-10-15"), java.sql.Date.valueOf("2026-10-16"));

        assertThatThrownBy(() -> KrasTableBrowser.addCondition(KrasTableBrowser.column("parea", "numeric"), "abc",
                where, args)).hasMessageContaining("숫자");
        assertThatThrownBy(() -> KrasTableBrowser.addCondition(KrasTableBrowser.column("geom", "geometry"), "x",
                where, args)).hasMessageContaining("검색할 수 없습니다");
    }

    @Test
    void readsTheFilterOfSingleConditionViewsFromPostgresViewDefinitions() {
        // pg_get_viewdef 출력 형식 그대로 (v_lt_c_uzone_* 306개가 모두 이 두 모양).
        var layer = KrasTableBrowser.SIMPLE_VIEW_FILTER.matcher(
                " SELECT geom AS _geometry,\n    mnum\n   FROM lt_c_uzone\n  WHERE ((layer_code)::text = 'UD210'::text);");
        assertThat(layer.find()).isTrue();
        assertThat(layer.group(1)).isEqualTo("layer_code");
        assertThat(layer.group(2)).isEqualTo("UD210");
        assertThat(KrasTableBrowser.SIMPLE_VIEW_FILTER.matcher(
                " SELECT uid FROM lt_c_uzone\n  WHERE ((theme_code)::text = 'UQU330'::text);").find()).isTrue();
        assertThat(KrasTableBrowser.SIMPLE_VIEW_FILTER.matcher(
                " SELECT b.pnu FROM land_basic b JOIN parcel p USING (pnu)\n  WHERE (b.org_cd)::text = 'X'::text AND 1=1;").find())
                .isFalse();
    }

    @Test
    void masksOwnerPersonalDataAndKeepsItOutOfSearch() {
        var name = KrasTableBrowser.column("owner_nm", "text");
        var addr = KrasTableBrowser.column("owner_addr", "varchar");
        var regno = KrasTableBrowser.column("dregno", "varchar");
        var json = KrasTableBrowser.column("extra_attributes", "jsonb");
        var plain = KrasTableBrowser.column("note", "text");

        assertThat(name.searchable()).isFalse();
        assertThat(name.masked()).isTrue();
        assertThat(KrasTableBrowser.mask(name, "홍길동")).isEqualTo("홍**");
        assertThat(KrasTableBrowser.mask(addr, "경기도 양평군 양평읍 시민로 1")).isEqualTo("경기도 양평군 ***");
        assertThat(KrasTableBrowser.mask(regno, "8001011234567")).isEqualTo("***");
        assertThat(KrasTableBrowser.mask(json, "{\"OWNER_NM\": \"홍길동\", \"jimok\": \"08\"}"))
                .isEqualTo("{\"OWNER_NM\": \"***\", \"jimok\": \"08\"}");
        assertThat(KrasTableBrowser.mask(plain, "비고 800101-1234567")).isEqualTo("비고 [MASKED]");
        assertThat(KrasTableBrowser.mask(name, null)).isNull();
    }
}
