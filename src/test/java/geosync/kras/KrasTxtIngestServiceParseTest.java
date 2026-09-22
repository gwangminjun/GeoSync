package geosync.kras;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전체 TXT 파싱만 검증한다 — DB도 API도 안 탄다.
 * 구분자 판정과 헤더 스킵이 틀리면 수십만 행이 통째로 잘못 들어가는데, 그걸 실행 전에 잡는 게 목적이다.
 */
class KrasTxtIngestServiceParseTest {

    private static final char VT = 11;   // kras.md §16이 ♂로 표기한 ASCII 11 구분자

    @SuppressWarnings("unchecked")
    private static List<String[]> parse(String text, int expectedCols) throws Exception {
        Method m = KrasTxtIngestService.class.getDeclaredMethod("parseTxt", byte[].class, int.class);
        m.setAccessible(true);
        byte[] bytes = text.getBytes(Charset.forName("EUC-KR"));
        return (List<String[]>) m.invoke(new KrasTxtIngestService(null, null), bytes, expectedCols);
    }

    @Test
    void parsesAscii11DelimitedLandBasicFileAndSkipsHeader() throws Exception {
        // kras.md §16 결과 파일 구조 예시 그대로
        String text = String.join("\n",
                "ADM_SECT_CD" + VT + "LAND_LOC_CD" + VT + "LEDG_GBN" + VT + "BOBN" + VT + "BUBN"
                        + VT + "JIMOK" + VT + "PAREA" + VT + "OWN_GBN",
                "27170" + VT + "10200" + VT + "1" + VT + "0399" + VT + "0036" + VT + "14" + VT + "47" + VT + "02",
                "27170" + VT + "10200" + VT + "1" + VT + "0399" + VT + "0037" + VT + "14" + VT + "348" + VT + "04");

        List<String[]> rows = parse(text, 8);

        assertThat(rows).hasSize(2);                 // 헤더 행은 제외됐다
        assertThat(rows.get(0)).containsExactly("27170", "10200", "1", "0399", "0036", "14", "47", "02");
        // 앞 5개를 이어붙이면 19자리 PNU가 된다 — 적재가 이 규칙에 기대고 있다
        String[] c = rows.get(0);
        assertThat(c[0] + c[1] + c[2] + c[3] + c[4]).isEqualTo("2717010200103990036").hasSize(19);
    }

    @Test
    void fallsBackToPipeWhenAscii11Absent() throws Exception {
        // 기존 KrasTxtLoaderService가 다루던 파이프 구분 형식도 그대로 읽혀야 한다
        String text = "1283025625|2026|123456|01|Y\n1283025626|2026|654321|01|N";

        List<String[]> rows = parse(text, 5);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly("1283025625", "2026", "123456", "01", "Y");
    }

    @Test
    void dropsLinesWithTooFewColumns() throws Exception {
        String text = "27170" + VT + "10200" + VT + "1\n"
                + "27170" + VT + "10200" + VT + "1" + VT + "0399" + VT + "0036" + VT + "14" + VT + "47" + VT + "02";

        assertThat(parse(text, 8)).hasSize(1);
    }
}
