package geosync.database;

import com.zaxxer.hikari.HikariDataSource;
import geosync.settings.DatabaseSettings;
import geosync.settings.RuntimeSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DatabaseConnectionService implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionService.class);

    @FunctionalInterface
    interface PoolFactory {
        ManagedPool open(DatabaseSettings settings) throws Exception;
    }

    public record ManagedPool(JdbcTemplate jdbc, AutoCloseable closeable) { }

    private final RuntimeSettingsService settings;
    private final JdbcTemplate primaryJdbc;
    private final ApplicationEventPublisher events;
    private final PoolFactory poolFactory;
    private Holder current;
    private boolean shuttingDown;

    @Autowired
    public DatabaseConnectionService(RuntimeSettingsService settings, JdbcTemplate primaryJdbc,
                                     DataSource primaryDataSource,
                                     ApplicationEventPublisher events) {
        this(settings, primaryJdbc, events, DatabaseConnectionService::openHikariPool);
    }

    DatabaseConnectionService(RuntimeSettingsService settings, JdbcTemplate primaryJdbc,
                              PoolFactory poolFactory) {
        this(settings, primaryJdbc, event -> { }, poolFactory);
    }

    private DatabaseConnectionService(RuntimeSettingsService settings, JdbcTemplate primaryJdbc,
                                      ApplicationEventPublisher events, PoolFactory poolFactory) {
        this.settings = settings;
        this.primaryJdbc = primaryJdbc;
        this.events = events;
        this.poolFactory = poolFactory;
        DatabaseSettings startup = settings.database();
        this.current = Holder.primary(startup, primaryJdbc);
    }

    public synchronized DatabaseSession acquire() {
        if (shuttingDown) throw new IllegalStateException("database service is shutting down");
        refresh();
        Holder holder = current;
        holder.retain();
        return new DatabaseSession(holder.settings.displayName(), holder.settings.url(),
                holder.fingerprint, holder.jdbc, holder::release);
    }

    public synchronized void refresh() {
        if (shuttingDown) return;
        DatabaseSettings desired = settings.database();
        if (current.matches(desired)) return;
        if (desired.source() == DatabaseSettings.Source.STARTUP) {
            Holder old = current;
            current = Holder.primary(desired, primaryJdbc);
            old.retire();
            events.publishEvent(new DatabaseChangedEvent(this, current.fingerprint));
            return;
        }
        try {
            ManagedPool managed = poolFactory.open(desired);
            Holder old = current;
            current = Holder.dynamic(desired, managed);
            old.retire();
            events.publishEvent(new DatabaseChangedEvent(this, current.fingerprint));
        } catch (Exception e) {
            log.warn("DB 연결 교체 실패 — 기존 연결 유지: {}", e.getMessage());
        }
    }

    @Override
    public synchronized void destroy() {
        shuttingDown = true;
        current.retire();
    }

    private static ManagedPool openHikariPool(DatabaseSettings settings) throws Exception {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(settings.url());
        dataSource.setUsername(settings.username());
        dataSource.setPassword(settings.password());
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setMaximumPoolSize(5);
        dataSource.setMinimumIdle(0);
        dataSource.setConnectionTimeout(30000);
        try {
            dataSource.getConnection().close();
            return new ManagedPool(new JdbcTemplate(dataSource), dataSource);
        } catch (Exception e) {
            dataSource.close();
            throw e;
        }
    }

    private static String fingerprint(DatabaseSettings settings) {
        String raw = settings.url() + "\n" + settings.username() + "\n" + settings.password();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return raw;
        }
    }

    private static final class Holder {
        private final DatabaseSettings settings;
        private final JdbcTemplate jdbc;
        private final AutoCloseable closeable;
        private final String fingerprint;
        private int leases;
        private boolean retired;
        private boolean closed;

        private Holder(DatabaseSettings settings, JdbcTemplate jdbc, AutoCloseable closeable,
                       String fingerprint) {
            this.settings = settings;
            this.jdbc = jdbc;
            this.closeable = closeable;
            this.fingerprint = fingerprint;
        }

        static Holder primary(DatabaseSettings settings, JdbcTemplate jdbc) {
            return new Holder(settings, jdbc, () -> { }, "primary");
        }

        static Holder dynamic(DatabaseSettings settings, ManagedPool managed) {
            return new Holder(settings, managed.jdbc(), managed.closeable(), fingerprint(settings));
        }

        boolean matches(DatabaseSettings requested) {
            return "primary".equals(fingerprint) && requested.source() == DatabaseSettings.Source.STARTUP
                    || fingerprint.equals(fingerprint(requested));
        }

        synchronized void retain() { leases++; }

        synchronized void release() {
            if (leases > 0) leases--;
            closeIfReady();
        }

        synchronized void retire() {
            retired = true;
            closeIfReady();
        }

        private void closeIfReady() {
            if (retired && leases == 0 && !closed) {
                closed = true;
                try { closeable.close(); }
                catch (Exception e) { log.warn("DB 풀 종료 실패: {}", e.getMessage()); }
            }
        }
    }
}
