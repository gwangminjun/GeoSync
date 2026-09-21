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
import static geosync.kras.KrasStagePromotionService.StagePromotionSpec;

/**
 * collective_building("대지권등록부(건물조회)") 매퍼 — PNU 하나에 집합건물이 여러 동 있으면
 * <LAND_RGT_BLDG_INFO_LIST> 안에 <LAND_RGT_BLDG_INFO>가 반복된다(kras.md §4 결과 XML).
 * 승격은 패턴 C(신원 매칭 후 UPSERT, pnu+cbldg_seqno) — collective_building_id는 collective_unit이
 * 참조할 수 있어 재조회 때마다 새 ID를 만들면 안 된다(설계 §8.4/§8.1).
 *
 * conn_svc_id 미확정: kras.sync_dataset에 collective_building은 service_code=NULL로 등록돼 있고,
 * 이 저장소(kras.md, application.yml, GatewayPaths) 어디에도 실제 값이 없다 — KRAS 연계 담당자 확인 후
 * connSvcId()의 예외를 실제 값으로 바꾸면 이 매퍼는 그대로 동작한다(파싱/승격 로직은 이미 완성).
 */
@Component
public class CollectiveBuildingMapper implements KrasXmlServiceMapper {

    @Override
    public String datasetCode() { return "collective_building"; }

    @Override
    public String connSvcId() {
        throw new UnsupportedOperationException(
                "collective_building의 conn_svc_id가 아직 확인되지 않았습니다 — "
                        + "kras.sync_dataset.service_code=NULL(§7). KRAS 연계 담당자 확인 후 채워야 실제 호출이 가능합니다.");
    }

    @Override
    public List<StagePromotionSpec> promotionSpecs() {
        return List.of(
            StagePromotionSpec.naturalKeyUpsert("kras.stage_parcel", "kras.parcel",
                List.of("pnu"), List.of("pnu", "adm_sect_cd", "land_loc_cd", "ledg_gbn", "bobn", "bubn")),
            StagePromotionSpec.identityMatchUpsert("kras.stage_collective_building", "kras.collective_building",
                "collective_building_id", List.of("pnu", "cbldg_seqno"),
                List.of("pnu", "cbldg_seqno", "cbldg_nm"))
        );
    }

    @Override
    public MappingResult map(Document xml, String pnu) {
        List<Element> buildings = elementsOf(xml, "LAND_RGT_BLDG_INFO");
        if (buildings.isEmpty()) {
            // kras.md에 "0건" 응답 샘플이 없어 이게 정상(집합건물 없음)인지 응답 구조가 다른 건지 근거가 없다 —
            // 추측으로 빈 목록을 정상 처리하지 않는다(명시적 근거 없이 상태 확정 금지 원칙).
            throw new IllegalStateException(
                    "collective_building 응답에 LAND_RGT_BLDG_INFO가 없습니다 — 정상(집합건물 없음)인지 "
                            + "응답 구조가 문서와 다른지 실제 응답을 먼저 확인하세요.");
        }

        // 필지 식별 5태그는 모든 반복 항목에 동일하게 들어있다 — 첫 항목에서 읽어 PNU와 대조한다.
        Element first = buildings.get(0);
        String admSectCd = textOf(first, "ADM_SECT_CD");
        String landLocCd = textOf(first, "LAND_LOC_CD");
        String ledgGbn   = textOf(first, "LEDG_GBN");
        String bobn      = textOf(first, "BOBN");
        String bubn      = textOf(first, "BUBN");
        String respPnu = String.valueOf(admSectCd) + landLocCd + ledgGbn + bobn + bubn;
        if (!pnu.equals(respPnu)) {
            throw new IllegalStateException(
                    "collective_building 응답 필지 식별자가 요청 PNU와 다릅니다: 요청=" + pnu + ", 응답조합=" + respPnu);
        }

        Map<String, Object> parcel = new LinkedHashMap<>();
        parcel.put("pnu", pnu);
        parcel.put("adm_sect_cd", admSectCd);
        parcel.put("land_loc_cd", landLocCd);
        parcel.put("ledg_gbn", ledgGbn);
        parcel.put("bobn", bobn);
        parcel.put("bubn", bubn);

        List<StageRow> rows = new ArrayList<>();
        rows.add(new StageRow("kras.stage_parcel", parcel));
        for (Element b : buildings) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pnu", pnu);
            row.put("cbldg_seqno", textOf(b, "CBLDG_SEQNO"));
            row.put("cbldg_nm", textOf(b, "CBLDG_NM"));
            rows.add(new StageRow("kras.stage_collective_building", row));
        }

        return new MappingResult(rows, new ArrayList<>());
    }
}
