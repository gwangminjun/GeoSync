package geosync.kras;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static geosync.common.xml.XmlUtil.elementsOf;
import static geosync.common.xml.XmlUtil.textOf;
import static geosync.kras.KrasFieldParsers.putDate;
import static geosync.kras.KrasFieldParsers.putDecimal;
import static geosync.kras.KrasFieldParsers.putInt;
import static geosync.kras.KrasStagePromotionService.ChildSpec;
import static geosync.kras.KrasStagePromotionService.StagePromotionSpec;

/**
 * integrated_building(§13, "건물통합정보") 매퍼 — land_mov_hist와 같은 모양의 중첩 반복:
 * &lt;GIS_BLDG_INTERG_INFO_SET&gt;의 &lt;GIS_BLDG_INTERG_INFO&gt;가 건물 개수만큼 반복되고,
 * 그 안에 &lt;RELJIBUN&gt;이 관련지번 개수만큼 또 반복된다(kras.md §13). 승격은 land_mov_hist와
 * 동일하게 SCOPE_REPLACE_WITH_CHILDREN — 건물(integrated_building)을 INSERT RETURNING으로
 * 새 building_id를 받고, 그 ID로 building_parcel(관련지번)을 잇달아 INSERT한다.
 *
 * building_parcel.relation_type이 MAIN/RELATED 두 값인데 응답 반복 그룹(RELJIBUN)은 RELATED에
 * 해당하는 관련지번만 준다 — 그 건물 자신이 서 있는 필지는 MAIN으로 별도 1행을 만들어 채운다
 * (응답 필드가 아니라 이 매퍼가 구성한 것, 값은 요청 PNU).
 *
 * 이 응답은 다른 서비스처럼 5태그(ADM_SECT_CD 등) 대신 PNU를 통째로 한 태그로 준다. land_info와
 * 동일하게 이 값이 요청 PNU와 다르면 예외로 막는다 — 패턴 승격(SCOPE_REPLACE_WITH_CHILDREN)의
 * DELETE 범위가 요청 PNU 하나라는 전제이기도 해서, 불일치를 조용히 넘기면 승격 범위가 틀어진다.
 */
@Component
public class IntegratedBuildingMapper implements KrasXmlServiceMapper {

    @Override
    public String datasetCode() { return "integrated_building"; }

    @Override
    public String connSvcId() {
        throw new UnsupportedOperationException(
                "integrated_building의 conn_svc_id가 아직 확인되지 않았습니다 — "
                        + "kras.sync_dataset.service_code=NULL(§7). KRAS 연계 담당자 확인 후 채워야 실제 호출이 가능합니다.");
    }

    @Override
    public List<StagePromotionSpec> promotionSpecs() {
        return List.of(
            StagePromotionSpec.scopeReplaceWithChildren("kras.stage_integrated_building", "kras.integrated_building",
                "pnu", "building_id",
                List.of("pnu", "land_loc_nm", "jibn", "ufid", "bldg_nm", "dong", "bldg_gbn_no", "pnu_org",
                    "vio_bldg_yn", "stru_cd", "stru_nm", "main_use_cd", "main_use_nm", "main_sub_gbn",
                    "main_sub_gbn_nm", "bndr_info_src_cd", "bndr_info_src_nm", "attr_info_src_cd",
                    "attr_info_src_nm", "km_name", "km_name_src", "km_name_src_nm", "permi_num", "use_apr_num",
                    "etc_cd", "etc_cd_nm", "ch_jibun", "ch_jibun_nm", "mat_cd", "s_mat", "s_mat_nm",
                    "bu_mat_gb_cd", "bu_mat_gb_nm", "larea", "barea", "garea", "blr", "fsi", "hgt",
                    "uflr", "bflr", "bld_cnt", "ais_cnt", "sub_info_cnt", "use_aprv_ymd", "regist_day", "nem_date"),
                new ChildSpec("kras.stage_building_parcel", "kras.building_parcel", "building_id", "relation_no",
                    List.of("pnu", "relation_type", "rel_jibun")))
        );
    }

    @Override
    public MappingResult map(Document xml, String pnu) {
        List<Element> buildings = elementsOf(xml, "GIS_BLDG_INTERG_INFO");
        if (buildings.isEmpty()) {
            throw new IllegalStateException(
                    "integrated_building 응답에 GIS_BLDG_INTERG_INFO가 없습니다 — 정상(건물 없음)인지 응답 구조가 "
                            + "문서와 다른지 실제 응답을 먼저 확인하세요.");
        }

        List<String> warnings = new ArrayList<>();
        List<StageRow> rows = new ArrayList<>();
        int buildingIdx = 1;
        for (Element b : buildings) {
            String bldgPnu = textOf(b, "PNU");
            if (!pnu.equals(bldgPnu)) {
                throw new IllegalStateException(
                        "integrated_building 응답의 PNU가 요청 PNU와 다릅니다: 요청=" + pnu + ", 응답=" + bldgPnu);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pnu", pnu);
            row.put("land_loc_nm", textOf(b, "LAND_LOC_NM"));
            row.put("jibn", textOf(b, "JIBN"));
            row.put("ufid", textOf(b, "UFID"));
            row.put("bldg_nm", textOf(b, "BLDG_NM"));
            row.put("dong", textOf(b, "DONG"));
            row.put("bldg_gbn_no", textOf(b, "BLDG_GBN_NO"));
            row.put("pnu_org", textOf(b, "PNU_ORG"));
            row.put("vio_bldg_yn", textOf(b, "VIO_BLDG_YN"));
            row.put("stru_cd", textOf(b, "STRU_CD"));
            row.put("stru_nm", textOf(b, "STRU_NM"));
            row.put("main_use_cd", textOf(b, "MAIN_USE_CD"));
            row.put("main_use_nm", textOf(b, "MAIN_USE_NM"));
            row.put("main_sub_gbn", textOf(b, "MAIN_SUB_GBN"));
            row.put("main_sub_gbn_nm", textOf(b, "MAIN_SUB_GBN_NM"));
            row.put("bndr_info_src_cd", textOf(b, "BNDR_INFO_SRC_CD"));
            row.put("bndr_info_src_nm", textOf(b, "BNDR_INFO_SRC_NM"));
            row.put("attr_info_src_cd", textOf(b, "ATTR_INFO_SRC_CD"));
            row.put("attr_info_src_nm", textOf(b, "ATTR_INFO_SRC_NM"));
            row.put("km_name", textOf(b, "KM_NAME"));
            row.put("km_name_src", textOf(b, "KM_NAME_SRC"));
            row.put("km_name_src_nm", textOf(b, "KM_NAME_SRC_NM"));
            row.put("permi_num", textOf(b, "PERMI_NUM"));
            row.put("use_apr_num", textOf(b, "USE_APR_NUM"));
            row.put("etc_cd", textOf(b, "ETC_CD"));
            row.put("etc_cd_nm", textOf(b, "ETC_CD_NM"));
            row.put("ch_jibun", textOf(b, "CH_JIBUN"));
            row.put("ch_jibun_nm", textOf(b, "CH_JIBUN_NM"));
            row.put("mat_cd", textOf(b, "MAT_CD"));
            row.put("s_mat", textOf(b, "S_MAT"));
            row.put("s_mat_nm", textOf(b, "S_MAT_NM"));
            row.put("bu_mat_gb_cd", textOf(b, "BU_MAT_GB_CD"));
            row.put("bu_mat_gb_nm", textOf(b, "BU_MAT_GB_NM"));
            // 면적/비율 계열은 응답이 천단위 구분자(,)를 붙여 준다(kras.md 샘플 확인) — 숫자 파싱 전 제거.
            putDecimal(row, "larea", stripComma(textOf(b, "LAREA")), warnings);
            putDecimal(row, "barea", stripComma(textOf(b, "BAREA")), warnings);
            putDecimal(row, "garea", stripComma(textOf(b, "GAREA")), warnings);
            putDecimal(row, "blr", stripComma(textOf(b, "BLR")), warnings);
            putDecimal(row, "fsi", stripComma(textOf(b, "FSI")), warnings);
            putDecimal(row, "hgt", stripComma(textOf(b, "HGT")), warnings);
            putInt(row, "uflr", textOf(b, "UFLR"), warnings);
            putInt(row, "bflr", textOf(b, "BFLR"), warnings);
            putInt(row, "bld_cnt", textOf(b, "BLD_CNT"), warnings);
            putInt(row, "ais_cnt", textOf(b, "AIS_CNT"), warnings);
            putInt(row, "sub_info_cnt", textOf(b, "SUB_INFO_CNT"), warnings);
            putDate(row, "use_aprv_ymd", textOf(b, "USE_APRV_YMD"), warnings);
            putDate(row, "regist_day", textOf(b, "REGIST_DAY"), warnings);
            putDate(row, "nem_date", textOf(b, "NEM_DATE"), warnings);
            rows.add(new StageRow("kras.stage_integrated_building", row));

            // MAIN: 건물 자신이 서 있는 필지 — 응답 필드가 아니라 요청 PNU로 구성.
            Map<String, Object> mainRow = new LinkedHashMap<>();
            mainRow.put("pnu", pnu);
            mainRow.put("relation_type", "MAIN");
            mainRow.put("rel_jibun", null);
            mainRow.put("parent_record_no", (long) buildingIdx);
            rows.add(new StageRow("kras.stage_building_parcel", mainRow));

            // RELATED: RELJIBUN 반복.
            for (Element rel : elementsOf(b, "RELJIBUN")) {
                Map<String, Object> relRow = new LinkedHashMap<>();
                relRow.put("pnu", null);
                relRow.put("relation_type", "RELATED");
                relRow.put("rel_jibun", textOf(rel, "REL_JIBUN"));
                relRow.put("parent_record_no", (long) buildingIdx);
                rows.add(new StageRow("kras.stage_building_parcel", relRow));
            }
            buildingIdx++;
        }

        return new MappingResult(rows, warnings);
    }

    private static String stripComma(String raw) {
        return raw == null ? null : raw.replace(",", "");
    }
}
