package geosync.kras;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static geosync.common.xml.XmlUtil.textOf;
import static geosync.kras.KrasStagePromotionService.StagePromotionSpec;
import static geosync.kras.KrasXmlServiceMapper.StageRow;

/**
 * land_info(KRAS000002) 매퍼 — 반복 구조 없는 평평한 응답.
 * 태그 매핑: docs/superpowers/specs/2026-09-18-kras-ingest-implementation-design.md §6.2.
 */
@Component
public class LandInfoMapper implements KrasXmlServiceMapper {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Override
    public String datasetCode() { return "land_info"; }

    @Override
    public String connSvcId() { return "KRAS000002"; }

    /** 부모(parcel)→자식(land_register, land_owner) 순서. org_cd는 넣지 않는다(guard_business_row가 채움). */
    @Override
    public List<StagePromotionSpec> promotionSpecs() {
        return List.of(
            StagePromotionSpec.naturalKeyUpsert("kras.stage_parcel", "kras.parcel",
                List.of("pnu"), List.of("pnu", "adm_sect_cd", "land_loc_cd", "ledg_gbn", "bobn", "bubn")),
            StagePromotionSpec.naturalKeyUpsert("kras.stage_land_register", "kras.land_register",
                List.of("pnu"), List.of("pnu", "jimok", "jimok_nm", "parea", "grd", "grd_ymd",
                    "land_mov_rsn_cd", "land_mov_rsn_cd_nm", "land_mov_ymd", "ledg_cntrst_cnf_gbn",
                    "biz_act_ntc_gbn", "map_gbn", "land_last_hist_odrno", "own_rgt_last_hist_odrno",
                    "scale", "scale_nm", "doho", "jiga_base_mon", "pann_jiga", "last_jibn", "last_bu",
                    "lastbobn", "lastbubn", "land_mov_chrg_man_id", "own_rgt_chg_chrg_man_id",
                    "bldg_gbn_no", "land_move_rell_jibn")),
            StagePromotionSpec.naturalKeyUpsert("kras.stage_land_owner", "kras.land_owner",
                List.of("pnu"), List.of("pnu", "owner_nm", "dregno", "own_gbn", "own_gbn_nm", "shr_cnt",
                    "owner_addr", "own_rgt_chg_rsn_cd", "own_rgt_chg_rsn_cd_nm", "owndymd", "availability"))
        );
    }

    @Override
    public MappingResult map(Document xml, String pnu) {
        List<String> warnings = new ArrayList<>();

        // 응답의 필지 식별 5태그 vs 요청 PNU 직접 분해 — 신원이 어긋나면 더 진행하지 않는다(§6.2).
        String admSectCd = textOf(xml, "ADM_SECT_CD");
        String landLocCd = textOf(xml, "LAND_LOC_CD");
        String ledgGbn   = textOf(xml, "LEDG_GBN");
        String bobn      = textOf(xml, "BOBN");
        String bubn      = textOf(xml, "BUBN");
        String respPnu = String.valueOf(admSectCd) + landLocCd + ledgGbn + bobn + bubn;
        if (!pnu.equals(respPnu)) {
            throw new IllegalStateException(
                    "land_info 응답 필지 식별자가 요청 PNU와 다릅니다: 요청=" + pnu + ", 응답조합=" + respPnu);
        }

        Map<String, Object> parcel = new LinkedHashMap<>();
        parcel.put("pnu", pnu);
        parcel.put("adm_sect_cd", admSectCd);
        parcel.put("land_loc_cd", landLocCd);
        parcel.put("ledg_gbn", ledgGbn);
        parcel.put("bobn", bobn);
        parcel.put("bubn", bubn);

        Map<String, Object> register = new LinkedHashMap<>();
        register.put("pnu", pnu);
        register.put("jimok", textOf(xml, "JIMOK"));
        register.put("jimok_nm", textOf(xml, "JIMOK_NM"));
        putDecimal(register, "parea", textOf(xml, "PAREA"), warnings);
        register.put("grd", textOf(xml, "GRD"));
        putDate(register, "grd_ymd", textOf(xml, "GRD_YMD"), warnings);
        register.put("land_mov_rsn_cd", textOf(xml, "LAND_MOV_RSN_CD"));
        register.put("land_mov_rsn_cd_nm", textOf(xml, "LAND_MOV_RSN_CD_NM"));
        putDate(register, "land_mov_ymd", textOf(xml, "LAND_MOV_YMD"), warnings);
        register.put("ledg_cntrst_cnf_gbn", textOf(xml, "LEDG_CNTRST_CNF_GBN"));
        register.put("biz_act_ntc_gbn", textOf(xml, "BIZ_ACT_NTC_GBN"));
        register.put("map_gbn", textOf(xml, "MAP_GBN"));
        register.put("land_last_hist_odrno", textOf(xml, "LAND_LAST_HIST_ODRNO"));
        register.put("own_rgt_last_hist_odrno", textOf(xml, "OWN_RGT_LAST_HIST_ODRNO"));
        register.put("scale", textOf(xml, "SCALE"));
        register.put("scale_nm", textOf(xml, "SCALE_NM"));
        register.put("doho", textOf(xml, "DOHO"));
        register.put("jiga_base_mon", textOf(xml, "JIGA_BASE_MON"));
        putDecimal(register, "pann_jiga", textOf(xml, "PANN_JIGA"), warnings);
        register.put("last_jibn", textOf(xml, "LAST_JIBN"));
        register.put("last_bu", textOf(xml, "LAST_BU"));
        register.put("lastbobn", textOf(xml, "LASTBOBN"));
        register.put("lastbubn", textOf(xml, "LASTBUBN"));
        register.put("land_mov_chrg_man_id", textOf(xml, "LAND_MOV_CHRG_MAN_ID"));
        register.put("own_rgt_chg_chrg_man_id", textOf(xml, "OWN_RGT_CHG_CHRG_MAN_ID"));
        // HWP NUMBER(28)이지만 varchar로 저장 — Java에서도 문자열로만 다루고 숫자 변환 금지(§6.2 비고).
        register.put("bldg_gbn_no", textOf(xml, "BLDG_GBN_NO"));
        register.put("land_move_rell_jibn", textOf(xml, "LAND_MOVE_RELL_JIBN"));

        Map<String, Object> owner = new LinkedHashMap<>();
        owner.put("pnu", pnu);
        owner.put("owner_nm", textOf(xml, "OWNER_NM"));
        owner.put("dregno", textOf(xml, "DREGNO"));
        owner.put("own_gbn", textOf(xml, "OWN_GBN"));
        owner.put("own_gbn_nm", textOf(xml, "OWN_GBN_NM"));
        putInt(owner, "shr_cnt", textOf(xml, "SHR_CNT"), warnings);
        owner.put("owner_addr", textOf(xml, "OWNER_ADDR"));
        owner.put("own_rgt_chg_rsn_cd", textOf(xml, "OWN_RGT_CHG_RSN_CD"));
        owner.put("own_rgt_chg_rsn_cd_nm", textOf(xml, "OWN_RGT_CHG_RSN_CD_NM"));
        putDate(owner, "owndymd", textOf(xml, "OWNDYMD"), warnings);
        // §13: 소유내역 옵션 파라미터명이 확인되기 전까지는 항상 NOT_REQUESTED.
        owner.put("availability", "NOT_REQUESTED");

        List<StageRow> rows = List.of(
                new StageRow("kras.stage_parcel", parcel),
                new StageRow("kras.stage_land_register", register),
                new StageRow("kras.stage_land_owner", owner));
        return new MappingResult(rows, warnings);
    }

    /** YYYYMMDD/YYYY-MM-DD만 정상 날짜로 인정한다 — 그 외 형식을 NULL로 조용히 삼키지 않는다. */
    private void putDate(Map<String, Object> row, String col, String raw, List<String> warnings) {
        if (raw == null || raw.isBlank()) { row.put(col, null); return; }
        String normalized = raw.length() == 10 ? raw.replace("-", "") : raw;
        try {
            row.put(col, LocalDate.parse(normalized, YYYYMMDD));
        } catch (DateTimeParseException e) {
            row.put(col, null);
            markUnparsed(row, col, raw, warnings);
        }
    }

    private void putDecimal(Map<String, Object> row, String col, String raw, List<String> warnings) {
        if (raw == null || raw.isBlank()) { row.put(col, null); return; }
        try {
            row.put(col, new BigDecimal(raw.trim()));
        } catch (NumberFormatException e) {
            row.put(col, null);
            markUnparsed(row, col, raw, warnings);
        }
    }

    private void putInt(Map<String, Object> row, String col, String raw, List<String> warnings) {
        if (raw == null || raw.isBlank()) { row.put(col, null); return; }
        try {
            row.put(col, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            row.put(col, null);
            markUnparsed(row, col, raw, warnings);
        }
    }

    @SuppressWarnings("unchecked")
    private void markUnparsed(Map<String, Object> row, String col, String raw, List<String> warnings) {
        Map<String, Object> extra = (Map<String, Object>) row.computeIfAbsent(
                "extra_attributes", k -> new LinkedHashMap<String, Object>());
        extra.put(col + "_raw", raw);
        warnings.add(col + " 파싱 실패, 원문 보존: " + raw);
    }
}
