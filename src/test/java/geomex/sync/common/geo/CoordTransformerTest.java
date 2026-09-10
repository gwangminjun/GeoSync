package geomex.sync.common.geo;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;

import static org.assertj.core.api.Assertions.assertThat;

class CoordTransformerTest {

    private final CoordTransformer transformer = new CoordTransformer();

    @Test
    void sameEpsgReturnsOriginal() {
        String wkt = "MULTIPOLYGON(((173120.45 88234.56, 173150.45 88234.56, 173150.45 88264.56, 173120.45 88234.56)))";
        String result = transformer.transformWkt(wkt, 5176);
        assertThat(result).isEqualTo(wkt);
    }

    @Test
    void transforms5174to5176() throws Exception {
        // 완도군 근사 좌표 (EPSG:5174)
        String wkt = "POINT(173120.45 88234.56)";
        String result = transformer.transformWkt(wkt, 5174);

        assertThat(result).isNotNull();
        // 변환 후 좌표가 원본과 달라야 함
        assertThat(result).isNotEqualTo(wkt);

        // 변환된 좌표가 유효한 WKT인지 확인
        Geometry geom = new WKTReader().read(result);
        assertThat(geom).isNotNull();
        assertThat(geom.isValid()).isTrue();
    }

    @Test
    void transforms5179to5176() throws Exception {
        // UTM-K 좌표 (EPSG:5179) - 완도 근사
        String wkt = "POINT(292000.0 187000.0)";
        String result = transformer.transformWkt(wkt, 5179);

        assertThat(result).isNotNull();
        assertThat(result).isNotEqualTo(wkt);
    }

    @Test
    void nullWktReturnsNull() {
        assertThat(transformer.transformWkt(null, 5174)).isNull();
    }
}
