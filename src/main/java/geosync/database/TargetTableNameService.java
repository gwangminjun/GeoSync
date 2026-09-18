package geosync.database;

import geosync.settings.RuntimeSettingsService;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TargetTableNameService {
    private final RuntimeSettingsService settings;

    public TargetTableNameService(RuntimeSettingsService settings) {
        this.settings = settings;
    }

    public String resolve(String configuredTableName) {
        return resolve(configuredTableName, null);
    }

    public String resolve(String configuredTableName, String schemaOverride) {
        if (!StringUtils.hasText(configuredTableName)) return configuredTableName;
        String schema = StringUtils.hasText(schemaOverride) ? schemaOverride.trim()
                      : (StringUtils.hasText(settings.odsSchema()) ? settings.odsSchema().trim() : "ods");
        String tableName = configuredTableName.trim();
        int dot = tableName.lastIndexOf('.');
        String baseName = dot >= 0 ? tableName.substring(dot + 1) : tableName;
        return schema + "." + baseName;
    }

    public String getOdsSchema() {
        return settings.odsSchema();
    }
}
