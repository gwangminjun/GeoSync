package geosync.kras;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

import static geosync.kras.KrasSpecMapper.Section;
import static geosync.kras.KrasStagePromotionService.ChildSpec;
import static geosync.kras.KrasStagePromotionService.ParentLookup;
import static geosync.kras.KrasStagePromotionService.StagePromotionSpec;

/**
 * kras.md에 응답 규격이 없는 14개 서비스의 매퍼 선언.
 *
 * <p>손으로 쓴 매퍼(LandInfoMapper 등)는 kras.md에 실제 XML이 있어서 태그를 하나씩 확인해 옮긴 것이고,
 * 여기 14개는 그 근거가 없다. 대신 <b>conn_svc_id와 요청 파라미터는 이미 안다</b>
 * (GatewayPaths + KrasApiClient — /conn 경로가 운영에서 도는 중) — 막힌 건 응답 구조뿐이라
 * 실제 호출 한 번이면 확인된다.
 *
 * <p><b>태그명은 업무 테이블 컬럼명을 대문자로 바꾼 가설이다.</b> 14개를 거의 똑같은 클래스로
 * 복붙하는 대신 선언으로 두는 이유가 여기 있다 — 실응답을 보고 틀린 태그를 고칠 때
 * 이 파일 한 곳만 바꾸면 된다.
 *
 * <p>승격 패턴은 대상 테이블이 guard_business_row의 DELETE 차단 목록에 있는지로 갈린다:
 * building_register/building_unit은 자식이 참조하는 부모라 패턴 C(신원 매칭, ID 보존),
 * 나머지 리프 테이블은 패턴 B(범위 전체 교체).
 */
@Configuration
public class KrasSpecMapperConfig {

    private static final List<String> NONE = List.of();

    // ── 토지이용 계열 (독립, PNU만 필요) ──────────────────────────────

    @Bean
    public KrasSpecMapper useZoneMapper() {
        List<String> cols = List.of("pnu", "use_zone_zone_cd", "use_zone_zone_cd_nm", "cflt_yn");
        return new KrasSpecMapper("use_zone", "KRAS000027", "KRAS",
                List.of(Section.repeated("kras.stage_land_use_zone", "USE_ZONE_ZONE_CD", cols, NONE, NONE)),
                List.of(StagePromotionSpec.scopeReplace("kras.stage_land_use_zone", "kras.land_use_zone",
                        "pnu", cols)),
                false);
    }

    @Bean
    public KrasSpecMapper landUsePlanAttrMapper() {
        List<String> cols = List.of("pnu", "ctype", "divno", "gubun", "lawnm", "seq", "ucode", "uname", "unm");
        return new KrasSpecMapper("land_use_plan_attr", "KRAS000025", "KRAS",
                List.of(Section.repeated("kras.stage_land_use_attribute", "UCODE", cols, NONE, NONE)),
                List.of(StagePromotionSpec.scopeReplace("kras.stage_land_use_attribute",
                        "kras.land_use_attribute", "pnu", cols)),
                false);
    }

    /**
     * 토지이용계획 + 행위제한. 응답에 지도 이미지도 딸려 오지만(land_use_plan_asset), 이미지 저장은
     * 실응답으로 인코딩·태그를 확인한 뒤에 붙인다 — 지금은 계획/제한 두 테이블만 채운다.
     * request_key는 NOT NULL이라 kras.request_key() DB 함수로 채워야 해서 별도 처리가 필요하다.
     */
    @Bean
    public KrasSpecMapper landUsePlanInfoMapper() {
        List<String> planCols = List.of("pnu", "request_key", "land_loc_nm", "jibn", "jimok", "jimok_nm", "jiga_ym",
                "scale", "iss_no", "iss_scale", "adm_sect_head", "parea", "jiga");
        List<String> restrictionCols = List.of("seqno", "ucode", "law_full_cd", "law_level", "law_contents",
                "uselaw_a", "uselaw_b", "use_restrict");
        return new KrasSpecMapper("land_use_plan_info", "KRAS000026", "KRAS",
                List.of(
                    Section.flat("kras.stage_land_use_plan", planCols, NONE, List.of("parea", "jiga"))
                            .withConstants(Map.of("request_key", KrasSpecMapper.COMPUTE_REQUEST_KEY)),
                    Section.repeated("kras.stage_land_use_restriction", "LAW_FULL_CD", restrictionCols, NONE, NONE)),
                List.of(StagePromotionSpec.scopeReplaceWithChildren("kras.stage_land_use_plan",
                        "kras.land_use_plan", "pnu", "plan_id", planCols,
                        List.of(new ChildSpec("kras.stage_land_use_restriction", "kras.land_use_restriction",
                                "plan_id", "restriction_id", restrictionCols)))),
                false);
    }

    // ── 건물 계열 ────────────────────────────────────────────────────

    /**
     * 건물 동 정보 — <b>건물 계열의 관문</b>. 여기서 나오는 bldg_gbn_no(건물식별번호)가 있어야
     * bldg_hds_info / cbldg_hds_info / cbldg_dfhs_info / bldg_ho_info를 호출할 수 있다.
     * building_register는 자식이 참조하는 부모라 DELETE 금지 → 패턴 C.
     */
    @Bean
    public KrasSpecMapper bldgDongInfoMapper() {
        List<String> cols = List.of("pnu", "bldg_gbn_no", "bldg_kind_cd", "bldg_kind_nm", "bldg_nm",
                "dong_nm", "bmap_yn", "garea");
        return new KrasSpecMapper("bldg_dong_info", "KRAS000102", "KRAS",
                List.of(Section.repeated("kras.stage_building_register", "BLDG_GBN_NO", cols,
                        NONE, List.of("garea"))),
                List.of(StagePromotionSpec.identityMatchUpsert("kras.stage_building_register",
                        "kras.building_register", "register_id", List.of("pnu", "bldg_gbn_no"), cols)),
                false);
    }

    /** 건축물대장 총괄표제부 — register_id가 nullable이라 building_register 없이도 들어간다. */
    @Bean
    public KrasSpecMapper bldgLedgGenHdsInfoMapper() {
        List<String> cols = List.of("pnu", "bldg_gbn_no", "bldg_nm", "main_use_nm", "lega_yn", "vio_bldg_yn",
                "larea", "barea", "garea", "blr", "fsi", "fsi_calc_garea", "land_cnt", "tot_main_bldg_cnt",
                "sub_bldg_cnt", "tot_fmly_cnt", "tot_hehd_cnt", "tot_ho_cnt", "tot_park_cnt",
                "perm_ymd", "bgcons_ymd", "use_aprv_ymd");
        return new KrasSpecMapper("bldg_ledg_gen_hds_info", "KRAS000017", "KRAS",
                List.of(Section.repeated("kras.stage_building_summary", "BLDG_GBN_NO", cols,
                        List.of("perm_ymd", "bgcons_ymd", "use_aprv_ymd"),
                        List.of("larea", "barea", "garea", "blr", "fsi", "fsi_calc_garea", "land_cnt",
                                "tot_main_bldg_cnt", "sub_bldg_cnt", "tot_fmly_cnt", "tot_hehd_cnt",
                                "tot_ho_cnt", "tot_park_cnt"))),
                List.of(StagePromotionSpec.scopeReplace("kras.stage_building_summary",
                        "kras.building_summary", "pnu", cols)),
                false);
    }

    /** 건물 호(전유) — building_unit도 자식(building_exclusive)이 참조하는 부모라 패턴 C. */
    @Bean
    public KrasSpecMapper bldgHoInfoMapper() {
        List<String> cols = List.of("pnu", "bldg_gbn_no", "parent_bno", "bldg_kind_cd", "dong_nm",
                "flr_ho_nm", "bmap_yn", "garea");
        return new KrasSpecMapper("bldg_ho_info", "KRAS000103", "KRAS",
                List.of(Section.repeated("kras.stage_building_unit", "BLDG_GBN_NO", cols, NONE, List.of("garea"))),
                List.of(StagePromotionSpec.identityMatchUpsert("kras.stage_building_unit",
                        "kras.building_unit", "building_unit_id", List.of("pnu", "bldg_gbn_no"), cols)),
                true);
    }

    /**
     * 건축물대장 표제부 — 이번에 추가한 "드릴다운 + 자식 여러 개" 패턴을 쓰는 첫 서비스다.
     * 부모 building_title은 building_register(register_id)를 참조하므로 bldg_dong_info가 먼저
     * 승격돼 있어야 하고, 층별/소유자/변동 3개 자식은 매 호출마다 통째로 교체된다.
     */
    @Bean
    public KrasSpecMapper bldgHdsInfoMapper() {
        return buildingTitleMapper("bldg_hds_info", "KRAS000014", true);
    }

    /** 집합건물 표제부 — 같은 building_title을 채우되 source_service로 출처를 구분한다. */
    @Bean
    public KrasSpecMapper cbldgHdsInfoMapper() {
        return buildingTitleMapper("cbldg_hds_info", "KRAS000015", false);
    }

    private KrasSpecMapper buildingTitleMapper(String datasetCode, String connSvcId, boolean withChildren) {
        List<String> titleCols = List.of("bldg_gbn_no", "bldg_kind_cd", "bldg_nm", "dong", "main_sub_gbn_nm",
                "main_sub_seqno", "main_use_nm", "stru_nm", "roof_nm", "lega_yn", "vio_bldg_yn",
                "larea", "barea", "garea", "land_cnt", "fmly_cnt", "hehd_cnt", "ho_cnt");
        List<String> titleNums = List.of("larea", "barea", "garea", "land_cnt", "fmly_cnt", "hehd_cnt", "ho_cnt");

        List<String> floorCols = List.of("flr_gbn_cd", "flr", "main_use_nm", "stru_nm", "btm_area");
        List<String> ownerCols = List.of("owner_nm", "dregno", "own_gbn_nm", "jibun_desc", "detl_addr",
                "last_yn", "adj_ymd");
        List<String> changeCols = List.of("chg_rsn_nm", "chg_cntn", "chg_ymd");

        // source_service는 응답 태그가 아니라 "어느 서비스가 채웠는지"를 남기는 우리 쪽 값이다
        // (bldg_hds_info와 cbldg_hds_info가 같은 building_title을 채우므로 출처로 구분한다).
        List<String> titleCopyCols = new java.util.ArrayList<>(titleCols);
        titleCopyCols.add("source_service");

        List<Section> sections = new java.util.ArrayList<>();
        sections.add(Section.repeated("kras.stage_building_title", "BLDG_GBN_NO", titleCols, NONE, titleNums)
                .withConstants(Map.of("source_service", datasetCode)));
        List<ChildSpec> children = new java.util.ArrayList<>();
        if (withChildren) {
            sections.add(Section.repeated("kras.stage_building_floor", "FLR_GBN_CD", floorCols,
                    NONE, List.of("btm_area")));
            sections.add(Section.repeated("kras.stage_building_title_owner", "OWNER_NM", ownerCols,
                    List.of("adj_ymd"), NONE));
            sections.add(Section.repeated("kras.stage_building_title_change", "CHG_RSN_NM", changeCols,
                    List.of("chg_ymd"), NONE));
            children.add(new ChildSpec("kras.stage_building_floor", "kras.building_floor",
                    "title_id", "floor_id", floorCols));
            children.add(new ChildSpec("kras.stage_building_title_owner", "kras.building_title_owner",
                    "title_id", "owner_id", ownerCols));
            children.add(new ChildSpec("kras.stage_building_title_change", "kras.building_title_change",
                    "title_id", "change_id", changeCols));
        }

        return new KrasSpecMapper(datasetCode, connSvcId, "KRAS", sections,
                List.of(StagePromotionSpec.parentLookupIdentityMatchUpsert("kras.stage_building_title",
                        "kras.building_title", "title_id", List.of("bldg_gbn_no", "source_service"), titleCopyCols,
                        new ParentLookup("kras.building_register", "register_id",
                                List.of("pnu"), List.of("_pnu"), "register_id"),
                        children)),
                true);
    }

    /**
     * 집합건물 전유부 — building_unit을 부모로 하는 드릴다운 + 자식 3개(면적/소유자/가격).
     * request_key가 NOT NULL이라 승격 전에 채워야 하는데, 지금은 실응답 확인 전이라
     * building_exclusive 본체까지만 선언하고 자식은 실응답 확인 후 붙인다.
     */
    @Bean
    public KrasSpecMapper cbldgDfhsInfoMapper() {
        List<String> exclusiveCols = List.of("pnu", "request_key", "bldg_gbn_no", "upper_bldg_no");
        List<String> areaCols = List.of("expos_comm_gbn_nm", "flr", "flr_no", "main_sub_gbn_nm",
                "main_use_nm", "stru_nm");
        List<String> ownerCols = List.of("owner_nm", "dregno", "own_gbn_nm", "jibun_desc", "detl_addr",
                "chg_rsn_nm", "last_yn", "chg_ymd");
        List<String> priceCols = List.of("base_ymd", "house_prc");

        return new KrasSpecMapper("cbldg_dfhs_info", "KRAS000016", "KRAS",
                List.of(
                    Section.repeated("kras.stage_building_exclusive", "BLDG_GBN_NO", exclusiveCols, NONE, NONE)
                            .withConstants(Map.of("request_key", KrasSpecMapper.COMPUTE_REQUEST_KEY)),
                    Section.repeated("kras.stage_building_exclusive_area", "EXPOS_COMM_GBN_NM", areaCols, NONE, NONE),
                    Section.repeated("kras.stage_building_exclusive_owner", "OWNER_NM", ownerCols,
                            List.of("chg_ymd"), NONE),
                    Section.repeated("kras.stage_building_exclusive_price", "HOUSE_PRC", priceCols,
                            List.of("base_ymd"), List.of("house_prc"))),
                List.of(StagePromotionSpec.parentLookupIdentityMatchUpsert("kras.stage_building_exclusive",
                        "kras.building_exclusive", "exclusive_id", List.of("bldg_gbn_no"), exclusiveCols,
                        new ParentLookup("kras.building_unit", "building_unit_id",
                                List.of("pnu"), List.of("_pnu"), "building_unit_id"),
                        List.of(
                            new ChildSpec("kras.stage_building_exclusive_area", "kras.building_exclusive_area",
                                    "exclusive_id", "area_id", areaCols),
                            new ChildSpec("kras.stage_building_exclusive_owner", "kras.building_exclusive_owner",
                                    "exclusive_id", "owner_id", ownerCols),
                            new ChildSpec("kras.stage_building_exclusive_price", "kras.building_exclusive_price",
                                    "exclusive_id", "price_id", priceCols)))),
                true);
    }

    // ── KOREPS 5종 (별도 게이트웨이) ─────────────────────────────────

    @Bean
    public KrasSpecMapper landJigaMapper() {
        List<String> cols = List.of("pnu", "base_year", "base_mon", "jibun", "jiga_jibn", "pann_jiga", "pann_ymd");
        return new KrasSpecMapper("land_jiga", "KOREPS00011", "KOREPS",
                List.of(Section.repeated("kras.stage_koreps_land_price", "BASE_YEAR", cols,
                        List.of("pann_ymd"), List.of("pann_jiga"))),
                List.of(StagePromotionSpec.scopeReplace("kras.stage_koreps_land_price",
                        "kras.koreps_land_price", "pnu", cols)),
                false);
    }

    @Bean
    public KrasSpecMapper houseInfoMapper() {
        List<String> cols = List.of("pnu", "base_year", "stdmt", "dong_no", "land_area", "land_calc_area",
                "bldg_area", "bldg_calc_area", "indi_house_prc");
        return new KrasSpecMapper("house_info", "KOREPS00033", "KOREPS",
                List.of(Section.repeated("kras.stage_house_price", "BASE_YEAR", cols, NONE,
                        List.of("land_area", "land_calc_area", "bldg_area", "bldg_calc_area", "indi_house_prc"))),
                List.of(StagePromotionSpec.scopeReplace("kras.stage_house_price", "kras.house_price", "pnu", cols)),
                false);
    }

    @Bean
    public KrasSpecMapper finDecJigaMapper() {
        List<String> cols = List.of("pnu", "base_year", "stdmt", "jiga");
        return new KrasSpecMapper("fin_dec_jiga", "KOREPS00034", "KOREPS",
                List.of(Section.repeated("kras.stage_final_land_price", "BASE_YEAR", cols, NONE, List.of("jiga"))),
                List.of(StagePromotionSpec.scopeReplace("kras.stage_final_land_price",
                        "kras.final_land_price", "pnu", cols)),
                false);
    }

    @Bean
    public KrasSpecMapper readDecJigaMapper() {
        List<String> cols = List.of("pnu", "cald_stdmt", "seqno", "jimok", "land_loc_addr",
                "decn_jiga", "read_jiga", "py_jiga", "parea");
        return new KrasSpecMapper("read_dec_jiga", "KOREPS00035", "KOREPS",
                List.of(Section.repeated("kras.stage_read_land_price", "CALD_STDMT", cols, NONE,
                        List.of("decn_jiga", "read_jiga", "py_jiga", "parea"))),
                List.of(StagePromotionSpec.scopeReplace("kras.stage_read_land_price",
                        "kras.read_land_price", "pnu", cols)),
                false);
    }

    @Bean
    public KrasSpecMapper landAttrMapper() {
        List<String> cols = List.of("pnu", "land_seqno", "sgg_cd", "land_loc_cd", "ledg_gbn", "bobn", "bubn",
                "land_loc_nm", "jimok", "jimok_nm", "own_gbn", "land_use", "geo_form", "geo_hl", "road_side",
                "spfc1", "pann_year", "stdmt", "land_mov_rsn_cd", "parea", "spfc1_area", "pnilp",
                "calc_jiga", "py_jiga", "land_mov_ymd");
        return new KrasSpecMapper("land_attr", "KOREPS00047", "KOREPS",
                List.of(Section.repeated("kras.stage_land_attribute", "LAND_SEQNO", cols,
                        List.of("land_mov_ymd"),
                        List.of("parea", "spfc1_area", "pnilp", "calc_jiga", "py_jiga"))),
                List.of(StagePromotionSpec.scopeReplace("kras.stage_land_attribute",
                        "kras.land_attribute", "pnu", cols)),
                false);
    }
}
