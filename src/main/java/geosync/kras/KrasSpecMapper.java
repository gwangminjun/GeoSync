package geosync.kras;

import geosync.common.xml.XmlUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static geosync.common.xml.XmlUtil.textOf;
import static geosync.kras.KrasStagePromotionService.StagePromotionSpec;

/**
 * 선언만으로 만들어지는 PNU 단건 매퍼 — kras.md에 응답 규격이 없는 14개 서비스용.
 *
 * <p><b>이 매퍼의 태그명은 가설이다.</b> kras.md에 §1~16 어디에도 이 서비스들의 응답 XML이 없어서,
 * 업무 테이블 컬럼명을 대문자로 바꾼 것을 태그명으로 가정했다. 근거는 이미 검증된 서비스들에서
 * 그 규칙이 성립한다는 것뿐이다(§13 건물통합정보의 LAREA/BAREA ↔ larea/barea 등).
 * <b>실제 운영 응답으로 확인하기 전에는 VERIFIED로 올리면 안 된다.</b>
 *
 * <p>대신 틀렸을 때 조용히 NULL로 채워지지 않게 만들었다:
 * <ul>
 *   <li>반복 그룹을 감싸는 태그 이름은 <b>추측하지 않는다</b> — 항목마다 반드시 있는 필드 하나(probeTag)로
 *       반복 단위를 역추적한다({@link XmlUtil#repeatUnitsContaining}). 감싸는 태그가 뭐든 동작한다.</li>
 *   <li>probeTag가 응답에 아예 없으면 예외를 던지고, <b>응답에 실제로 있던 태그 목록을 메시지에 붙인다</b> —
 *       한 번 호출해 보면 올바른 태그명을 바로 알 수 있다.</li>
 * </ul>
 *
 * <p>손으로 쓴 매퍼(LandInfoMapper 등)와 달리 응답 신원(필지 5태그) 대조를 하지 않는다 —
 * 그 태그들이 이 서비스 응답에 있는지조차 모르기 때문이다. 요청 PNU를 그대로 stage에 넣는다.
 */
public class KrasSpecMapper implements KrasXmlServiceMapper {

    /**
     * 하나의 stage/업무 테이블 쌍에 대한 선언.
     *
     * @param probeTag   반복 단위를 찾을 때 쓸 기준 태그(이 섹션에서 항상 존재하는 필드). null이면
     *                   문서 전체를 1건으로 본다(평평한 응답).
     * @param columns    stage 컬럼명 목록. 태그명은 각각을 대문자로 바꾼 것으로 가정한다.
     * @param dateCols   date 타입 컬럼(파싱 실패 시 원문 보존).
     * @param numberCols numeric 타입 컬럼.
     * @param constants  응답 태그가 아니라 고정값으로 채우는 컬럼. NOT NULL인데 XML이 들고 있지 않은
     *                   컬럼용이다(예: building_title.source_service = 어느 서비스가 채웠는지).
     *                   값이 {@link #COMPUTE_REQUEST_KEY}면 KrasPnuIngestService가 DB의
     *                   kras.request_key()로 계산해 넣는다.
     */
    public record Section(String stageTable, String probeTag, List<String> columns,
                           List<String> dateCols, List<String> numberCols, Map<String, Object> constants) {

        public static Section flat(String stageTable, List<String> columns,
                                    List<String> dateCols, List<String> numberCols) {
            return new Section(stageTable, null, columns, dateCols, numberCols, Map.of());
        }

        public static Section repeated(String stageTable, String probeTag, List<String> columns,
                                        List<String> dateCols, List<String> numberCols) {
            return new Section(stageTable, probeTag, columns, dateCols, numberCols, Map.of());
        }

        public Section withConstants(Map<String, Object> constants) {
            return new Section(stageTable, probeTag, columns, dateCols, numberCols, constants);
        }
    }

    /** request_key(NOT NULL)를 DB 함수로 계산하라는 표시 — Java에서 해시를 재구현하지 않는다. */
    public static final String COMPUTE_REQUEST_KEY = "_COMPUTE_REQUEST_KEY_";

    private final String datasetCode;
    private final String connSvcId;
    private final String sourceSystem;
    private final List<Section> sections;
    private final List<StagePromotionSpec> promotionSpecs;
    private final boolean requiresExtraParams;

    public KrasSpecMapper(String datasetCode, String connSvcId, String sourceSystem, List<Section> sections,
                           List<StagePromotionSpec> promotionSpecs, boolean requiresExtraParams) {
        this.datasetCode = datasetCode;
        this.connSvcId = connSvcId;
        this.sourceSystem = sourceSystem;
        this.sections = sections;
        this.promotionSpecs = promotionSpecs;
        this.requiresExtraParams = requiresExtraParams;
    }

    @Override public String datasetCode() { return datasetCode; }
    @Override public String connSvcId() { return connSvcId; }
    @Override public String sourceSystem() { return sourceSystem; }
    @Override public List<StagePromotionSpec> promotionSpecs() { return promotionSpecs; }

    /** 이 서비스가 PNU 외 추가 파라미터(건물식별번호 등)를 반드시 요구하는가 — UI 안내용. */
    public boolean requiresExtraParams() { return requiresExtraParams; }

    @Override
    public MappingResult map(Document xml, String pnu) {
        List<String> warnings = new ArrayList<>();
        List<StageRow> rows = new ArrayList<>();

        for (Section section : sections) {
            List<Element> units = section.probeTag() == null
                    ? List.of()
                    : XmlUtil.repeatUnitsContaining(xml, section.probeTag());

            if (section.probeTag() != null && units.isEmpty()) {
                throw new IllegalStateException(
                        datasetCode + " 응답에서 " + section.stageTable() + "용 기준 태그 <" + section.probeTag()
                                + ">를 찾지 못했습니다. 이 매퍼의 태그명은 업무 테이블 컬럼명에서 유추한 가설이라"
                                + " 실제 응답과 다를 수 있습니다. 응답에 있던 태그: " + XmlUtil.tagNamesIn(xml));
            }

            int parentRecordNo = 1;
            if (units.isEmpty()) {
                rows.add(new StageRow(section.stageTable(), readFlat(xml, section, pnu, warnings)));
            } else {
                for (Element unit : units) {
                    Map<String, Object> row = readUnit(unit, section, pnu, warnings);
                    row.put("parent_record_no", (long) parentRecordNo++);
                    rows.add(new StageRow(section.stageTable(), row));
                }
            }
        }
        return new MappingResult(rows, warnings);
    }

    private Map<String, Object> readFlat(Document xml, Section section, String pnu, List<String> warnings) {
        Map<String, Object> row = new LinkedHashMap<>(section.constants());
        if (section.columns().contains("pnu")) row.put("pnu", pnu);
        for (String col : section.columns()) {
            if ("pnu".equals(col)) continue;
            put(row, col, textOf(xml, col.toUpperCase()), section, warnings);
        }
        return row;
    }

    private Map<String, Object> readUnit(Element unit, Section section, String pnu, List<String> warnings) {
        Map<String, Object> row = new LinkedHashMap<>(section.constants());
        if (section.columns().contains("pnu")) row.put("pnu", pnu);
        for (String col : section.columns()) {
            if ("pnu".equals(col)) continue;
            put(row, col, textOf(unit, col.toUpperCase()), section, warnings);
        }
        return row;
    }

    private void put(Map<String, Object> row, String col, String raw, Section section, List<String> warnings) {
        if (section.dateCols().contains(col)) {
            KrasFieldParsers.putDate(row, col, raw, warnings);
        } else if (section.numberCols().contains(col)) {
            // 면적·금액은 응답에 천단위 콤마가 붙어 오는 경우가 있다(§13에서 확인됨).
            KrasFieldParsers.putDecimal(row, col, raw == null ? null : raw.replace(",", ""), warnings);
        } else {
            row.put(col, raw);
        }
    }
}
