// Java port of tests/xml_tei_tests.py
package io.github.danielonet.trafilatura.tests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.danielonet.trafilatura.lxml.Element;
import io.github.danielonet.trafilatura.lxml.Etree;
import io.github.danielonet.trafilatura.Settings.Document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.danielonet.trafilatura.lxml.Etree.fromstring;
import static io.github.danielonet.trafilatura.lxml.Etree.tostring;
import static io.github.danielonet.trafilatura.Xml._handle_unwanted_tails;
import static io.github.danielonet.trafilatura.Xml._move_element_one_level_up;
import static io.github.danielonet.trafilatura.Xml._wrap_unwanted_siblings_of_div;
import static io.github.danielonet.trafilatura.Xml.check_tei;
import static io.github.danielonet.trafilatura.Xml.replace_element_text;
import static io.github.danielonet.trafilatura.Xml.write_fullheader;

/** Test for transformation to TEI. */
class XmlTeiTests {
    /** A Python tuple with possibly None members. */
    static List<String> t(String... values) {
        return Arrays.asList(values);
    }

    static List<String> tags(Iterable<Element> elements) {
        List<String> result = new ArrayList<>();
        for (Element e : elements) {
            result.add(e.tag);
        }
        return result;
    }

    /** [(e.tag, e.text, e.tail) for e in elements] */
    static List<List<String>> triples(Iterable<Element> elements) {
        List<List<String>> result = new ArrayList<>();
        for (Element e : elements) {
            result.add(t(e.tag, e.text, e.tail));
        }
        return result;
    }

    /** [(e.tag, e.text, e.tail.strip() if e.tail is not None else None) for e in elements] */
    static List<List<String>> strippedTriples(Iterable<Element> elements) {
        List<List<String>> result = new ArrayList<>();
        for (Element e : elements) {
            result.add(t(e.tag, e.text, e.tail == null ? null : e.tail.strip()));
        }
        return result;
    }

    static String unicode(Element e) {
        return tostring(e, "unicode");
    }

    @Test
    void test_sanity() {
        Element element = Etree.Element("p");
        element.text = "test";
        Element backup = element.copy();
        _handle_unwanted_tails(element);
        assertEquals(backup.text, element.text);
        element.tail = "tail";
        backup = element.copy();
        _handle_unwanted_tails(element);
        assertEquals("test tail", element.text);
        Element div_elem = Etree.Element("div");
        div_elem.append(backup);
        _handle_unwanted_tails(backup);
        assertEquals("<div><p>test tail</p></div>", tostring(div_elem));

        Element new_elem = Etree.Element("div");
        _wrap_unwanted_siblings_of_div(new_elem);
        assertNotNull(new_elem);

        Element p_elem = Etree.SubElement(new_elem, "p");
        _move_element_one_level_up(p_elem);
        assertEquals("<div><p/></div>", tostring(new_elem));

        Element head = Etree.Element("head");
        Element result = check_tei(head, "");
        assertSame(head, result);
    }

    /** Each run of block siblings after a div gets its own wrapper, up to the next div. */
    @Test
    void test_wrap_unwanted_siblings_of_div_runs() {
        Element body = fromstring("<body><div/><p>a</p><p>b</p><lb/><p>c</p><div/><p>d</p></body>");
        _wrap_unwanted_siblings_of_div(body.get(0));
        assertEquals("<body><div/><div><p>a</p><p>b</p></div><lb/><div><p>c</p></div><div/><p>d</p></body>", tostring(body));
    }

    static Element publicationStmt(Document metadata) {
        Element teidoc = Etree.Element("TEI", "xmlns", "http://www.tei-c.org/ns/1.0");
        Element header = write_fullheader(teidoc, metadata);
        return header.find(".//{*}fileDesc/{*}publicationStmt");
    }

    @Test
    void test_publisher_added_before_availability_in_publicationStmt() {
        // add publisher string
        Document metadata = new Document();
        metadata.sitename = "The Publisher";
        metadata.license = "CC BY-SA 4.0";
        metadata.categories = metadata.tags = List.of("cat");
        Element publicationstmt = publicationStmt(metadata);
        assertEquals(List.of("publisher", "availability"), tags(publicationstmt.getchildren()));
        assertEquals("The Publisher", publicationstmt.get(0).text);

        metadata = new Document();
        metadata.hostname = "example.org";
        metadata.license = "CC BY-SA 4.0";
        metadata.categories = metadata.tags = List.of("cat");
        publicationstmt = publicationStmt(metadata);
        assertEquals(List.of("publisher", "availability"), tags(publicationstmt.getchildren()));
        assertEquals("example.org", publicationstmt.get(0).text);

        metadata = new Document();
        metadata.hostname = "example.org";
        metadata.sitename = "Example";
        metadata.license = "CC BY-SA 4.0";
        metadata.categories = metadata.tags = List.of("cat");
        publicationstmt = publicationStmt(metadata);
        assertEquals(List.of("publisher", "availability"), tags(publicationstmt.getchildren()));
        assertEquals("Example (example.org)", publicationstmt.get(0).text);
        // no publisher, add "N/A"
        metadata = new Document();
        metadata.categories = metadata.tags = List.of("cat");
        metadata.license = "CC BY-SA 4.0";
        publicationstmt = publicationStmt(metadata);
        assertEquals(List.of("publisher", "availability"), tags(publicationstmt.getchildren()));
        assertEquals("N/A", publicationstmt.get(0).text);
        // no license, add nothing
        metadata = new Document();
        metadata.categories = metadata.tags = List.of("cat");
        publicationstmt = publicationStmt(metadata);
        assertEquals(List.of("p"), tags(publicationstmt.getchildren()));
    }

    static List<String> divTags(String xml) {
        Element cleaned = check_tei(fromstring(xml), "fake_url");
        return tags(cleaned.find(".//div").iter());
    }

    static String bodyString(String xml) {
        Element cleaned = check_tei(fromstring(xml), "fake_url");
        return unicode(cleaned.find(".//body"));
    }

    @Test
    void test_unwanted_siblings_of_div_removed() {
        assertEquals(List.of("div", "div", "p", "div", "p"),
                divTags("<TEI><text><body><div><div><p>text1</p></div><p>text2</p></div></body></text></TEI>"));
        assertEquals("<body><div><div><p>text1</p></div><div><p>text2</p></div></div></body>",
                bodyString("<TEI><text><body><div><div><p>text1</p></div><p>text2</p></div></body></text></TEI>"));
        assertEquals(List.of("div", "div", "div", "list", "item"),
                divTags("<TEI><text><body><div><div/><list><item>text</item></list></div></body></text></TEI>"));
        assertEquals(List.of("div", "div", "div", "table", "row", "cell"),
                divTags("<TEI><text><body><div><div/><table><row><cell>text</cell></row></table></div></body></text></TEI>"));
        assertEquals("<body><div><p>text1</p><div/><div/><div><p>text2</p></div></div></body>",
                bodyString("<TEI><text><body><div><p>text1</p><div/><div/><p>text2</p></div></body></text></TEI>"));
        assertEquals(List.of("div", "div", "p", "div", "p", "p"),
                divTags("<TEI><text><body><div><div><p>text1</p></div><p>text2</p><p>text3</p></div></body></text></TEI>"));
        assertEquals(List.of("div", "p", "div", "div", "p", "div", "div", "p"),
                divTags("<TEI><text><body><div><p>text1</p><div/><p>text2</p><div/><p>text3</p></div></body></text></TEI>"));
        assertEquals(List.of("div", "p", "div", "div", "p", "div", "div", "list"),
                divTags("<TEI><text><body><div><p>text1</p><div/><p>text2</p><div/><list/></div></body></text></TEI>"));
        assertEquals("<body><div><p/><div/><div><p>text1</p><ab/><p>text2</p></div></div></body>",
                bodyString("<TEI><text><body><div><p/><div/><p>text1</p><ab/><p>text2</p></div></body></text></TEI>"));
        assertEquals("<body><div><div/><div><ab/></div></div></body>",
                bodyString("<TEI><text><body><div><div/><ab/></div></body></text></TEI>"));
        assertEquals(List.of("div", "div", "div", "quote"),
                divTags("<TEI><text><body><div><div/><quote>text</quote></div></body></text></TEI>"));
        assertEquals(List.of("div", "div", "lb"), divTags("<TEI><text><body><div><div/><lb/></div></body></text></TEI>"));
        assertEquals(List.of("div", "div", "div", "ab"), divTags("<TEI><text><body><div><div/><ab/></div></body></text></TEI>"));
        assertEquals("<body><div><div><p>text1</p></div><div><list/></div></div></body>",
                bodyString("<TEI><text><body><div><div><p>text1</p></div><list/><ul><li>text2</li></ul></div></body></text></TEI>"));
        assertEquals("<body><div><div><p>text1</p></div><div><list/></div></div></body>",
                bodyString("<TEI><text><body><div><div><p>text1</p></div><ul><li>text2</li></ul><list/></div></body></text></TEI>"));
        String xml_str = "<TEI><text><body><div><p/><div/></div></body></text></TEI>";
        assertEquals(xml_str, unicode(check_tei(fromstring(xml_str), "fake_url")));
        assertEquals(List.of("div", "div", "div", "p"), divTags("<TEI><text><body><div><div/><lb/>tail</div></body></text></TEI>"));
    }

    @Test
    void test_tail_on_p_like_elements_removed() {
        Element xml_doc = fromstring("""

                    <TEI><text><body>
                      <div>
                        <p>text</p>former link
                        <p>more text</p>former span
                        <p>even more text</p>another span
                      </div>
                    </body></text></TEI>""".stripLeading());
        Element cleaned = check_tei(xml_doc, "fake_url");
        List<List<String>> result = new ArrayList<>();
        for (Element el : cleaned.iter("p")) {
            result.add(t(el.text, el.tail));
        }
        assertEquals(List.of(t("text former link", null), t("more text former span", null),
                t("even more text another span", null)), result);
        cleaned = check_tei(fromstring("<TEI><text><body><div><head>title</head>some text<p>article</p></div></body></text></TEI>"),
                "fake_url");
        assertEquals(List.of(t("ab", "title", null), t("p", "some text", null), t("p", "article", null)),
                triples(cleaned.find(".//div").iterdescendants()));
        cleaned = check_tei(fromstring("<TEI><text><body><div><ab>title</ab>tail<p>more text</p></div></body></text></TEI>"),
                "fake_url");
        assertEquals(List.of(t("ab", "title", null), t("p", "tail", null), t("p", "more text", null)),
                triples(cleaned.find(".//div").iterdescendants()));
        cleaned = check_tei(fromstring("<TEI><text><body><div><p>text</p><lb/>tail</div></body></text></TEI>"), "fake_url");
        assertEquals(List.of(t("p", "text", null), t("p", "tail", null)), triples(cleaned.find(".//div").iterdescendants()));
        cleaned = check_tei(fromstring("<TEI><text><body><div><p/>tail</div></body></text></TEI>"), "fake_url");
        assertEquals(List.of(t("p", "tail", null)), triples(cleaned.find(".//p").iter()));
        cleaned = check_tei(fromstring("<TEI><text><body><div><p/>tail1<ab/>tail2<ab/>tail3</div></body></text></TEI>"), "fake_url");
        List<String> texts = new ArrayList<>();
        for (Element elem : cleaned.iter()) {
            if (elem.text != null && !elem.text.isEmpty()) {
                texts.add(elem.text);
            }
        }
        assertEquals(List.of("tail1", "tail2", "tail3"), texts);
    }

    @Test
    void test_head_with_children_converted_to_ab() {
        Element cleaned = check_tei(fromstring("<text><head>heading</head><p>some text</p></text>"), "fake_url");
        List<Object> mixed = new ArrayList<>();
        for (Element child : cleaned.iter()) {
            mixed.add(child.text != null ? t(child.tag, child.text) : child.tag);
        }
        assertEquals(List.of("text", t("ab", "heading"), t("p", "some text")), mixed);
        cleaned = check_tei(fromstring("<text><head><p>text</p></head></text>"), "fake_url");
        assertEquals(List.of(t("text", null, null), t("ab", "text", null)), triples(cleaned.iter()));
        cleaned = check_tei(fromstring("<text><head><p>first</p><p>second</p><p>third</p></head></text>"), "fake_url");
        assertEquals(List.of(t("text", null, null), t("ab", "first", null), t("lb", null, "second"), t("lb", null, "third")),
                triples(cleaned.iter()));
        cleaned = check_tei(fromstring(
                "<text><head><p>first</p><list><item>text</item></list><p>second</p><p>third</p></head></text>"), "fake_url");
        assertEquals(List.of(t("text", null, null), t("ab", "first", null), t("list", null, "second"), t("item", "text", null),
                t("lb", null, "third")), triples(cleaned.iter()));
        cleaned = check_tei(fromstring("<text><head><list><item>text1</item></list><p>text2</p></head></text>"), "fake_url");
        assertEquals(List.of(t("text", null, null), t("ab", null, null), t("list", null, "text2"), t("item", "text1", null)),
                triples(cleaned.iter()));
        cleaned = check_tei(fromstring("<text><head>heading</head><p>some text</p></text>"), "fake_url");
        assertEquals(Map.of("type", "header"), cleaned.get(0).attrib);
        cleaned = check_tei(fromstring("<text><head rend='h3'>heading</head><p>some text</p></text>"), "fake_url");
        assertEquals(Map.of("type", "header", "rend", "h3"), cleaned.get(0).attrib);
        cleaned = check_tei(fromstring("<TEI><teiheader/><text><body><head><p>text</p></head></body></text></TEI>"), "fake_url");
        Element ab = cleaned.find(".//ab");
        assertEquals("text", ab.text);
        assertEquals(Map.of("type", "header"), ab.attrib);
        cleaned = check_tei(fromstring("<text><body><head>text1<p>text2</p></head></body></text>"), "fake_url");
        assertEquals(List.of(t("ab", "text1", null), t("lb", null, "text2")), triples(cleaned.find(".//ab").iter()));
        cleaned = check_tei(fromstring("""
                <text>
                            <body>
                                <head>text1
                                    <p>text2</p>
                                </head>
                            </body>
                        </text>
                    """.strip()), "fake_url");
        assertEquals(List.of(t("ab", "text1", null), t("lb", null, "text2")), triples(cleaned.find(".//ab").iter()));
        cleaned = check_tei(fromstring(
                "<TEI><text><body><head><list><item>text1</item></list><p>text2</p></head>tail</body></text></TEI>"), "fake_url");
        assertEquals(List.of(t("body", null, null), t("ab", null, null), t("list", null, "text2"), t("item", "text1", null),
                t("p", "tail", null)), triples(cleaned.find(".//body").iter()));
    }

    @Test
    void test_ab_with_p_parent_resolved() {
        Element cleaned = check_tei(fromstring("<text><p><head>text1</head></p></text>"), "fake_url");
        assertNotNull(cleaned.find(".//ab"));
        assertNull(cleaned.find(".//p"));
        cleaned = check_tei(fromstring("<body><p>text1<head>text2</head></p></body>"), "fake_url");
        Element result = cleaned.find(".//ab");
        assertEquals("body", result.getparent().tag);
        assertEquals("text2", result.text);
        cleaned = check_tei(fromstring("<TEI><text><body><p><head>text1</head></p>text2</body></text></TEI>"), "fake_url");
        assertEquals("text1", cleaned.find(".//ab").text);
        assertEquals("text2", cleaned.find(".//p").text);
        cleaned = check_tei(fromstring("<text><p><head rend='h3'>text</head></p></text>"), "fake_url");
        assertEquals(Map.of("type", "header", "rend", "h3"), cleaned.find("ab").attrib);
        cleaned = check_tei(fromstring("<text><p><head>text1</head><list/><head>text2</head></p></text>"), "fake_url");
        assertEquals(List.of(t("text", null, null), t("ab", "text1", null), t("p", null, null), t("list", null, null),
                t("ab", "text2", null)), triples(cleaned.iter()));
        cleaned = check_tei(fromstring("<TEI><text><body><p><head>text1</head>text2</p></body></text></TEI>"), "fake_url");
        assertEquals("text2", cleaned.find(".//p").text);
        cleaned = check_tei(fromstring("<TEI><text><body><p>text0<head>text1</head>text2</p></body></text></TEI>"), "fake_url");
        assertEquals("text2", cleaned.find(".//ab").getnext().text);
        cleaned = check_tei(fromstring("<TEI><text><body><p>text0<list/><head>text1</head>text2</p>text3</body></text></TEI>"),
                "fake_url");
        assertTrue(unicode(cleaned).contains("text2"));
        assertNotNull(cleaned.find(".//p/list"));
        Element xml_doc = fromstring("""
                <TEI>
                      <text><body>
                        <p>text1<head>text2</head>text3
                          <head>text4</head>text5
                        </p>
                      </body></text>
                    </TEI>""");
        check_tei(xml_doc, "fake_url");
        assertEquals(List.of(t("p", "text1", ""), t("ab", "text2", null), t("p", "text3", null), t("ab", "text4", null),
                t("p", "text5", null)), strippedTriples(xml_doc.iter(List.of("p", "ab"))));
        xml_doc = fromstring("""
                <TEI>
                      <text>
                        <body>
                          <p>text0<head>text1</head>
                            <list>
                              <item>text2</item>
                            </list>
                            <head>text3</head>text4
                          </p>
                        </body>
                      </text>
                    </TEI>""");
        check_tei(xml_doc, "fake_url");
        assertEquals(List.of(t("p", "text0", ""), t("ab", "text1", ""), t("p", null, null), t("ab", "text3", null),
                t("p", "text4", null)), strippedTriples(xml_doc.iter(List.of("p", "ab"))));
        xml_doc = fromstring("<TEI><text><body><p>text0<head>text1</head></p>text2</body></text></TEI>");
        check_tei(xml_doc, "fake_url");
        assertEquals(List.of(t("p", "text0", null), t("ab", "text1", null), t("p", "text2", null)),
                triples(xml_doc.iter(List.of("p", "ab"))));
        xml_doc = fromstring("<TEI><text><body><p>text1<head>text2</head>text3<head>text4</head></p></body></text></TEI>");
        cleaned = check_tei(xml_doc, "fake_url");
        assertEquals(List.of(t("p", "text1", null), t("ab", "text2", null), t("p", "text3", null), t("ab", "text4", null)),
                triples(cleaned.iter(List.of("p", "ab"))));
        cleaned = check_tei(fromstring("<text><head>text1</head><p>text2<head>text3</head></p></text>"), "fake_url");
        assertEquals(List.of(t("ab", "text1", null), t("p", "text2", null), t("ab", "text3", null)), triples(cleaned));
        cleaned = check_tei(fromstring("<text><p>text1<head>text2</head></p><head>text3</head></text>"), "fake_url");
        assertEquals(List.of(t("p", "text1", null), t("ab", "text2", null), t("ab", "text3", null)), triples(cleaned));
        xml_doc = fromstring("""

                        <text>
                            <head>text1<p>text2</p></head>
                            <p>text3<head>text4</head></p>
                        </text>""".strip(), true);
        cleaned = check_tei(xml_doc, "fake_url");
        assertEquals(List.of(t("text", null, null), t("ab", "text1", null), t("lb", null, "text2"), t("p", "text3", null),
                t("ab", "text4", null)), triples(cleaned.iter()));
        xml_doc = fromstring("""

                        <TEI>
                        <text>
                          <body>
                            <p>text1<head>
                                    <list>
                                        <item>text2</item>
                                    </list>
                                </head>
                            text3
                            </p>
                          </body>
                        </text>
                        </TEI>
                        """.strip(), true);
        cleaned = check_tei(xml_doc, "fake_url");
        assertEquals(List.of(t("p", "text1", null), t("ab", null, null), t("item", "text2", null), t("p", "text3", null)),
                triples(cleaned.iter(List.of("p", "ab", "item"))));
    }

    @Test
    void test_handling_of_text_content_in_div() {
        Element cleaned = check_tei(fromstring("<TEI><text><body><div>text<head/></div></body></text></TEI>"), "fake_url");
        assertEquals("text", cleaned.find(".//p").text);
        cleaned = check_tei(fromstring("<TEI><text><body><div>text1<p>text2</p></div></body></text></TEI>"), "fake_url");
        assertEquals("text1 text2", cleaned.find(".//p").text);
        cleaned = check_tei(fromstring("<TEI><text><body><div>text<p/></div></body></text></TEI>"), "fake_url");
        assertEquals("text", cleaned.find(".//p").text);
        cleaned = check_tei(fromstring("<TEI><text><body><div><p/></div>tail</body></text></TEI>"), "fake_url");
        assertEquals("tail", cleaned.find(".//p").text);
    }

    @Test
    void test_replace_element_text() {
        Element elem = Etree.Element("head");
        elem.text = "Title";
        elem.set("rend", "h1");
        assertEquals("# Title", replace_element_text(elem, true));

        elem = Etree.Element("hi");
        elem.text = "Text";
        elem.set("rend", "#b");
        assertEquals("**Text**", replace_element_text(elem, true));

        elem = Etree.Element("item");
        elem.text = "Test text";
        elem.tag = "item";
        assertEquals("Test text", replace_element_text(elem, true)); // the list marker is added by the serializer

        elem = Etree.Element("ref");
        elem.text = "Link";
        elem.set("target", "https://example.com");
        assertEquals("[Link](https://example.com)", replace_element_text(elem, true));

        elem = Etree.Element("ref");
        elem.text = "Link";
        assertEquals("[Link]", replace_element_text(elem, true));
    }

    /** Merging an invalid element into its list item must not inject a markdown list marker into the TEI tree. */
    @Test
    void test_no_markdown_marker_in_merged_item_content() {
        Element doc = fromstring("<TEI><text><body><div><list><item><foo>bar</foo></item></list></div></body></text></TEI>");
        check_tei(doc, null);
        assertTrue(unicode(doc).endsWith("<list><item>bar</item></list></div></body></text></TEI>"));
    }
}
