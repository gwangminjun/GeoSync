package geomex.sync.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class KrasFileReader {

    private static final Logger log = LoggerFactory.getLogger(KrasFileReader.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${kras.work-dir:./workspace/kras}")
    private String workDir;

    @Value("${sync.org-code:46870}")
    private String orgCode;

    public Map<String, List<String>> readManifest() throws IOException {
        Path manifestPath = Path.of(workDir, orgCode, "_manifest.json");
        if (!Files.exists(manifestPath)) {
            throw new IOException("매니페스트 파일 없음 (수집을 먼저 실행하세요): " + manifestPath);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = objectMapper.readValue(manifestPath.toFile(), Map.class);
        Object tables = raw.get("tables");
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (tables instanceof Map<?, ?> tMap) {
            tMap.forEach((k, v) -> {
                if (v instanceof List<?> list) {
                    result.put(k.toString(), list.stream().map(Object::toString).toList());
                }
            });
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> readJson(String fileBaseName) throws IOException {
        Path jsonPath = Path.of(workDir, orgCode, fileBaseName + ".json");
        if (!Files.exists(jsonPath)) {
            log.warn("[KRAS] JSON 캐시 파일 없음: {}", jsonPath);
            return List.of();
        }
        return objectMapper.readValue(jsonPath.toFile(), List.class);
    }
}
