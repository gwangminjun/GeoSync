package geomex.sync.service;

import geomex.sync.model.CatalogFileStatus;
import geomex.sync.model.SyncHistory;
import geomex.sync.service.TargetDbService.ActiveTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
public class KrasCatalogStatusService {

    private static final Set<String> SHP_SET = Set.of(".shp", ".dbf", ".shx");
    private static final long DB_CHECK_TIMEOUT_MS = 150;
    private static final long DB_CACHE_TTL_MS = 30_000;

    private final SyncStatusService statusService;
    private final TargetDbService targetDbService;
    private final TargetTableNameService tableNameService;
    private final Map<String, CachedDbStatus> dbStatusCache = new ConcurrentHashMap<>();

    @Value("${kras.work-dir:./workspace/kras}")
    private String workDir;

    @Value("${sync.org-code:46870}")
    private String orgCode;

    @Value("${kras.catalog:./46870_DATA_CATALOG.md}")
    private String catalogPath;

    public KrasCatalogStatusService(SyncStatusService statusService, TargetDbService targetDbService,
                                    TargetTableNameService tableNameService) {
        this.statusService = statusService;
        this.targetDbService = targetDbService;
        this.tableNameService = tableNameService;
    }

    public List<CatalogFileStatus> getStatuses() {
        Path dir = Path.of(workDir, orgCode).normalize();
        Map<String, FileGroup> groups = readGroups(dir);
        LocalDateTime lastSuccess = statusService.getLastSuccess("KRAS")
                .map(SyncHistory::startTime)
                .orElse(null);

        List<CatalogFileStatus> statuses = new ArrayList<>();
        statuses.add(usezoneSummaryStatus(groups, lastSuccess));
        statuses.addAll(usezoneLayerStatuses(groups, lastSuccess));
        statuses.add(exactStatus(groups, "엔진 중간 산출물", "연속지적도", "lp_pa_cbnd",
                        "ods.lp_pa_cbnd.*", Set.of(".shp", ".dbf", ".gmx"), 1, lastSuccess,
                        "카탈로그의 ods.lp_pa_cbnd 처리 대상"));
        statuses.add(exactStatus(groups, "엔진 중간 산출물", "토지기본정보", "land_frst_ledg",
                        "ods.land_frst_ledg.*", Set.of(".txt", ".gmx"), 1, lastSuccess,
                        "카탈로그의 ods.land_frst_ledg 처리 대상"));
        statuses.add(legacyStatus(groups, "엔진 중간 산출물", "용도지역지구 통합 GMX", "lt_c_uzone",
                        "ods.lt_c_uzone.gmx", Set.of(".gmx"), lastSuccess,
                        "현재 앱은 GMX를 만들지 않고 DB에 직접 적재"));
        statuses.add(legacyStatus(groups, "비활성 산출물", "건물통합", "f_fac_building",
                        "ods.f_fac_building.*", Set.of(".shp", ".dbf", ".gmx"), lastSuccess,
                        "base-tables.xml에서 비활성화된 항목"));
        return statuses;
    }

    private CatalogFileStatus usezoneSummaryStatus(Map<String, FileGroup> groups, LocalDateTime lastSuccess) {
        int complete = 0;
        int files = 0;
        LocalDateTime latest = null;

        for (Map.Entry<String, FileGroup> entry : groups.entrySet()) {
            if (!entry.getKey().startsWith("lsmd_cont_u")) continue;
            FileGroup group = entry.getValue();
            files += group.fileCount;
            if (group.extensions.containsAll(SHP_SET)) complete++;
            latest = max(latest, group.latestModified);
        }

        DbStatus db = dbStatus("lt_c_uzone");
        boolean fileLoaded = complete >= 47;
        return new CatalogFileStatus(
                "KRAS 수신 원본 요약",
                "KRAS 수신 원본",
                "lt_c_uzone",
                "lsmd_cont_u*.{shp,dbf,shx}",
                "레이어별 shp/dbf/shx",
                47,
                complete,
                files,
                latest,
                fileLoaded,
                isSynced(latest, lastSuccess),
                db.loaded,
                db.rowCount,
                db.message,
                db.targetLabels,
                "46870_DATA_CATALOG 기준 47개 용도지역지구 레이어"
        );
    }

    private List<CatalogFileStatus> usezoneLayerStatuses(Map<String, FileGroup> groups, LocalDateTime lastSuccess) {
        DbStatus db = dbStatus("lt_c_uzone");
        List<CatalogLayer> layers = readCatalogLayers();
        List<CatalogFileStatus> statuses = new ArrayList<>();
        for (CatalogLayer layer : layers) {
            FileGroup group = groups.get(layer.fileName);
            int complete = group != null && group.extensions.containsAll(SHP_SET) ? 1 : 0;
            int files = group != null ? group.fileCount : 0;
            LocalDateTime latest = group != null ? group.latestModified : null;
            boolean fileLoaded = complete == 1;
            statuses.add(new CatalogFileStatus(
                    layer.category,
                    layer.code + " " + layer.description,
                    "lt_c_uzone",
                    layer.fileName + ".{shp,dbf,shx}",
                    "shp, dbf, shx",
                    1,
                    complete,
                    files,
                    latest,
                    fileLoaded,
                    isSynced(latest, lastSuccess),
                    db.loaded,
                    db.rowCount,
                    db.message,
                    db.targetLabels,
                    layer.fileName
            ));
        }
        return statuses;
    }

    private CatalogFileStatus exactStatus(Map<String, FileGroup> groups, String category, String name, String target,
                                          String pattern, Set<String> requiredExtensions,
                                          int expectedCount, LocalDateTime lastSuccess, String note) {
        String baseName = baseNameFromPattern(pattern);
        FileGroup group = groups.get(baseName);
        int complete = group != null && group.extensions.containsAll(requiredExtensions) ? 1 : 0;
        int files = group != null ? group.fileCount : 0;
        LocalDateTime latest = group != null ? group.latestModified : null;

        DbStatus db = dbStatus(target);
        boolean fileLoaded = complete >= expectedCount;
        return new CatalogFileStatus(category, name, target, pattern,
                String.join(", ", requiredExtensions), expectedCount, complete, files,
                latest, fileLoaded, isSynced(latest, lastSuccess), db.loaded, db.rowCount, db.message,
                db.targetLabels, note);
    }

    private CatalogFileStatus legacyStatus(Map<String, FileGroup> groups, String category, String name, String target,
                                           String pattern, Set<String> requiredExtensions,
                                           LocalDateTime lastSuccess, String note) {
        String baseName = baseNameFromPattern(pattern);
        FileGroup group = groups.get(baseName);
        int complete = group != null && group.extensions.containsAll(requiredExtensions) ? 1 : 0;
        int files = group != null ? group.fileCount : 0;
        LocalDateTime latest = group != null ? group.latestModified : null;

        DbStatus db = dbStatus(target);
        boolean fileLoaded = complete > 0;
        return new CatalogFileStatus(category, name, target, pattern,
                String.join(", ", requiredExtensions), 1, complete, files,
                latest, fileLoaded, isSynced(latest, lastSuccess), db.loaded, db.rowCount, db.message,
                db.targetLabels, note);
    }

    private List<CatalogLayer> readCatalogLayers() {
        Path path = Path.of(catalogPath).normalize();
        if (!Files.isRegularFile(path)) return fallbackLayers();

        List<CatalogLayer> layers = new ArrayList<>();
        String category = "용도지역지구";
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.startsWith("#### ")) {
                    category = line.substring(5).trim();
                    continue;
                }
                if (!line.startsWith("| `lsmd_cont_")) continue;
                String[] cells = line.split("\\|");
                if (cells.length < 4) continue;
                String fileName = cleanCell(cells[1]);
                String code = cleanCell(cells[2]);
                String description = cleanCell(cells[3]);
                if (!fileName.startsWith("lsmd_cont_") || !code.matches("[A-Z]{2}\\d{3}")) {
                    continue;
                }
                layers.add(new CatalogLayer(category, fileName, code, description));
            }
        } catch (IOException e) {
            return fallbackLayers();
        }
        return layers.isEmpty() ? fallbackLayers() : layers;
    }

    private String cleanCell(String cell) {
        return cell.replace("`", "").trim();
    }

    private List<CatalogLayer> fallbackLayers() {
        return List.of(
                new CatalogLayer("용도지역지구", "lsmd_cont_ub201", "UB201", "제1종일반주거지역"),
                new CatalogLayer("용도지역지구", "lsmd_cont_ub210", "UB210", "준주거지역")
        );
    }

    private DbStatus dbStatus(String tableName) {
        String targetTableName = tableNameService.resolve(tableName);
        CachedDbStatus cached = dbStatusCache.get(targetTableName);
        if (cached != null && !cached.isExpired()) {
            return cached.status;
        }

        CompletableFuture<DbStatus> future = CompletableFuture
                .supplyAsync(() -> dbStatusBlocking(targetTableName));
        future.thenAccept(status -> dbStatusCache.put(targetTableName, new CachedDbStatus(status)));

        try {
            DbStatus status = future.get(DB_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            dbStatusCache.put(targetTableName, new CachedDbStatus(status));
            return status;
        } catch (Exception e) {
            DbStatus fallback = cached != null
                    ? cached.status
                    : new DbStatus(false, null, "DB 확인 지연", "확인 중");
            dbStatusCache.put(targetTableName, new CachedDbStatus(fallback));
            return fallback;
        }
    }

    private DbStatus dbStatusBlocking(String tableName) {
        List<ActiveTarget> targets;
        try {
            targets = targetDbService.getActiveTargets();
        } catch (Exception e) {
            return new DbStatus(false, null, "DB 대상 초기화 실패", "-");
        }
        if (targets.isEmpty()) {
            return new DbStatus(false, null, "DB 대상 없음", "-");
        }

        int loadedTargets = 0;
        int totalRows = 0;
        int checkedTargets = 0;
        List<String> labels = new ArrayList<>();
        for (ActiveTarget target : targets) {
            checkedTargets++;
            labels.add(target.label());
            try {
                Integer count = target.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM " + tableName + " WHERE org_cd = ?",
                        Integer.class,
                        orgCode);
                int rows = count != null ? count : 0;
                totalRows += rows;
                if (rows > 0) loadedTargets++;
            } catch (Exception e) {
                return new DbStatus(false, totalRows,
                        target.name() + " 조회 실패: " + shortMessage(e),
                        String.join(", ", labels));
            }
        }

        boolean loaded = checkedTargets > 0 && loadedTargets == checkedTargets;
        String message = loaded
                ? "적재 완료"
                : loadedTargets + "/" + checkedTargets + "개 대상 적재";
        return new DbStatus(loaded, totalRows, message, String.join(", ", labels));
    }

    private String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        int newline = message.indexOf('\n');
        return newline >= 0 ? message.substring(0, newline) : message;
    }

    private Map<String, FileGroup> readGroups(Path dir) {
        Map<String, FileGroup> groups = new HashMap<>();
        if (!Files.isDirectory(dir)) return groups;

        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(Files::isRegularFile).forEach(path -> addFile(groups, path));
        } catch (IOException ignored) {
            return Map.of();
        }
        return groups;
    }

    private String baseNameFromPattern(String pattern) {
        String lower = pattern.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".*")) {
            return lower.substring(0, lower.length() - 2);
        }
        int bracePattern = lower.indexOf(".{");
        if (bracePattern > 0) {
            return lower.substring(0, bracePattern);
        }
        int dot = lower.lastIndexOf('.');
        return dot > 0 ? lower.substring(0, dot) : lower;
    }

    private void addFile(Map<String, FileGroup> groups, Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = fileName.lastIndexOf('.');
        if (dot < 1) return;

        String baseName = fileName.substring(0, dot);
        String extension = fileName.substring(dot);
        FileGroup group = groups.computeIfAbsent(baseName, ignored -> new FileGroup());
        group.extensions.add(extension);
        group.fileCount++;
        group.latestModified = max(group.latestModified, modifiedTime(path).orElse(null));
    }

    private Optional<LocalDateTime> modifiedTime(Path path) {
        try {
            return Optional.of(LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(path).toInstant(),
                    ZoneId.systemDefault()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private boolean isSynced(LocalDateTime latestModified, LocalDateTime lastSuccess) {
        return latestModified != null && lastSuccess != null && !latestModified.isBefore(lastSuccess);
    }

    private LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private static class FileGroup {
        private final Set<String> extensions = new java.util.HashSet<>();
        private int fileCount;
        private LocalDateTime latestModified;
    }

    private record DbStatus(boolean loaded, Integer rowCount, String message, String targetLabels) {
    }

    private record CatalogLayer(String category, String fileName, String code, String description) {
    }

    private static class CachedDbStatus {
        private final DbStatus status;
        private final long checkedAt;

        private CachedDbStatus(DbStatus status) {
            this.status = status;
            this.checkedAt = System.currentTimeMillis();
        }

        private boolean isExpired() {
            return System.currentTimeMillis() - checkedAt > DB_CACHE_TTL_MS;
        }
    }
}
