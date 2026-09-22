package geosync.common.xml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

public final class XmlUtil {

    private static final ThreadLocal<DocumentBuilder> BUILDER = ThreadLocal.withInitial(() -> {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return dbf.newDocumentBuilder();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    });

    public static Document parse(byte[] xml) throws Exception {
        return BUILDER.get().parse(new ByteArrayInputStream(xml));
    }

    public static String textOf(Document doc, String tagName) {
        NodeList nl = doc.getElementsByTagName(tagName);
        return nl.getLength() > 0 ? nl.item(0).getTextContent() : null;
    }

    /** el의 자손 중 tagName — 반복 그룹 안에서 항목별로 스코프를 좁혀 읽을 때 쓴다(Document 전역 조회와 다름). */
    public static String textOf(Element el, String tagName) {
        NodeList nl = el.getElementsByTagName(tagName);
        return nl.getLength() > 0 ? nl.item(0).getTextContent() : null;
    }

    /** tagName을 가진 모든 요소를 문서 순서대로 반환한다 — 반복 그룹(SHR_YMB_SET > SHR_YMB 등) 순회용. */
    public static List<Element> elementsOf(Document doc, String tagName) {
        return elementsOf(doc.getElementsByTagName(tagName));
    }

    /** el의 자손 중 tagName인 요소들 — 중첩 반복(LAND_MOV_HIST 안의 RELJIBUN 등) 순회용. */
    public static List<Element> elementsOf(Element el, String tagName) {
        return elementsOf(el.getElementsByTagName(tagName));
    }

    /**
     * probeTag를 가진 요소들의 <b>부모</b>를 문서 순서대로(중복 제거) 반환한다.
     * 반복 그룹의 감싸는 태그 이름을 모를 때 쓴다 — "USE_ZONE_SET &gt; USE_ZONE"인지 다른 이름인지
     * 몰라도, 항목마다 반드시 있는 필드 하나(probeTag)만 알면 반복 단위를 찾아낼 수 있다.
     * 반복이 없는 평평한 응답이면 결과가 1개(=BODY)라 같은 코드로 처리된다.
     */
    public static List<Element> repeatUnitsContaining(Document doc, String probeTag) {
        List<Element> parents = new ArrayList<>();
        NodeList nl = doc.getElementsByTagName(probeTag);
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i).getParentNode() instanceof Element parent && !parents.contains(parent)) {
                parents.add(parent);
            }
        }
        return parents;
    }

    /** 응답에 실제로 들어있는 태그 이름을 모아 반환한다 — 기대한 태그가 없을 때 에러 메시지에 붙이는 용도. */
    public static List<String> tagNamesIn(Document doc) {
        List<String> names = new ArrayList<>();
        NodeList nl = doc.getElementsByTagName("*");
        for (int i = 0; i < nl.getLength(); i++) {
            String name = nl.item(i).getNodeName();
            if (!names.contains(name)) names.add(name);
        }
        return names;
    }

    private static List<Element> elementsOf(NodeList nl) {
        List<Element> result = new ArrayList<>(nl.getLength());
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element el) {
                result.add(el);
            }
        }
        return result;
    }

    private XmlUtil() {}
}
