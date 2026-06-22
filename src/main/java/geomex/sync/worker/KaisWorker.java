package geomex.sync.worker;

import geomex.sync.geo.CoordTransformer;
import geomex.sync.mapper.TableMapper;
import geomex.sync.model.ColumnDef;
import geomex.sync.model.SyncTableDef;
import geomex.sync.repository.OdsRepository;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class KaisWorker {

    private static final Logger log = LoggerFactory.getLogger(KaisWorker.class);
    private static final int KAIS_EPSG = 5179;

    private final TableMapper tableMapper;
    private final OdsRepository odsRepository;
    private final CoordTransformer coordTransformer;

    @Value("${kais.work-dir:./KAIS_WORK}")
    private String workDir;

    @Value("${kais.config:conf/kais/base-tables.xml}")
    private String configPath;

    @Value("${sync.org-code:46870}")
    private String orgCode;

    public KaisWorker(TableMapper tableMapper, OdsRepository odsRepository,
                      CoordTransformer coordTransformer) {
        this.tableMapper = tableMapper;
        this.odsRepository = odsRepository;
        this.coordTransformer = coordTransformer;
    }

    public void run() {
        log.info("[KAIS] 동기화 시작 (workDir={})", workDir);
        List<SyncTableDef> tableDefs = tableMapper.load(configPath);

        for (SyncTableDef def : tableDefs) {
            File shpFile = findShpFile(def.srcTableName);
            if (shpFile == null) {
                log.warn("[KAIS] SHP 파일 없음: {}", def.srcTableName);
                continue;
            }
            processShp(def, shpFile);
        }
        log.info("[KAIS] 동기화 완료");
    }

    private void processShp(SyncTableDef def, File shpFile) {
        ShapefileDataStore store = null;
        try {
            store = new ShapefileDataStore(shpFile.toURI().toURL());
            store.setCharset(Charset.forName("EUC-KR"));

            SimpleFeatureSource source = store.getFeatureSource();
            SimpleFeatureCollection collection = source.getFeatures();

            List<Map<String, Object>> rows = new ArrayList<>();
            WKTWriter wktWriter = new WKTWriter();

            try (SimpleFeatureIterator it = collection.features()) {
                while (it.hasNext()) {
                    SimpleFeature feature = it.next();
                    Map<String, Object> row = extractRow(def, feature, wktWriter);
                    rows.add(row);
                }
            }

            int saved = odsRepository.replaceAll(def, orgCode, coordTransformer.getTargetEpsg(), rows);
            log.info("[KAIS] {} → {}건 처리", def.tgtTableName, saved);

        } catch (Exception e) {
            log.error("[KAIS] {} 처리 실패: {}", def.tgtTableName, e.getMessage(), e);
        } finally {
            if (store != null) store.dispose();
        }
    }

    private Map<String, Object> extractRow(SyncTableDef def, SimpleFeature feature, WKTWriter wktWriter) {
        Map<String, Object> row = new HashMap<>();
        for (ColumnDef col : def.columns) {
            if (col.isGeometry) {
                Geometry geom = (Geometry) feature.getDefaultGeometry();
                if (geom != null) {
                    Geometry transformed = coordTransformer.transform(geom, KAIS_EPSG);
                    row.put(col.srcName, wktWriter.write(transformed));
                }
            } else {
                Object val = feature.getAttribute(col.srcName);
                row.put(col.srcName, val != null ? val.toString() : null);
            }
        }
        return row;
    }

    private File findShpFile(String tableName) {
        // tableName: tl_spbd_buld → workDir/tl_spbd_buld.shp
        File f = new File(workDir, tableName + ".shp");
        if (f.exists()) return f;

        // 대소문자 무시 검색
        File dir = new File(workDir);
        if (dir.isDirectory()) {
            File[] matches = dir.listFiles(
                    (d, name) -> name.equalsIgnoreCase(tableName + ".shp"));
            if (matches != null && matches.length > 0) return matches[0];
        }
        return null;
    }
}
