package geosync.kras;

import java.util.List;

/**
 * 차세대 스케줄 묶음 — 스케줄은 이 세 개에만 건다. 설계 2026-10-15 §7.
 * 레이어 적재는 2026-10-16에 전체 적재에서 분리했다(원천 SHAPE가 매일 갱신 — 설계 2026-10-16 §3·§4).
 */
public enum KrasNextgenGroup {
    FULL_LOAD("full-load", "전체 적재", List.of(KrasNextgenJob.LAND_BASIC, KrasNextgenJob.PNU_FULL)),
    CORRECTION("correction", "보정 적재", List.of(KrasNextgenJob.LAND_CHANGE, KrasNextgenJob.PNU_REFRESH,
            KrasNextgenJob.PNU_QUEUE)),
    LAYER("layer", "레이어 적재", List.of(KrasNextgenJob.CADASTRAL, KrasNextgenJob.USEZONE));

    public final String id;
    public final String label;
    public final List<KrasNextgenJob> steps;

    KrasNextgenGroup(String id, String label, List<KrasNextgenJob> steps) {
        this.id = id;
        this.label = label;
        this.steps = steps;
    }

    public boolean replacesPublic() {
        return steps.stream().anyMatch(s -> s.replacesPublic);
    }

    public static KrasNextgenGroup byId(String id) {
        for (KrasNextgenGroup g : values()) {
            if (g.id.equals(id)) return g;
        }
        throw new IllegalArgumentException("알 수 없는 묶음: " + id);
    }
}
