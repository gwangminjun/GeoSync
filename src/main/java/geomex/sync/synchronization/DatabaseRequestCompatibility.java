package geomex.sync.synchronization;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compatibility rules for legacy multi-target request parameters. */
public final class DatabaseRequestCompatibility {
    private static final Pattern SCHEMA = Pattern.compile("schema_(\\d+)");

    private DatabaseRequestCompatibility() { }

    public static String schema(Map<String, String> params, String fallback) {
        String zero = params.get("schema_0");
        if (zero != null && !zero.isBlank()) return zero.trim();
        int selected = Integer.MAX_VALUE;
        String value = null;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            Matcher matcher = SCHEMA.matcher(entry.getKey());
            if (!matcher.matches() || entry.getValue() == null || entry.getValue().isBlank()) continue;
            int index = Integer.parseInt(matcher.group(1));
            if (index < selected) {
                selected = index;
                value = entry.getValue().trim();
            }
        }
        return value == null ? fallback : value;
    }
}
