package geomex.sync.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import geomex.sync.geo.CoordTransformer;
import geomex.sync.mapper.TableMapper;
import geomex.sync.model.ColumnDef;
import geomex.sync.model.SyncTableDef;
import geomex.sync.service.RuntimeSettingsService;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * workspace/kras/{orgCode}/ 에 있는 기존 SHP/TXT 파일을 읽어
 * JSON 캐시와 _manifest.json을 생성한다.
 * API 없이 적재만 테스트할 때 사용.
 */
@Component
public class KrasWorkspaceScanner {

    private static final Logger log = LoggerFactory.getLogger(KrasWorkspaceScanner.class);

    private static final int SHP_EPSG = 5174;

    private final TableMapper tableMapper;
    private final KrasFileWriter fileWriter;
    private final RuntimeSettingsService settings;
    private final CoordTransformer coordTransformer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KrasWorkspaceScanner(TableMapper tableMapper, KrasFileWriter fileWriter,
                                RuntimeSettingsService settings, CoordTransformer coordTransformer) {
        this.tableMapper = tableMapper;
        this.fileWriter = fileWriter;
        this.settings = settings;
        this.coordTransformer = coordTransformer;
    }

    public record ScanResult(int fileCount, int rowCount, List<String> messages) {}

    /**
     * 워크스페이스 디렉토리를 탐색하여 base-tables.xml 정의와 매칭되는 파일 목록을 반환한다.
     * manifest 파일 쓰기 없이 현재 상태를 조회만 한다.
     * 결과 형식: srcTableName → List&lt;fileBaseName&gt;
     */
    public Map<String, List<String>> discoverWorkspaceFiles() {
        Path dir = Path.of(settings.krasWorkDir(), settings.orgCode());
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return result;

        Set<String> claimed = new LinkedHashSet<>();
        try {
            List<SyncTableDef> defs = tableMapper.load(settings.krasConfig());

            for (SyncTableDef def : defs) {
                if (def.srcTableName.startsWith("USEZONE:")) continue;
                String baseName = findFile(dir, def, claimed);
                if (baseName != null) {
                    result.put(def.srcTableName, List.of(baseName));
                    claimed.add(baseName);
                }
            }

            for (SyncTableDef def : defs) {
                if (!def.srcTableName.startsWith("USEZONE:")) continue;
                List<String> usezoneFiles = findUsezoneFiles(dir, claimed);
                if (!usezoneFiles.isEmpty()) {
                    result.put(def.srcTableName, usezoneFiles);
                }
            }
        } catch (Exception e) {
            log.warn("[Scanner] 워크스페이스 탐색 실패: {}", e.getMessage());
        }
        return result;
    }

    public ScanResult buildManifest() {
        Path dir = Path.of(settings.krasWorkDir(), settings.orgCode());
        List<String> messages = new ArrayList<>();

        if (!Files.exists(dir)) {
            messages.add("워크스페이스 디렉토리 없음: " + dir.toAbsolutePath());
            return new ScanResult(0, 0, messages);
        }

        int fileCount = 0, rowCount = 0;
        Map<String, List<String>> manifest = new LinkedHashMap<>();
        Set<String> claimed = new LinkedHashSet<>();

        try {
            List<SyncTableDef> defs = tableMapper.load(settings.krasConfig());

            // 1단계: USEZONE이 아닌 테이블 처리
            for (SyncTableDef def : defs) {
                if (def.srcTableName.startsWith("USEZONE:")) continue;

                String baseName = findFile(dir, def, claimed);
                if (baseName == null) {
                    messages.add("파일 없음: " + def.srcTableName + " → " + def.tgtTableName);
                    continue;
                }

                try {
                    List<Map<String, Object>> rows = readFile(dir, baseName, def);
                    if (!rows.isEmpty()) {
                        fileWriter.writeJson(baseName, rows);
                        manifest.put(def.srcTableName, List.of(baseName));
                        claimed.add(baseName);
                        fileCount++;
                        rowCount += rows.size();
                        messages.add("✓ " + def.srcTableName + " → " + baseName
                                + ".json (" + rows.size() + "건)");
                    } else {
                        messages.add("빈 파일: " + baseName);
                    }
                } catch (Exception e) {
                    messages.add("✗ " + def.srcTableName + " 읽기 실패: " + e.getMessage());
                    log.warn("[Scanner] {} 읽기 실패: {}", def.srcTableName, e.getMessage());
                }
            }

            // 2단계: USEZONE — 나머지 미사용 SHP 파일 수집
            for (SyncTableDef def : defs) {
                if (!def.srcTableName.startsWith("USEZONE:")) continue;

                List<String> candidates = findUsezoneFiles(dir, claimed);
                List<String> written = new ArrayList<>();
                int uzRows = 0;
                for (String baseName : candidates) {
                    try {
                        List<Map<String, Object>> rows = readFile(dir, baseName, def);
                        if (!rows.isEmpty()) {
                            fileWriter.writeJson(baseName, rows);
                            written.add(baseName);
                            claimed.add(baseName);
                            fileCount++;
                            uzRows += rows.size();
                        }
                    } catch (Exception e) {
                        messages.add("✗ USEZONE " + baseName + " 읽기 실패: " + e.getMessage());
                        log.warn("[Scanner] USEZONE {} 읽기 실패: {}", baseName, e.getMessage());
                    }
                }
                if (!written.isEmpty()) {
                    manifest.put(def.srcTableName, written);
                    rowCount += uzRows;
                    messages.add("✓ USEZONE " + written.size() + "개 파일 (" + uzRows + "건)");
                } else {
                    messages.add("파일 없음: USEZONE (*.shp)");
                }
            }

            fileWriter.writeManifest(manifest);
            messages.add("_manifest.json 생성 완료 (" + manifest.size() + "개 테이블)");

        } catch (Exception e) {
            messages.add("오류: " + e.getMessage());
            log.error("[Scanner] 매니페스트 생성 실패: {}", e.getMessage(), e);
        }

        return new ScanResult(fileCount, rowCount, messages);
    }

    // 파일 탐색: 신규 네이밍 먼저, 없으면 구 네이밍 시도
    private String findFile(Path dir, SyncTableDef def, Set<String> claimed) {
        // 신규 네이밍: toFileBaseName(srcTableName)
        String newName = toFileBaseName(def.srcTableName);
        if (def.hasGeometry()) {
            if (!claimed.contains(newName) && Files.exists(dir.resolve(newName + ".shp")))
                return newName;
            // 구 네이밍: "ods.lp_pa_cbnd" 형태
            String oldName = def.tgtTableName;
            if (!claimed.contains(oldName) && Files.exists(dir.resolve(oldName + ".shp")))
                return oldName;
        } else {
            if (!claimed.contains(newName) && Files.exists(dir.resolve(newName + ".txt")))
                return newName;
            String oldName = def.tgtTableName;
            if (!claimed.contains(oldName) && Files.exists(dir.resolve(oldName + ".txt")))
                return oldName;
        }
        return null;
    }

    // dot이 없는 SHP 파일 = 신규 시스템이 쓴 파일 (USEZONE 후보)
    private List<String> findUsezoneFiles(Path dir, Set<String> claimed) {
        List<String> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".shp"))
                 .map(p -> p.getFileName().toString().replace(".shp", ""))
                 .filter(name -> !claimed.contains(name) && !name.contains("."))
                 .sorted()
                 .forEach(result::add);
        } catch (IOException e) {
            log.warn("[Scanner] 디렉토리 스캔 실패: {}", e.getMessage());
        }
        return result;
    }

    private List<Map<String, Object>> readFile(Path dir, String baseName, SyncTableDef def) throws Exception {
        Path shpPath = dir.resolve(baseName + ".shp");
        Path txtPath = dir.resolve(baseName + ".txt");
        if (Files.exists(shpPath)) return readShp(shpPath, def);
        if (Files.exists(txtPath)) return readTxt(txtPath, def);
        throw new IOException("파일 없음: " + baseName + ".shp/.txt");
    }

    private List<Map<String, Object>> readShp(Path shpPath, SyncTableDef def) throws Exception {
        ShapefileDataStore store = new ShapefileDataStore(shpPath.toUri().toURL());
        store.setCharset(Charset.forName("EUC-KR"));
        WKTWriter wktWriter = new WKTWriter();
        List<Map<String, Object>> rows = new ArrayList<>();

        // 속성 컬럼: SHP 10자 잘림 이름 → srcName 매핑 (대소문자 무시)
        Map<String, String> attrMap = new LinkedHashMap<>();
        ColumnDef geomCol = null;
        for (ColumnDef col : def.columns) {
            if (col.isGeometry) {
                geomCol = col;
            } else {
                String shpName = col.srcName.length() > 10
                        ? col.srcName.substring(0, 10).toLowerCase()
                        : col.srcName.toLowerCase();
                attrMap.put(shpName, col.srcName);
            }
        }

        try {
            SimpleFeatureSource src = store.getFeatureSource();
            SimpleFeatureCollection coll = src.getFeatures();
            try (SimpleFeatureIterator iter = coll.features()) {
                while (iter.hasNext()) {
                    SimpleFeature feature = iter.next();
                    Map<String, Object> row = new LinkedHashMap<>();

                    if (geomCol != null) {
                        Geometry geom = (Geometry) feature.getDefaultGeometry();
                        if (geom != null) {
                            try {
                                geom = coordTransformer.transform(geom, SHP_EPSG);
                            } catch (Exception e) {
                                log.warn("[Scanner] 좌표 변환 실패 (EPSG:{} → 5174): {}", SHP_EPSG, e.getMessage());
                            }
                        }
                        row.put(geomCol.srcName, geom != null ? wktWriter.write(geom) : null);
                    }

                    for (Map.Entry<String, String> e : attrMap.entrySet()) {
                        Object val = feature.getAttribute(e.getKey());
                        if (val == null) val = feature.getAttribute(e.getKey().toUpperCase());
                        row.put(e.getValue(), val != null ? val.toString() : null);
                    }

                    rows.add(row);
                }
            }
        } finally {
            store.dispose();
        }
        log.info("[Scanner] SHP 읽기: {} → {}건", shpPath.getFileName(), rows.size());
        return rows;
    }

    private List<Map<String, Object>> readTxt(Path txtPath, SyncTableDef def) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(txtPath.toFile()),
                        Charset.forName("EUC-KR")))) {
            String headerLine = br.readLine();
            if (headerLine == null) return rows;
            String[] headers = headerLine.split(",");

            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",", -1);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.length; i++) {
                    row.put(headers[i].trim(), i < parts.length ? parts[i].trim() : null);
                }
                rows.add(row);
            }
        }
        log.info("[Scanner] TXT 읽기: {} → {}건", txtPath.getFileName(), rows.size());
        return rows;
    }

    private static String toFileBaseName(String layerName) {
        String name = layerName.contains(":") ? layerName.substring(layerName.indexOf(':') + 1) : layerName;
        return name.toLowerCase();
    }
}
