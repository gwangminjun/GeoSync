package geomex.sync.database;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

/** Immutable database handle held for one operation. */
public final class DatabaseSession implements AutoCloseable {
    private final String displayName;
    private final String url;
    private final String fingerprint;
    private final JdbcTemplate jdbc;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    DatabaseSession(String displayName, String url, String fingerprint,
                    JdbcTemplate jdbc, Runnable release) {
        this.displayName = displayName;
        this.url = url;
        this.fingerprint = fingerprint;
        this.jdbc = jdbc;
        this.release = release;
    }

    public String displayName() { return displayName; }
    public String url() { return url; }
    public String fingerprint() { return fingerprint; }
    public JdbcTemplate jdbc() { return jdbc; }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) release.run();
    }
}
