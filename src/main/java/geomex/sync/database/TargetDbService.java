package geomex.sync.database;

import geomex.sync.configuration.TargetDb;
import geomex.sync.settings.DatabaseSettings;
import geomex.sync.settings.RuntimeSettingsService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** Compatibility facade for callers that still expect a target list. */
@Service
public class TargetDbService implements DisposableBean {
    private final DatabaseConnectionService connectionService;
    private final RuntimeSettingsService settings;
    private DatabaseSession session;
    private String sessionFingerprint;

    public TargetDbService(DatabaseConnectionService connectionService,
                           RuntimeSettingsService settings) {
        this.connectionService = connectionService;
        this.settings = settings;
    }

    public synchronized List<JdbcTemplate> getActiveTemplates() {
        return List.of(getActiveTargets().get(0).jdbc());
    }

    public synchronized List<ActiveTarget> getActiveTargets() {
        DatabaseSettings configured = settings.database();
        if (session == null || !configuredFingerprint(configured).equals(sessionFingerprint)) {
            closeSession();
            session = connectionService.acquire();
            sessionFingerprint = session.fingerprint();
        }
        return List.of(new ActiveTarget(session.displayName(), session.url(), session.jdbc()));
    }

    public List<TargetDb> getTargets() {
        DatabaseSettings db = settings.database();
        TargetDb target = new TargetDb();
        target.setName(db.displayName());
        target.setUsername(db.username());
        target.setPassword(db.password());
        target.setEnabled(true);
        String[] parts = parseUrl(db.url());
        target.setHost(parts[0]);
        target.setPort(Integer.parseInt(parts[1]));
        target.setDbname(parts[2]);
        return List.of(target);
    }

    public List<ActiveTarget> getConfiguredTargets() { return getActiveTargets(); }

    public synchronized void evictStaleTargets() {
        closeSession();
        connectionService.refresh();
    }

    @Override
    public synchronized void destroy() {
        closeSession();
        connectionService.destroy();
    }

    private void closeSession() {
        if (session != null) session.close();
        session = null;
        sessionFingerprint = null;
    }

    private String configuredFingerprint(DatabaseSettings db) {
        return db.url() + "\n" + db.username() + "\n" + db.password();
    }

    private String[] parseUrl(String url) {
        String clean = url == null ? "" : url.replaceFirst("^jdbc:postgresql://", "");
        int slash = clean.indexOf('/');
        if (slash < 0) return new String[]{clean, "5432", ""};
        String hostPort = clean.substring(0, slash);
        String database = clean.substring(slash + 1);
        int query = database.indexOf('?');
        if (query >= 0) database = database.substring(0, query);
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0 && colon < hostPort.length() - 1) {
            return new String[]{hostPort.substring(0, colon), hostPort.substring(colon + 1), database};
        }
        return new String[]{hostPort, "5432", database};
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
