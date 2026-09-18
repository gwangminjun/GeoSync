package geosync.kras;

import geosync.synchronization.model.ColumnDef;
import geosync.synchronization.model.SyncTableDef;
import org.geotools.api.data.FeatureWriter;
import org.geotools.api.data.Transaction;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKTReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import geosync.settings.RuntimeSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class KrasFileWriter {

    private static final Logger log = LoggerFactory.getLogger(KrasFileWriter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeSettingsService settings;

    public KrasFileWriter(RuntimeSettingsService settings) {
        this.settings = settings;
    }

    public void write(String layerName, SyncTableDef def, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        try {
            Path dir = Path.of(settings.krasWorkDir(), settings.orgCode());
            Files.createDirectories(dir);
            String fileName = toFileName(layerName);
            if (def.hasGeometry()) {
                writeShp(dir, fileName, def, rows);
            } else {
                writeTxt(dir, fileName, def, rows);
            }
            log.debug("[KRAS] 파일 저장: {}/{}", settings.orgCode(), fileName);
        } catch (Exception e) {
            log.warn("[KRAS] 파일 저장 실패 ({}): {}", layerName, e.getMessage());
        }
    }

    public void writeJson(String layerName, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        try {
            Path dir = Path.of(settings.krasWorkDir(), settings.orgCode());
            Files.createDirectories(dir);
            String fileName = toFileName(layerName);
            objectMapper.writeValue(dir.resolve(fileName + ".json").toFile(), rows);
            log.debug("[KRAS] JSON 캐시 저장: {}/{}.json", settings.orgCode(), fileName);
        } catch (Exception e) {
            log.warn("[KRAS] JSON 캐시 저장 실패 ({}): {}", layerName, e.getMessage());
        }
    }

    public void writeManifest(Map<String, List<String>> tableToFiles) {
        try {
            Path dir = Path.of(settings.krasWorkDir(), settings.orgCode());
            Files.createDirectories(dir);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("collectedAt", LocalDateTime.now().toString());
            manifest.put("tables", tableToFiles);
            objectMapper.writeValue(dir.resolve("_manifest.json").toFile(), manifest);
            log.info("[KRAS] 수집 매니페스트 저장: {} 테이블", tableToFiles.size());
        } catch (Exception e) {
            log.warn("[KRAS] 매니페스트 저장 실패: {}", e.getMessage());
        }
    }

    private String toFileName(String layerName) {
        String name = layerName.contains(":") ? layerName.substring(layerName.indexOf(':') + 1) : layerName;
        return name.toLowerCase();
    }

    private void writeShp(Path dir, String fileName, SyncTableDef def,
                          List<Map<String, Object>> rows) throws Exception {
        File shpFile = dir.resolve(fileName + ".shp").toFile();

        // 기존 파일 제거 (덮어쓰기)
        for (String ext : List.of(".shp", ".dbf", ".shx", ".prj", ".fix")) {
            Files.deleteIfExists(dir.resolve(fileName + ext));
        }

        ColumnDef geomCol = def.columns.stream().filter(c -> c.isGeometry).findFirst().orElseThrow();

        SimpleFeatureTypeBuilder tb = new SimpleFeatureTypeBuilder();
        tb.setName(fileName);
        tb.setCRS(CRS.decode("EPSG:5174"));
        tb.add("the_geom", resolveGeomClass(def.geomType));
        for (ColumnDef col : def.columns) {
            if (col.isGeometry) continue;
            tb.add(shpColName(col.srcName), String.class);
        }
        SimpleFeatureType featureType = tb.buildFeatureType();

        ShapefileDataStoreFactory factory = new ShapefileDataStoreFactory();
        ShapefileDataStore store = (ShapefileDataStore) factory.createNewDataStore(
                Map.of("url", shpFile.toURI().toURL()));
        store.createSchema(featureType);
        store.setCharset(Charset.forName("MS949"));

        WKTReader wktReader = new WKTReader();
        Transaction tx = new DefaultTransaction("kras-write");
        try (FeatureWriter<SimpleFeatureType, SimpleFeature> writer =
                     store.getFeatureWriterAppend(store.getTypeNames()[0], tx)) {
            for (Map<String, Object> row : rows) {
                SimpleFeature feature = writer.next();
                String wkt = (String) row.get(geomCol.srcName);
                if (wkt != null) {
                    feature.setDefaultGeometry(wktReader.read(wkt));
                }
                for (ColumnDef col : def.columns) {
                    if (col.isGeometry) continue;
                    Object val = row.get(col.srcName);
                    try {
                        feature.setAttribute(shpColName(col.srcName), val != null ? val.toString() : null);
                    } catch (Exception ignored) {}
                }
                writer.write();
            }
            tx.commit();
        } catch (Exception e) {
            tx.rollback();
            throw e;
        } finally {
            tx.close();
            store.dispose();
        }
    }

    private void writeTxt(Path dir, String fileName, SyncTableDef def,
                          List<Map<String, Object>> rows) throws Exception {
        File txtFile = dir.resolve(fileName + ".txt").toFile();
        List<ColumnDef> cols = def.columns.stream()
                .filter(c -> !c.isGeometry).collect(Collectors.toList());

        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(txtFile), Charset.forName("MS949")))) {
            pw.println(cols.stream().map(c -> c.srcName).collect(Collectors.joining(",")));
            for (Map<String, Object> row : rows) {
                pw.println(cols.stream()
                        .map(c -> {
                            Object v = row.get(c.srcName);
                            return v != null ? v.toString().replace(",", " ") : "";
                        })
                        .collect(Collectors.joining(",")));
            }
        }
    }

    private String shpColName(String name) {
        return name.length() > 10 ? name.substring(0, 10) : name;
    }

    private Class<? extends Geometry> resolveGeomClass(String type) {
        if (type == null) return Geometry.class;
        return switch (type.toUpperCase()) {
            case "MULTIPOLYGON"    -> MultiPolygon.class;
            case "MULTILINESTRING" -> MultiLineString.class;
            case "POINT"           -> Point.class;
            default                -> Geometry.class;
        };
    }
}
