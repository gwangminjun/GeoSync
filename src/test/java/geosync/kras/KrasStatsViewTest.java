package geosync.kras;

import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class KrasStatsViewTest {

    @Test
    void rendersCountsWithoutDatasetControls() {
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
        context.setVariable("currentPage", "kras-stats");
        context.setVariable("orgCode", "12830");
        context.setVariable("krasCount", 1234L);
        context.setVariable("publicCount", 1230L);
        context.setVariable("uzoneKrasCount", 56L);
        context.setVariable("uzonePublicCount", 55L);
        context.setVariable("landBasicCount", 9999L);
        context.setVariable("landPriceFileRowCount", 4321L);
        context.setVariable("selectedDataset", "land_info");
        context.setVariable("selectedLabel", "토지(임야)대장");
        context.setVariable("datasetCodes", java.util.List.of("land_info", "shr_ymb"));

        String html = engine.process("kras-stats", context);

        assertThat(html).contains("KRAS 연계 통계", ">1234<", ">9999<", ">4321<", ">56<",
                "id=\"integration-history\"", "id=\"history-items\"", "규격 상세 · 토지(임야)대장",
                "id=\"history-initial\"", "data-dataset=\"land_info\"", "name=\"datasetCode\"",
                "/js/kras-history.js");
        assertThat(html).doesNotContain("dataset-nav", "verify-submit", "krasSpecDetail");
    }
}
