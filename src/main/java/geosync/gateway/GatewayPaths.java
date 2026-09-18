package geosync.gateway;

import java.util.Map;
import java.util.Set;

final class GatewayPaths {

    static final Map<String, String> KRAS = Map.ofEntries(
        Map.entry("land_info",              "KRAS000002"),
        Map.entry("shr_ymb",               "KRAS000003"),
        Map.entry("land_mov_hist",          "KRAS000006"),
        Map.entry("own_rgt_hist",           "KRAS000007"),
        Map.entry("bldg_hds_info",          "KRAS000014"),
        Map.entry("cbldg_hds_info",         "KRAS000015"),
        Map.entry("cbldg_dfhs_info",        "KRAS000016"),
        Map.entry("bldg_ledg_gen_hds_info", "KRAS000017"),
        Map.entry("land_use_plan_attr",     "KRAS000025"),
        Map.entry("land_use_plan_info",     "KRAS000026"),
        Map.entry("use_zone",               "KRAS000027"),
        Map.entry("land_bldg_check",        "KRAS000101"),
        Map.entry("bldg_dong_info",         "KRAS000102"),
        Map.entry("bldg_ho_info",           "KRAS000103")
    );

    static final Map<String, String> KOREPS = Map.of(
        "land_jiga",     "KOREPS00011",
        "house_info",    "KOREPS00033",
        "fin_dec_jiga",  "KOREPS00034",
        "read_dec_jiga", "KOREPS00035",
        "land_attr",     "KOREPS00047"
    );

    // 기존 KrasConn.getBldgData 사용 서비스만 건물번호를 bldg_gbn_no 파라미터로 전송
    static final Set<String> BLDG_GBN_NO_PATHS = Set.of(
        "bldg_hds_info", "cbldg_hds_info", "cbldg_dfhs_info", "bldg_ho_info"
    );

    private GatewayPaths() {}
}
