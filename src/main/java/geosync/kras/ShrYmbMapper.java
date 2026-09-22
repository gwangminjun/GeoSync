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
import static geosync.kras.KrasStagePromotionService.StagePromotionSpec;

/**
 * shr_ymb(KRAS000003, "공유지연명부") 매퍼 — 공유인 수만큼 &lt;SHR_YMB_SET&gt;&lt;SHR_YMB&gt;가 반복된다
 * (kras.md §2 결과 XML). land_share는 guard_business_row의 DELETE 차단 목록에 없고 매 조회가
 * "그 시점 전체 목록"을 반환하는 구조라 패턴 B(범위 전체 교체, §8.3)로 승격한다.
 *
 * 확인 안 된 점 2가지:
 * 1) "말소자 포함유무"(요청 필수 항목, kras.md 225행)의 실제 쿼리 파라미터명이 문서/코드 어디에도 없다 —
 *    지금은 안 보내고 호출한다. 게이트웨이가 필수로 거부하면 그때 에러 메시지로 실제 파라미터명 단서를
 *    얻을 수 있다(추측으로 아무 이름이나 넣지 않는다).
 * 2) kras.md 결과 XML 예시(254~289행)에서 SHR_YMB 항목마다 BOBN 값이 다르게 나온다(9999 vs 0104) —
 *    같은 PNU 조회 결과인데 왜 다른지 문서만으로는 알 수 없다. land_share 테이블 자체는 pnu 컬럼만
 *    쓰고 ADM_SECT_CD 등은 저장하지 않으므로(DDL 확인) 실제 저장에는 영향 없지만, 신원 대조는 첫
 *    항목 기준으로만 하고 이 불일치는 실제 응답으로 확인해야 한다.
 */
@Component
public class ShrYmbMapper implements KrasXmlServiceMapper {

    @Override
    public String datasetCode() { return "shr_ymb"; }

    @Override
    public String connSvcId() { return "KRAS000003"; }

    @Override
    public List<StagePromotionSpec> promotionSpecs() {
        return List.of(
            StagePromotionSpec.naturalKeyUpsert("kras.stage_parcel", "kras.parcel",
                List.of("pnu"), List.of("pnu", "adm_sect_cd", "land_loc_cd", "ledg_gbn", "bobn", "bubn")),
            StagePromotionSpec.scopeReplace("kras.stage_land_share", "kras.land_share", "pnu",
                List.of("pnu", "shr_seqno", "own_rgt_chg_rsn_cd", "own_rgt_chg_rsn_nm", "own_rgt_chg_ymd",
                    "owner_regno", "owner_nm", "owner_addr", "own_rgt_jibun", "own_rgt_chg_del_ymd",
                    "own_gbn", "own_gbn_nm", "parea"))
        );
    }

    @Override
    public MappingResult map(Document xml, String pnu) {
        List<Element> shares = elementsOf(xml, "SHR_YMB");
        if (shares.isEmpty()) {
            throw new IllegalStateException(
                    "shr_ymb 응답에 SHR_YMB가 없습니다 — 정상(공유인 없음)인지 응답 구조가 문서와 다른지 "
                            + "실제 응답을 먼저 확인하세요.");
        }

        Element first = shares.get(0);
        String admSectCd = textOf(first, "ADM_SECT_CD");
        String landLocCd = textOf(first, "LAND_LOC_CD");
        String ledgGbn   = textOf(first, "LEDG_GBN");
        String bobn      = textOf(first, "BOBN");
        String bubn      = textOf(first, "BUBN");
        String respPnu = String.valueOf(admSectCd) + landLocCd + ledgGbn + bobn + bubn;
        if (!pnu.equals(respPnu)) {
            throw new IllegalStateException(
                    "shr_ymb 응답 필지 식별자가 요청 PNU와 다릅니다: 요청=" + pnu + ", 응답조합=" + respPnu);
        }

        Map<String, Object> parcel = new LinkedHashMap<>();
        parcel.put("pnu", pnu);
        parcel.put("adm_sect_cd", admSectCd);
        parcel.put("land_loc_cd", landLocCd);
        parcel.put("ledg_gbn", ledgGbn);
        parcel.put("bobn", bobn);
        parcel.put("bubn", bubn);

        List<String> warnings = new ArrayList<>();
        List<StageRow> rows = new ArrayList<>();
        rows.add(new StageRow("kras.stage_parcel", parcel));
        for (Element s : shares) {
            Map<String, Object> row = new LinkedHashMap<>();
            // land_share.pnu는 응답 항목별 태그가 아니라 요청 PNU 그대로 쓴다 — 테이블에 ADM_SECT_CD 등
            // 식별 컬럼이 없고(DDL 확인), 이 값이 "어느 필지의 공유인인가"를 뜻하기 때문.
            row.put("pnu", pnu);
            row.put("shr_seqno", textOf(s, "SHR_SEQNO"));
            row.put("own_rgt_chg_rsn_cd", textOf(s, "OWN_RGT_CHG_RSN_CD"));
            row.put("own_rgt_chg_rsn_nm", textOf(s, "OWN_RGT_CHG_RSN_NM"));
            putDate(row, "own_rgt_chg_ymd", textOf(s, "OWN_RGT_CHG_YMD"), warnings);
            row.put("owner_regno", textOf(s, "OWNER_REGNO"));
            row.put("owner_nm", textOf(s, "OWNER_NM"));
            row.put("owner_addr", textOf(s, "OWNER_ADDR"));
            row.put("own_rgt_jibun", textOf(s, "OWN_RGT_JIBUN"));
            putDate(row, "own_rgt_chg_del_ymd", textOf(s, "OWN_RGT_CHG_DEL_YMD"), warnings);
            row.put("own_gbn", textOf(s, "OWN_GBN"));
            row.put("own_gbn_nm", textOf(s, "OWN_GBN_NM"));
            putDecimal(row, "parea", textOf(s, "PAREA"), warnings);
            rows.add(new StageRow("kras.stage_land_share", row));
        }

        return new MappingResult(rows, warnings);
    }
}
