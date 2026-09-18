package geosync.monitoring;

import java.time.LocalDateTime;

public record CatalogFileStatus(
        String category,
        String name,
        String target,
        String filePattern,
        String requiredFiles,
        int expectedCount,
        int completeCount,
        int fileCount,
        LocalDateTime latestModified,
        boolean fileLoaded,
        boolean fileSynced,
        boolean dbLoaded,
        Integer dbRowCount,
        String dbMessage,
        String dbTargets,
        String note
) {
    public boolean exists() {
        return fileCount > 0;
    }
}
