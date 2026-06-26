package geomex.sync.service;

import geomex.sync.config.TargetDb;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class RuntimeSettingsService {

    @Value("${spring.config.location:conf/application.yml}")
    private String configLocation;

    @Value("${kras.url:http://110.20.1.12:8385/conn/estateGateway}")
    private String defaultKrasUrl;

    @Value("${kras.conn-svc-id:KRAS000037}")
    private String defaultKrasConnSvcId;

    @Value("${kras.conn-sys-id:}")
    private String defaultKrasConnSysId;

    @Value("${kras.chk-pnu:4687025625111190010}")
    private String defaultKrasChkPnu;

    @Value("${kras.config:conf/kras/base-tables.xml}")
    private String defaultKrasConfig;

    @Value("${kras.work-dir:./workspace/kras}")
    private String defaultKrasWorkDir;

    @Value("${kras.shp-charset:MS949}")
    private String defaultKrasShpCharset;

    @Value("${kras.schedule:0 30 4 * * *}")
    private String defaultKrasSchedule;

    @Value("${ods.schema:ods}")
    private String defaultOdsSchema;

    @Value("${sync.org-code:46870}")
    private String defaultOrgCode;

    @Value("${sync.enabled:true}")
    private boolean defaultSyncEnabled;

    private volatile Snapshot snapshot;

    public void reload() {
        snapshot = null;
    }

    public String krasUrl() {
        return str(child("kras").get("url"), defaultKrasUrl);
    }

    public String krasConnSvcId() {
        return str(child("kras").get("conn-svc-id"), defaultKrasConnSvcId);
    }

    public String krasConnSysId() {
        return str(child("kras").get("conn-sys-id"), defaultKrasConnSysId);
    }

    public String krasChkPnu() {
        return str(child("kras").get("chk-pnu"), defaultKrasChkPnu);
    }

    public String krasConfig() {
        return str(child("kras").get("config"), defaultKrasConfig);
    }

    public String krasWorkDir() {
        String dir = str(child("kras").get("work-dir"), defaultKrasWorkDir);
        // kras.work-dir에 orgCode가 이미 포함된 경우 제거 (호출부에서 항상 orgCode를 붙이므로 중복 방지)
        String org = orgCode();
        if (org != null && !org.isBlank()) {
            if (dir.endsWith("/" + org))  return dir.substring(0, dir.length() - org.length() - 1);
            if (dir.endsWith("\\" + org)) return dir.substring(0, dir.length() - org.length() - 1);
        }
        return dir;
    }

    public String krasShpCharset() {
        return str(child("kras").get("shp-charset"), defaultKrasShpCharset);
    }

    public String krasSchedule() {
        // @Value 기본값은 YAML에 schedule: "" 저장 시 Spring이 빈 문자열로 주입해 무효화됨
        // → 빈 경우 항상 하드코딩 fallback 사용
        String v = str(child("kras").get("schedule"), defaultKrasSchedule);
        return v.isBlank() ? "0 30 4 * * *" : v;
    }

    public String odsSchema() {
        return str(child("ods").get("schema"), defaultOdsSchema);
    }

    public String orgCode() {
        return str(child("sync").get("org-code"), defaultOrgCode);
    }

    public boolean syncEnabled() {
        Object value = child("sync").get("enabled");
        return value != null ? Boolean.parseBoolean(String.valueOf(value)) : defaultSyncEnabled;
    }

    public List<TargetDb> targets() {
        Object raw = snapshot().root().get("targets");
        if (!(raw instanceof List<?> list)) return Collections.emptyList();

        List<TargetDb> targets = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            TargetDb target = new TargetDb();
            target.setName(str(map.get("name"), ""));
            target.setHost(str(map.get("host"), ""));
            target.setPort(intValue(map.get("port"), 5432));
            target.setDbname(str(map.get("dbname"), ""));
            target.setUsername(str(map.get("username"), ""));
            target.setPassword(str(map.get("password"), ""));
            target.setEnabled(boolValue(map.get("enabled"), true));
            targets.add(target);
        }
        return targets;
    }

    private Map<String, Object> child(String key) {
        Object value = snapshot().root().get(key);
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) map;
            return cast;
        }
        return Collections.emptyMap();
    }

    private synchronized Snapshot snapshot() {
        Path path = resolveConfigPath();
        long modified = modifiedTime(path);
        Snapshot current = snapshot;
        if (current != null && current.modifiedTime() == modified) {
            return current;
        }

        Map<String, Object> root = Collections.emptyMap();
        if (Files.exists(path)) {
            try {
                Object loaded = new Yaml().load(Files.readString(path, StandardCharsets.UTF_8));
                if (loaded instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) map;
                    root = cast;
                }
            } catch (Exception ignored) {
                root = Collections.emptyMap();
            }
        }

        Snapshot next = new Snapshot(modified, root);
        snapshot = next;
        return next;
    }

    private Path resolveConfigPath() {
        String loc = configLocation;
        if (loc.startsWith("file:")) loc = loc.substring(5);
        return Path.of(loc).normalize();
    }

    private static long modifiedTime(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    private static String str(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static int intValue(Object value, int fallback) {
        try {
            return value != null ? Integer.parseInt(String.valueOf(value).trim()) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean boolValue(Object value, boolean fallback) {
        return value != null ? Boolean.parseBoolean(String.valueOf(value)) : fallback;
    }

    private record Snapshot(long modifiedTime, Map<String, Object> root) {
    }
}
