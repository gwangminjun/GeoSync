package geosync.kras;

import geosync.database.TargetDbService;
import geosync.settings.RuntimeSettingsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * KRAS 연계 적재 결과의 건수 통계 화면. 운영 판단용 상태(첫 화면)와 분리해 두었다.
 * 숫자만 보여주며 어떤 값도 바꾸지 않는다.
 */
@Controller
public class KrasStatsController {

    private final TargetDbService targetDbService;
    private final RuntimeSettingsService settings;

    public KrasStatsController(TargetDbService targetDbService, RuntimeSettingsService settings) {
        this.targetDbService = targetDbService;
        this.settings = settings;
    }

    @GetMapping("/kras-stats")
    public String page(@RequestParam(required = false) String dataset,
                       @RequestParam(required = false) String label,
                       Model model) {
        JdbcTemplate jdbc = targetDbService.getConfiguredTargets().get(0).jdbc();
        model.addAttribute("currentPage", "kras-stats");
        model.addAttribute("orgCode", settings.orgCode());
        // 규격 상세에서 넘어온 경우: 그 연계의 수집 이력을 처음부터 선택해 둔다 (kras-history.js가 읽음)
        model.addAttribute("selectedDataset", dataset == null ? "" : dataset);
        model.addAttribute("selectedLabel", label == null || label.isBlank() ? dataset : label);
        model.addAttribute("datasetCodes", KrasSchemaController.historyDatasetCodes());
        model.addAttribute("krasCount", count(jdbc, "SELECT count(*) FROM kras.lp_pa_cbnd"));
        model.addAttribute("publicCount", count(jdbc, "SELECT count(*) FROM public.lp_pa_cbnd"));
        model.addAttribute("uzoneKrasCount", count(jdbc, "SELECT count(*) FROM kras.lt_c_uzone"));
        model.addAttribute("uzonePublicCount", count(jdbc, "SELECT count(*) FROM public.lt_c_uzone"));
        model.addAttribute("landBasicCount", count(jdbc, "SELECT count(*) FROM kras.land_basic"));
        model.addAttribute("landPriceFileRowCount",
                count(jdbc, "SELECT count(*) FROM kras.land_price_file_row WHERE org_cd=?", settings.orgCode()));
        return "kras-stats";
    }

    private static long count(JdbcTemplate jdbc, String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
}
