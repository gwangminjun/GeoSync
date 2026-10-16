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
                "landBasicFile")) {
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
        context.setVariable("extraParamsHints", Map.of("collective_unit", Map.of("cbldg_seqno", "0285")));
        context.setVariable("datasetGroups", List.of("토지", "건물", "공간(SHP)", "가격", "전체TXT", "기간조회"));
        Map<String, Object> indexRow = new java.util.HashMap<>(Map.of(
                "slug", "use-zone", "datasetCode", "use_zone", "label", "용도지역지구", "group", "토지",
                "status", "UNVERIFIED", "enabled", false, "running", false));
        indexRow.put("lastVerifiedAt", null);
        indexRow.put("lastVerifiedBy", null);
        indexRow.put("hasPnuMapper", true);
        context.setVariable("datasetIndex", List.of(indexRow));
        List<Map<String, Object>> specRows = KrasSchemaController.toSpecItemRows(Map.of());
        KrasSchemaController.withRunInfo(specRows, Map.of(), java.util.Set.of("land_info", "shr_ymb"));
        KrasSchemaController.withIncrementalSchedule(specRows,
                Map.of("land_change", new String[]{"일일 증분 · 0 0 6 * * *", "2026-10-15 06:00"}));
        specRows.stream().filter(r -> "land_info".equals(r.get("datasetCode"))).findFirst().ifPresent(r -> {
            r.put("pendingCount", 2L);
            r.put("pendingItemId", 77L);
        });
        context.setVariable("specItems", specRows);
        context.setVariable("mapConfig", KrasSchemaController.mapConfig("http://gs.local/geoserver/YG-Space"));
        context.setVariable("legacyNextRun", "기존 KRAS 동기화(KrasWorker) 다음 실행: 2026-10-08 04:30 (cron 0 30 4 * * *)");
        context.setVariable("specCallableCount", 0L);
        context.setVariable("specNoIdCount", 7L);
        context.setVariable("specDocErrorCount", 2L);
        String html = engine.process("kras-db", context);
        Files.createDirectories(Path.of("build/kras-ui"));
        Files.writeString(Path.of("build/kras-ui/kras-db.html"), html);
        assertThat(html).contains("KRAS 연계 관리",
                "id=\"dataset-nav-search\"", "href=\"#card-use-zone\"", "id=\"card-use-zone\"", ">토지<",
                "규격 16개 항목 연계 현황", "id=\"spec-items\"", "KRAS000002", "상세 보기 →",
                "krasOpenHistoryPopup(&#39;land_info&#39;, &#39;토지(임야)대장&#39;)",
                "id=\"hist-modal-backdrop\"", "id=\"hist-items\"", "id=\"hist-promote\"", "/kras-db/history?dataset=",
                // 반영 대기 건이 있는 행만 이어하기 버튼이 뜨고, 그 건을 바로 연다.
                "반영 대기 2건 · 이어하기", "krasOpenHistoryPopup(&#39;land_info&#39;, &#39;토지(임야)대장&#39;, 77)",
                "규격서 오류 · 미구현", "서비스 ID 없음 · 호출 불가", "전체 연계 목록 · 데이터셋 카드",
                "기존 KRAS 동기화(KrasWorker) 다음 실행", ">수동<", "실행 이력 없음",
                // 증분 스케줄이 켜진 행만 "수동" 대신 스케줄·다음 실행을 보여준다.
                ">일일 증분 · 0 0 6 * * *<", ">2026-10-15 06:00<",
                "krasOpenDatasetPopup(&#39;land-info&#39;, &#39;land_info&#39;, &#39;토지(임야)대장&#39;, &#39;UNVERIFIED&#39;, false, true)",
                "id=\"dset-modal-backdrop\"", "id=\"dset-pane-structure\"",
                "id=\"dset-approve-panel\"", "id=\"dset-approve-sample\"", "/kras-db/verify-check",
                "id=\"dset-approve-submit\"", "id=\"dset-approve-revert\"", "계약 미승인", "승인 · 활성",
                // 실제 수집/승격 — PNU 단건 매퍼가 있는 데이터셋이면 "응답 확인·승인" 패널 안에 같이 뜬다.
                "id=\"dset-live-row\"", "id=\"dset-live-pnu\"", "id=\"dset-live-extra-fields\"",
                "onclick=\"krasLiveCollect()\"", "onclick=\"krasLivePromote()\"", "/kras-db/ingest/", "/kras-db/promote/",
                // 추가 파라미터가 필요한 데이터셋만 입력칸을 보여준다 — 그 목록을 JS에 심어둔다.
                "KRAS_EXTRA_PARAMS_HINTS", "cbldg_seqno",
                // 적재 DB 테이블 칸: 테이블마다 조회 팝업 버튼, 화살표·쉼표는 글자로 남는다.
                "data-table=\"kras.land_change_event\" data-dataset=\"land_change\"",
                ">kras.land_change_event</button><span> → </span><button",
                "data-table=\"kras.land_owner\" data-dataset=\"land_info\"", "<span>, </span>",
                "id=\"tbl-modal-backdrop\"", "id=\"tbl-col\"", "id=\"tbl-q\"", "/kras-db/table?",
                // 지도 미리보기: [초기화] 옆 버튼, 지도 팝업, yml 지오서버 주소가 JS로 넘어간다.
                "id=\"tbl-reset\">초기화</button>\n          <button type=\"button\" class=\"btn btn-secondary\" id=\"tbl-preview\" hidden>미리보기</button>",
                "id=\"map-modal-backdrop\"", "id=\"map-view\"", "id=\"map-portal-scale\"", "/kras-db/table/extent?",
                "name=\"tbl-pick\"", "선택 1건 미리보기", "CQL_FILTER", "params.set('key', key)",
                "\"wmsUrl\":\"http:\\/\\/gs.local\\/geoserver\\/YG-Space\\/wms\"", "public.lt_c_uzone");
        // 규격 상세(수집 이력)는 통계 페이지로 옮겼다
        assertThat(html).doesNotContain("전체 연계 작업 이력", "id=\"integration-history\"", "id=\"spec-detail\"",
                "krasSpecDetail", "kras ↔ public 건수 비교", "kras.lp_pa_cbnd (게시본)", "kras.land_basic 현재 건수",
                "krasApiTestPopup", "krasOpenDatasetModal(", "id=\"api-test-modal-backdrop\"");
        // 담당자/매퍼 버전 입력은 더 이상 없다 — 응답 붙여넣기로만 승인한다.
        assertThat(html).doesNotContain("name=\"verifiedBy\"", "name=\"mapperVersion\"", "매퍼 버전(수동",
                "계약 VERIFIED", "계약 UNVERIFIED", "VERIFIED · enabled", "VERIFIED로 전환");
        // 데이터셋별로 따로 있던 PNU 테스트 수집/승격 카드는 팝업 안의 공용 패널로 합쳤다.
        assertThat(html).doesNotContain("onclick=\"runPnuIngest(this.dataset.slug)\"",
                "runPnuIngest(", "runPnuPromote(", "data-companion-of=\"land-info\"",
                "land_info PNU 테스트 수집/승격");
        // API 테스트(실서버 raw 응답 조회용 iframe)는 실제 수집으로 기능이 겹쳐 완전히 뺐다.
        // (전역 상단 메뉴의 "API 테스트" 링크는 별개 페이지라 남아있는 게 정상 — 그 텍스트는 검사하지 않는다.)
        assertThat(html).doesNotContain("<th>API 테스트</th>",
                "API 테스트 — 여기서 먼저 호출해 응답을 확인하세요", "API 없음",
                "id=\"dset-test-frame\"", "id=\"dset-test-empty\"", "/api-test?embed=1");
        // 규격 표의 "상세 보기"는 이제 /kras-stats로 이동하지 않고 새 팝업을 연다.
        // (상단 메뉴의 "KRAS 통계" 링크만 주석 처리했을 뿐 페이지 자체는 남아있어, 본문의 다른 안내 링크는 그대로다.)
        assertThat(html).doesNotContain("href=\"/kras-stats?dataset=");
        assertThat(html.indexOf("id=\"spec-items\"")).isLessThan(html.indexOf("id=\"dset-modal-backdrop\""));
        assertThat(html.split("건 · 이어하기", -1)).hasSize(2);
    }
}
