package geosync.kras;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KrasLayerStyleSldTest {

    /** dev DB의 v_lt_c_uzone_uf801 스타일 행 그대로(선 a=100, 채움 a=0.3, 1:500–1:5,000, 라벨 remark). */
    private static KrasLayerStyleSld.Style uf801() {
        return new KrasLayerStyleSld.Style("산지관리/보전준보전산지", "Y", "rgba(51,153,102,100)", 2.0, "Y",
                "rgba(51,204,102,0.3)", 500, 5000, "Y", "remark", "Dialog", "normal", 12, "rgba(0,0,0,255)",
                "rgba(0,0,0,1.0)", 1, 5000, "center", 0.0);
    }

    @Test
    void readsDhcColorsTheWayBrowsersReadCssRgba() {
        assertThat(KrasLayerStyleSld.color("rgba(51,153,102,100)")).containsExactly("#339966", "1");
        assertThat(KrasLayerStyleSld.color("rgba(255,47,47,255)")).containsExactly("#FF2F2F", "1");
        assertThat(KrasLayerStyleSld.color("rgba(51,204,102,0.3)")).containsExactly("#33CC66", "0.3");
        assertThat(KrasLayerStyleSld.color("rgba(163,73,164,0.0)")).containsExactly("#A349A4", "0");
        assertThat(KrasLayerStyleSld.color("#a349a4")).containsExactly("#A349A4", "1");
        assertThat(KrasLayerStyleSld.color("없음")).isNull();
        assertThat(KrasLayerStyleSld.color(null)).isNull();
    }

    @Test
    void buildsPortalStyleWithOrWithoutTheScaleRange() {
        String withScale = KrasLayerStyleSld.sld("v_lt_c_uzone_uf801", uf801(), true, Set.of("remark", "mnum"));
        assertThat(withScale).contains("<Name>v_lt_c_uzone_uf801</Name>",
                "<CssParameter name=\"fill\">#33CC66</CssParameter><CssParameter name=\"fill-opacity\">0.3</CssParameter>",
                "<CssParameter name=\"stroke\">#339966</CssParameter><CssParameter name=\"stroke-opacity\">1</CssParameter>"
                        + "<CssParameter name=\"stroke-width\">2</CssParameter>",
                "<Rule><MinScaleDenominator>500</MinScaleDenominator><MaxScaleDenominator>5000</MaxScaleDenominator><PolygonSymbolizer>",
                "<ogc:PropertyName>remark</ogc:PropertyName>",
                // 글꼴이 없으면 한글 라벨이 사라진다
                "<CssParameter name=\"font-family\">Dialog</CssParameter>", "<Halo><Radius>1</Radius>",
                "<AnchorPointX>0.5</AnchorPointX>");

        String always = KrasLayerStyleSld.sld("v_lt_c_uzone_uf801", uf801(), false, Set.of("remark"));
        assertThat(always).contains("<Rule><PolygonSymbolizer>")
                .doesNotContain("<MinScaleDenominator>500")
                // 라벨 축척(label_scale)은 그대로 둔다
                .contains("<Rule><MaxScaleDenominator>5000</MaxScaleDenominator><TextSymbolizer>");
    }

    @Test
    void dropsLabelsForMissingColumnsAndEscapesDatabaseValues() {
        String sld = KrasLayerStyleSld.sld("v_lt_c_uzone_uf801", uf801(), true, Set.of("mnum"));
        assertThat(sld).doesNotContain("TextSymbolizer");

        var odd = new KrasLayerStyleSld.Style(null, "Y", "rgba(0,0,0,255)", 1.5, "N", null, 0, 0, "Y", "nm",
                "A&B<\"font\">", "bold", 10, null, null, 0, 0, "left", 15.0);
        String escaped = KrasLayerStyleSld.sld("lay<er", odd, true, Set.of("nm"));
        assertThat(escaped).contains("<Name>lay&lt;er</Name>", "A&amp;B&lt;&quot;font&quot;&gt;",
                "<CssParameter name=\"font-weight\">bold</CssParameter>", "<AnchorPointX>0.0</AnchorPointX>",
                "<Rotation>15</Rotation>", "<CssParameter name=\"stroke-width\">1.5</CssParameter>")
                .doesNotContain("<PolygonSymbolizer><Fill>", "ScaleDenominator", "<Halo>");
    }

    @Test
    void addsHighlightOnTopOfPortalStyleForAPickedRow() {
        String picked = KrasLayerStyleSld.withHighlight("v_lt_c_uzone_uf801",
                KrasLayerStyleSld.sld("v_lt_c_uzone_uf801", uf801(), false, Set.of("remark")));
        assertThat(picked).contains("<CssParameter name=\"fill\">#33CC66</CssParameter>")
                .endsWith(KrasLayerStyleSld.HIGHLIGHT_RULE + "</FeatureTypeStyle></UserStyle></NamedLayer></StyledLayerDescriptor>");
        // 포털 스타일이 없는 레이어도 강조는 그린다.
        assertThat(KrasLayerStyleSld.withHighlight("lt_c_uzone", null))
                .contains("<Name>lt_c_uzone</Name>", "<CssParameter name=\"stroke\">#FFD400</CssParameter>");
    }
}
