package geosync.kras;

import geosync.common.xml.XmlUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class LandChangeMapperTest {
    private static final LocalDate D1 = LocalDate.of(2026, 10, 1), D2 = LocalDate.of(2026, 10, 10);

    @Test
    void serviceIdAndDateParamsComeFromDeployConfig() {
        LandChangeMapper mapper = new LandChangeMapper(" KRAS000099 ", "PRCS_ST_YMD", "PRCS_ED_YMD", "yyyyMMdd");
        assertThat(mapper.connSvcId()).isEqualTo("KRAS000099");
        assertThat(mapper.dateParams(D1, D2))
                .containsEntry("PRCS_ST_YMD", "20261001").containsEntry("PRCS_ED_YMD", "20261010");
    }

    @Test
    void blankServiceIdStaysUnconfirmedSoReadinessBlocksCalls() {
        LandChangeMapper mapper = new LandChangeMapper("", "", "", "");
        assertThatThrownBy(mapper::connSvcId).isInstanceOf(UnsupportedOperationException.class);
        assertThat(mapper.dateParams(D1, D2)).isEmpty();
    }

    @Test
    void emptyEventListIsZeroRowsButMissingListStillFails() throws Exception {
        LandChangeMapper mapper = new LandChangeMapper("X", "", "", "yyyyMMdd");
        var empty = mapper.map(XmlUtil.parse("<LAND_MOV_CHG_SET/>".getBytes(StandardCharsets.UTF_8)), "12830", D1, D2);
        assertThat(empty.rows()).isEmpty();
        assertThat(empty.fieldWarnings()).isEmpty();
        assertThatThrownBy(() -> mapper.map(XmlUtil.parse("<OTHER/>".getBytes(StandardCharsets.UTF_8)), "12830", D1, D2))
                .isInstanceOf(IllegalStateException.class);
    }
}
