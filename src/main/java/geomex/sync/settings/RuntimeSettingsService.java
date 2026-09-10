package geomex.sync.settings;

import geomex.sync.configuration.TargetDb;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
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

    private final Environment environment;
    private final String configLocation;
    private final String startupDbUrl;
    private final String startupDbUsername;
    private final String startupDbPassword;

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

    @Value("${kras.file-download.schedule:}")
    private String defaultFileDownloadSchedule;

    @Value("${kras.file-download.output-dir:}")
    private String defaultFileDownloadOutputDir;

    @Value("${koreps.url:http://10.188.226.221:8385/conn/estateGateway}")
    private String defaultKorepsUrl;

    @Value("${koreps.conn-sys-id:VUHK-MFG4-XUME-M9AO}")
    private String defaultKorepsConnSysId;

    @Value("${ods.schema:ods}")
    private String defaultOdsSchema;

    @Value("${sync.org-code:46870}")
    private String defaultOrgCode;

    @Value("${sync.enabled:true}")
    private boolean defaultSyncEnabled;

    private volatile Snapshot snapshot;

    @Autowired
    public RuntimeSettingsService(
            Environment environment,
            @Value("${spring.config.location:conf/application.yml}") String configLocation,
            @Value("${spring.datasource.url:}") String startupDbUrl,
            @Value("${spring.datasource.username:}") String startupDbUsername,
            @Value("${spring.datasource.password:}") String startupDbPassword) {
        this.environment = environment;
        this.configLocation = configLocation;
        this.startupDbUrl = startupDbUrl;
        this.startupDbUsername = startupDbUsername;
        this.startupDbPassword = startupDbPassword;
    }

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

    private Map<String, Object> fileDownloadChild() {
        Object raw = child("kras").get("file-download");
        if (raw instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) map;
            return cast;
        }
        return Collections.emptyMap();
    }

    public String fileDownloadSchedule() {
        return str(fileDownloadChild().get("schedule"), defaultFileDownloadSchedule);
    }

    public String korepsUrl() {
        return str(child("koreps").get("url"), defaultKorepsUrl);
    }

    public String korepsConnSysId() {
        return str(child("koreps").get("conn-sys-id"), defaultKorepsConnSysId);
    }

    public String fileDownloadOutputDir() {
        String v = str(fileDownloadChild().get("output-dir"), defaultFileDownloadOutputDir);
        return v == null ? "" : v;
    }

    public boolean fileDownloadCbndShp() {
        return boolValue(fileDownloadChild().get("cbnd-shp"), true);
    }

    public boolean fileDownloadUsezoneShp() {
        return boolValue(fileDownloadChild().get("usezone-shp"), true);
    }

    public boolean fileDownloadJigaTxt() {
        return boolValue(fileDownloadChild().get("jiga-txt"), false);
    }

    public boolean fileDownloadLandTxt() {
        return boolValue(fileDownloadChild().get("land-txt"), false);
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

    public DatabaseSettings database() {
        Map<String, Object> root = snapshot().root();
        Map<String, Object> datasource = childMap(childMap(root, "spring"), "datasource");
        String canonicalUrl = resolve(datasource.get("url"));
        String canonicalUser = resolve(datasource.get("username"));
        if (!blank(canonicalUrl) && !blank(canonicalUser)) {
            String display = resolve(childMap(childMap(root, "geomex"), "database").get("display-name"));
            return new DatabaseSettings(defaultDisplay(display, canonicalUrl), canonicalUrl,
                    canonicalUser, resolve(datasource.get("password")), DatabaseSettings.Source.CANONICAL);
        }

        List<TargetDb> legacy = parseTargets(root);
        TargetDb selected = legacy.stream().filter(TargetDb::isEnabled).findFirst()
                .orElseGet(() -> legacy.stream().filter(t -> !blank(t.getHost()) && !blank(t.getUsername()))
                        .findFirst().orElse(null));
        if (selected != null && !blank(selected.getHost()) && !blank(selected.getUsername())) {
            String url = selected.jdbcUrl();
            return new DatabaseSettings(defaultDisplay(selected.getName(), url), url,
                    selected.getUsername(), selected.getPassword(), DatabaseSettings.Source.LEGACY);
        }

        String url = resolve(startupDbUrl);
        String user = resolve(startupDbUsername);
        return new DatabaseSettings(defaultDisplay("", url), url, user,
                resolve(startupDbPassword), DatabaseSettings.Source.STARTUP);
    }

    public List<TargetDb> targets() {
        return parseTargets(snapshot().root());
    }

    private List<TargetDb> parseTargets(Map<String, Object> root) {
        Object raw = root.get("targets");
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

    private Map<String, Object> childMap(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) map;
            return cast;
        }
        return Collections.emptyMap();
    }

    private String resolve(Object value) {
        if (value == null) return "";
        return environment.resolvePlaceholders(String.valueOf(value)).trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String defaultDisplay(String display, String url) {
        if (!blank(display)) return display.trim();
        if (url == null || url.isBlank()) return "default DB";
        String clean = url.substring(url.lastIndexOf('/') + 1);
        int query = clean.indexOf('?');
        return query >= 0 ? clean.substring(0, query) : clean;
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
