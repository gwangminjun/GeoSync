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
        String html = engine.process("kras-db", context);
        Files.createDirectories(Path.of("build/kras-ui"));
        Files.writeString(Path.of("build/kras-ui/kras-db.html"), html);
        assertThat(html).contains("전체 연계 작업 이력", "data-slug=\"use-zone\"",
                "onclick=\"runPnuIngest(this.dataset.slug)\"", "KRAS 연계 관리");
    }
}
