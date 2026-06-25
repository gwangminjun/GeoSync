package geomex.sync.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final TableMapper tableMapper;
    private final KrasFileWriter fileWriter;
    private final RuntimeSettingsService settings;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KrasWorkspaceScanner(TableMapper tableMapper, KrasFileWriter fileWriter,
                                RuntimeSettingsService settings) {
        this.tableMapper = tableMapper;
        this.fileWriter = fileWriter;
        this.settings = settings;
    }

    public record ScanResult(int fileCount, int rowCount, List<String> messages) {}

    /**
     * 워크스페이스 디렉토리를 탐색하여 base-tables.xml 정의와 매칭되는 파일 목록을 반환한다.
     * manifest 파일 쓰기 없이 현재 상태를 조회만 한다.
     * 결과 형식: srcTableName → List&lt;fileBaseName&gt;
     */
    /** 스캔 기본 경로 (orgCode 포함 전체 경로) */
    public String defaultDirAbsolutePath() {
        return resolveDir(null).toAbsolutePath().toString();
    }

    public String orgCode() {
        return settings.orgCode();
    }

    /**
     * customDir이 지정된 경우 해당 경로를 그대로 사용 (orgCode 미추가).
     * 지정하지 않은 경우 kras.work-dir + orgCode를 기본 경로로 사용.
     */
    private Path resolveDir(String customDir) {
        if (customDir != null && !customDir.isBlank()) {
            return Path.of(customDir.trim());
        }
        return Path.of(settings.krasWorkDir(), settings.orgCode());
    }

    public Map<String, List<String>> discoverWorkspaceFiles() {
        Path dir = resolveDir(null);
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

    /**
     * base-tables.xml의 비-USEZONE 테이블 def에 대해 SHP 파일을 직접 읽어 반환한다.
     * JSON 캐시를 거치지 않고 바로 DB 적재에 사용.
     */
    public List<Map<String, Object>> loadTable(SyncTableDef def) {
        Path dir = resolveDir(null);
        Set<String> claimed = new LinkedHashSet<>();
        String baseName = findFile(dir, def, claimed);
        if (baseName == null) {
            log.warn("[Scanner] {} 파일 없음 (dir={})", def.srcTableName, dir.toAbsolutePath());
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = readFile(dir, baseName, def);
            log.info("[Scanner] {} 직접 읽기: {}건", def.srcTableName, rows.size());
            return rows;
        } catch (Exception e) {
            log.error("[Scanner] {} 읽기 실패: {}", baseName, e.getMessage(), e);
            return List.of();
        }
    }

    public ScanResult buildManifest() {
        return buildManifest(null);
    }

    public ScanResult buildManifest(String customDir) {
        Path dir = resolveDir(customDir);
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
                        injectUsezoneCode(rows, baseName, def);
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
                    String tgtBase = tgtBaseName(def.tgtTableName);
                    messages.add("✓ " + tgtBase + " " + written.size() + "개 파일 (" + uzRows + "건)");
                } else {
                    messages.add("파일 없음: " + tgtBaseName(def.tgtTableName) + " (lsmd_cont_*.shp)");
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
        store.setCharset(Charset.forName("MS949"));
        WKTWriter wktWriter = new WKTWriter();
        List<Map<String, Object>> rows = new ArrayList<>();

        // 속성 컬럼: SHP 10자 잘림 이름(소문자) → srcName 매핑
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

            // SHP 스키마 실제 컬럼명(소문자) → 인덱스 매핑 (DBF는 대문자 저장이 일반적)
            Map<String, Integer> schemaIndex = new java.util.HashMap<>();
            var schema = src.getSchema();
            for (int i = 0; i < schema.getAttributeCount(); i++) {
                schemaIndex.put(schema.getDescriptor(i).getLocalName().toLowerCase(), i);
            }
            if (log.isDebugEnabled()) {
                log.debug("[Scanner] SHP 스키마 컬럼: {}", schemaIndex.keySet());
            }

            SimpleFeatureCollection coll = src.getFeatures();
            try (SimpleFeatureIterator iter = coll.features()) {
                while (iter.hasNext()) {
                    SimpleFeature feature = iter.next();
                    Map<String, Object> row = new LinkedHashMap<>();

                    if (geomCol != null) {
                        Geometry geom = (Geometry) feature.getDefaultGeometry();
                        row.put(geomCol.srcName, geom != null ? wktWriter.write(geom) : null);
                    }

                    for (Map.Entry<String, String> e : attrMap.entrySet()) {
                        Integer idx = schemaIndex.get(e.getKey());
                        Object val = idx != null ? feature.getAttribute(idx) : null;
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
                        Charset.forName("MS949")))) {
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

    // SHP 파일명(예: "lsmd_cont_ub201") → ulyr 코드(예: "UB201") 추출 후 row에 주입
    private void injectUsezoneCode(List<Map<String, Object>> rows, String baseName, SyncTableDef def) {
        boolean hasUlyr = def.columns.stream().anyMatch(c -> "ulyr".equals(c.srcName));
        if (!hasUlyr || rows.isEmpty()) return;
        int idx = baseName.lastIndexOf('_');
        String code = idx >= 0 ? baseName.substring(idx + 1).toUpperCase() : baseName.toUpperCase();
        for (Map<String, Object> row : rows) {
            row.put("ulyr", code);
        }
    }

    private static String toFileBaseName(String layerName) {
        String name = layerName.contains(":") ? layerName.substring(layerName.indexOf(':') + 1) : layerName;
        return name.toLowerCase();
    }

    private static String tgtBaseName(String tgtTableName) {
        int dot = tgtTableName.lastIndexOf('.');
        return dot >= 0 ? tgtTableName.substring(dot + 1) : tgtTableName;
    }
}
