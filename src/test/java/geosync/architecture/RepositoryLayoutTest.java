package geosync.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryLayoutTest {

    private final Path root = Path.of("").toAbsolutePath();

    @Test
    void preservesOperationalPaths() {
        assertThat(root.resolve("conf/kras/base-tables.xml")).exists();
        assertThat(root.resolve("conf/sql/sync_public_tables.sql")).exists();
        assertThat(root.resolve("conf/sql/mt_usezone_cd.sql")).exists();
        assertThat(root.resolve("scripts/run.bat")).exists();
        assertThat(root.resolve("scripts/install-service.bat")).exists();
        assertThat(root.resolve("scripts/uninstall-service.bat")).exists();
        assertThat(root.resolve("mock")).isDirectory();
        assertThat(root.resolve("api-tests")).isDirectory();
    }

    @Test
    void referenceDocumentsHaveApprovedLocations() {
        assertThat(root.resolve("docs/reference/2026-09-18/46870-data-catalog.md")).exists();
        assertThat(root.resolve("docs/reference/2026-09-10/lt-c-uzone-plan.md")).exists();
        assertThat(root.resolve("docs/reference/2026-09-18/kras-geosync-structure.md")).exists();
        assertThat(root.resolve("docs/reviews/2026-09-18/improvements.md")).exists();
    }
}
