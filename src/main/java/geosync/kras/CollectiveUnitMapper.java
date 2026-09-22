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
import static geosync.kras.KrasFieldParsers.putInt;
import static geosync.kras.KrasStagePromotionService.ParentLookup;
import static geosync.kras.KrasStagePromotionService.StagePromotionSpec;

/**
 * collective_unit(§5, "대지권등록부(전유부조회)") 매퍼 — 드릴다운 1단계.
 * PNU만으로 호출 불가: 소재지 5요소 + 집합건물순번(CBLDG_SEQNO)이 필수 입력이다(kras.md §5 In 항목).
 * CBLDG_SEQNO는 §4 collective_building을 먼저 승격해야 알 수 있다 — 그 화면에서 값을 확인한 뒤
 * 여기 "추가 파라미터"에 직접 입력해서 호출한다.
 *
 * 확인 안 된 것:
 * - conn_svc_id 미확인(kras.sync_dataset.service_code=NULL) — connSvcId()가 예외를 던진다.
 * - KRAS 쪽 실제 요청 파라미터명(예: "cbldg_seqno"가 맞는 키인지)도 문서에 없다 — 운영자가
 *   "추가 파라미터(JSON)"에 직접 채워 넣은 값을 그대로 보낸다(추측 금지).
 *
 * 승격에 쓰는 collective_building_id 조회는 요청 파라미터가 아니라, 응답에 그대로 echo되는
 * CBLDG_SEQNO(요청과 동일해야 정상)를 mapper가 직접 stage 행에 남겨서 쓴다 — 운영자가 입력한 키
 * 이름에 기대지 않는다.
 */
@Component
public class CollectiveUnitMapper implements KrasXmlServiceMapper {

    @Override
    public String datasetCode() { return "collective_unit"; }

    @Override
    public String connSvcId() {
        throw new UnsupportedOperationException(
                "collective_unit의 conn_svc_id가 아직 확인되지 않았습니다 — kras.sync_dataset.service_code=NULL(§7). "
                        + "KRAS 연계 담당자 확인 후 채워야 실제 호출이 가능합니다.");
    }

    @Override
    public List<StagePromotionSpec> promotionSpecs() {
        return List.of(
            StagePromotionSpec.parentLookupIdentityMatchUpsert("kras.stage_collective_unit", "kras.collective_unit",
                "unit_id", List.of("dong", "flr", "ho", "sil"),
                List.of("dong", "flr", "ho", "sil", "cbldg_nm", "shr_cnt"),
                new ParentLookup("kras.collective_building", "collective_building_id",
                    List.of("pnu", "cbldg_seqno"), List.of("_pnu", "_cbldg_seqno"), "collective_building_id"))
        );
    }

    @Override
    public MappingResult map(Document xml, String pnu, Map<String, String> extraParams) {
        List<Element> units = elementsOf(xml, "LAND_RGT_RFHS");
        if (units.isEmpty()) {
            throw new IllegalStateException(
                    "collective_unit 응답에 LAND_RGT_RFHS가 없습니다 — 정상(전유부 없음)인지 응답 구조가 문서와 다른지 "
                            + "실제 응답을 먼저 확인하세요.");
        }

        List<String> warnings = new ArrayList<>();
        List<StageRow> rows = new ArrayList<>();
        for (Element u : units) {
            Map<String, Object> row = new LinkedHashMap<>();
            // 부모(collective_building) 조회용 — 응답이 echo하는 CBLDG_SEQNO를 그대로 쓴다.
            // putInt 등이 파싱 실패 시 같은 extra_attributes 맵에 더 얹으므로 먼저 만들어 둔다.
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("_cbldg_seqno", textOf(u, "CBLDG_SEQNO"));
            row.put("extra_attributes", extra);

            row.put("dong", textOf(u, "DONG"));
            row.put("flr", textOf(u, "FLR"));
            row.put("ho", textOf(u, "HO"));
            row.put("sil", textOf(u, "SIL"));
            row.put("cbldg_nm", textOf(u, "CBLDG_NM"));
            putInt(row, "shr_cnt", textOf(u, "SHR_CNT"), warnings);
            rows.add(new StageRow("kras.stage_collective_unit", row));
        }

        return new MappingResult(rows, warnings);
    }

    /** 2-인자 map()은 이 매퍼에서 쓰지 않는다 — extraParams(cbldg_seqno 등) 없이는 호출 자체가 안 된다. */
    @Override
    public MappingResult map(Document xml, String pnu) {
        throw new UnsupportedOperationException("collective_unit은 extraParams가 필요합니다 — map(xml, pnu, extraParams)를 쓰세요.");
    }
}
