package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

class AccountWsPhoneExportMapperSqlShapeTest {

    private static final String MAPPER_XML = "/mapper/account/AccountMapper.xml";
    private static final String NON_EMPTY_IDS = "ids != null and ids.size() > 0";
    private static final String EMPTY_IDS = "ids == null or ids.size() == 0";

    @Test
    void exportQueryDoesNotFilterByAccountState() throws Exception {
        Element select = selectElement("selectWsPhonesByIds");
        Element nonEmpty = branch(select, NON_EMPTY_IDS);
        String sql = normalizedSql(nonEmpty);

        assertThat(select.getAttribute("resultType"))
                .isEqualTo("com.armada.account.model.vo.AccountWsPhoneExportRow");
        assertThat(sql).isEqualTo(
                "SELECT a.id, a.ws_phone AS wsPhone "
                        + "FROM account a "
                        + "WHERE a.deleted_at IS NULL "
                        + "AND a.id IN #{id} "
                        + "ORDER BY a.id ASC");

        List<Element> foreachElements = childElements(nonEmpty, "foreach");
        assertThat(foreachElements).as("non-empty ID branch foreach").hasSize(1);
        Element foreach = foreachElements.get(0);
        assertThat(foreach.getAttribute("collection")).isEqualTo("ids");
        assertThat(foreach.getAttribute("item")).isEqualTo("id");
        assertThat(foreach.getAttribute("open")).isEqualTo("(");
        assertThat(foreach.getAttribute("separator")).isEqualTo(",");
        assertThat(foreach.getAttribute("close")).isEqualTo(")");
        assertThat(sql).doesNotContain(
                "account_state", "accountState", "normalAccountState",
                "#{tenantId}", "#{tenant_id}");
    }

    @Test
    void exportQueryUsesAccountBasedZeroRowFallbackForEmptyIds() throws Exception {
        Element select = selectElement("selectWsPhonesByIds");
        Element empty = branch(select, EMPTY_IDS);
        String sql = normalizedSql(empty);

        assertThat(childElements(select, "if")).hasSize(2);
        assertThat(sql)
                .isEqualTo("SELECT a.id, a.ws_phone AS wsPhone FROM account a WHERE 1=0")
                .contains("FROM account a")
                .doesNotContain("DUAL", " IN ", "#{tenantId}", "#{tenant_id}");
    }

    private Element selectElement(String id) throws Exception {
        InputStream stream = getClass().getResourceAsStream(MAPPER_XML);
        assertThat(stream).as("mapper resource " + MAPPER_XML + " exists").isNotNull();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        Document document;
        try (stream) {
            document = builder.parse(stream);
        }

        NodeList selects = document.getElementsByTagName("select");
        for (int i = 0; i < selects.getLength(); i++) {
            Element select = (Element) selects.item(i);
            if (id.equals(select.getAttribute("id"))) {
                return select;
            }
        }
        throw new AssertionError("mapper select " + id + " does not exist");
    }

    private static Element branch(Element select, String test) {
        return childElements(select, "if").stream()
                .filter(element -> test.equals(element.getAttribute("test")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing if branch: " + test));
    }

    private static List<Element> childElements(Element parent, String tagName) {
        List<Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element && tagName.equals(element.getTagName())) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static String normalizedSql(Element branch) {
        return branch.getTextContent().replaceAll("\\s+", " ").trim();
    }
}
