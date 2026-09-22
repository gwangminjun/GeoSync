package geosync.kras;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static geosync.common.xml.XmlUtil.textOf;
import static geosync.kras.KrasFieldParsers.putInt;
import static geosync.kras.KrasStagePromotionService.StagePromotionSpec;

/**
 * building_image(§14, "건물통합도면") 매퍼 — 다른 13개 매퍼와 달리 응답 자체가 태그 나열이 아니라
 * base64 인코딩된 파일 한 덩어리(BLDG_INTERG_IMG)다. kras.building_image가 kras.sync_file(file_id)을
 * FK로 참조해 파일을 별도 저장하므로, StageRow에 "_file_bytes"(디코딩된 바이트)를 얹어두면
 * KrasPnuIngestService.resolveFileColumns가 kras.sync_file 삽입 + file_id/request_key 채움을 대신한다
 * — 이 매퍼는 새 저장 메커니즘을 직접 만들지 않는다.
 *
 * 확인 안 된 것:
 * - conn_svc_id 미확인(kras.sync_dataset.service_code=NULL, §7).
 * - 너비/높이/스케일은 kras.md §14 In 항목에 있지만 실제 요청 필드명이 문서에 없다 — 운영자가
 *   "추가 파라미터(JSON)"에 KRAS가 요구하는 실제 키로 입력한 값이 그대로 요청에 실린다. 이 매퍼는
 *   그중 자기 자신의 저장용 관례로 "width"/"height"/"scale" 키를 읽어 kras.building_image의 동명
 *   컬럼에 채운다 — 실제 KRAS 필드명이 이와 다르면(예: IMG_WIDTH) 운영자가 두 키를 모두 넣어야 한다.
 * - mime_type: kras.md/DDL 어디에도 실제 파일 포맷이 명시돼 있지 않다(kras.building_image 코멘트는
 *   "HWP 건물통합도면 파일 참조"라 되어 있으나 §14 결과 XML은 그냥 "이미지"라고만 함) — 추측하지 않고
 *   NULL로 둔다.
 */
@Component
public class BuildingImageMapper implements KrasXmlServiceMapper {

    @Override
    public String datasetCode() { return "building_image"; }

    @Override
    public String connSvcId() {
        throw new UnsupportedOperationException(
                "building_image의 conn_svc_id가 아직 확인되지 않았습니다 — kras.sync_dataset.service_code=NULL(§7). "
                        + "KRAS 연계 담당자 확인 후 채워야 실제 호출이 가능합니다.");
    }

    @Override
    public List<StagePromotionSpec> promotionSpecs() {
        return List.of(
            StagePromotionSpec.appendOnly("kras.stage_building_image", "kras.building_image",
                List.of("pnu", "file_id", "width", "height", "scale", "request_key", "mime_type"))
        );
    }

    @Override
    public MappingResult map(Document xml, String pnu, Map<String, String> extraParams) {
        String base64 = textOf(xml, "BLDG_INTERG_IMG");
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(
                    "building_image 응답에 BLDG_INTERG_IMG가 없습니다 — 응답 구조가 문서와 다른지 실제 응답을 먼저 확인하세요.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("BLDG_INTERG_IMG base64 디코딩 실패: " + e.getMessage(), e);
        }

        List<String> warnings = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pnu", pnu);
        row.put("_file_bytes", bytes);
        row.put("_file_type", "BUILDING_IMAGE");
        row.put("mime_type", null);
        putInt(row, "width", extraParams.get("width"), warnings);
        putInt(row, "height", extraParams.get("height"), warnings);
        row.put("scale", extraParams.get("scale"));

        List<StageRow> rows = List.of(new StageRow("kras.stage_building_image", row));
        return new MappingResult(rows, warnings);
    }

    /** 2-인자 map()은 이 매퍼에서 안 쓴다 — width/height/scale은 extraParams로만 받는다(전부 선택값이라도 경로 통일). */
    @Override
    public MappingResult map(Document xml, String pnu) {
        return map(xml, pnu, Map.of());
    }
}
