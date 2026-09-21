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
        NodeList nl = doc.getElementsByTagName(tagName);
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
