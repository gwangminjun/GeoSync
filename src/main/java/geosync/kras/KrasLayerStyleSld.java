package geosync.kras;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 포털 레이어 스타일(public.dhc_layer_style) → WMS SLD_BODY. 지오서버 등록 스타일(*_random_style)은 투명도·축척 규칙이
 * 포털과 달라 미리보기에서는 이 값을 직접 붙인다. 읽기만 한다.
 * 설계: docs/superpowers/specs/2026-10-15/2026-10-15-kras-db-layer-map-preview-design.md §4.4
 */
final class KrasLayerStyleSld {

    record Style(String title, String lineYn, String lineColor, Double lineWeight, String fillYn, String fillColor,
                 Integer minScale, Integer maxScale, String labelYn, String labelColumn, String labelFont,
                 String labelStyle, Integer labelFontSize, String labelFontColor, String labelBorderColor,
                 Integer labelBorderWeight, Integer labelScale, String labelSort, Double labelRotate) {}

    private static final Pattern RGBA = Pattern.compile(
            "rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*(?:,\\s*([\\d.]+)\\s*)?\\)");
    private static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{6})");

    private KrasLayerStyleSld() {}

    /**
     * "스키마.테이블" → 스타일. dhc_layer.tb_schema·tb_name으로 찾고, 같은 이름이 여러 행이면
     * 사용 중(use_yn='Y') → 최근 등록 순으로 하나. dhc 테이블이 없는 DB에서는 빈 맵.
     */
    static Map<String, Style> load(JdbcTemplate tx, Collection<String> qualifiedNames) {
        Map<String, Style> out = new LinkedHashMap<>();
        if (qualifiedNames.isEmpty()
                || tx.queryForObject("SELECT to_regclass('public.dhc_layer')::text", String.class) == null
                || tx.queryForObject("SELECT to_regclass('public.dhc_layer_style')::text", String.class) == null) {
            return out;
        }
        String in = String.join(",", qualifiedNames.stream().map(n -> "?").toList());
        tx.query("""
                SELECT DISTINCT ON (k) k, title, line_yn, line_color, line_weight::float8 AS line_weight, fill_yn, fill_color, min_scale, max_scale,
                       label_yn, label_column, label_font, label_style, label_font_size, label_font_color,
                       label_border_color, label_border_weight, label_scale, label_sort, label_rotate::float8 AS label_rotate
                FROM (SELECT coalesce(nullif(l.tb_schema, ''), 'public') || '.' || l.tb_name AS k, l.title, l.use_yn,
                             l.reg_date, s.*
                      FROM public.dhc_layer l JOIN public.dhc_layer_style s ON s.layer_cd = l.layer_cd) x
                WHERE k IN (""" + in + """
                )
                ORDER BY k, (use_yn = 'Y') DESC, reg_date DESC NULLS LAST
                """, rs -> {
            out.put(rs.getString("k"), new Style(rs.getString("title"), rs.getString("line_yn"), rs.getString("line_color"),
                    rs.getObject("line_weight", Double.class), rs.getString("fill_yn"), rs.getString("fill_color"),
                    rs.getObject("min_scale", Integer.class), rs.getObject("max_scale", Integer.class),
                    rs.getString("label_yn"), rs.getString("label_column"), rs.getString("label_font"),
                    rs.getString("label_style"), rs.getObject("label_font_size", Integer.class),
                    rs.getString("label_font_color"), rs.getString("label_border_color"),
                    rs.getObject("label_border_weight", Integer.class), rs.getObject("label_scale", Integer.class),
                    rs.getString("label_sort"), rs.getObject("label_rotate", Double.class)));
        }, qualifiedNames.toArray());
        return out;
    }

    /**
     * 한 레이어의 SLD. withScale=false면 도형 축척 규칙(min/max_scale)을 빼서 어느 축척에서나 그린다(라벨 축척은 유지).
     * columns: 레이어 칼럼 — 라벨 칼럼이 여기 없으면 라벨을 뺀다(없는 칼럼이면 지오서버가 요청을 거절한다).
     */
    static String sld(String layerName, Style s, boolean withScale, Set<String> columns) {
        StringBuilder rules = new StringBuilder();
        String symbolizer = polygonSymbolizer(s);
        if (symbolizer != null) {
            rules.append("<Rule>");
            if (withScale) rules.append(scaleRange(s.minScale(), s.maxScale()));
            rules.append(symbolizer).append("</Rule>");
        }
        if ("Y".equals(s.labelYn()) && s.labelColumn() != null && columns.contains(s.labelColumn())) {
            Integer max = positive(s.labelScale());
            if (withScale && positive(s.maxScale()) != null) max = max == null ? s.maxScale() : Math.min(max, s.maxScale());
            rules.append("<Rule>").append(scaleRange(withScale ? s.minScale() : null, max))
                    .append(textSymbolizer(s)).append("</Rule>");
        }
        return "<StyledLayerDescriptor version=\"1.0.0\" xmlns=\"http://www.opengis.net/sld\""
                + " xmlns:ogc=\"http://www.opengis.net/ogc\"><NamedLayer><Name>" + xml(layerName)
                + "</Name><UserStyle><FeatureTypeStyle>" + rules + "</FeatureTypeStyle></UserStyle></NamedLayer>"
                + "</StyledLayerDescriptor>";
    }

    /** 행 한 건 미리보기의 강조(노란 굵은 선 + 반투명 채움). 축척 규칙 없이 늘 그린다. */
    static final String HIGHLIGHT_RULE = "<Rule><PolygonSymbolizer><Fill>" + css("fill", "#FFD400") + css("fill-opacity", "0.25")
            + "</Fill><Stroke>" + css("stroke", "#FFD400") + css("stroke-width", "4") + "</Stroke></PolygonSymbolizer></Rule>";

    /** 포털 스타일 SLD 끝에 강조 규칙을 붙인다. 포털 스타일이 없으면(null) 강조만 있는 SLD. */
    static String withHighlight(String layerName, String sld) {
        if (sld == null) {
            return "<StyledLayerDescriptor version=\"1.0.0\" xmlns=\"http://www.opengis.net/sld\""
                    + " xmlns:ogc=\"http://www.opengis.net/ogc\"><NamedLayer><Name>" + xml(layerName)
                    + "</Name><UserStyle><FeatureTypeStyle>" + HIGHLIGHT_RULE + "</FeatureTypeStyle></UserStyle></NamedLayer>"
                    + "</StyledLayerDescriptor>";
        }
        return sld.replace("</FeatureTypeStyle>", HIGHLIGHT_RULE + "</FeatureTypeStyle>");
    }

    private static String polygonSymbolizer(Style s) {
        String fill = "";
        String[] fc = "Y".equals(s.fillYn()) ? color(s.fillColor()) : null;
        if (fc != null) {
            fill = "<Fill>" + css("fill", fc[0]) + css("fill-opacity", fc[1]) + "</Fill>";
        }
        String stroke = "";
        String[] lc = "Y".equals(s.lineYn()) ? color(s.lineColor()) : null;
        if (lc != null) {
            stroke = "<Stroke>" + css("stroke", lc[0]) + css("stroke-opacity", lc[1])
                    + css("stroke-width", s.lineWeight() == null ? "1" : trim(s.lineWeight())) + "</Stroke>";
        }
        if (fill.isEmpty() && stroke.isEmpty()) return null;
        return "<PolygonSymbolizer>" + fill + stroke + "</PolygonSymbolizer>";
    }

    private static String textSymbolizer(Style s) {
        StringBuilder font = new StringBuilder("<Font>");
        // 글꼴을 빼면 지오서버 기본 글꼴에 한글이 없어 라벨이 사라진다(2026-10-15 확인) — dhc 값이 없으면 Dialog.
        font.append(css("font-family", s.labelFont() == null || s.labelFont().isBlank() ? "Dialog" : s.labelFont()));
        String style = s.labelStyle() == null ? "" : s.labelStyle().toLowerCase();
        if (style.contains("italic")) font.append(css("font-style", "italic"));
        if (style.contains("bold")) font.append(css("font-weight", "bold"));
        font.append(css("font-size", String.valueOf(s.labelFontSize() == null ? 12 : s.labelFontSize()))).append("</Font>");

        double[] anchor = switch (s.labelSort() == null ? "center" : s.labelSort().toLowerCase()) {
            case "left" -> new double[]{0, 0.5};
            case "right" -> new double[]{1, 0.5};
            default -> new double[]{0.5, 0.5};
        };
        String placement = "<LabelPlacement><PointPlacement><AnchorPoint><AnchorPointX>" + anchor[0]
                + "</AnchorPointX><AnchorPointY>" + anchor[1] + "</AnchorPointY></AnchorPoint>"
                + (s.labelRotate() == null || s.labelRotate() == 0 ? "" : "<Rotation>" + trim(s.labelRotate()) + "</Rotation>")
                + "</PointPlacement></LabelPlacement>";

        String halo = "";
        String[] border = color(s.labelBorderColor());
        if (border != null && s.labelBorderWeight() != null && s.labelBorderWeight() > 0) {
            halo = "<Halo><Radius>" + s.labelBorderWeight() + "</Radius><Fill>" + css("fill", border[0])
                    + css("fill-opacity", border[1]) + "</Fill></Halo>";
        }
        String[] fc = color(s.labelFontColor());
        String fill = "<Fill>" + css("fill", fc == null ? "#000000" : fc[0]) + "</Fill>";
        return "<TextSymbolizer><Label><ogc:PropertyName>" + xml(s.labelColumn()) + "</ogc:PropertyName></Label>"
                + font + placement + halo + fill + "</TextSymbolizer>";
    }

    /**
     * `rgba(r,g,b,a)` 또는 `#RRGGBB` → {#RRGGBB, 투명도}. dhc 값은 a가 0–1(채움)과 0–255(선)로 섞여 있어
     * 포털이 문자열을 브라우저에 그대로 넘긴다고 보고 CSS와 같게 해석한다: a가 1보다 크면 1(불투명).
     */
    static String[] color(String value) {
        if (value == null) return null;
        Matcher m = RGBA.matcher(value.trim());
        if (m.matches()) {
            double a = m.group(4) == null ? 1 : Math.min(Double.parseDouble(m.group(4)), 1);
            return new String[]{"#%02X%02X%02X".formatted(clamp(m.group(1)), clamp(m.group(2)), clamp(m.group(3))), trim(a)};
        }
        Matcher h = HEX.matcher(value.trim());
        return h.matches() ? new String[]{"#" + h.group(1).toUpperCase(), "1"} : null;
    }

    private static String scaleRange(Integer min, Integer max) {
        return (positive(min) == null ? "" : "<MinScaleDenominator>" + min + "</MinScaleDenominator>")
                + (positive(max) == null ? "" : "<MaxScaleDenominator>" + max + "</MaxScaleDenominator>");
    }

    private static Integer positive(Integer v) {
        return v == null || v <= 0 ? null : v;
    }

    private static int clamp(String v) {
        return Math.min(Integer.parseInt(v), 255);
    }

    private static String css(String name, String value) {
        return "<CssParameter name=\"" + name + "\">" + xml(value) + "</CssParameter>";
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String xml(String v) {
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
