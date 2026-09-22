package geosync.kras;

import org.w3c.dom.Document;

import java.time.LocalDate;
import java.util.List;

/**
 * 기간(날짜 범위) 조회 서비스 매퍼 — PNU 단건이 아니라 "이 기간 동안 뭐가 바뀌었나"를 묻는
 * kras.md §10~12(변동내역) 계열용. KrasXmlServiceMapper(PNU 단건)와 최상위 식별자 자체가 달라서
 * 별도 인터페이스로 뒀다 — StageRow/MappingResult는 그대로 재사용한다(반복 응답을 stage 행으로
 * 쪼개는 모양은 똑같기 때문).
 */
public interface KrasDateRangeServiceMapper {

    String datasetCode();

    String connSvcId();

    /** kras.sync_dataset.max_query_days — 이 값보다 넓은 기간을 요청하면 호출 전에 막는다. */
    int maxQueryDays();

    /**
     * @throws IllegalStateException 응답 구조가 예상과 다른 등 안전하게 계속 진행할 수 없는 경우
     */
    KrasXmlServiceMapper.MappingResult map(Document xml, String orgCd, LocalDate startDate, LocalDate endDate);

    List<KrasStagePromotionService.StagePromotionSpec> promotionSpecs();
}
