package geomex.sync.synchronization;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseRequestCompatibilityTest {

    @Test
    void prefersSchemaZeroThenSmallestNumericLegacySchema() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("schema_12", "twelve");
        params.put("schema_2", "two");
        params.put("schema_0", "zero");

        assertThat(DatabaseRequestCompatibility.schema(params, "ods")).isEqualTo("zero");
        params.remove("schema_0");
        assertThat(DatabaseRequestCompatibility.schema(params, "ods")).isEqualTo("two");
    }

    @Test
    void usesConfiguredDefaultWhenNoLegacySchemaIsPresent() {
        assertThat(DatabaseRequestCompatibility.schema(Map.of("targetIdx", "99"), "custom_ods"))
                .isEqualTo("custom_ods");
    }
}
