package geosync.settings;

/** Resolved single-database connection settings. */
public record DatabaseSettings(
        String displayName,
        String url,
        String username,
        String password,
        Source source) {

    public enum Source { CANONICAL, LEGACY, STARTUP }
}
