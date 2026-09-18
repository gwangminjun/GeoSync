package geosync.common.xml;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;

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

    private XmlUtil() {}
}
