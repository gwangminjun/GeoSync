package geosync.kras;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static geosync.common.xml.XmlUtil.textOf;
import static geosync.kras.KrasStagePromotionService.StagePromotionSpec;

/**
 * land_bldg_check(KRAS000101, "토지(건물) 존재 여부 조회") 매퍼 — 반복 구조 없는 평평한 응답.
 * 실제 응답 샘플: docs/reference/kras.md §3 "토지(건물) 존재 여부 조회" 결과 XML.
 * land_info(§6.2)와 달리 날짜/숫자 필드가 없어 파싱 실패 케이스 자체가 없다.
 */
@Component
public class LandBldgCheckMapper implements KrasXmlServiceMapper {

    @Override
    public String datasetCode() { return "land_bldg_check"; }

    @Override
    public String connSvcId() { return "KRAS000101"; }

    @Override
    public List<StagePromotionSpec> promotionSpecs() {
        return List.of(
            StagePromotionSpec.naturalKeyUpsert("kras.stage_parcel", "kras.parcel",
                List.of("pnu"), List.of("pnu", "adm_sect_cd", "land_loc_cd", "ledg_gbn", "bobn", "bubn")),
            StagePromotionSpec.naturalKeyUpsert("kras.stage_land_presence", "kras.land_presence",
                List.of("pnu"), List.of("pnu", "real_gbn", "sect_loc_cd", "adm_sect_nm", "sect_loc_nm", "map_gbn"))
        );
    }

    @Override
    public MappingResult map(Document xml, String pnu) {
        // 응답의 필지 식별 5태그 vs 요청 PNU 직접 분해 — land_info와 같은 원칙(§6.2), 신원이 어긋나면 진행하지 않는다.
        String admSectCd = textOf(xml, "ADM_SECT_CD");
        String landLocCd = textOf(xml, "LAND_LOC_CD");
        String ledgGbn   = textOf(xml, "LEDG_GBN");
        String bobn      = textOf(xml, "BOBN");
        String bubn      = textOf(xml, "BUBN");
        String respPnu = String.valueOf(admSectCd) + landLocCd + ledgGbn + bobn + bubn;
        if (!pnu.equals(respPnu)) {
            throw new IllegalStateException(
                    "land_bldg_check 응답 필지 식별자가 요청 PNU와 다릅니다: 요청=" + pnu + ", 응답조합=" + respPnu);
        }

        Map<String, Object> parcel = new LinkedHashMap<>();
        parcel.put("pnu", pnu);
        parcel.put("adm_sect_cd", admSectCd);
        parcel.put("land_loc_cd", landLocCd);
        parcel.put("ledg_gbn", ledgGbn);
        parcel.put("bobn", bobn);
        parcel.put("bubn", bubn);

        Map<String, Object> presence = new LinkedHashMap<>();
        presence.put("pnu", pnu);
        presence.put("real_gbn", textOf(xml, "REAL_GBN"));
        presence.put("sect_loc_cd", textOf(xml, "SECT_LOC_CD"));
        presence.put("adm_sect_nm", textOf(xml, "ADM_SECT_NM"));
        presence.put("sect_loc_nm", textOf(xml, "SECT_LOC_NM"));
        presence.put("map_gbn", textOf(xml, "MAP_GBN"));

        List<StageRow> rows = List.of(
                new StageRow("kras.stage_parcel", parcel),
                new StageRow("kras.stage_land_presence", presence));
        return new MappingResult(rows, new ArrayList<>());
    }
}
