package geomex.sync.kras;

import geomex.sync.synchronization.SyncStatusService;
import geomex.sync.database.TargetTableNameService;
import geomex.sync.database.TargetDbService;

import geomex.sync.settings.RuntimeSettingsService;

import geomex.sync.monitoring.CatalogFileStatus;
import geomex.sync.synchronization.model.SyncHistory;
import geomex.sync.database.TargetDbService.ActiveTarget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final RuntimeSettingsService settings;
    private final Map<String, CachedDbStatus> dbStatusCache = new ConcurrentHashMap<>();

    @Value("${kras.catalog:}")
    private String catalogPath;

    public KrasCatalogStatusService(SyncStatusService statusService, TargetDbService targetDbService,
                                    TargetTableNameService tableNameService, RuntimeSettingsService settings) {
        this.statusService = statusService;
        this.targetDbService = targetDbService;
        this.tableNameService = tableNameService;
        this.settings = settings;
    }

    public List<CatalogFileStatus> getStatuses() {
        Path dir = Path.of(settings.krasWorkDir(), settings.orgCode()).normalize();
        Map<String, FileGroup> groups = readGroups(dir);
        LocalDateTime lastSuccess = statusService.getLastSuccess("KRAS")
                .map(SyncHistory::startTime)
                .orElse(null);

        List<CatalogFileStatus> statuses = new ArrayList<>();
        statuses.add(usezoneSummaryStatus(groups, lastSuccess));
        statuses.add(exactStatus(groups, "KRAS 수신 원본", "연속지적도", "lp_pa_cbnd",
                        "lsmd_cont_ldreg.*", SHP_SET, 1, lastSuccess,
                        "lp_pa_cbnd", false));
        return statuses;
    }

    private CatalogFileStatus usezoneSummaryStatus(Map<String, FileGroup> groups, LocalDateTime lastSuccess) {
        List<CatalogLayer> layers = readCatalogLayers();
        int expectedCount = layers.size();
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

        DbStatus db = dbStatus("lt_c_uzone", true);
        boolean fileLoaded = expectedCount > 0 && complete >= expectedCount;
        return new CatalogFileStatus(
                "KRAS 수신 원본 요약",
                "KRAS 수신 원본",
                "lt_c_uzone",
                "lsmd_cont_u*.{shp,dbf,shx}",
                "레이어별 shp/dbf/shx",
                expectedCount,
                complete,
                files,
                latest,
                fileLoaded,
                isSynced(latest, lastSuccess),
                db.loaded,
                db.rowCount,
                db.message,
                db.targetLabels,
                catalogPath().getFileName() + " 기준 " + expectedCount + "개 용도지역지구 레이어"
        );
    }

    private CatalogFileStatus exactStatus(Map<String, FileGroup> groups, String category, String name, String target,
                                          String pattern, Set<String> requiredExtensions,
                                          int expectedCount, LocalDateTime lastSuccess, String note,
                                          boolean filterByOrgCode) {
        String baseName = baseNameFromPattern(pattern);
        FileGroup group = groups.get(baseName);
        int complete = group != null && group.extensions.containsAll(requiredExtensions) ? 1 : 0;
        int files = group != null ? group.fileCount : 0;
        LocalDateTime latest = group != null ? group.latestModified : null;

        DbStatus db = dbStatus(target, filterByOrgCode);
        boolean fileLoaded = complete >= expectedCount;
        return new CatalogFileStatus(category, name, target, pattern,
                String.join(", ", requiredExtensions), expectedCount, complete, files,
                latest, fileLoaded, isSynced(latest, lastSuccess), db.loaded, db.rowCount, db.message,
                db.targetLabels, note);
    }

    private List<CatalogLayer> readCatalogLayers() {
        Path path = catalogPath();
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

    private Path catalogPath() {
        if (catalogPath != null && !catalogPath.isBlank()) {
            return Path.of(catalogPath).normalize();
        }
        return Path.of("./" + settings.orgCode() + "_DATA_CATALOG.md").normalize();
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

    private DbStatus dbStatus(String tableName, boolean filterByOrgCode) {
        String cacheKey = targetTableName(tableName);
        CachedDbStatus cached = dbStatusCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.status;
        }

        CompletableFuture<DbStatus> future = CompletableFuture
                .supplyAsync(() -> dbStatusBlocking(targetTableName(tableName), filterByOrgCode));
        future.thenAccept(status -> dbStatusCache.put(cacheKey, new CachedDbStatus(status)));

        try {
            DbStatus status = future.get(DB_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            dbStatusCache.put(cacheKey, new CachedDbStatus(status));
            return status;
        } catch (Exception e) {
            DbStatus fallback = cached != null
                    ? cached.status
                    : new DbStatus(false, null, "DB 확인 지연", "확인 중");
            dbStatusCache.put(cacheKey, new CachedDbStatus(fallback));
            return fallback;
        }
    }

    private String targetTableName(String tableName) {
        return tableNameService.resolve(tableName);
    }

    private DbStatus dbStatusBlocking(String tableName, boolean filterByOrgCode) {
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
        String errorMsg = null;
        List<String> labels = new ArrayList<>();
        for (ActiveTarget target : targets) {
            checkedTargets++;
            labels.add(target.label());
            try {
                Integer count;
                if (filterByOrgCode) {
                    count = target.jdbc().queryForObject(
                            "SELECT COUNT(*) FROM " + tableName + " WHERE org_cd = ?",
                            Integer.class, settings.orgCode());
                } else {
                    count = target.jdbc().queryForObject(
                            "SELECT COUNT(*) FROM " + tableName,
                            Integer.class);
                }
                int rows = count != null ? count : 0;
                totalRows += rows;
                if (rows > 0) loadedTargets++;
            } catch (Exception e) {
                String msg = shortMessage(e);
                if (isTableMissing(msg)) {
                    // 테이블 미존재 = 아직 적재 안 됨 (정상 상태), 계속 진행
                } else {
                    errorMsg = target.name() + " 조회 실패: " + msg;
                }
            }
        }

        if (errorMsg != null && loadedTargets == 0) {
            return new DbStatus(false, totalRows, errorMsg, String.join(", ", labels));
        }
        boolean loaded = checkedTargets > 0 && loadedTargets == checkedTargets;
        String message = loaded
                ? "적재 완료"
                : (loadedTargets == 0 ? "미적재" : loadedTargets + "/" + checkedTargets + "개 대상 적재");
        return new DbStatus(loaded, totalRows, message, String.join(", ", labels));
    }

    private boolean isTableMissing(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("does not exist") || lower.contains("존재하지") || lower.contains("no such table");
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
        return latestModified != null && lastSuccess != null && !lastSuccess.isBefore(latestModified);
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
