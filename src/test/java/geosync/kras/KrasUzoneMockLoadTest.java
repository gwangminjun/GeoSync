package geosync.kras;

import geosync.common.geo.CoordTransformer;
import geosync.synchronization.TableMapper;
import geosync.synchronization.model.SyncTableDef;
import geosync.ods.OdsRepository;
import geosync.settings.RuntimeSettingsService;
import geosync.database.TargetDbService;
import geosync.ods.UsezoneCodeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * lt_c_uzone 목 데이터를 test 스키마에 적재하는 통합 테스트.
 * 05_lt_c_uzone_res.json 기반 3건 + theme_code/theme_name 파생 검증.
 */
@SpringBootTest
class KrasUzoneMockLoadTest {

    @Autowired OdsRepository odsRepository;
    @Autowired TableMapper tableMapper;
    @Autowired RuntimeSettingsService settings;
    @Autowired CoordTransformer coordTransformer;
    @Autowired TargetDbService targetDbService;
    @Autowired UsezoneCodeService usezoneCodeService;

    private static final String TEST_SCHEMA = "test";

    @Test
    void loadMockUsezoneIntoTestSchema() throws Exception {
        // 1. USEZONE 테이블 정의 조회
        SyncTableDef def = tableMapper.load(settings.krasConfig()).stream()
                .filter(d -> d.srcTableName.startsWith("USEZONE:"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("USEZONE 테이블 정의 없음"));

        // 2. 목 데이터 구성 (05_lt_c_uzone_res.json 기반)
        List<Map<String, Object>> rows = buildMockRows();

        // 3. theme_code / theme_name 파생 (구 시스템 방식)
        rows.forEach(this::deriveUsezoneFields);

        // 파생 결과 출력
        rows.forEach(row -> System.out.printf(
                "mnum=%-25s ulyr=%-6s ucode=%-6s uname=%s%n",
                row.get("mnum"), row.get("ulyr"), row.get("ucode"), row.get("uname")));

        // 4. 첫 번째 대상 DB에 test 스키마로 적재
        List<TargetDbService.ActiveTarget> targets = targetDbService.getActiveTargets();
        assertThat(targets).isNotEmpty();
        JdbcTemplate jdbc = targets.get(0).jdbc();

        // test 스키마 생성 (없으면)
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + TEST_SCHEMA);

        // USEZONE: API가 5186으로 반환 → transform 없이 5186으로 직접 저장
        int storageEpsg = coordTransformer.getStorageEpsg();
        int saved = odsRepository.replaceAllTo(
                jdbc, def, settings.orgCode(),
                storageEpsg, storageEpsg,
                rows, TEST_SCHEMA, null, false);

        System.out.printf("→ test.lt_c_uzone 적재 완료: %d건%n", saved);
        assertThat(saved).isEqualTo(rows.size());

        // 5. DB에서 재조회하여 컬럼 값 검증
        List<Map<String, Object>> dbRows = jdbc.queryForList(
                "SELECT mnum, layer_code, theme_code, theme_name, alias FROM test.lt_c_uzone ORDER BY mnum");

        assertThat(dbRows).hasSize(rows.size());
        dbRows.forEach(r -> System.out.printf(
                "DB: mnum=%-25s layer_code=%-6s theme_code=%-6s theme_name=%s alias=%s%n",
                r.get("mnum"), r.get("layer_code"), r.get("theme_code"), r.get("theme_name"), r.get("alias")));

        // theme_code, theme_name 값 존재 여부 확인
        for (Map<String, Object> r : dbRows) {
            assertThat(r.get("theme_code")).as("theme_code 존재").isNotNull();
            // ucode가 UQ0112이고 DB에 있는 경우 theme_name도 있어야 함
            if (usezoneCodeService.getName("UQ0112") != null) {
                assertThat(r.get("theme_name")).as("theme_name 존재").isNotNull();
            }
        }
    }

    /** 05_lt_c_uzone_res.json 기반 목 데이터 3건 */
    private List<Map<String, Object>> buildMockRows() {
        List<Map<String, Object>> rows = new ArrayList<>();

        // feature 1
        Map<String, Object> r1 = new LinkedHashMap<>();
        r1.put("mnum",     "4687025625-UQ112-0001");
        r1.put("ulyr",     "UQ112");
        r1.put("ucode",    "UQ0112");
        r1.put("uname",    "제2종일반주거지역");
        r1.put("alias",    "2종일주");
        r1.put("remark",   "");
        r1.put("geometry", "MULTIPOLYGON(((155000.00 315000.00, 155500.00 315000.00, 155500.00 315500.00, 155000.00 315500.00, 155000.00 315000.00)))");
        rows.add(r1);

        // feature 2
        Map<String, Object> r2 = new LinkedHashMap<>();
        r2.put("mnum",     "4687025625-UQ112-0002");
        r2.put("ulyr",     "UQ112");
        r2.put("ucode",    "UQ0112");
        r2.put("uname",    "제2종일반주거지역");
        r2.put("alias",    "2종일주");
        r2.put("remark",   "");
        r2.put("geometry", "MULTIPOLYGON(((155500.00 315000.00, 156200.00 315000.00, 156200.00 315700.00, 155500.00 315700.00, 155500.00 315000.00)))");
        rows.add(r2);

        // feature 3
        Map<String, Object> r3 = new LinkedHashMap<>();
        r3.put("mnum",     "4687025625-UQ112-0003");
        r3.put("ulyr",     "UQ112");
        r3.put("ucode",    "UQ0112");
        r3.put("uname",    "제2종일반주거지역");
        r3.put("alias",    "2종일주");
        r3.put("remark",   "일부 지형 복잡");
        r3.put("geometry", "MULTIPOLYGON(((154800.00 314800.00, 155100.00 314800.00, 155200.00 314900.00, 155100.00 315100.00, 154800.00 315100.00, 154800.00 314800.00)))");
        rows.add(r3);

        return rows;
    }

    /**
     * 구 시스템 방식으로 ucode / uname 파생.
     * - ucode: substr(mnum, 21, 6), mnum이 짧으면 API 값 유지
     * - uname: mt_usezone_cd 조회, 없으면 API 값 유지
     */
    private void deriveUsezoneFields(Map<String, Object> row) {
        String mnum = (String) row.get("mnum");
        String derivedCode = (mnum != null && mnum.length() >= 26)
                ? mnum.substring(20, 26)
                : (String) row.get("ucode");
        if (derivedCode != null) {
            row.put("ucode", derivedCode);
            String name = usezoneCodeService.getName(derivedCode);
            if (name != null) row.put("uname", name);
        }
    }
}
