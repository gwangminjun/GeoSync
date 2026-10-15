package geosync.kras;

import java.util.List;

/**
 * 차세대 스케줄의 단계. 스케줄은 단계가 아니라 묶음({@link KrasNextgenGroup}: 전체 적재 / 보정 적재)에 건다.
 * 단계는 묶음 안에서 순서대로 돌고, 고급 영역의 "단계 실행"으로 하나만 돌릴 수도 있다.
 * 설계: docs/superpowers/specs/2026-10-15/2026-10-15-kras-pnu-api-full-incremental-design.md §7
 *
 * @param datasetCode    현황판(/kras-db) 행·실행 기록을 묶는 대표 데이터셋 (대기열 단계는 null)
 * @param gateDatasets   실행 전 readiness를 확인할 데이터셋(계약 승인·활성·서비스 ID)
 * @param target         화면에 보여줄 반영 대상
 * @param replacesPublic 기존 연계가 쓰는 public 테이블을 자동 교체하는 단계
 */
public enum KrasNextgenJob {
    LAND_BASIC("land-basic", "토지기본정보 TXT", "land_basic_file", List.of("land_basic_file"),
            "kras.parcel·land_basic — 기준선이 없으면 기준선, 있으면 전체 대조", false),
    CADASTRAL("cadastral", "연속지적 SHP", "cadastral_file", List.of("cadastral_file"),
            "kras → public.lp_pa_cbnd", true),
    USEZONE("usezone", "용도지역 SHP", "usezone_file", List.of("layer_list", "usezone_file"),
            "kras → 발행 → public.lt_c_uzone", true),
    LAND_PRICE("land-price", "공시지가 TXT", "land_price_file", List.of("land_price_file"),
            "kras.land_price_file_row", false),
    PNU_FULL("pnu-full", "PNU 서비스 전체 대기열 등록", null, List.of(),
            "필지 × 켜진 서비스 → kras.pnu_work", false),
    LAND_CHANGE("land-change", "토지이동내역", "land_change", List.of("land_change"),
            "kras.land_change_event → parcel·land_basic → 바뀐 필지 대기열 등록", false),
    PNU_REFRESH("pnu-refresh", "순환 재조회·연 1회 가격 대기열 등록", null, List.of(),
            "오래된 행·공시 후 가격 → kras.pnu_work", false),
    PNU_QUEUE("pnu-queue", "대기열 처리", null, List.of(),
            "kras.pnu_work → PNU 서비스 수집·반영", false);

    public final String id;
    public final String label;
    public final String datasetCode;
    public final List<String> gateDatasets;
    public final String target;
    public final boolean replacesPublic;

    KrasNextgenJob(String id, String label, String datasetCode, List<String> gateDatasets, String target,
                   boolean replacesPublic) {
        this.id = id;
        this.label = label;
        this.datasetCode = datasetCode;
        this.gateDatasets = gateDatasets;
        this.target = target;
        this.replacesPublic = replacesPublic;
    }

    public static KrasNextgenJob byId(String id) {
        for (KrasNextgenJob job : values()) {
            if (job.id.equals(id)) return job;
        }
        throw new IllegalArgumentException("알 수 없는 단계: " + id);
    }
}
