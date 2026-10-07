package geosync.kras;

import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import org.springframework.mock.web.MockServletContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KrasIntegrationViewTest {
    @Test
    void rendersHistoryAndWorkingDynamicServiceButtons() throws Exception {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.addDialect(new LayoutDialect());
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), Locale.KOREAN);
        for (String prefix : List.of("dataset", "landInfo", "landBldgCheck", "collectiveBuilding", "shrYmb",
                "ownRgtHist", "landMovHist", "collectiveUnit", "landRight", "unitOwnershipHistory",
                "integratedBuilding", "buildingImage", "landChange", "layerList", "usezoneFile",
                "landBasicFile", "landPriceFile")) {
            context.setVariable(prefix + "Status", "UNVERIFIED");
            context.setVariable(prefix + "Enabled", false);
            context.setVariable(prefix + "Running", false);
        }
        context.setVariable("currentPage", "kras-db");
        context.setVariable("orgCode", "12830");
        context.setVariable("ingestRunning", false);
        context.setVariable("usezoneRunning", false);
        context.setVariable("recentRuns", List.of());
        context.setVariable("usezoneReleaseLayers", List.of());
        Map<String, Object> spec = new java.util.HashMap<>(Map.of(
                "slug", "use-zone", "datasetCode", "use_zone", "serviceCode", "KRAS000027",
                "label", "용도지역지구", "status", "UNVERIFIED", "enabled", false,
                "running", false, "needsBno", false, "dependsOn", "", "apiTestId", "conn/use_zone"));
        spec.put("businessTables", "kras.land_use_zone");
        spec.put("lastResult", null);
        context.setVariable("specServices", List.of(spec));
        context.setVariable("datasetGroups", List.of("토지", "건물", "공간(SHP)", "가격", "전체TXT", "기간조회"));
        Map<String, Object> indexRow = new java.util.HashMap<>(Map.of(
                "slug", "use-zone", "datasetCode", "use_zone", "label", "용도지역지구", "group", "토지",
                "status", "UNVERIFIED", "enabled", false, "running", false));
        indexRow.put("lastVerifiedAt", null);
        indexRow.put("lastVerifiedBy", null);
        context.setVariable("datasetIndex", List.of(indexRow));
        List<Map<String, Object>> specRows = KrasSchemaController.toSpecItemRows(Map.of());
        KrasSchemaController.withRunInfo(specRows, Map.of());
        context.setVariable("specItems", specRows);
        context.setVariable("legacyNextRun", "기존 KRAS 동기화(KrasWorker) 다음 실행: 2026-10-08 04:30 (cron 0 30 4 * * *)");
        context.setVariable("specCallableCount", 0L);
        context.setVariable("specNoIdCount", 7L);
        context.setVariable("specDocErrorCount", 2L);
        String html = engine.process("kras-db", context);
        Files.createDirectories(Path.of("build/kras-ui"));
        Files.writeString(Path.of("build/kras-ui/kras-db.html"), html);
        assertThat(html).contains("data-slug=\"use-zone\"",
                "onclick=\"runPnuIngest(this.dataset.slug)\"", "KRAS 연계 관리",
                "id=\"dataset-nav-search\"", "href=\"#card-use-zone\"", "id=\"card-use-zone\"", ">토지<",
                "규격 16개 항목 연계 현황", "id=\"spec-items\"", "KRAS000002", "상세 보기 →",
                "href=\"/kras-stats?dataset=land_info&amp;label=",
                "규격서 오류 · 미구현", "서비스 ID 없음 · 호출 불가", "전체 연계 목록 · 데이터셋 카드",
                "기존 KRAS 동기화(KrasWorker) 다음 실행", ">수동<", "실행 이력 없음", "API 테스트",
                "API 없음", "krasApiTestPopup(&#39;conn/land_info&#39;", "id=\"api-test-modal-backdrop\"");
        // 규격 상세(수집 이력)는 통계 페이지로 옮겼다
        assertThat(html).doesNotContain("전체 연계 작업 이력", "id=\"integration-history\"", "id=\"spec-detail\"",
                "krasSpecDetail", "kras ↔ public 건수 비교", "kras.lp_pa_cbnd (게시본)", "kras.land_basic 현재 건수");
        assertThat(html.indexOf("id=\"spec-items\"")).isLessThan(html.indexOf("id=\"dataset-modal-backdrop\""));
    }
}
