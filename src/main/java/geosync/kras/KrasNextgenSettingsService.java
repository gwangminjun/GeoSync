package geosync.kras;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 차세대 연계 설정 — conf/application.yml의 최상위 kras-nextgen 블록만 읽고 쓴다.
 *
 * - 서버 환경변수는 쓰지 않는다(2026-10-14 결정). 이 블록 하나만 바꾸고 나머지 블록은 그대로 보존한다
 *   (기존 SettingsFileService와 같은 방식, 그 클래스는 수정하지 않음).
 * - kras 블록 밖에 두는 이유: 설정 화면 저장이 kras 블록을 화면 항목으로 통째로 교체한다.
 * - 파일 수정 시각이 바뀌면 다시 읽는다 — 저장·직접 수정 모두 재시작 없이 다음 읽기부터 반영된다.
 * - 스케줄은 묶음 2개(full-load, correction)만 둔다(2026-10-15 설계 §8). 이전 단계별 키는 읽을 때 옮기고 저장할 때 지운다.
 */
@Service
public class KrasNextgenSettingsService {

    static final String BLOCK = "kras-nextgen";
    /** 2026-10-14 커밋 fa0fcf6의 이전 이름 — 저장할 때 지운다(배포 전이라 이전 값은 옮기지 않음). */
    private static final String OLD_BLOCK = "kras-incremental";
    /** 2026-10-14 단계별 스케줄 키 → 옮겨 갈 묶음 (앞에 있는 키가 우선). */
    private static final Map<String, List<String>> OLD_SCHEDULE_KEYS = Map.of(
            "correction", List.of("land-change-daily"),
            "full-load", List.of("cadastral-full", "usezone-full", "land-basic-correction", "land-price-full"));

    /** PNU 대기열 설정 (kras-nextgen.pnu). 설계 §8. */
    public record Pnu(List<String> datasets, int parallelism, int dailyCallLimit, int callIntervalMs, String stopAt,
                      int circuitBreak, int refreshDays, int rotateLimit, String priceRefreshAfter,
                      List<String> noDataCodes) {
        public static Pnu defaults() {
            return new Pnu(List.of(), 8, 0, 0, "07:00", 20, 90, 40000, "06-01", List.of());
        }

        /** 실제로 켜진 데이터셋 — 비어 있으면 기본 목록(건물 호·전유부는 실응답 확인 전이라 제외). */
        public List<String> effectiveDatasets() {
            return datasets.isEmpty() ? KrasPnuQueueService.DEFAULT_DATASETS : datasets;
        }
    }

    /**
     * @param schedules 묶음 ID(full-load / correction) → cron. 빈 값이면 꺼짐.
     */
    public record Settings(String serviceId, String startParam, String endParam, String dateFormat,
                           Map<String, String> schedules, Pnu pnu) {
        public Settings(String serviceId, String startParam, String endParam, String dateFormat,
                        Map<String, String> schedules) {
            this(serviceId, startParam, endParam, dateFormat, schedules, Pnu.defaults());
        }

        public String schedule(KrasNextgenGroup group) {
            return schedules.getOrDefault(group.id, "");
        }

        static Settings empty() {
            return new Settings("", "", "", "yyyyMMdd", Map.of(), Pnu.defaults());
        }
    }

    private record Snapshot(long modified, Settings settings) {}

    private final Path path;
    private volatile Snapshot snapshot;

    public KrasNextgenSettingsService(@Value("${spring.config.location:conf/application.yml}") String configLocation) {
        String loc = configLocation.startsWith("file:") ? configLocation.substring(5) : configLocation;
        this.path = Path.of(loc).normalize();
    }

    public Path path() { return path; }

    public Settings current() {
        long modified = modified();
        Snapshot s = snapshot;
        if (s != null && s.modified() == modified) return s.settings();
        Settings loaded = parse(readRoot().get(BLOCK));
        snapshot = new Snapshot(modified, loaded);
        return loaded;
    }

    /** 저장 전 검증. 빈 목록이면 통과. */
    public static List<String> validate(Settings s) {
        List<String> errors = new ArrayList<>();
        if (s.startParam().isBlank() != s.endParam().isBlank()) {
            errors.add("처리일자 시작일·종료일 필드명은 둘 다 채우거나 둘 다 비워야 합니다.");
        }
        try {
            DateTimeFormatter.ofPattern(s.dateFormat().isBlank() ? "yyyyMMdd" : s.dateFormat());
        } catch (IllegalArgumentException e) {
            errors.add("날짜 형식이 올바르지 않습니다: " + s.dateFormat());
        }
        for (KrasNextgenGroup group : KrasNextgenGroup.values()) {
            String cron = s.schedule(group);
            if (!cron.isBlank() && !CronExpression.isValidExpression(cron)) {
                errors.add(group.label + " 스케줄이 올바른 cron이 아닙니다: " + cron);
            }
        }
        Pnu p = s.pnu();
        if (p.parallelism() < 1 || p.parallelism() > 32) errors.add("동시 호출 수는 1~32 사이여야 합니다.");
        if (p.dailyCallLimit() < 0) errors.add("하루 호출 한도는 0(무제한) 이상이어야 합니다.");
        if (p.callIntervalMs() < 0) errors.add("호출 간격은 0 이상이어야 합니다.");
        if (!p.stopAt().isBlank()) {
            try {
                LocalTime.parse(p.stopAt());
            } catch (Exception e) {
                errors.add("종료 시각은 HH:mm 형식이어야 합니다: " + p.stopAt());
            }
        }
        if (p.circuitBreak() < 1) errors.add("연속 실패 차단 횟수는 1 이상이어야 합니다.");
        if (p.refreshDays() < 1) errors.add("재조회 주기(일)는 1 이상이어야 합니다.");
        if (p.rotateLimit() < 0) errors.add("하루 순환 재조회 건수는 0 이상이어야 합니다.");
        try {
            MonthDay.parse("--" + p.priceRefreshAfter());
        } catch (Exception e) {
            errors.add("가격 갱신 기준일은 MM-dd 형식이어야 합니다: " + p.priceRefreshAfter());
        }
        for (String ds : p.datasets()) {
            if (!KrasPnuQueueService.ALL_DATASETS.contains(ds)) errors.add("알 수 없는 PNU 서비스: " + ds);
        }
        return errors;
    }

    /** 검증 → kras-nextgen 블록만 교체해 저장(임시 파일에 쓴 뒤 교체). */
    public synchronized void save(Settings s) throws IOException {
        List<String> errors = validate(s);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join(" ", errors));

        Map<String, Object> root = readRoot();
        root.remove(OLD_BLOCK);
        root.put(BLOCK, toMap(s));

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, new Yaml(options).dump(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        snapshot = null;
    }

    private static Map<String, Object> toMap(Settings s) {
        Map<String, Object> landChange = new LinkedHashMap<>();
        landChange.put("service-id", s.serviceId().trim());
        landChange.put("start-param", s.startParam().trim());
        landChange.put("end-param", s.endParam().trim());
        landChange.put("date-format", s.dateFormat().isBlank() ? "yyyyMMdd" : s.dateFormat().trim());
        Map<String, Object> schedules = new LinkedHashMap<>();
        for (KrasNextgenGroup g : KrasNextgenGroup.values()) schedules.put(g.id, s.schedule(g).trim());
        Pnu p = s.pnu();
        Map<String, Object> pnu = new LinkedHashMap<>();
        pnu.put("datasets", new ArrayList<>(p.datasets()));
        pnu.put("parallelism", p.parallelism());
        pnu.put("daily-call-limit", p.dailyCallLimit());
        pnu.put("call-interval-ms", p.callIntervalMs());
        pnu.put("stop-at", p.stopAt().trim());
        pnu.put("circuit-break", p.circuitBreak());
        pnu.put("refresh-days", p.refreshDays());
        pnu.put("rotate-limit", p.rotateLimit());
        pnu.put("price-refresh-after", p.priceRefreshAfter().trim());
        pnu.put("no-data-codes", new ArrayList<>(p.noDataCodes()));
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("land-change", landChange);
        block.put("schedules", schedules);
        block.put("pnu", pnu);
        return block;
    }

    static Settings parse(Object block) {
        if (!(block instanceof Map<?, ?> m)) return Settings.empty();
        Map<?, ?> lc = m.get("land-change") instanceof Map<?, ?> x ? x : Map.of();
        Map<?, ?> sc = m.get("schedules") instanceof Map<?, ?> x ? x : Map.of();
        Map<String, String> schedules = new LinkedHashMap<>();
        for (KrasNextgenGroup g : KrasNextgenGroup.values()) {
            String cron = str(sc.get(g.id));
            if (cron.isEmpty() && !sc.containsKey(g.id)) {
                for (String old : OLD_SCHEDULE_KEYS.get(g.id)) {
                    cron = str(sc.get(old));
                    if (!cron.isEmpty()) break;
                }
            }
            schedules.put(g.id, cron);
        }
        String format = str(lc.get("date-format"));
        return new Settings(str(lc.get("service-id")), str(lc.get("start-param")), str(lc.get("end-param")),
                format.isEmpty() ? "yyyyMMdd" : format, schedules, parsePnu(m.get("pnu")));
    }

    private static Pnu parsePnu(Object block) {
        Pnu d = Pnu.defaults();
        if (!(block instanceof Map<?, ?> m)) return d;
        return new Pnu(list(m.get("datasets")), num(m, "parallelism", d.parallelism()),
                num(m, "daily-call-limit", d.dailyCallLimit()), num(m, "call-interval-ms", d.callIntervalMs()),
                m.containsKey("stop-at") ? str(m.get("stop-at")) : d.stopAt(),
                num(m, "circuit-break", d.circuitBreak()), num(m, "refresh-days", d.refreshDays()),
                num(m, "rotate-limit", d.rotateLimit()),
                m.containsKey("price-refresh-after") ? str(m.get("price-refresh-after")) : d.priceRefreshAfter(),
                list(m.get("no-data-codes")));
    }

    private static int num(Map<?, ?> m, String key, int fallback) {
        String v = str(m.get(key));
        try {
            return v.isEmpty() ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return -1;   // 검증에서 걸린다
        }
    }

    /** YAML 목록 또는 쉼표 구분 문자열. */
    static List<String> list(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            l.forEach(x -> { if (!str(x).isEmpty()) out.add(str(x)); });
        } else {
            for (String x : str(v).split("[,\\s]+")) if (!x.isEmpty()) out.add(x);
        }
        return List.copyOf(out);
    }

    private Map<String, Object> readRoot() {
        try {
            if (!Files.exists(path)) return new LinkedHashMap<>();
            Object loaded = new Yaml().load(Files.readString(path, StandardCharsets.UTF_8));
            Map<String, Object> root = new LinkedHashMap<>();
            if (loaded instanceof Map<?, ?> raw) raw.forEach((k, v) -> root.put(String.valueOf(k), v));
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("설정 파일을 읽을 수 없습니다: " + path + " — " + e.getMessage(), e);
        }
    }

    private long modified() {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }
}
