package geomex.sync.service;

import geomex.sync.config.TargetDb;
import geomex.sync.config.TargetDbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TargetDbService {

    private static final Logger log = LoggerFactory.getLogger(TargetDbService.class);

    private final TargetDbProperties props;
    private final JdbcTemplate primaryJdbc;

    @Value("${spring.datasource.url:}")
    private String primaryUrl;

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
