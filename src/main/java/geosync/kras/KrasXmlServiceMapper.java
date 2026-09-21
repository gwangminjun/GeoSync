package geosync.kras;

import org.w3c.dom.Document;

import java.util.List;
import java.util.Map;

/**
 * PNU 단건 XML 응답 → kras.stage_* 테이블 행 매퍼.
 * 설계: docs/superpowers/specs/2026-09-18-kras-ingest-implementation-design.md §6.2.
 *
 * TableMapper/SyncTableDef(SHP용)는 컬럼 나열 방식이라 중첩 XML(반복 그룹)엔 안 맞아 재사용하지 않는다 —
 * 서비스별 구현 클래스로 만든다.
 */
public interface KrasXmlServiceMapper {

    /** kras.sync_dataset.dataset_code (예: "land_info") */
    String datasetCode();

    /** KRAS/KOREPS 게이트웨이 서비스 코드 (예: "KRAS000002") */
    String connSvcId();

    /**
     * 응답 XML을 stage 테이블 행으로 분해한다.
     * @throws IllegalStateException 응답 신원이 요청 PNU와 어긋나는 등 안전하게 계속 진행할 수 없는 경우
     */
    MappingResult map(Document xml, String pnu);

    /** 이 매퍼가 쓰는 stage 테이블마다 승격 방법을 선언한다(설계 §8.6). 부모→자식 순서로 반환한다. */
    List<KrasStagePromotionService.StagePromotionSpec> promotionSpecs();

    record StageRow(String stageTable, Map<String, Object> columns) {}

    /**
     * fieldWarnings가 비어있지 않으면 일부 필드가 파싱 실패해 stage에는 원문(extra_attributes)만 보존됐다는 뜻 —
     * 이 경우 호출자는 item을 SUCCESS로 전환하지 않고 사람이 확인하게 남겨둔다.
     */
    record MappingResult(List<StageRow> rows, List<String> fieldWarnings) {}
}
