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
import static geosync.kras.KrasStagePromotionService.StagePromotionSpec;

/**
 * own_rgt_hist(KRAS000007, "소유권변동연혁") 매퍼 — shr_ymb와 같은 모양(단일 레벨 반복,
 * 자식 테이블 없음, 자연키 UNIQUE 없는 surrogate PK) — 패턴 B(§8.3)로 승격.
 * kras.md §8 결과 XML 기준.
 */
@Component
public class OwnRgtHistMapper implements KrasXmlServiceMapper {

    @Override
    public String datasetCode() { return "own_rgt_hist"; }

    @Override
    public String connSvcId() { return "KRAS000007"; }

    @Override
    public List<StagePromotionSpec> promotionSpecs() {
        return List.of(
            StagePromotionSpec.naturalKeyUpsert("kras.stage_parcel", "kras.parcel",
                List.of("pnu"), List.of("pnu", "adm_sect_cd", "land_loc_cd", "ledg_gbn", "bobn", "bubn")),
            StagePromotionSpec.scopeReplace("kras.stage_land_ownership_history", "kras.land_ownership_history", "pnu",
                List.of("pnu", "own_rgt_chg_hist_odrno", "dodrno", "own_rgt_chg_rsn_cd", "own_rgt_chg_rsn_cd_nm",
                    "dregno", "owner_nm", "own_gbn", "own_gbn_nm", "own_rgt_chg_chrg_man_id", "dymd", "shr_cnt"))
        );
    }

    @Override
    public MappingResult map(Document xml, String pnu) {
        List<Element> hist = elementsOf(xml, "OWN_RGT_HIST");
        if (hist.isEmpty()) {
            throw new IllegalStateException(
                    "own_rgt_hist 응답에 OWN_RGT_HIST가 없습니다 — 정상(연혁 없음)인지 응답 구조가 문서와 다른지 "
                            + "실제 응답을 먼저 확인하세요.");
        }

        Element first = hist.get(0);
        String admSectCd = textOf(first, "ADM_SECT_CD");
        String landLocCd = textOf(first, "LAND_LOC_CD");
        String ledgGbn   = textOf(first, "LEDG_GBN");
        String bobn      = textOf(first, "BOBN");
        String bubn      = textOf(first, "BUBN");
        String respPnu = String.valueOf(admSectCd) + landLocCd + ledgGbn + bobn + bubn;
        if (!pnu.equals(respPnu)) {
            throw new IllegalStateException(
                    "own_rgt_hist 응답 필지 식별자가 요청 PNU와 다릅니다: 요청=" + pnu + ", 응답조합=" + respPnu);
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
        for (Element h : hist) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pnu", pnu);
            row.put("own_rgt_chg_hist_odrno", textOf(h, "OWN_RGT_CHG_HIST_ODRNO"));
            row.put("dodrno", textOf(h, "DODRNO"));
            row.put("own_rgt_chg_rsn_cd", textOf(h, "OWN_RGT_CHG_RSN_CD"));
            row.put("own_rgt_chg_rsn_cd_nm", textOf(h, "OWN_RGT_CHG_RSN_CD_NM"));
            row.put("dregno", textOf(h, "DREGNO"));
            row.put("owner_nm", textOf(h, "OWNER_NM"));
            row.put("own_gbn", textOf(h, "OWN_GBN"));
            row.put("own_gbn_nm", textOf(h, "OWN_GBN_NM"));
            row.put("own_rgt_chg_chrg_man_id", textOf(h, "OWN_RGT_CHG_CHRG_MAN_ID"));
            putDate(row, "dymd", textOf(h, "DYMD"), warnings);
            putInt(row, "shr_cnt", textOf(h, "SHR_CNT"), warnings);
            rows.add(new StageRow("kras.stage_land_ownership_history", row));
        }

        return new MappingResult(rows, warnings);
    }
}
