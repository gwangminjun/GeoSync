package geomex.sync.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TargetTableNameService {
    @Value("${ods.schema:ods}")
    private String odsSchema;

    public String resolve(String configuredTableName) {
        return resolve(configuredTableName, null);
    }

    public String resolve(String configuredTableName, String schemaOverride) {
        if (!StringUtils.hasText(configuredTableName)) return configuredTableName;
        String schema = StringUtils.hasText(schemaOverride) ? schemaOverride.trim()
                      : (StringUtils.hasText(odsSchema) ? odsSchema.trim() : "ods");
        String tableName = configuredTableName.trim();
        int dot = tableName.lastIndexOf('.');
        String baseName = dot >= 0 ? tableName.substring(dot + 1) : tableName;
        return schema + "." + baseName;
    }

    public String getOdsSchema() {
        return odsSchema;
    }
}
