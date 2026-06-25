package geomex.sync.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * mt_usezone_cd.sql 로딩 → use_zone_zone_cd → use_zone_zone_cd_nm 코드 맵.
 * theme_code(ucode) 기준 theme_name(uname) 조회에 사용.
 */
@Service
public class UsezoneCodeService {

    private static final Logger log = LoggerFactory.getLogger(UsezoneCodeService.class);

    // VALUES('code', ...(7개)..., 'name', ...)
    private static final Pattern INSERT_PATTERN = Pattern.compile(
            "VALUES\\s*\\('([^']+)'(?:\\s*,\\s*'[^']*'){7}\\s*,\\s*'([^']*)'");

    private final Map<String, String> codeToName;

    public UsezoneCodeService(
            @Value("${usezone.code-sql:conf/sql/mt_usezone_cd.sql}") String sqlPath) {
        this.codeToName = loadCodes(Path.of(sqlPath));
        log.info("[UsezoneCode] {} 코드 로드 (path={})", codeToName.size(), sqlPath);
    }

    /** use_zone_zone_cd → use_zone_zone_cd_nm 반환. 없으면 null. */
    public String getName(String code) {
        if (code == null) return null;
        return codeToName.get(code);
    }

    private static Map<String, String> loadCodes(Path sqlFile) {
        Map<String, String> map = new LinkedHashMap<>();
        if (!Files.exists(sqlFile)) {
            log.warn("[UsezoneCode] SQL 파일 없음: {}", sqlFile.toAbsolutePath());
            return map;
        }
        try {
            for (String line : Files.readAllLines(sqlFile, StandardCharsets.UTF_8)) {
                Matcher m = INSERT_PATTERN.matcher(line);
                if (m.find()) {
                    map.put(m.group(1), m.group(2));
                }
            }
        } catch (IOException e) {
            log.warn("[UsezoneCode] SQL 파일 읽기 실패: {}", e.getMessage());
        }
        return map;
    }
}
