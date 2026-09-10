package geomex.sync.database;

import geomex.sync.settings.DatabaseSettings;
import geomex.sync.settings.RuntimeSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseConnectionServiceTest {

    @Test
    void retainsRetiredPoolUntilTheLastSessionIsClosed() throws Exception {
        Path config = config("jdbc:postgresql://db-a:5432/a", "user-a");
        RuntimeSettingsService settings = settings(config);
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        DatabaseConnectionService service = new DatabaseConnectionService(
                settings, new JdbcTemplate(new SingleConnectionDataSource()),
                requested -> {
                    opened.incrementAndGet();
                    return new DatabaseConnectionService.ManagedPool(
                            new JdbcTemplate(new SingleConnectionDataSource()), closed::incrementAndGet);
                });

        DatabaseSession oldSession = service.acquire();
        assertThat(oldSession.url()).isEqualTo("jdbc:postgresql://db-a:5432/a");

        Files.writeString(config, """
                spring:
                  datasource:
                    url: "jdbc:postgresql://db-b:5432/b"
                    username: "user-b"
                    password: "pw-b"
                """, StandardCharsets.UTF_8);
        settings.reload();
        service.refresh();

        assertThat(opened).hasValue(2);
        assertThat(closed).hasValue(0);
        assertThat(oldSession.url()).isEqualTo("jdbc:postgresql://db-a:5432/a");

        oldSession.close();
        assertThat(closed).hasValue(1);
    }

    @Test
    void keepsTheCurrentPoolWhenOpeningTheReplacementFails() throws Exception {
        Path config = config("jdbc:postgresql://db-a:5432/a", "user-a");
        RuntimeSettingsService settings = settings(config);
        AtomicInteger attempts = new AtomicInteger();
        DatabaseConnectionService service = new DatabaseConnectionService(
                settings, new JdbcTemplate(new SingleConnectionDataSource()),
                requested -> {
                    attempts.incrementAndGet();
                    if (requested.url().contains("db-b")) throw new IllegalStateException("unreachable");
                    return new DatabaseConnectionService.ManagedPool(
                            new JdbcTemplate(new SingleConnectionDataSource()), () -> { });
                });

        DatabaseSession before = service.acquire();
        Files.writeString(config, """
                spring:
                  datasource:
                    url: "jdbc:postgresql://db-b:5432/b"
                    username: "user-b"
                """, StandardCharsets.UTF_8);
        settings.reload();
        service.refresh();
        DatabaseSession after = service.acquire();

        assertThat(attempts).hasValue(3);
        assertThat(after.fingerprint()).isEqualTo(before.fingerprint());
        before.close();
        after.close();
    }

    private static Path config(String url, String username) throws Exception {
        Path path = Files.createTempFile("geomex-db", ".yml");
        Files.writeString(path, """
                spring:
                  datasource:
                    url: "%s"
                    username: "%s"
                    password: "pw-a"
                """.formatted(url, username), StandardCharsets.UTF_8);
        return path;
    }

    private static RuntimeSettingsService settings(Path config) {
        return new RuntimeSettingsService(new MockEnvironment(), config.toString(),
                "jdbc:postgresql://startup/db", "startup-user", "startup-password");
    }
}
