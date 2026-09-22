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
 * land_mov_hist(KRAS000006, "토지이동연혁") 매퍼 — 중첩 반복: &lt;LAND_MOV_HIST_SET&gt;의
 * &lt;LAND_MOV_HIST&gt;가 연혁 개수만큼 반복되고, 그 안에 &lt;RELJIBUN&gt;이 관련지번 개수만큼 또
 * 반복된다(kras.md §7). land_movement_relation.history_id는 land_movement_history가 INSERT될 때
 * 새로 생성되는 서로게이트 PK를 참조해야 해서(§8.1 패턴 B의 2단 변형) 자식 stage 행에는
 * "몇 번째 연혁 항목에 속하는지"를 parent_record_no로 적어둔다 — KrasStagePromotionService가
 * 승격 시 이 값으로 실제 history_id를 찾아 연결한다.
 *
 * 이 응답에는 필지 식별 태그(ADM_SECT_CD 등)가 전혀 없어(§7 필드표 확인) land_info/shr_ymb처럼
 * 신원 대조나 parcel 승격을 할 수 없다 — land_movement_history.pnu는 요청 PNU를 그대로 쓰고,
 * kras.parcel(pnu)가 이미 존재한다고 전제한다(다른 서비스로 먼저 채워져 있어야 함, FK로 강제됨).
 */
@Component
public class LandMovHistMapper implements KrasXmlServiceMapper {

    @Override
    public String datasetCode() { return "land_mov_hist"; }

    @Override
    public String connSvcId() { return "KRAS000006"; }

    @Override
    public List<StagePromotionSpec> promotionSpecs() {
        return List.of(
            StagePromotionSpec.scopeReplaceWithChildren("kras.stage_land_movement_history",
                "kras.land_movement_history", "pnu", "history_id",
                List.of("pnu", "land_mov_hist_odrno", "land_hist_odrno", "jimok", "jimok_nm",
                    "land_mov_rsn_cd", "land_mov_rsn_cd_nm", "scale", "scale_nm", "own_gbn", "doho",
                    "land_mov_chrg_man_id", "owner_addr", "owner_nm", "dymd", "del_ymd",
                    "land_mov_del_ymd", "parea", "shr_cnt"),
                new ChildSpec("kras.stage_land_movement_relation", "kras.land_movement_relation",
                    "history_id", "relation_no", List.of("jibun")))
        );
    }

    @Override
    public MappingResult map(Document xml, String pnu) {
        List<Element> histEntries = elementsOf(xml, "LAND_MOV_HIST");
        if (histEntries.isEmpty()) {
            throw new IllegalStateException(
                    "land_mov_hist 응답에 LAND_MOV_HIST가 없습니다 — 정상(연혁 없음)인지 응답 구조가 문서와 다른지 "
                            + "실제 응답을 먼저 확인하세요.");
        }

        List<String> warnings = new ArrayList<>();
        List<StageRow> rows = new ArrayList<>();
        int historyIdx = 1;
        for (Element h : histEntries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pnu", pnu);
            row.put("land_mov_hist_odrno", textOf(h, "LAND_MOV_HIST_ODRNO"));
            row.put("land_hist_odrno", textOf(h, "LAND_HIST_ODRNO"));
            row.put("jimok", textOf(h, "JIMOK"));
            row.put("jimok_nm", textOf(h, "JIMOK_NM"));
            row.put("land_mov_rsn_cd", textOf(h, "LAND_MOV_RSN_CD"));
            row.put("land_mov_rsn_cd_nm", textOf(h, "LAND_MOV_RSN_CD_NM"));
            row.put("scale", textOf(h, "SCALE"));
            row.put("scale_nm", textOf(h, "SCALE_NM"));
            row.put("own_gbn", textOf(h, "OWN_GBN"));
            row.put("doho", textOf(h, "DOHO"));
            row.put("land_mov_chrg_man_id", textOf(h, "LAND_MOV_CHRG_MAN_ID"));
            row.put("owner_addr", textOf(h, "OWNER_ADDR"));
            row.put("owner_nm", textOf(h, "OWNER_NM"));
            putDate(row, "dymd", textOf(h, "DYMD"), warnings);
            putDate(row, "del_ymd", textOf(h, "DEL_YMD"), warnings);
            putDate(row, "land_mov_del_ymd", textOf(h, "LAND_MOV_DEL_YMD"), warnings);
            putDecimal(row, "parea", textOf(h, "PAREA"), warnings);
            putInt(row, "shr_cnt", textOf(h, "SHR_CNT"), warnings);
            rows.add(new StageRow("kras.stage_land_movement_history", row));

            for (Element rel : elementsOf(h, "RELJIBUN")) {
                Map<String, Object> relRow = new LinkedHashMap<>();
                relRow.put("jibun", textOf(rel, "JIBUN"));
                relRow.put("parent_record_no", (long) historyIdx);
                rows.add(new StageRow("kras.stage_land_movement_relation", relRow));
            }
            historyIdx++;
        }

        return new MappingResult(rows, warnings);
    }
}
