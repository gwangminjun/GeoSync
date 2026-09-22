package geosync.kras;

import geosync.common.xml.XmlUtil;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static geosync.kras.KrasSpecMapper.Section;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 선언형 매퍼의 핵심은 "반복 그룹을 감싸는 태그 이름을 모르는 채로 반복 단위를 찾는 것"이다.
 * 이게 틀리면 14개 서비스가 전부 조용히 빈 값으로 들어가므로 여기서 잡는다.
 */
class KrasSpecMapperTest {

    private static final String PNU = "1283025625102000001";

    private static Document parse(String xml) throws Exception {
        return XmlUtil.parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static KrasSpecMapper mapper(Section... sections) {
        return new KrasSpecMapper("test_ds", "KRAS000000", "KRAS", List.of(sections), List.of(), false);
    }

    @Test
    void findsRepeatUnitsWithoutKnowingTheWrapperTagName() throws Exception {
        // 감싸는 태그가 USE_ZONE_SET/USE_ZONE인지 다른 이름인지 매퍼는 모른다 — 기준 필드로 역추적한다.
        Document xml = parse("""
            <RESPONSE><BODY><WHATEVER_SET>
              <WHATEVER_ITEM><USE_ZONE_ZONE_CD>UQA</USE_ZONE_ZONE_CD><CFLT_YN>N</CFLT_YN></WHATEVER_ITEM>
              <WHATEVER_ITEM><USE_ZONE_ZONE_CD>UQB</USE_ZONE_ZONE_CD><CFLT_YN>Y</CFLT_YN></WHATEVER_ITEM>
            </WHATEVER_SET></BODY></RESPONSE>
            """);

        var result = mapper(Section.repeated("kras.stage_land_use_zone", "USE_ZONE_ZONE_CD",
                List.of("pnu", "use_zone_zone_cd", "cflt_yn"), List.of(), List.of())).map(xml, PNU);

        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).columns())
                .containsEntry("pnu", PNU)
                .containsEntry("use_zone_zone_cd", "UQA")
                .containsEntry("cflt_yn", "N");
        assertThat(result.rows().get(1).columns()).containsEntry("use_zone_zone_cd", "UQB");
        assertThat(result.fieldWarnings()).isEmpty();
    }

    @Test
    void treatsSingleOccurrenceAsOneRow() throws Exception {
        Document xml = parse("""
            <RESPONSE><BODY><X><USE_ZONE_ZONE_CD>UQA</USE_ZONE_ZONE_CD></X></BODY></RESPONSE>
            """);

        var result = mapper(Section.repeated("kras.stage_land_use_zone", "USE_ZONE_ZONE_CD",
                List.of("pnu", "use_zone_zone_cd"), List.of(), List.of())).map(xml, PNU);

        assertThat(result.rows()).hasSize(1);
    }

    @Test
    void failsLoudlyWithActualTagsWhenTheGuessedTagIsAbsent() throws Exception {
        // 태그명 가설이 틀린 경우 — 조용히 NULL로 채우지 않고, 실제 응답 태그를 알려줘야 한 번에 고칠 수 있다.
        Document xml = parse("""
            <RESPONSE><BODY><ZONE_LIST><ZONE><REAL_ZONE_CD>UQA</REAL_ZONE_CD></ZONE></ZONE_LIST></BODY></RESPONSE>
            """);

        assertThatThrownBy(() -> mapper(Section.repeated("kras.stage_land_use_zone", "USE_ZONE_ZONE_CD",
                List.of("pnu", "use_zone_zone_cd"), List.of(), List.of())).map(xml, PNU))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("USE_ZONE_ZONE_CD")
                .hasMessageContaining("REAL_ZONE_CD");   // 실제 응답 태그가 메시지에 들어있다
    }

    @Test
    void stripsThousandSeparatorsAndKeepsRawOnParseFailure() throws Exception {
        Document xml = parse("""
            <RESPONSE><BODY><X><GAREA>1,788.25</GAREA><BLR>없음</BLR></X></BODY></RESPONSE>
            """);

        var result = mapper(Section.repeated("kras.stage_building_summary", "GAREA",
                List.of("garea", "blr"), List.of(), List.of("garea", "blr"))).map(xml, PNU);

        var row = result.rows().get(0).columns();
        assertThat(row.get("garea")).hasToString("1788.25");
        assertThat(row.get("blr")).isNull();
        // 파싱 실패는 경고로 남아 item이 SUCCESS로 못 올라간다 + 원문은 extra_attributes에 보존된다
        assertThat(result.fieldWarnings()).isNotEmpty();
        assertThat(row).extractingByKey("extra_attributes").isNotNull();
    }

    @Test
    void marksParentRecordNoSoChildRowsCanBeLinked() throws Exception {
        Document xml = parse("""
            <RESPONSE><BODY>
              <I><FLR_GBN_CD>10</FLR_GBN_CD></I>
              <I><FLR_GBN_CD>20</FLR_GBN_CD></I>
            </BODY></RESPONSE>
            """);

        var result = mapper(Section.repeated("kras.stage_building_floor", "FLR_GBN_CD",
                List.of("flr_gbn_cd"), List.of(), List.of())).map(xml, PNU);

        assertThat(result.rows().get(0).columns()).containsEntry("parent_record_no", 1L);
        assertThat(result.rows().get(1).columns()).containsEntry("parent_record_no", 2L);
    }
}
