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
import static geosync.kras.KrasStagePromotionService.ParentLookup;
import static geosync.kras.KrasStagePromotionService.StagePromotionSpec;

/**
 * unit_ownership_history(§9, "집합건물소유권연혁") 매퍼 — 드릴다운 2단계, land_right(§6)과 같은
 * 깊이지만 별개 서비스다. kras.md §9 In 항목을 보면 이 서비스는 **본번/부번(PNU)을 아예 안 받는다** —
 * 소재지코드 + 집합건물일련번호 + 동/층/호/실만 받는다.
 *
 * 그런데 이 프레임워크의 ingest()는 항상 19자리 PNU를 요구한다(scope_key, 부모 조회용 _pnu 등에 씀).
 * 그래서 운영자가 이 전유부가 속한 필지의 PNU를 "우리 쪽 장부 정리용"으로 그대로 입력하게 한다 —
 * KRAS에 실제로 보내는 값(krasApiClient.query()가 pnu를 5개 필드로 분해해 보냄)에 원래 이 서비스가
 * 원치 않는 BOBN/BUBN까지 같이 전송될 수 있다는 뜻이다. 이게 문제가 되는지는 실제 호출 전까지 알 수 없다
 * — 추측으로 만들지 않고 이 사실을 그대로 남겨둔다.
 *
 * 승격 대상 kras.unit_ownership_history는 land_right(§6)의 OWN_HIST와 테이블을 공유한다 —
 * source_service='unit_ownership_history'로 구별.
 */
@Component
public class UnitOwnershipHistoryMapper implements KrasXmlServiceMapper {

    @Override
    public String datasetCode() { return "unit_ownership_history"; }

    @Override
    public String connSvcId() {
        throw new UnsupportedOperationException(
                "unit_ownership_history의 conn_svc_id가 아직 확인되지 않았습니다 — "
                        + "kras.sync_dataset.service_code=NULL(§7). KRAS 연계 담당자 확인 후 채워야 실제 호출이 가능합니다.");
    }

    @Override
    public List<StagePromotionSpec> promotionSpecs() {
        ParentLookup unitLookup = new ParentLookup(
                "kras.collective_unit cu JOIN kras.collective_building cb "
                        + "ON cb.collective_building_id = cu.collective_building_id",
                "cu.unit_id",
                List.of("cb.pnu", "cb.cbldg_seqno", "cu.dong", "cu.flr", "cu.ho", "cu.sil"),
                List.of("_pnu", "_cbldg_seqno", "_dong", "_flr", "_ho", "_sil"),
                "unit_id");
        return List.of(
            StagePromotionSpec.parentLookupIdentityMatchUpsert("kras.stage_unit_ownership_history",
                "kras.unit_ownership_history", "history_id", List.of("source_service", "own_rgt_hist_odrno"),
                List.of("source_service", "own_rgt_hist_odrno", "own_rgt_chg_rsn_cd", "own_rgt_chg_rsn_nm",
                    "own_rgt_jibun", "owner_regno", "owner_nm", "owner_addr", "own_gbn", "own_gbn_nm",
                    "chrg_man_id", "own_rgt_chg_ymd", "del_ymd"),
                unitLookup)
        );
    }

    @Override
    public MappingResult map(Document xml, String pnu, Map<String, String> extraParams) {
        List<Element> hist = elementsOf(xml, "CBLDG_OWN_HIST");
        if (hist.isEmpty()) {
            throw new IllegalStateException(
                    "unit_ownership_history 응답에 CBLDG_OWN_HIST가 없습니다 — 정상(연혁 없음)인지 응답 구조가 문서와 "
                            + "다른지 실제 응답을 먼저 확인하세요.");
        }

        List<String> warnings = new ArrayList<>();
        List<StageRow> rows = new ArrayList<>();
        for (Element h : hist) {
            Map<String, Object> row = new LinkedHashMap<>();
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("_cbldg_seqno", textOf(h, "CBLDG_SEQNO"));
            extra.put("_dong", textOf(h, "DONG"));
            extra.put("_flr", textOf(h, "FLR"));
            extra.put("_ho", textOf(h, "HO"));
            extra.put("_sil", textOf(h, "SIL"));
            row.put("extra_attributes", extra);

            row.put("source_service", "unit_ownership_history");
            row.put("own_rgt_hist_odrno", textOf(h, "OWN_RGT_HIST_ODRNO"));
            row.put("own_rgt_chg_rsn_cd", textOf(h, "OWN_RGT_CHG_RSN_CD"));
            row.put("own_rgt_chg_rsn_nm", textOf(h, "OWN_RGT_CHG_RSN_NM"));
            putDate(row, "own_rgt_chg_ymd", textOf(h, "OWN_RGT_CHG_YMD"), warnings);
            row.put("own_rgt_jibun", textOf(h, "OWN_RGT_JIBUN"));
            row.put("owner_regno", textOf(h, "DREGNO"));
            row.put("owner_nm", textOf(h, "OWNER_NM"));
            row.put("owner_addr", textOf(h, "OWNER_ADDR"));
            row.put("own_gbn", textOf(h, "OWN_GBN"));
            row.put("own_gbn_nm", textOf(h, "OWN_GBN_NM"));
            row.put("chrg_man_id", textOf(h, "CHRG_MAN_ID"));
            putDate(row, "del_ymd", textOf(h, "DEL_YMD"), warnings);
            rows.add(new StageRow("kras.stage_unit_ownership_history", row));
        }

        return new MappingResult(rows, warnings);
    }

    /** 2-인자 map()은 쓰지 않는다 — extraParams(cbldg_seqno/dong/flr/ho/sil) 없이는 호출 자체가 안 된다. */
    @Override
    public MappingResult map(Document xml, String pnu) {
        throw new UnsupportedOperationException(
                "unit_ownership_history는 extraParams가 필요합니다 — map(xml, pnu, extraParams)를 쓰세요.");
    }
}
