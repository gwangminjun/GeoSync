package geosync.kras;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
 * land_change(§10, "토지이동 변동내역") 매퍼 — PNU 단건이 아니라 기간(날짜 범위) 조회.
 * kras.md §10 In 항목: 행정구역코드 + 처리일자(시작일/종료일)만 받는다(PNU 없음).
 * 응답은 &lt;LAND_MOV_CHG_SET&gt;의 &lt;LAND_MOV_CHG&gt;가 이동 건수만큼 반복 — 매 조회가
 * "그 기간의 새 사건 목록"이라 승격은 패턴 D(순수 append, §8.5)다.
 *
 * kras.land_change_event.record_no는 kras.sync_record(item_id,record_no)를 FK로 참조한다 —
 * 그래서 이 매퍼가 채우는 StageRow에 "payload"(원본 태그 전체를 그대로 담은 맵)를 포함시키면
 * KrasDateRangeIngestService가 stage 삽입과 함께 kras.sync_record도 자동으로 같이 넣어준다
 * (payload_hash도 DB에서 sha256으로 계산 — Java에서 해시 재구현 안 함).
 *
 * 확인 안 된 것:
 * - conn_svc_id, 처리일자 요청 필드명·형식 — 배포 시 kras-incremental.land-change.* 설정으로 넣는다.
 * - before_pnu/after_pnu는 응답에 행정구역코드가 안 나와서 요청 시 쓴 org_cd를 그대로 붙여 조립했다 —
 *   실제로 항상 같은 기관인지는 실제 응답으로 확인 필요.
 * - kras.md §11(소유권변동내역)·§12(집합건물소유권변동내역)는 §10과 완전히 동일한 내용(필드명·샘플
 *   응답까지 똑같음)이 그대로 복사돼 있다 — 문서 자체의 오류로 보여 그 둘은 만들지 않았다.
 */
@Component
public class LandChangeMapper implements KrasDateRangeServiceMapper {

    // 서비스 ID·요청 날짜 필드명은 배포 시에만 알 수 있어 설정으로 받는다(kras-incremental.land-change.*).
    private final String serviceId;
    private final String startParam;
    private final String endParam;
    private final DateTimeFormatter dateFormat;

    public LandChangeMapper(@Value("${kras-incremental.land-change.service-id:}") String serviceId,
                            @Value("${kras-incremental.land-change.start-param:}") String startParam,
                            @Value("${kras-incremental.land-change.end-param:}") String endParam,
                            @Value("${kras-incremental.land-change.date-format:yyyyMMdd}") String dateFormat) {
        this.serviceId = serviceId.trim();
        this.startParam = startParam.trim();
        this.endParam = endParam.trim();
        this.dateFormat = DateTimeFormatter.ofPattern(dateFormat.isBlank() ? "yyyyMMdd" : dateFormat.trim());
    }

    @Override
    public String datasetCode() { return "land_change"; }

    @Override
    public String connSvcId() {
        if (serviceId.isEmpty()) {
            throw new UnsupportedOperationException(
                    "land_change의 conn_svc_id가 설정되지 않았습니다 — 배포 서버 conf/application.yml의 "
                            + "kras-incremental.land-change.service-id와 kras.sync_dataset.service_code를 같은 값으로 채우세요.");
        }
        return serviceId;
    }

    /** 요청 필드명이 설정돼 있지 않으면 비운다 — 수동 화면에서는 추가 파라미터로 직접 넣을 수 있다. */
    @Override
    public Map<String, String> dateParams(LocalDate startDate, LocalDate endDate) {
        if (startParam.isEmpty() || endParam.isEmpty()) return Map.of();
        return Map.of(startParam, dateFormat.format(startDate), endParam, dateFormat.format(endDate));
    }

    @Override
    public int maxQueryDays() { return 10; }

    @Override
    public List<StagePromotionSpec> promotionSpecs() {
        return List.of(
            StagePromotionSpec.appendOnly("kras.stage_land_change_event", "kras.land_change_event",
                List.of("land_mov_no", "land_mov_nm", "land_mov_item", "bf_land_loc_cd", "bf_ledg_gbn",
                    "bf_bobn", "bf_bubn", "bf_jimok", "af_land_loc_cd", "af_ledg_gbn", "af_bobn", "af_bubn",
                    "af_jimok", "land_mov_rsn_cd", "land_mov_rsn_nm", "bf_parea", "af_parea",
                    "before_pnu", "after_pnu", "adj_ymd", "hndl_ymd", "payload", "payload_hash", "record_no"))
        );
    }

    /** land_change_event 승격 직후, 그 이벤트를 kras.parcel/kras.land_basic 현재 상태에 반영한다. */
    @Override
    public void afterPromote(JdbcTemplate tx, String orgCd, long itemId) {
        KrasLandChangeReflectionService.reflect(tx, orgCd, itemId);
    }

    @Override
    public KrasXmlServiceMapper.MappingResult map(Document xml, String orgCd, LocalDate startDate, LocalDate endDate) {
        List<Element> events = elementsOf(xml, "LAND_MOV_CHG");
        if (events.isEmpty()) {
            // 목록 태그는 있는데 비었으면 그 기간에 이동이 없었던 것 — 0건 정상으로 끝내야 증분 커서가 안 멈춘다.
            // 목록 태그조차 없으면 응답 구조가 문서와 다른 것이라 멈춘다. (0건 응답 실제 모양은 배포 시 확인 V-3)
            if (!elementsOf(xml, "LAND_MOV_CHG_SET").isEmpty()) {
                return new KrasXmlServiceMapper.MappingResult(List.of(), List.of());
            }
            throw new IllegalStateException(
                    "land_change 응답에 LAND_MOV_CHG_SET이 없습니다 — 응답 구조가 문서와 다른지 실제 응답을 먼저 확인하세요.");
        }

        List<String> warnings = new ArrayList<>();
        List<KrasXmlServiceMapper.StageRow> rows = new ArrayList<>();
        for (Element e : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            String[] tags = {"LAND_MOV_NO", "LAND_MOV_NM", "LAND_MOV_ITEM", "BF_LAND_LOC_CD", "BF_LEDG_GBN",
                    "BF_BOBN", "BF_BUBN", "BF_JIMOK", "BF_PAREA", "AF_LAND_LOC_CD", "AF_LEDG_GBN", "AF_BOBN",
                    "AF_BUBN", "AF_JIMOK", "AF_PAREA", "LAND_MOV_RSN_CD", "LAND_MOV_RSN_NM", "ADJ_YMD", "HNDL_YMD"};
            Map<String, Object> payload = new LinkedHashMap<>();
            for (String tag : tags) payload.put(tag, textOf(e, tag));
            row.put("payload", payload);

            row.put("land_mov_no", textOf(e, "LAND_MOV_NO"));
            row.put("land_mov_nm", textOf(e, "LAND_MOV_NM"));
            row.put("land_mov_item", textOf(e, "LAND_MOV_ITEM"));
            row.put("bf_land_loc_cd", textOf(e, "BF_LAND_LOC_CD"));
            row.put("bf_ledg_gbn", textOf(e, "BF_LEDG_GBN"));
            row.put("bf_bobn", textOf(e, "BF_BOBN"));
            row.put("bf_bubn", textOf(e, "BF_BUBN"));
            row.put("bf_jimok", textOf(e, "BF_JIMOK"));
            row.put("af_land_loc_cd", textOf(e, "AF_LAND_LOC_CD"));
            row.put("af_ledg_gbn", textOf(e, "AF_LEDG_GBN"));
            row.put("af_bobn", textOf(e, "AF_BOBN"));
            row.put("af_bubn", textOf(e, "AF_BUBN"));
            row.put("af_jimok", textOf(e, "AF_JIMOK"));
            row.put("land_mov_rsn_cd", textOf(e, "LAND_MOV_RSN_CD"));
            row.put("land_mov_rsn_nm", textOf(e, "LAND_MOV_RSN_NM"));
            putDecimal(row, "bf_parea", textOf(e, "BF_PAREA"), warnings);
            putDecimal(row, "af_parea", textOf(e, "AF_PAREA"), warnings);
            putDate(row, "adj_ymd", textOf(e, "ADJ_YMD"), warnings);
            putDate(row, "hndl_ymd", textOf(e, "HNDL_YMD"), warnings);

            // before_pnu/after_pnu: 응답에 행정구역코드가 없어 요청 org_cd를 그대로 붙여 조립(미확인 가정).
            row.put("before_pnu", buildPnu(orgCd, textOf(e, "BF_LAND_LOC_CD"), textOf(e, "BF_LEDG_GBN"),
                    textOf(e, "BF_BOBN"), textOf(e, "BF_BUBN")));
            row.put("after_pnu", buildPnu(orgCd, textOf(e, "AF_LAND_LOC_CD"), textOf(e, "AF_LEDG_GBN"),
                    textOf(e, "AF_BOBN"), textOf(e, "AF_BUBN")));

            rows.add(new KrasXmlServiceMapper.StageRow("kras.stage_land_change_event", row));
        }

        return new KrasXmlServiceMapper.MappingResult(rows, warnings);
    }

    private static String buildPnu(String orgCd, String landLocCd, String ledgGbn, String bobn, String bubn) {
        if (landLocCd == null || ledgGbn == null || bobn == null || bubn == null) return null;
        return orgCd + landLocCd + ledgGbn + bobn + bubn;
    }
}
