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
import static geosync.kras.KrasFieldParsers.putInt;
import static geosync.kras.KrasStagePromotionService.ParentLookup;
import static geosync.kras.KrasStagePromotionService.StagePromotionSpec;

/**
 * land_right(§6, "대지권등록부") 매퍼 — 드릴다운 2단계(collective_unit 다음). PNU+집합건물순번+
 * 동/층/호/실이 전부 필요하다(kras.md §6 In 항목). 응답은 &lt;LAND_RGT_SET&gt; 아래
 * &lt;LAND_RGT&gt;(대지권부분)와 &lt;OWN_HIST&gt;(소유권연혁)가 각각 독립적으로 반복된다(중첩 아님) —
 * OWN_HIST는 kras.unit_ownership_history에 source_service='land_right'로 같이 쌓인다
 * (§9 unit_ownership_history와 테이블을 공유, source_service로 출처만 구별).
 *
 * 확인 안 된 것:
 * - conn_svc_id 미확인(service_code=NULL).
 * - CLOSURE_GBN(폐쇄구분)은 응답 태그가 아니라 요청 입력이라 여기서는 알 수 없다 — extraParams에
 *   "closure_gbn"이 있으면 그 값을, 없으면 "0"(현재)을 쓴다(우리가 보낸 기본 요청을 스스로 기록하는
 *   것이라 추측이 아님 — 응답 필드 의미를 추측하는 것과는 다름).
 * - LAND_RGT 여러 건을 구분하는 자연키를 reljibn(관련지번)으로 가정했다 — 실제 응답으로 검증 필요.
 */
@Component
public class LandRightMapper implements KrasXmlServiceMapper {

    @Override
    public String datasetCode() { return "land_right"; }

    @Override
    public String connSvcId() {
        throw new UnsupportedOperationException(
                "land_right의 conn_svc_id가 아직 확인되지 않았습니다 — kras.sync_dataset.service_code=NULL(§7). "
                        + "KRAS 연계 담당자 확인 후 채워야 실제 호출이 가능합니다.");
    }

    private static ParentLookup unitLookup(String childFkColumn) {
        return new ParentLookup(
                "kras.collective_unit cu JOIN kras.collective_building cb "
                        + "ON cb.collective_building_id = cu.collective_building_id",
                "cu.unit_id",
                List.of("cb.pnu", "cb.cbldg_seqno", "cu.dong", "cu.flr", "cu.ho", "cu.sil"),
                List.of("_pnu", "_cbldg_seqno", "_dong", "_flr", "_ho", "_sil"),
                childFkColumn);
    }

    @Override
    public List<StagePromotionSpec> promotionSpecs() {
        return List.of(
            StagePromotionSpec.parentLookupIdentityMatchUpsert("kras.stage_land_right", "kras.land_right",
                "land_right_id", List.of("reljibn"),
                List.of("pnu", "land_rgt_jibun_rate", "shr_cnt", "reljibn", "closure_gbn"),
                unitLookup("unit_id")),
            StagePromotionSpec.parentLookupIdentityMatchUpsert("kras.stage_unit_ownership_history",
                "kras.unit_ownership_history", "history_id", List.of("source_service", "own_rgt_hist_odrno"),
                List.of("source_service", "own_rgt_hist_odrno", "own_rgt_chg_rsn_cd", "own_rgt_chg_rsn_nm",
                    "own_rgt_jibun", "owner_regno", "owner_nm", "owner_addr", "own_gbn", "own_gbn_nm",
                    "chrg_man_id", "own_rgt_chg_ymd"),
                unitLookup("unit_id"))
        );
    }

    @Override
    public MappingResult map(Document xml, String pnu, Map<String, String> extraParams) {
        List<Element> rights = elementsOf(xml, "LAND_RGT");
        List<Element> hist = elementsOf(xml, "OWN_HIST");
        if (rights.isEmpty() && hist.isEmpty()) {
            throw new IllegalStateException(
                    "land_right 응답에 LAND_RGT/OWN_HIST가 없습니다 — 응답 구조가 문서와 다른지 실제 응답을 먼저 확인하세요.");
        }

        List<String> warnings = new ArrayList<>();
        List<StageRow> rowsOut = new ArrayList<>();

        if (!rights.isEmpty()) {
            Element first = rights.get(0);
            String admSectCd = textOf(first, "ADM_SECT_CD");
            String landLocCd = textOf(first, "LAND_LOC_CD");
            String ledgGbn   = textOf(first, "LEDG_GBN");
            String bobn      = textOf(first, "BOBN");
            String bubn      = textOf(first, "BUBN");
            String respPnu = String.valueOf(admSectCd) + landLocCd + ledgGbn + bobn + bubn;
            if (!pnu.equals(respPnu)) {
                throw new IllegalStateException(
                        "land_right 응답 필지 식별자가 요청 PNU와 다릅니다: 요청=" + pnu + ", 응답조합=" + respPnu);
            }
        }

        String closureGbn = extraParams.getOrDefault("closure_gbn", "0");

        for (Element r : rights) {
            Map<String, Object> row = new LinkedHashMap<>();
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("_cbldg_seqno", textOf(r, "CBLDG_SEQNO"));
            extra.put("_dong", textOf(r, "DONG"));
            extra.put("_flr", textOf(r, "FLR"));
            extra.put("_ho", textOf(r, "HO"));
            extra.put("_sil", textOf(r, "SIL"));
            row.put("extra_attributes", extra);

            row.put("pnu", String.valueOf(textOf(r, "ADM_SECT_CD")) + textOf(r, "LAND_LOC_CD")
                    + textOf(r, "LEDG_GBN") + textOf(r, "BOBN") + textOf(r, "BUBN"));
            row.put("land_rgt_jibun_rate", textOf(r, "LAND_RGT_JIBUN_RATE"));
            putInt(row, "shr_cnt", textOf(r, "SHR_CNT"), warnings);
            row.put("reljibn", textOf(r, "RELJIBN"));
            row.put("closure_gbn", closureGbn);
            rowsOut.add(new StageRow("kras.stage_land_right", row));
        }

        for (Element h : hist) {
            Map<String, Object> row = new LinkedHashMap<>();
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("_cbldg_seqno", textOf(h, "CBLDG_SEQNO"));
            extra.put("_dong", textOf(h, "DONG"));
            extra.put("_flr", textOf(h, "FLR"));
            extra.put("_ho", textOf(h, "HO"));
            extra.put("_sil", textOf(h, "SIL"));
            row.put("extra_attributes", extra);

            row.put("source_service", "land_right");
            row.put("own_rgt_hist_odrno", textOf(h, "OWN_RGT_HIST_ODRNO"));
            row.put("own_rgt_chg_rsn_cd", textOf(h, "OWN_RGT_CHG_RSN_CD"));
            row.put("own_rgt_chg_rsn_nm", textOf(h, "OWN_RGT_CHG_RSN_NM"));
            putDate(row, "own_rgt_chg_ymd", textOf(h, "OWN_RGT_CHG_YMD"), warnings);
            row.put("own_rgt_jibun", textOf(h, "OWN_RGT_JIBUN"));
            row.put("owner_regno", textOf(h, "OWNER_REGNO"));
            row.put("owner_nm", textOf(h, "OWNER_NM"));
            row.put("owner_addr", textOf(h, "OWNER_ADDR"));
            row.put("own_gbn", textOf(h, "OWN_GBN"));
            row.put("own_gbn_nm", textOf(h, "OWN_GBN_NM"));
            row.put("chrg_man_id", textOf(h, "CHRG_MAN_ID"));
            rowsOut.add(new StageRow("kras.stage_unit_ownership_history", row));
        }

        return new MappingResult(rowsOut, warnings);
    }

    /** 2-인자 map()은 쓰지 않는다 — extraParams(cbldg_seqno/dong/flr/ho/sil) 없이는 호출 자체가 안 된다. */
    @Override
    public MappingResult map(Document xml, String pnu) {
        throw new UnsupportedOperationException("land_right은 extraParams가 필요합니다 — map(xml, pnu, extraParams)를 쓰세요.");
    }
}
