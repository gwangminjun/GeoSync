package geomex.sync.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TargetTableNameService {
    @Value("${ods.schema:ods}")
    private String odsSchema;

    public String resolve(String configuredTableName) {
        if (!StringUtils.hasText(configuredTableName) || !StringUtils.hasText(odsSchema)) {
            return configuredTableName;
        }
        String tableName = configuredTableName.trim();
        int dot = tableName.lastIndexOf('.');
        String baseName = dot >= 0 ? tableName.substring(dot + 1) : tableName;
        return odsSchema.trim() + "." + baseName;
    }
}
