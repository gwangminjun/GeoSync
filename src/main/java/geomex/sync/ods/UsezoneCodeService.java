package geomex.sync.ods;

import geomex.sync.database.TargetTableNameService;

import geomex.sync.database.TargetDbService;
import geomex.sync.database.DatabaseChangedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mt_usezone_cd DB 테이블 조회 → use_zone_zone_cd → use_zone_zone_cd_nm 코드 맵.
 * 동기화 실행 시 첫 조회에 로드, 1시간 TTL로 캐싱.
 */
@Service
public class UsezoneCodeService {

    private static final Logger log = LoggerFactory.getLogger(UsezoneCodeService.class);
    private static final long TTL_MS = 3_600_000L;

    private final TargetDbService targetDbService;
    private final TargetTableNameService tableNameService;

    private volatile Map<String, String> codeToName = null;
    private volatile long loadedAt = 0;

    public UsezoneCodeService(TargetDbService targetDbService, TargetTableNameService tableNameService) {
        this.targetDbService = targetDbService;
        this.tableNameService = tableNameService;
    }

    /** use_zone_zone_cd → use_zone_zone_cd_nm 반환. 없으면 null. */
    public String getName(String code) {
        if (code == null) return null;
        ensureLoaded();
        return codeToName.get(code);
    }

    /** 동기화 시작 시 캐시 강제 갱신용. */
    public void refresh() {
        loadFromDb();
    }

    @EventListener
    public void onDatabaseChanged(DatabaseChangedEvent event) {
        codeToName = null;
        loadedAt = 0;
    }

    private synchronized void ensureLoaded() {
        if (codeToName != null && System.currentTimeMillis() - loadedAt < TTL_MS) return;
        loadFromDb();
    }

    private synchronized void loadFromDb() {
        List<TargetDbService.ActiveTarget> targets;
        try {
            targets = targetDbService.getActiveTargets();
        } catch (Exception e) {
            log.warn("[UsezoneCode] DB 대상 조회 실패: {}", e.getMessage());
            if (codeToName == null) codeToName = Collections.emptyMap();
            return;
        }

        if (targets.isEmpty()) {
            log.warn("[UsezoneCode] 활성 DB 없음 — 코드 테이블 로드 불가");
            if (codeToName == null) codeToName = Collections.emptyMap();
            return;
        }

        String tableName = tableNameService.resolve("mt_usezone_cd");
        JdbcTemplate jdbc = targets.get(0).jdbc();

        try {
            Map<String, String> map = new LinkedHashMap<>();
            jdbc.query(
                "SELECT use_zone_zone_cd, use_zone_zone_cd_nm FROM " + tableName
                    + " ORDER BY use_zone_zone_cd",
                (RowCallbackHandler) rs -> map.put(rs.getString(1), rs.getString(2))
            );
            codeToName = map;
            loadedAt = System.currentTimeMillis();
            log.info("[UsezoneCode] {} 코드 로드 (테이블={})", map.size(), tableName);
        } catch (Exception e) {
            log.warn("[UsezoneCode] {} 조회 실패: {}", tableName, e.getMessage());
            if (codeToName == null) codeToName = Collections.emptyMap();
        }
    }
}
