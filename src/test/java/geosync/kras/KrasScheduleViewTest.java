package geosync.kras;

import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KrasScheduleViewTest {

    private static Map<String, Object> step(String id, String label, List<String> blocked) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("target", "대상");
        m.put("blocked", blocked);
        m.put("lastResult", null);
        return m;
    }

    private static Map<String, Object> group(String id, String label, String cron, boolean replacesPublic,
                                             List<Map<String, Object>> steps) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("cron", cron);
        m.put("replacesPublic", replacesPublic);
        m.put("nextRun", cron.isEmpty() ? "—" : "2026-11-01 01:00");
        m.put("lastResult", null);
        m.put("steps", steps);
        return m;
    }

    @Test
    void rendersThreeGroupsQueuePanelHelpPopupAndAdvancedSettings() throws Exception {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.addDialect(new LayoutDialect());
        MockServletContext servletContext = new MockServletContext();
        WebContext ctx = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), Locale.KOREAN);
        var settings = new KrasNextgenSettingsService.Settings("KRAS000099", "ST", "ED", "yyyyMMdd", Map.of());
        ctx.setVariable("currentPage", "kras-schedule");
        ctx.setVariable("orgCode", "12830");
        ctx.setVariable("settings", settings);
        ctx.setVariable("pnu", settings.pnu());
        ctx.setVariable("configPath", "conf/application.yml");
        ctx.setVariable("groups", List.of(
                group("full-load", "전체 적재", "0 0 0 1 * *", false, List.of(
                        step("land-basic", "토지기본정보 TXT", List.of()),
                        step("pnu-full", "PNU 서비스 전체 대기열 등록", List.of()))),
                group("correction", "보정 적재", "", false, List.of(step("pnu-queue", "대기열 처리", List.of()))),
                group("layer", "레이어 적재", "0 30 7 * * *", true, List.of(
                        step("cadastral", "연속지적 SHP", List.of("운영 응답 검증이 필요합니다.")),
                        step("usezone", "용도지역 SHP", List.of())))));
        ctx.setVariable("pnuDatasets", List.of(
                Map.of("code", "land_info", "label", "토지(임야)대장", "enabled", true, "child", false, "blocked", List.of(),
                        "price", false, "refreshDays", "14"),
                Map.of("code", "land_jiga", "label", "개별공시지가", "enabled", true, "child", false, "blocked", List.of(),
                        "price", true, "refreshDays", ""),
                Map.of("code", "bldg_ho_info", "label", "호 목록", "enabled", false, "child", true,
                        "blocked", List.of("데이터셋이 비활성 상태입니다."))));
        ctx.setVariable("runningJob", null);
        ctx.setVariable("legacyConflict", true);
        ctx.setVariable("legacySchedule", "0 30 4 * * *");
        ctx.setVariable("legacySameTable", false);
        ctx.setVariable("dbServiceCode", null);
        ctx.setVariable("baseline", null);
        ctx.setVariable("help", Map.of("leaseMinutes", 10, "retryDelays", "1시간 → 4시간 → 1일", "maxRetries", 3,
                "circuitBreak", 20, "correctionSchedule", "다음 보정 적재(2026-10-16 01:00)"));
        ctx.setVariable("recentRuns", List.of(Map.of("started_at", "2026-10-14 06:00", "dataset_code", "land_change",
                "action", "INGEST", "status", "FAILED", "request_summary", "SCHEDULE 2026-10-13 ~ 2026-10-13",
                "message", "게이트웨이 연결 실패")));

        String html = engine.process("kras-schedule", ctx);
        Files.createDirectories(Path.of("build/kras-ui"));
        Files.writeString(Path.of("build/kras-ui/kras-schedule.html"), html);

        assertThat(html).contains("차세대 스케줄", "href=\"/kras-schedule\"", "id=\"legacy-conflict\"",
                "name=\"schedule_full-load\" value=\"0 0 0 1 * *\"", "name=\"schedule_correction\" value=\"\"",
                "name=\"schedule_layer\" value=\"0 30 7 * * *\"", "data-job=\"layer\"", "<b>레이어 적재</b>",
                "name=\"refreshDays_land_info\"", "value=\"14\"", "placeholder=\"30\"", ">연 1회<", "재조회(일)",
                "기존 연계도 매일 같은 레이어(연속지적·용도지역)를 받습니다",
                "data-public=\"true\"", "✕ 운영 응답 검증이 필요합니다.", "실행 가능",
                "data-job=\"full-load\"", "data-job=\"correction\"", "data-job=\"cadastral\"",
                "name=\"stopAt\"", "class=\"btn btn-primary apply-btn\"", "변경됨 — [적용]을 눌러야 반영됩니다", "id=\"queue-panel\"", "data-help=\"recovery\"", "ⓘ 끊겼을 때 어떻게 되나",
                "id=\"help-recovery\"", "10분 뒤 다시 조회합니다", "연속 20번 실패하면",
                "다음 보정 적재(2026-10-16 01:00)에서 다시 시도합니다.",
                "name=\"pnuDataset\" value=\"land_info\" checked", "name=\"parallelism\" value=\"8\"",
                "name=\"serviceId\" value=\"KRAS000099\"", "게이트웨이 연결 실패", "/kras-schedule/queue");
        assertThat(html).doesNotContain("value=\"bldg_ho_info\" checked", "name=\"refreshDays_land_jiga\"");
        // 도움말은 팝업(숨김)으로만 — 첫 화면에 펼치지 않는다.
        assertThat(html).containsPattern("id=\"help-recovery\"\\s+hidden");
        // ods.schema가 public이 아니면 "같이 덮어씁니다"는 붙지 않는다. 연속지적 단계는 레이어 적재 카드 안에 있다.
        assertThat(html).doesNotContain("같이 덮어씁니다");
        assertThat(html.indexOf("data-group=\"layer\"")).isPositive()
                .isLessThan(html.indexOf("data-job=\"cadastral\""));
        assertThat(html.indexOf("id=\"advanced\"")).isLessThan(html.indexOf("name=\"parallelism\""));
        // 재시도 대기·실패·처리 중 목록은 팝업으로, 행을 누르면 상세 팝업(둘 다 숨김으로 시작).
        assertThat(html).contains("class=\"q-open\" data-kind=\"RETRY\"", "class=\"q-open\" data-kind=\"FAILED\"",
                "class=\"q-open\" data-kind=\"RUNNING\"", "id=\"retry-selected\"", "/kras-schedule/queue/row?",
                "d.querySelector('.q-search')?.addEventListener")
                .containsPattern("id=\"q-list-modal\"\\s+hidden").containsPattern("id=\"q-row-modal\"\\s+hidden")
                .doesNotContain("<details class=\"q-list\"");
        assertThat(html.indexOf("id=\"q-list-modal\"")).isLessThan(html.indexOf("id=\"q-row-modal\""));
    }
}
