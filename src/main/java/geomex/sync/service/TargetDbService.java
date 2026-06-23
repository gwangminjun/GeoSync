package geomex.sync.service;

import geomex.sync.config.TargetDb;
import geomex.sync.config.TargetDbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class TargetDbService {

    private static final Logger log = LoggerFactory.getLogger(TargetDbService.class);

    private final TargetDbProperties props;
    private final JdbcTemplate primaryJdbc;

    @Value("${spring.datasource.url:}")
    private String primaryUrl;

    @Value("${spring.config.location:conf/application.yml}")
    private String configLocation;

    public TargetDbService(TargetDbProperties props, JdbcTemplate primaryJdbc) {
        this.props = props;
        this.primaryJdbc = primaryJdbc;
    }

    /**
     * 활성화된 대상 DB의 JdbcTemplate 목록을 반환한다.
     * targets 설정이 비어있으면 Spring primary DataSource를 단일 대상으로 반환한다.
     */
    public List<JdbcTemplate> getActiveTemplates() {
        return getActiveTargets().stream()
                .map(ActiveTarget::jdbc)
                .toList();
    }

    public List<ActiveTarget> getActiveTargets() {
        List<TargetDb> enabled = props.getTargets().stream()
                .filter(TargetDb::isEnabled)
                .toList();

        if (enabled.isEmpty()) {
            log.debug("[TargetDbService] targets 설정 없음 — primary DataSource 사용");
            return List.of(new ActiveTarget("기본 DB", primaryUrl, primaryJdbc));
        }

        List<ActiveTarget> result = new ArrayList<>();
        for (TargetDb t : enabled) {
            try {
                DriverManagerDataSource ds = new DriverManagerDataSource();
                ds.setDriverClassName("org.postgresql.Driver");
                ds.setUrl(t.jdbcUrl());
                ds.setUsername(t.getUsername());
                ds.setPassword(t.getPassword() != null ? t.getPassword() : "");
                result.add(new ActiveTarget(t.getName(), t.jdbcUrl(), new JdbcTemplate(ds)));
                log.debug("[TargetDbService] 대상 추가: {} ({})", t.getName(), t.jdbcUrl());
            } catch (Exception e) {
                log.warn("[TargetDbService] {} 초기화 실패: {}", t.getName(), e.getMessage());
            }
        }
        return result;
    }

    public List<TargetDb> getTargets() {
        return props.getTargets();
    }

    /**
     * 설정 파일을 직접 읽어 현재 저장된 target 목록을 반환한다.
     * Spring 바인딩 캐시와 무관하게 설정 저장 즉시 반영된다.
     */
    @SuppressWarnings("unchecked")
    public List<ActiveTarget> getConfiguredTargets() {
        Path configFile = resolveConfigPath();
        if (!Files.exists(configFile)) {
            return List.of(new ActiveTarget("기본 DB", primaryUrl, primaryJdbc));
        }
        try {
            String content = Files.readString(configFile, StandardCharsets.UTF_8);
            Object loaded = new Yaml().load(content);
            if (!(loaded instanceof Map<?, ?> root)) return fallbackTargets();

            Object t = ((Map<String, Object>) root).get("targets");
            if (!(t instanceof List<?> list) || list.isEmpty()) return fallbackTargets();

            List<ActiveTarget> result = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) continue;
                Map<String, Object> map = (Map<String, Object>) m;
                String name   = strVal(map, "name");
                String host   = strVal(map, "host");
                String dbname = strVal(map, "dbname");
                String user   = strVal(map, "username");
                String pass   = strVal(map, "password");
                Object portObj = map.get("port");
                if (host.isEmpty()) continue;
                int port = portObj != null ? Integer.parseInt(String.valueOf(portObj).trim()) : 5432;
                String url = "jdbc:postgresql://" + host + ":" + port + "/" + dbname;
                try {
                    DriverManagerDataSource ds = new DriverManagerDataSource();
                    ds.setDriverClassName("org.postgresql.Driver");
                    ds.setUrl(url);
                    ds.setUsername(user);
                    ds.setPassword(pass);
                    result.add(new ActiveTarget(name.isEmpty() ? url : name, url, new JdbcTemplate(ds)));
                } catch (Exception e) {
                    log.warn("[TargetDbService] {} 초기화 실패: {}", name, e.getMessage());
                }
            }
            return result.isEmpty() ? fallbackTargets() : result;
        } catch (Exception e) {
            log.warn("[TargetDbService] 설정 파일 targets 읽기 실패: {}", e.getMessage());
            return fallbackTargets();
        }
    }

    private List<ActiveTarget> fallbackTargets() {
        return List.of(new ActiveTarget("기본 DB", primaryUrl, primaryJdbc));
    }

    private Path resolveConfigPath() {
        String loc = configLocation;
        if (loc.startsWith("file:")) loc = loc.substring(5);
        return Path.of(loc).normalize();
    }

    private static String strVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? String.valueOf(v).trim() : "";
    }

    public record ActiveTarget(String name, String url, JdbcTemplate jdbc) {
        public String label() {
            String displayName = name == null || name.isBlank() ? "이름 없음" : name;
            return displayName + " (" + sanitizeUrl(url) + ")";
        }

        private static String sanitizeUrl(String url) {
            if (url == null || url.isBlank()) return "URL 없음";
            return url.replaceFirst("^jdbc:postgresql://", "");
        }
    }
}
