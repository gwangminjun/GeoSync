package geosync.settings;

import org.yaml.snakeyaml.Yaml;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persists managed settings without discarding unrelated YAML keys. */
@Service
public class SettingsFileService {

    public void saveDatabase(Path path, DatabaseSettings settings) throws IOException {
        save(path, settings, Map.of());
    }

    public void save(Path path, DatabaseSettings settings, Map<String, Object> managedRoot) throws IOException {
        Map<String, Object> root = load(path);
        copyManagedSection(root, managedRoot, "kras");
        copyManagedSection(root, managedRoot, "ods");
        copyManagedSection(root, managedRoot, "sync");
        Map<String, Object> geosync = childMap(root, "geosync");
        Map<String, Object> database = childMap(geosync, "database");
        database.put("display-name", settings.displayName());
        geosync.put("database", database);
        root.put("geosync", geosync);

        Map<String, Object> spring = childMap(root, "spring");
        Map<String, Object> datasource = childMap(spring, "datasource");
        datasource.put("url", settings.url());
        datasource.put("username", settings.username());
        datasource.put("password", settings.password());
        spring.put("datasource", datasource);
        root.put("spring", spring);
        root.remove("targets");

        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, new Yaml().dump(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void copyManagedSection(Map<String, Object> root, Map<String, Object> managedRoot, String key) {
        Object value = managedRoot.get(key);
        if (value instanceof Map<?, ?> raw) root.put(key, stringMap(raw));
    }

    private Map<String, Object> load(Path path) throws IOException {
        if (!Files.exists(path)) return new LinkedHashMap<>();
        Object loaded = new Yaml().load(Files.readString(path, StandardCharsets.UTF_8));
        if (!(loaded instanceof Map<?, ?> raw)) return new LinkedHashMap<>();
        return stringMap(raw);
    }

    private Map<String, Object> childMap(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof Map<?, ?> raw) return stringMap(raw);
        return new LinkedHashMap<>();
    }

    private Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
