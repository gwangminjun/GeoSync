package geomex.sync.service;

import com.zaxxer.hikari.HikariDataSource;
import geomex.sync.config.TargetDb;
import geomex.sync.config.TargetDbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class TargetDbService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TargetDbService.class);

    private final TargetDbProperties props;
    private final JdbcTemplate primaryJdbc;
    private final RuntimeSettingsService settings;
    private final ConcurrentMap<String, CachedTarget> targetCache = new ConcurrentHashMap<>();

    @Value("${spring.datasource.url:}")
    private String primaryUrl;

    public TargetDbService(TargetDbProperties props, JdbcTemplate primaryJdbc,
                           RuntimeSettingsService settings) {
        this.props = props;
        this.primaryJdbc = primaryJdbc;
        this.settings = settings;
    }

    public List<JdbcTemplate> getActiveTemplates() {
        return getActiveTargets().stream()
                .map(ActiveTarget::jdbc)
                .toList();
    }

    public List<ActiveTarget> getActiveTargets() {
        List<TargetDb> configured = settings.targets();
        if (configured.isEmpty()) configured = props.getTargets();
        List<TargetDb> enabled = configured.stream()
                .filter(TargetDb::isEnabled)
                .toList();

        if (enabled.isEmpty()) {
            log.debug("[TargetDbService] no targets configured; using primary DataSource");
            return fallbackTargets();
        }

        List<ActiveTarget> result = new ArrayList<>();
        for (TargetDb t : enabled) {
            try {
                if (t.getHost() == null || t.getHost().isBlank()) continue;
                result.add(cachedTarget(t.getName(), t.jdbcUrl(), t.getUsername(),
                        t.getPassword() != null ? t.getPassword() : ""));
                log.debug("[TargetDbService] active target: {} ({})", t.getName(), t.jdbcUrl());
            } catch (Exception e) {
                log.warn("[TargetDbService] {} init failed: {}", t.getName(), e.getMessage());
            }
        }
        return result.isEmpty() ? fallbackTargets() : result;
    }

    public List<TargetDb> getTargets() {
        List<TargetDb> configured = settings.targets();
        return configured.isEmpty() ? props.getTargets() : configured;
    }

    public List<ActiveTarget> getConfiguredTargets() {
        return getActiveTargets();
    }

    private HikariDataSource buildDataSource(String url, String username, String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setMaximumPoolSize(3);
        ds.setMinimumIdle(0);
        ds.setConnectionTimeout(30000);
        return ds;
    }

    private ActiveTarget cachedTarget(String name, String url, String username, String password) {
        String key = url + "\n" + username + "\n" + password;
        CachedTarget cached = targetCache.computeIfAbsent(key, ignored -> {
            log.info("[TargetDbService] target DataSource created: {} ({})", name, url);
            HikariDataSource dataSource = buildDataSource(url, username, password);
            return new CachedTarget(dataSource, new JdbcTemplate(dataSource));
        });
        return new ActiveTarget(name, url, cached.jdbc());
    }

    private List<ActiveTarget> fallbackTargets() {
        return List.of(new ActiveTarget("default DB", primaryUrl, primaryJdbc));
    }

    @Override
    public void destroy() {
        targetCache.values().forEach(cached -> cached.dataSource().close());
        targetCache.clear();
    }

    private record CachedTarget(HikariDataSource dataSource, JdbcTemplate jdbc) {
    }

    public record ActiveTarget(String name, String url, JdbcTemplate jdbc) {
        public String label() {
            String displayName = name == null || name.isBlank() ? "unnamed" : name;
            return displayName + " (" + sanitizeUrl(url) + ")";
        }

        private static String sanitizeUrl(String url) {
            if (url == null || url.isBlank()) return "no URL";
            return url.replaceFirst("^jdbc:postgresql://", "");
        }
    }
}
