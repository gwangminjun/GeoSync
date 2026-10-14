package geosync.kras;

import org.springframework.jdbc.core.JdbcTemplate;
import org.w3c.dom.Document;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
     * 조회 기간을 KRAS 요청 파라미터로 바꾼다. 수집 서비스가 extraParams 앞에 깔아서 보낸다
     * (같은 키를 extraParams로 넣으면 그쪽이 우선). 필드명을 모르면 빈 맵.
     */
    default Map<String, String> dateParams(LocalDate startDate, LocalDate endDate) {
        return Map.of();
    }

    /**
     * @throws IllegalStateException 응답 구조가 예상과 다른 등 안전하게 계속 진행할 수 없는 경우
     */
    KrasXmlServiceMapper.MappingResult map(Document xml, String orgCd, LocalDate startDate, LocalDate endDate);

    List<KrasStagePromotionService.StagePromotionSpec> promotionSpecs();

    /**
     * 승격(promotionSpecs 반영) 직후, 같은 트랜잭션에서 추가로 할 일이 있으면 오버라이드한다.
     * land_change처럼 이벤트를 현재 상태(parcel/land_basic)에 반영하는 데이터셋이 쓴다.
     * 기본은 아무 것도 하지 않는다 — 대부분의 데이터셋은 승격만으로 끝난다.
     */
    default void afterPromote(JdbcTemplate tx, String orgCd, long itemId) {
    }
}
