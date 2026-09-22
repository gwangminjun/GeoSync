package geosync.kras;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 응답 샘플에 소유자명/주소/주민번호가 그대로 저장되면 안 된다 — 저장 전 마스킹이 실제로 걸리는지 확인.
 */
class KrasVerificationEvidenceServiceTest {

    @Test
    void masksKnownPiiTags() {
        String sample = "<OWNER_NM>홍길동</OWNER_NM><OWNER_ADDR>대구 중구 서성로1가 65</OWNER_ADDR><JIMOK>답</JIMOK>";

        String masked = KrasVerificationEvidenceService.mask(sample);

        assertThat(masked).contains("<OWNER_NM>***</OWNER_NM>", "<OWNER_ADDR>***</OWNER_ADDR>")
                .contains("<JIMOK>답</JIMOK>")
                .doesNotContain("홍길동", "서성로1가");
    }

    @Test
    void masksResidentRegistrationNumberPatternAsSafetyNet() {
        String sample = "OWNER_REGNO=830101-1234567 (태그 목록에 없는 필드에 들어간 경우)";

        String masked = KrasVerificationEvidenceService.mask(sample);

        assertThat(masked).doesNotContain("830101-1234567").contains("[MASKED]");
    }

    @Test
    void leavesNonPiiContentUntouched() {
        String sample = "<JIMOK>답</JIMOK><PAREA>1,788.25</PAREA>";

        assertThat(KrasVerificationEvidenceService.mask(sample)).isEqualTo(sample);
    }

    @Test
    void handlesNullAndBlank() {
        assertThat(KrasVerificationEvidenceService.mask(null)).isNull();
        assertThat(KrasVerificationEvidenceService.mask("")).isEmpty();
    }
}
