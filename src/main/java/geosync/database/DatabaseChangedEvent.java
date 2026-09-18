package geosync.database;

import org.springframework.context.ApplicationEvent;

public final class DatabaseChangedEvent extends ApplicationEvent {
    private final String fingerprint;

    public DatabaseChangedEvent(Object source, String fingerprint) {
        super(source);
        this.fingerprint = fingerprint;
    }

    public String fingerprint() { return fingerprint; }
}
