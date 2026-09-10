package geomex.sync.common.geo;

import org.geotools.api.geometry.MismatchedDimensionException;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.api.referencing.operation.TransformException;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CoordTransformer {

    private static final Logger log = LoggerFactory.getLogger(CoordTransformer.class);

    // 변환 좌표계: EPSG:5176 (Bessel 동부원점, 기존 GEOMEX-SYNC-HOME 중간 변환 좌표계)
    private static final int TARGET_EPSG = 5176;
    // DB 저장 좌표계: EPSG:5186 (Korea 2000 중부원점)
    private static final int STORAGE_EPSG = 5186;

    // transform 캐시 (소스 EPSG → MathTransform)
    private final Map<Integer, MathTransform> transformCache = new ConcurrentHashMap<>();
    private final CoordinateReferenceSystem targetCrs;

    public CoordTransformer() {
        try {
            // GeoTools가 경도·위도 순서 대신 x·y 순서를 사용하도록 설정
            System.setProperty("org.geotools.referencing.forceXY", "true");
            targetCrs = CRS.decode("EPSG:" + TARGET_EPSG);
        } catch (FactoryException e) {
            throw new IllegalStateException("대상 CRS(EPSG:" + TARGET_EPSG + ") 초기화 실패", e);
        }
    }

    /**
     * WKT 도형 문자열을 소스 EPSG에서 EPSG:5174로 변환한 후 WKT로 반환
     */
    public String transformWkt(String wkt, int sourceEpsg) {
        if (wkt == null || wkt.isBlank()) return null;
        if (sourceEpsg == TARGET_EPSG) return wkt;

        try {
            Geometry geom = new WKTReader().read(wkt);
            Geometry transformed = transform(geom, sourceEpsg);
            return new WKTWriter().write(transformed);
        } catch (ParseException e) {
            log.warn("WKT 파싱 실패 (epsg={}): {}", sourceEpsg, e.getMessage());
            return null;
        }
    }

    /**
     * JTS Geometry를 소스 EPSG에서 EPSG:5174로 변환
     */
    public Geometry transform(Geometry geom, int sourceEpsg) {
        if (sourceEpsg == TARGET_EPSG) return geom;
        try {
            MathTransform tx = transformCache.computeIfAbsent(sourceEpsg, this::buildTransform);
            return JTS.transform(geom, tx);
        } catch (MismatchedDimensionException | TransformException e) {
            throw new IllegalStateException("좌표 변환 실패 (EPSG:" + sourceEpsg + " → " + TARGET_EPSG + ")", e);
        }
    }

    private MathTransform buildTransform(int sourceEpsg) {
        try {
            CoordinateReferenceSystem sourceCrs = CRS.decode("EPSG:" + sourceEpsg);
            return CRS.findMathTransform(sourceCrs, targetCrs, true);
        } catch (FactoryException e) {
            throw new IllegalStateException("CRS 변환 생성 실패 (EPSG:" + sourceEpsg + ")", e);
        }
    }

    public int getTargetEpsg() {
        return TARGET_EPSG;
    }

    public int getStorageEpsg() {
        return STORAGE_EPSG;
    }
}
