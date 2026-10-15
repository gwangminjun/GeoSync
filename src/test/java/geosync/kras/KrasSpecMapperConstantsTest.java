package geosync.kras;

import geosync.common.xml.XmlUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class KrasSpecMapperConstantsTest {

    /** 고정값으로 선언한 칼럼(request_key)을 응답 태그(없음 → null)로 덮으면 반영 때 NOT NULL에 걸린다. */
    @Test
    void constantColumnsAreNotOverwrittenByMissingResponseTags() throws Exception {
        String pnu = "1283025021100010000";
        byte[] xml = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><RESPONSE><HEADER><CODE>0000</CODE></HEADER><BODY>"
                + "<LAND_LOC_NM>시험리</LAND_LOC_NM><JIBN>1</JIBN><JIMOK>08</JIMOK><PAREA>10</PAREA><JIGA>100</JIGA>"
                + "<RESTRICT><SEQNO>1</SEQNO><LAW_FULL_CD>UQA100</LAW_FULL_CD></RESTRICT></BODY></RESPONSE>").getBytes(StandardCharsets.UTF_8);

        var result = new KrasSpecMapperConfig().landUsePlanInfoMapper().map(XmlUtil.parse(xml), pnu);

        var plan = result.rows().stream().filter(r -> r.stageTable().equals("kras.stage_land_use_plan"))
                .findFirst().orElseThrow();
        assertThat(plan.columns()).containsEntry("request_key", KrasSpecMapper.COMPUTE_REQUEST_KEY)
                .containsEntry("pnu", pnu).containsEntry("jimok", "08");
    }
}
