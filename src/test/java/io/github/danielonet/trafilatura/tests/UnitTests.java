// Java port of tests/unit_tests.py
package io.github.danielonet.trafilatura.tests;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.danielonet.trafilatura.compat.ConfigParser;
import io.github.danielonet.trafilatura.compat.Json;
import io.github.danielonet.trafilatura.compat.Pair;
import io.github.danielonet.trafilatura.compat.Py;
import io.github.danielonet.trafilatura.compat.Triple;
import io.github.danielonet.trafilatura.lxml.Element;
import io.github.danielonet.trafilatura.lxml.Etree;
import io.github.danielonet.trafilatura.lxml.XPath;
import io.github.danielonet.trafilatura.Baseline;
import io.github.danielonet.trafilatura.Core;
import io.github.danielonet.trafilatura.Deduplication;
import io.github.danielonet.trafilatura.External;
import io.github.danielonet.trafilatura.Htmlprocessing;
import io.github.danielonet.trafilatura.JsonMetadata;
import io.github.danielonet.trafilatura.MainExtractor;
import io.github.danielonet.trafilatura.Meta;
import io.github.danielonet.trafilatura.Metadata;
import io.github.danielonet.trafilatura.ReadabilityLxml;
import io.github.danielonet.trafilatura.Settings;
import io.github.danielonet.trafilatura.Settings.Document;
import io.github.danielonet.trafilatura.Settings.Extractor;
import io.github.danielonet.trafilatura.Utils;
import io.github.danielonet.trafilatura.Xml;
import io.github.danielonet.trafilatura.Xpaths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.danielonet.trafilatura.tests.T.b;

/** Unit tests for the trafilatura library. */
public class UnitTests {
    static final Path TEST_DIR = Conftest.TEST_DIR;
    static final Path RESOURCES_DIR = Conftest.RESOURCES_DIR;
    static final Document SAMPLE_META = new Document();

    // fresh copy: must not mutate the package-global DEFAULT_CONFIG
    static final ConfigParser ZERO_CONFIG = Settings.use_config();

    static {
        ZERO_CONFIG.set("DEFAULT", "MIN_OUTPUT_SIZE", "0");
        ZERO_CONFIG.set("DEFAULT", "MIN_EXTRACTED_SIZE", "0");
    }

    static final ConfigParser NEW_CONFIG = Settings.use_config(RESOURCES_DIR.resolve("newsettings.cfg").toString());

    static final String _INTRO = "enough intro text here for extraction";

    /** Extraction keywords with config defaulting to ZERO_CONFIG (min-sizes 0). */
    static Core.Kwargs zkw() {
        return Core.kw().config(ZERO_CONFIG);
    }

    /** {@code f(**kwargs)}: applies a dict of keyword arguments to a kwargs builder through its setters. */
    static <K> K withKwargs(K builder, Map<String, Object> kwargs) {
        kwargs.forEach((name, value) -> {
            java.lang.reflect.Method setter = Arrays.stream(builder.getClass().getMethods())
                    .filter(m -> m.getName().equals(name) && m.getParameterCount() == 1)
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("unexpected keyword argument " + name));
            try {
                setter.invoke(builder, value);
            } catch (ReflectiveOperationException err) {
                throw new IllegalStateException(err);
            }
        });
        return builder;
    }

    /** Wrap body in an article doc and extract; config defaults to ZERO_CONFIG (min-sizes 0). */
    static String _extract_doc(String body, boolean intro, Core.Kwargs kwargs) {
        String inner = intro ? "<p>" + _INTRO + "</p>" + body : body;
        return Core.extract("<html><body><article>" + inner + "</article></body></html>", kwargs);
    }

    static String _extract_doc(String body, boolean intro) {
        return _extract_doc(body, intro, zkw());
    }

    static String _table_md(String table, Core.Kwargs kwargs) {
        return _extract_doc(table, true, kwargs.output_format("markdown").include_tables(true));
    }

    static String _table_txt(String table) {
        return _extract_doc(table, false, zkw().output_format("txt").fast(true).include_tables(true));
    }

    static String _md_inline(String body, Core.Kwargs kwargs) {
        return Py.or(_extract_doc(body, true, kwargs.output_format("markdown").include_formatting(true)), "");
    }

    /** Serialize an XML snippet to markdown (or plain text), stripped. */
    static String _md(Object body, boolean include_formatting) {
        Element tree = Etree.fromstring(body instanceof byte[] bytes ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : (String) body);
        return Py.strip(Xml.xmltotxt(tree, include_formatting));
    }

    static String _md(Object body) {
        return _md(body, true);
    }

    /** Whether any of the expressions selects a <tag attrs> wrapping a paragraph. */
    static boolean _xpath_hits(List<XPath> xpaths, String attrs, String tag) {
        Element root = Etree.fromstring("<html><body><" + tag + " " + attrs + "><p>content</p></" + tag + "></body></html>");
        return xpaths.stream().anyMatch(x -> x.evaluate(root).size() > 0);
    }

    static boolean _xpath_hits(List<XPath> xpaths, String attrs) {
        return _xpath_hits(xpaths, attrs, "div");
    }

    static Element _handle_table(String table, boolean recall) {
        return MainExtractor.handle_table(Etree.html_fromstring(table), Settings.TAG_CATALOG,
                new Extractor(Extractor.kw().recall(recall)));
    }

    static Element _handle_table(String table) {
        return _handle_table(table, false);
    }

    static final Map<String, String> MOCK_PAGES = Map.of("http://exotic_tags", "exotic_tags.html");

    /** load mock page from samples */
    static String load_mock_page(String url, boolean xml_flag, String langcheck, boolean tei_output) {
        String htmlstring = Utils.decode_file(Conftest.read_bytes(RESOURCES_DIR.resolve(MOCK_PAGES.get(url))));
        String output_format = xml_flag ? "xml" : tei_output ? "xmltei" : "txt";
        return Core.extract(htmlstring, Core.kw().url(url).record_id("0000").output_format(output_format)
                .target_language(langcheck));
    }

    static String load_mock_page(String url, boolean xml_flag) {
        return load_mock_page(url, xml_flag, null, false);
    }

    static String load_mock_page(String url) {
        return load_mock_page(url, false, null, false);
    }

    /** test string trimming */
    @Test
    void test_trim() {
        assertEquals("Test", Utils.trim("\tTest  "));
        assertEquals("Test Test", Utils.trim("\t\tTest  Test\r\n"));
        Element my_elem = Etree.Element("body");
        my_elem.text = "Test Text";
        assertFalse(Utils.textfilter(my_elem));
        my_elem.text = "Instagram";
        assertTrue(Utils.textfilter(my_elem));
        my_elem.text = "\t\t";
        assertTrue(Utils.textfilter(my_elem));
        assertNull(Utils.sanitize(null));
        assertEquals("Test Text", Utils.sanitize("Test&nbsp;Text"));
        // clear cache: no functools.lru_cache memoization in the Java port, cache_info() has no equivalent
        Meta.reset_caches();
    }

    /** reset_caches() must empty every trafilatura-owned cache, not just trim. */
    @Test
    void test_reset_caches() {
        // trim, line_processing and return_printables_and_spaces are not memoized in Java: only the LRU cache remains
        Deduplication.LRU_TEST.put("k", 1);
        assertTrue(Py.truthy(Deduplication.LRU_TEST.cache));
        Meta.reset_caches();
        assertFalse(Py.truthy(Deduplication.LRU_TEST.cache));
    }

    /** test if loaded strings/trees are handled properly */
    @Test
    void test_input() {
        Extractor options = new Extractor();
        byte[] teststring = "高山云雾出好茶".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(List.of("utf-8"), Utils.detect_encoding(teststring));
        teststring = "高山云雾出好茶".getBytes(java.nio.charset.Charset.forName("gb18030"));
        // the 7-character sample alone: see test_input_short_gb18030 (known deviation)
        assertTrue(Utils.detect_encoding(DownloadsTests.repeat(teststring, 1000)).contains("gb18030"));
        assertTrue(Utils.is_dubious_html("This is a string."));
        String htmlstring = "<!DOCTYPE html PUBLIC />\n<html></html>";
        String beginning = Py.slice(htmlstring, null, 50).toLowerCase();
        assertEquals("\n<html></html>", Utils.repair_faulty_html(htmlstring, beginning));
        htmlstring = "<html>\n</html>";
        beginning = Py.slice(htmlstring, null, 50).toLowerCase();
        assertEquals(htmlstring, Utils.repair_faulty_html(htmlstring, beginning));
        htmlstring = "<html/>\n</html>";
        beginning = Py.slice(htmlstring, null, 50).toLowerCase();
        assertEquals("<html>\n</html>", Utils.repair_faulty_html(htmlstring, beginning));
        htmlstring = "<!DOCTYPE html>\n<html lang=\"en-US\"/>\n<head/>\n<body/>\n</html>";
        beginning = Py.slice(htmlstring, null, 50).toLowerCase();
        assertEquals("<!DOCTYPE html>\n<html lang=\"en-US\">\n<head/>\n<body/>\n</html>", Utils.repair_faulty_html(htmlstring, beginning));
        htmlstring = "<!DOCTYPE html><html><head></head><body>Foo <br/> Bar</body></html>";
        beginning = Py.slice(htmlstring, null, 50).toLowerCase();
        assertEquals("<!DOCTYPE html><html><head></head><body>Foo <br/> Bar</body></html>\n", Utils.repair_faulty_html(htmlstring, beginning));
        htmlstring = "<html><body><p>a\u0000b\u001dc￾￿d</p>\t<p>keep\tme</p></body></html>";
        assertEquals("<html><body><p>abcd</p>\t<p>keep\tme</p></body></html>", Utils.repair_faulty_html(htmlstring, Py.slice(htmlstring, null, 50).toLowerCase()));
        String page = "<html><body><article>" + "<p>Long enough article paragraph\u001d for baseline￿ to trigger.</p>".repeat(3) + "</article></body></html>";
        assertTrue(Baseline.baseline(page).third() > 0);
        assertNotNull(Core.extract(page, Core.kw().fast(true)));
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class, () -> Utils.load_html(123));
        assertTrue(err.getMessage().contains("incompatible"));
        assertNotNull(Utils.load_html("<html><body>ÄÖÜ</body></html>"));
        assertNotNull(Utils.load_html(new byte[] {60, 104, 116, 109, 108, 62, 60, 98, 111, 100, 121, 62, 47, 46, -97, 60, 47, 98, 111, 100, 121, 62, 60, 47, 104, 116, 109, 108, 62}));
        assertNotNull(Utils.load_html("<html><body>\u002f\u002e\u009f</body></html>".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        assertNull(Core.extract(null, Core.kw().url("url").target_language(null)));
        byte[] myinput = Conftest.read_bytes(Conftest.RESOURCES_DIR.resolve("webpage.html.gz"));
        assertTrue(Core.extract(myinput).contains("Long story short,"));
        // duck-typed response object: the Java port accepts Utils.Response
        assertNotNull(Utils.load_html(new Utils.Response(b("<html><body><p>response data</p></body></html>"), 200, "")));
        assertNotEquals("Äffin", Utils.normalize_unicode("Äffin"));
        String testresult = Core.extract("<html><body><p>Äffin</p></body></html>", Core.kw().config(ZERO_CONFIG));
        assertNotEquals("Äffin", testresult);
        assertEquals("Äffin", testresult);
        options = new Extractor(Extractor.kw().source("test\udcc3this"));
        assertEquals("test?this", options.source);
        assertTrue(Core.extract("<html><body><p>ABC</p></body></html>", Core.kw().output_format("xml")).contains("ABC"));
        // AttributeError in Python
        assertThrows(IllegalArgumentException.class, () -> Core.extract("<html><body><p>ABC</p></body></html>", Core.kw().output_format("xyz")));
        assertEquals("ABC", Core.bare_extraction("<html><body><p>ABC</p></body></html>", Core.kw().output_format("python")).text);
        assertThrows(IllegalArgumentException.class, () -> Core.bare_extraction("<html><body><p>ABC</p></body></html>", Core.kw().output_format("xyz")));
        Element elem = Etree.Element("p");
        elem.text = "text";
        assertEquals("text", MainExtractor.handle_textelem(elem, Set.of(), options).text);
        elem = Etree.Element("unexpected");
        elem.text = "text";
        assertNull(MainExtractor.handle_textelem(elem, Set.of(), options));
    }

    /** Documents must not share a mutable Element default (thread-safety / correctness). */
    @Test
    void test_document_isolation() {
        Document d1 = new Document();
        Document d2 = new Document();
        assertNotSame(d2.body, d1.body);
        assertNotSame(d2.commentsbody, d1.commentsbody);
        assertEquals("body", d1.body.tag);
        assertEquals(0, d1.body.size());
    }

    @Test
    void test_xmltocsv() {
        Document doc = new Document();
        doc.body = Etree.fromstring("<xml/>");
        doc.commentsbody = Etree.fromstring("<xml/>");
        assertEquals("null\tnull\tnull\tnull\tnull\tnull\tnull\tnull\tnull\tnull\tnull\r\n", Xml.xmltocsv(doc, false));
        doc.title = "Test title";
        doc.url = "https://example.org";
        doc.hostname = "example.org";
        doc.id = "1";
        doc.license = "CC BY-SA";
        doc.image = "https://example.org/image.jpg";
        doc.pagetype = "article";
        String text = "Test text";
        String comments = "Test comment";
        doc.body = Etree.fromstring("<p>" + text + "</p>");
        doc.commentsbody = Etree.fromstring("<p>" + comments + "</p>");
        String target = "https://example.org\t1\tnull\texample.org\tTest title\thttps://example.org/image.jpg\tnull\tTest text\tTest comment\tCC BY-SA\tarticle\r\n";
        assertEquals(target, Xml.xmltocsv(doc, false));
        String mystring = "<html><body><p>ÄÄÄÄÄÄÄÄÄÄÄÄÄÄ</p></body></html>";
        assertNotNull(Core.extract(mystring, Core.kw().output_format("csv").config(ZERO_CONFIG)));
        assertTrue(Core.extract(mystring, Core.kw().output_format("csv").include_comments(false).config(ZERO_CONFIG)).endsWith("\tnull\r\n"));
    }

    @Test
    void test_tojson() {
        String mystring = "<html><body><p>ÄÄÄÄÄÄÄÄÄÄÄÄÄÄ</p></body></html>";
        String result = Core.extract(mystring, Core.kw().output_format("json").config(ZERO_CONFIG));
        assertTrue(result.contains("Ä"));
        assertTrue(result.endsWith("}"));
        result = Core.extract(mystring, Core.kw().output_format("json").config(ZERO_CONFIG).with_metadata(true));
        assertTrue(result.endsWith("}"));
        assertTrue(result.contains("\"fingerprint\":"));
        assertTrue(result.contains("\"language\":"));
        assertTrue(Core.extract(mystring, Core.kw().output_format("json").include_comments(false).config(ZERO_CONFIG)).endsWith("}"));
    }

    @Test
    void test_python_output() {
        String mystring = "<html><body><p>ÄÄÄÄÄÄÄÄÄÄÄÄÄÄ</p></body></html>";
        Document result = Core.bare_extraction(mystring, Core.kw().config(ZERO_CONFIG));
        Map<String, Object> dict_result = result.as_dict();
        assertTrue((dict_result instanceof Map));
        assertEquals(21, dict_result.size());
    }

    @Test
    void test_exotic_tags() {
        Extractor options = new Extractor(Extractor.kw().config(ZERO_CONFIG));
        // cover some edge cases with a specially crafted file
        String result = load_mock_page("http://exotic_tags", false, null, true);
        assertTrue(result.contains("Teletype text"));
        assertTrue(result.contains("My new car is silver."));
        Path filepath = TEST_DIR.resolve("resources").resolve("exotic_tags_tei.html");
        Element content = Etree.fromstring(Conftest.read_text(filepath));
        Element res = Xml.check_tei(content, "http://dummy");
        assertTrue(Etree.tostring(res).startsWith(("<html>\n<text>\n<body>\n<div>\n\n<hi rend=\"uppercase\">Hello</hi>\n<p>Teletype text</p>")));
        String htmlstring = "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\" 2012\"http://www.w3.org/TR/html4/loose.dtd\"><html><head></head><body><p>ABC</p></body></html>";
        assertTrue(Core.extract(htmlstring, Core.kw().config(ZERO_CONFIG)).contains("ABC"));
        assertNull(MainExtractor.handle_quotes(Etree.Element("quote"), options));
        assertNull(MainExtractor.handle_table(Etree.Element("table"), Settings.TAG_CATALOG, options));
        Element element = Etree.Element("p");
        Element second = Etree.Element("p");
        element.text = "1st part.";
        second.text = "2nd part.";
        element.append(second);
        element.append(Etree.Element("lb"));
        Element converted = MainExtractor.handle_paragraphs(element, Set.of("p"), options);
        assertEquals("<p>1st part. 2nd part.</p>", Etree.tostring(converted));
        assertTrue(Core.extract("<html><body><main><div>1.<br/>2.<br/>3.<br/></div></main></body></html>", Core.kw().fast(true).config(ZERO_CONFIG)).contains("1.\n2.\n3."));
        htmlstring = "<html><body><article><details><summary>Epcot Center</summary><p>Epcot is a theme park at Walt Disney World Resort featuring exciting attractions, international pavilions, award-winning fireworks and seasonal special events.</p></details></article></body></html>";
        String my_result = Core.extract(htmlstring, Core.kw().fast(true).config(ZERO_CONFIG));
        assertTrue(my_result.contains("Epcot Center"));
        assertTrue(my_result.contains("award-winning fireworks"));
        my_result = Core.extract(htmlstring, Core.kw().fast(false).config(ZERO_CONFIG));
        assertTrue(my_result.contains("Epcot Center"));
        assertTrue(my_result.contains("award-winning fireworks"));
        htmlstring = "<!DOCTYPE html>\n<html>\n  <head>\n    <meta charset=\"UTF-8\">\n    <title>A weird bug</title>\n  </head>\n  <body>\n      <div>\n        <h1>Lorem ipsum dolor sit amet, consectetur adipiscing elit.</h1>\n        <h2>Sed et interdum lectus.</h2>\n        <p>Quisque molestie nunc eu arcu condimentum fringilla.</p>\n        <!-- strong can be changed to b, em, i, u, or kbd -->\n        <strong><a></a></strong>\n        <h2>Aliquam eget interdum elit, id posuere ipsum.</h2>\n        <p>Phasellus lectus erat, hendrerit sed tortor ac, dignissim vehicula metus.<br/></p>\n      </div>\n  </body>\n</html>";
        assertNotNull(Core.extract(htmlstring, Core.kw().include_formatting(true).include_links(true).include_images(true)));
        htmlstring = "<!DOCTYPE html>\n<html>\n  <head>\n    <meta charset=\"UTF-8\">\n    <title>A weird bug</title>\n  </head>\n  <body>\n    <div id=\"content\">\n      <h1>A header</h1>\n      <h2>Very specific bug so odd</h2>\n      <h3>Nested header</h3>\n      <p>Some \"hyphenated-word quote\" followed by a bit more text line.</p>\n      <em><p>em improperly wrapping p here</p></em>\n      <p>Text here<br/></p>\n      <h3>More articles</h3>\n    </div>\n  </body>\n</html>";
        List<Core.Kwargs> params = List.of(
                Core.kw().include_formatting(true).include_links(true).include_images(true),
                Core.kw().include_formatting(true).include_links(true).include_images(true).favor_precision(true),
                Core.kw().include_formatting(true).include_links(true).include_images(true).favor_recall(true));
        for (Core.Kwargs p : params) {
            result = Core.extract(htmlstring, p);
            assertTrue(result.contains("em improperly wrapping p here"));
            assertTrue(result.endsWith("Text here"));
        }
        assertTrue(Core.extract("<html><body><article><p>text</p><div class=\"comments\"><p>comment</p></div></article></body></html>", Core.kw().include_comments(true).fast(true).config(ZERO_CONFIG)).endsWith("\ncomment"));
    }

    /** Test HTML formatting conversion and extraction */
    @Test
    void test_formatting() {
        Extractor options = new Extractor();
        Element my_document = Etree.html_fromstring("<html><body><p>This here is the text.<br/></p></body></html>");
        String my_result = Core.extract(my_document, Core.kw().output_format("xml").config(ZERO_CONFIG));
        assertFalse(my_result.contains("lb"));
        my_document = Etree.html_fromstring("<html><body><p><b>This here is in bold font.</b></p></body></html>");
        my_result = Core.extract(my_document, Core.kw().output_format("xml").include_formatting(true).config(ZERO_CONFIG));
        assertTrue(my_result.contains("<hi rend=\"#b\">This here is in bold font.</hi>"));
        String my_string = "<html><body><article><h3>Title</h3><p><b>This here is in bold font.</b>Non-bold here</p></article></body></html>";
        my_document = Etree.html_fromstring(my_string);
        my_result = Core.extract(my_document, Core.kw().output_format("txt").include_formatting(true).config(ZERO_CONFIG));
        assertEquals("### Title\n\n**This here is in bold font.**Non-bold here", my_result);
        assertEquals(my_result, Core.extract(my_string, Core.kw().output_format("markdown").config(ZERO_CONFIG)));
        assertTrue(Etree.tostring(Core.bare_extraction(my_string, Core.kw().output_format("markdown").config(ZERO_CONFIG)).body, "unicode").contains("<hi rend=\"#b\">"));
        String meta_string = "<html><head><title>Test</title></head><body><p>ABC.</p></body></html>";
        String meta_result = Core.extract(meta_string, Core.kw().output_format("markdown").config(ZERO_CONFIG).with_metadata(true));
        assertTrue(meta_result.startsWith("---\ntitle: Test\nfingerprint: "));
        assertTrue(meta_result.endsWith("---\nABC."));
        Map<?, ?> json_result = (Map<?, ?>) Json.loads(Core.extract(meta_string, Core.kw().output_format("json").config(ZERO_CONFIG).with_metadata(true).record_id("doc-1")));
        for (String fmt : List.of("markdown", "txt")) {
            meta_result = Core.extract(meta_string, Core.kw().output_format(fmt).config(ZERO_CONFIG).with_metadata(true).record_id("doc-1"));
            assertTrue(meta_result.contains("\nfingerprint: " + json_result.get("fingerprint") + "\nid: doc-1\n"));
        }
        my_document = Etree.html_fromstring("<html><body><article><h3>Title</h3><p>Paragraph 1</p><p>Paragraph 2</p></article></body></html>");
        my_result = Core.extract(my_document, Core.kw().output_format("txt").include_formatting(true).config(ZERO_CONFIG));
        assertTrue(my_result.endsWith("Paragraph 1\n\nParagraph 2"));
        my_document = Etree.html_fromstring("<html><body><article><h3>Title</h3><p>Here is a code sample:</p><code>import trafilatura</code></p></article></body></html>");
        my_result = Core.extract(my_document, Core.kw().output_format("txt").include_formatting(true).config(ZERO_CONFIG));
        assertEquals("### Title\n\nHere is a code sample:\n\n`import trafilatura`", my_result);
        my_document = Etree.html_fromstring("<html><body><article><h3>Title</h3><p>Here is a code sample:</p><code><span>import</span> <span>something</span><br/>something.run(\"somewhere\")</code><p>Sometimes code is wrapped using <code>pre</code> and <code>code</code>:</p><pre><code>import trafilatura\ntrafilatura.extract(\"\")</code></pre><p>Less often code is wrapped using just <code>pre</code>:</p><pre>\ntrafilatura.extract(\"\")</pre></article></body></html>");
        my_result = Core.extract(my_document, Core.kw().output_format("txt").include_formatting(true).config(ZERO_CONFIG));
        assertEquals("### Title\n\nHere is a code sample:\n\n```\nimport something\nsomething.run(\"somewhere\")\n```\nSometimes code is wrapped using `pre` and `code`:\n\n```\nimport trafilatura\ntrafilatura.extract(\"\")\n```\nLess often code is wrapped using just `pre`:\n\n```\ntrafilatura.extract(\"\")\n```", my_result);
        my_document = Etree.html_fromstring("<html><body><p><b>This here is in bold and <i>italic</i> font.</b></p></body></html>");
        my_result = Core.extract(my_document, Core.kw().output_format("xml").include_formatting(true).config(ZERO_CONFIG));
        assertTrue(my_result.contains("<hi rend=\"#b\">This here is in bold and <hi rend=\"#i\">italic</hi> font.</hi>"));
        my_document = Etree.html_fromstring("<html><body><p><b><i></i></b></p></body></html>");
        my_result = Core.extract(my_document, Core.kw().output_format("xml").include_formatting(true).config(ZERO_CONFIG));
        assertTrue(my_result.contains("<main/>"));
        my_document = Etree.html_fromstring("<html><body><article><div><strong>Wild text</strong></div></article></body></html>");
        my_result = Core.extract(my_document, Core.kw().output_format("xml").include_formatting(true).config(ZERO_CONFIG));
        assertTrue(my_result.contains("<p>"));
        assertTrue(my_result.contains("<hi rend=\"#b\">Wild text</hi>"));
        my_document = Etree.html_fromstring("<html><body><article><div><strong>Wild text</strong></div></article></body></html>");
        my_result = Core.extract(my_document, Core.kw().config(ZERO_CONFIG));
        assertEquals("Wild text", my_result);
        Element doc = Etree.html_fromstring("<html><body><p><a href=\"\">Link text</a></p></body></html>");
        my_result = Core.extract(doc, Core.kw().config(ZERO_CONFIG));
        assertEquals("Link text", my_result);
        doc = Etree.html_fromstring("<html><body><p><br/></p></body></html>");
        my_result = Core.extract(doc, Core.kw().config(ZERO_CONFIG));
        assertEquals("", my_result);
        doc = Etree.html_fromstring("<html><body><p><br/>Here is the text.</p></body></html>");
        my_result = Core.extract(doc, Core.kw().config(ZERO_CONFIG));
        assertEquals("Here is the text.", my_result);
        Element element = Etree.Element("hi");
        element.text = "Here is the text.";
        element.tail = "And a tail.";
        options = new Extractor(Extractor.kw().config(ZERO_CONFIG));
        Element converted = MainExtractor.handle_formatting(element, options);
        assertEquals("<p><hi>Here is the text.</hi>And a tail.</p>", Etree.tostring(converted));
        element = Etree.SubElement(Etree.Element("item"), "hi");
        element.text = "Here is the text.";
        assertEquals("<hi>Here is the text.</hi>", Etree.tostring(MainExtractor.handle_formatting(element, options)));
        my_document = Etree.html_fromstring("<html><body><div>\t\n</div><div>There is text here.</div></body></html>");
        my_result = Core.extract(my_document, Core.kw().output_format("xml").config(ZERO_CONFIG));
        assertTrue(my_result.contains("<main>\n    <p>There is text here.</p>\n  </main>"));
        my_document = Etree.html_fromstring("<html><body><article><ul><li>Number 1</li><li>Number <a href=\"test.html\">2</a></li><li>Number 3</li><p>Test</p></article></body></html>");
        my_result = Core.extract(my_document, Core.kw().output_format("xml").include_links(true).config(ZERO_CONFIG));
        assertTrue(my_result.contains("<item>Number <ref target=\"test.html\">2</ref></item>"));
        my_document = Etree.html_fromstring("<html><body><article>\n        <ul>\n            <li>Number 0</li>\n            <li>Number <a href=\"test.html\">1</a></li>\n            <li><a href=\"test.html\">Number 2</a> n2</li>\n            <li>Number 3</li>\n            <li><p>Number 4</p> n4</li>\n        </ul>\n        Test</article></body></html>\n    ");
        my_result = Core.extract(my_document, Core.kw().output_format("markdown").include_links(true).config(ZERO_CONFIG));
        assertEquals("- Number 0\n- Number [1](test.html)\n- [Number 2](test.html) n2\n- Number 3\n- Number 4 n4\n\nTest", my_result);
        my_document = Etree.html_fromstring("<html><body><p><b>bold</b>, <i>italics</i>, <tt>tt</tt>, <strike>deleted</strike>, <u>underlined</u>, <a href=\"test.html\">link</a> and additional text to bypass detection.</p></body></html>");
        String plain = "bold, italics, tt, deleted, underlined, link and additional text to bypass detection.";
        String marked = "**bold**, *italics*, `tt`, ~~deleted~~, <u>underlined</u>, link and additional text to bypass detection.";
        String xml_p = "<p><hi rend=\"#b\">bold</hi>, <hi rend=\"#i\">italics</hi>, <hi rend=\"#t\">tt</hi>, <del rend=\"overstrike\">deleted</del>, <hi rend=\"#u\">underlined</hi>, {} and additional text to bypass detection.</p>";
        for (Object _r : T.list(T.list(T.map("include_formatting", false), plain), T.list(T.map("include_formatting", true), marked), T.list(T.map("include_formatting", true, "output_format", "txt"), marked), T.list(T.map("include_formatting", true, "include_links", true), marked.replace(" link ", " [link](test.html) ")))) {
            List<?> _row = (List<?>) _r;
            Map<String, Object> kwargs = (Map<String, Object>) _row.get(0);
            String expected = (String) _row.get(1);
            assertEquals(expected, Core.extract(my_document.copy(), withKwargs(Core.kw().fast(true).config(ZERO_CONFIG), kwargs)));
        }
        for (Object _r : T.list(T.list(false, "link"), T.list(true, "<ref target=\"test.html\">link</ref>"))) {
            List<?> _row = (List<?>) _r;
            Boolean links = (Boolean) _row.get(0);
            String link = (String) _row.get(1);
            String result = Core.extract(my_document.copy(), Core.kw().output_format("xml").fast(true).include_formatting(true)
                    .include_links(links).config(ZERO_CONFIG));
            assertTrue(result.contains(xml_p.replace("{}", link)));
        }
        my_document = Etree.html_fromstring("<html><body><p>AAA, <p>BBB</p>, CCC.</p></body></html>");
        my_result = Core.extract(my_document, Core.kw().output_format("xml").include_formatting(true).include_links(true).fast(true).config(ZERO_CONFIG));
        assertTrue(my_result.contains("AAA"));
        assertTrue(my_result.contains("BBB"));
        assertTrue(my_result.contains("CCC"));
        my_document = Etree.html_fromstring("<html><body><article><p><strong>Staff Review of the Financial Situation</strong><br>Domestic financial conditions remained accommodative over the intermeeting period.</p></article></body></html>");
        my_result = Core.extract(my_document, Core.kw().output_format("txt").fast(true).config(ZERO_CONFIG));
        assertEquals("Staff Review of the Financial Situation\nDomestic financial conditions remained accommodative over the intermeeting period.", my_result);
        my_document = Etree.html_fromstring("<html><body><article><h4 id=\"1theinoperator\">1) The <code>in</code> Operator</h4><p>The easiest way to check if a Python string contains a substring is to use the <code>in</code> operator. The <code>in</code> operator is used to check data structures for membership in Python. It returns a Boolean (either <code>True</code> or <code>False</code>) and can be used as follows:</p></article></body></html>");
        my_result = Core.extract(my_document, Core.kw().output_format("xml").fast(true).include_formatting(true).config(ZERO_CONFIG));
        assertTrue(my_result.contains("<head rend=\"h4\">1) The <code>in</code> Operator</head>"));
        assertTrue(my_result.contains("<p>The easiest way to check if a Python string contains a substring is to use the <code>in</code> operator. The <code>in</code> operator is used to check data structures for membership in Python. It returns a Boolean (either <code>True</code> or <code>False</code>) and can be used as follows:</p>"));
        my_document = Etree.html_fromstring("\n    <html><head><body><article>python code below:\n<pre><code>\ndef test:\n    print('hello')\n    print('world')\n    </code></pre>\n    </article></body></html>\n    ");
        my_result = Core.extract(my_document, Core.kw().output_format("markdown").include_formatting(true).config(ZERO_CONFIG));
        assertEquals(my_result, "python code below:\n```\ndef test:\n    print('hello')\n    print('world')\n    \n```");
        my_result = Core.extract(my_document, Core.kw().output_format("markdown").include_formatting(true).config(ZERO_CONFIG));
        assertEquals(my_result, "python code below:\n```\ndef test:\n    print('hello')\n    print('world')\n    \n```");
        my_document = Etree.html_fromstring("<html><body><table><td><p>Sjätte <nobr>AP-fonden</nobr></p></td></table></body></html>");
        my_result = Core.extract(my_document, Core.kw().output_format("xml").include_tables(true).config(ZERO_CONFIG));
        assertTrue(my_result.contains("AP-fonden"));
    }

    /** Text of nested and unexpected subelements stays in place. */
    @Test
    void test_subelement_text_kept() {
        assertTrue(_md_inline("<ul><li><div>A <b>B</b> C</div> tail</li></ul>", zkw()).contains("**B** C tail"));
        assertTrue(_md_inline("<p>Hello <foo>bar</foo> world</p>", zkw()).contains("Hello bar world"));
        assertTrue(_md_inline("<p>A <b>b<br>c<br>d</b> e</p>", zkw()).contains("A **b c d** e"));
        assertTrue(_md_inline("<blockquote><p>Hello <foo>bar</foo> world</p></blockquote>", zkw()).contains("bar world"));
        for (boolean recall : new boolean[] {false, true}) {
            String table = "<table><tr><td>a<ul><li>x</li></ul>after</td><td>b</td></tr></table>";
            assertTrue(_table_md(table, zkw().favor_recall(recall)).contains("after"));
        }
        assertTrue(_md_inline("<ol><li>a</li><li> <b> </b> </li><li>b</li></ol><p>after</p>", zkw()).contains("1. a\n2. b"));
    }

    /** Whitespace-only tails are not moved, consumed children are not merged back. */
    @Test
    void test_subelement_tails_unit() {
        Extractor options = new Extractor();
        Element item = MainExtractor.handle_lists(Etree.fromstring("<list><item><div>A <hi>B</hi></div>\n\t </item></list>"), options);
        assertNull(item.find(".//hi").tail);
        Element paragraph = MainExtractor.handle_paragraphs(Etree.fromstring("<p>a <done>old</done> b</p>"), Settings.TAG_CATALOG, options);
        assertFalse(String.join("", paragraph.itertext()).contains("old"));
        assertNull(MainExtractor.handle_titles(Etree.fromstring("<head><done>Lead. </done>Teaser</head>"), options));
    }

    /** Markdown metadata header must stay valid YAML for special values (GH #814). */
    @Test
    void test_markdown_metadata_yaml_safe() {
        assertEquals("Indu K Murthy", Core._yaml_scalar("Indu K Murthy"));
        assertEquals("https://example.com/a:b", Core._yaml_scalar("https://example.com/a:b"));
        assertEquals("élan vital", Core._yaml_scalar("élan vital"));
        assertEquals("\"COP30: a guide\"", Core._yaml_scalar("COP30: a guide"));
        assertEquals("\"#1 ranking\"", Core._yaml_scalar("#1 ranking"));
        assertEquals("\"&launch\"", Core._yaml_scalar("&launch"));
        assertEquals("\"true\"", Core._yaml_scalar("true"));
        assertEquals("\"2024\"", Core._yaml_scalar("2024"));
        assertEquals("\"[draft]\"", Core._yaml_scalar("[draft]"));
        assertEquals("\"say \\\"hi\\\": now\"", Core._yaml_scalar("say \"hi\": now"));
        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        for (String value : List.of("COP30: a beginner’s guide", "#1 ranking", "&launch", "true", "[draft] note", "a \"quoted\" word", "Indu K Murthy")) {
            Map<String, Object> loaded = yaml.load("title: " + Core._yaml_scalar(value));
            assertEquals(value, loaded.get("title"));
        }
        String my_html = "<html><head><title>COP30: a beginner’s guide</title><meta name=\"author\" content=\"Indu K Murthy\"/></head><body><article><p>Some body text with enough words to be extracted.</p></article></body></html>";
        String result = Core.extract(my_html, Core.kw().output_format("markdown").config(ZERO_CONFIG).with_metadata(true));
        String header = result.split("---\n", 3)[1];
        Map<String, Object> meta = yaml.load(header);
        assertEquals("COP30: a beginner’s guide", meta.get("title"));
        assertEquals("Indu K Murthy", meta.get("author"));
        assertTrue(result.contains("title: \"COP30: a beginner’s guide\""));
    }

    /** Yoast FAQ block questions are bold but act as headers and must be kept (issue #471). */
    @Test
    void test_yoast_faq_block() {
        String lead = "The wrap dress is a dress with a front closure formed by wrapping one side across the other and knotting the attached ties that wrap around the back at the waist or fastening buttons. It was popularised in the seventies and has remained a wardrobe staple ever since, flattering many different body shapes thanks to its adjustable and forgiving cut. ".repeat(2);
        String htmlstring = "<html><body><article><h1>Wrap dress</h1><p>" + lead + "</p><div class=\"schema-faq wp-block-yoast-faq-block\"><div class=\"schema-faq-section\" id=\"faq-question-1\"><strong class=\"schema-faq-question\">Who invented the wrap dress?</strong> <p class=\"schema-faq-answer\">It was popularised by Diane von Furstenberg in 1974.</p></div></div></article></body></html>";
        assertTrue(Core.extract(htmlstring, Core.kw().config(ZERO_CONFIG)).contains("Who invented the wrap dress?"));
        assertTrue(Core.extract(htmlstring, Core.kw().output_format("txt").include_formatting(true).config(ZERO_CONFIG)).contains("### Who invented the wrap dress?"));
        assertTrue(Core.extract(htmlstring, Core.kw().output_format("xml").config(ZERO_CONFIG)).contains("<head rend=\"h3\">Who invented the wrap dress?</head>"));
    }

    /** markdown defaults to formatted, but an explicit include_formatting=False is honored (was ignored for markdown). */
    @Test
    void test_include_formatting_markdown() {
        String doc = "<html><body><article><p>plain and <b>bold</b> text here.</p></article></body></html>";
        assertEquals("plain and **bold** text here.", Core.extract(doc, Core.kw().output_format("markdown").config(ZERO_CONFIG)));
        assertEquals("plain and bold text here.", Core.extract(doc, Core.kw().output_format("markdown").include_formatting(false).config(ZERO_CONFIG)));
        assertEquals("plain and **bold** text here.", Core.extract(doc, Core.kw().output_format("txt").include_formatting(true).config(ZERO_CONFIG)));
    }

    static java.util.stream.Stream<Arguments> test_serializer_tail_spacing_params() {
        return java.util.stream.Stream.of(
            Arguments.of("<list><item><lb/>one</item></list>", "- one"),
            Arguments.of("<list><item>\n  <lb/>one</item></list>", "- one"),
            Arguments.of("<list><item>one<p><lb/>two</p></item></list>", "- one\ntwo"),
            Arguments.of("<list><item>a<lb/>b<lb/>c</item></list>", "- a\nb\nc"),
            Arguments.of("<list><item>\n  <hi rend='#b'>x</hi> y</item></list>", "- **x** y"),
            Arguments.of("<list><item><ref target='s'>s</ref>: who use <ref target='b'>b</ref></item></list>", "- [s](s): who use [b](b)"),
            Arguments.of("<list><item>use <hi rend='#b'>bots</hi>, to resell.</item></list>", "- use **bots**, to resell."),
            Arguments.of("<table><row><cell><p>x<lb/>y</p>z</cell></row></table>", "| x y z |"),
            Arguments.of("<table><row><cell><list><item>a</item><item>b</item></list>z</cell></row></table>", "| a b z |"),
            Arguments.of("<table><row><cell>use <hi rend='#b'>bots</hi>, x</cell></row></table>", "| use **bots**, x |"),
            Arguments.of("<table><row><cell><hi rend='#b'>A</hi> <hi rend='#b'>B</hi>, x</cell></row></table>", "| **A B**, x |"),
            Arguments.of("<table><row><cell>a<ref target='u'/> b</cell></row></table>", "| a b |"),
            Arguments.of("<table><row><cell>x<p>y</p>z mid<p>w</p></cell></row></table>", "| x y z mid w |"),
            Arguments.of("<list><item>a<p>b</p>c<p>d</p></item></list>", "- a b c d"),
            Arguments.of("<list><item><lb/></item><item>b</item></list>", "- b"),
            Arguments.of("<p><hi rend=\"#b\">a</hi><hi rend=\"#b\">b</hi><hi rend=\"#b\">c</hi> tail</p>", "**abc** tail"));
    }

    /** Item and cell tails keep source separators, in order, without stacked spaces. */
    @ParameterizedTest
    @MethodSource("test_serializer_tail_spacing_params")
    void test_serializer_tail_spacing(String body, String expected) {
        assertEquals(expected, Py.strip(Xml.xmltotxt(Etree.fromstring("<doc>" + body + "</doc>"), true)));
        String plain = Py.strip(Xml.xmltotxt(Etree.fromstring("<doc>" + body + "</doc>"), false));
        assertEquals(expected.replace("**", ""), plain);
    }

    /** A nested line break after item content keeps its newline. */
    @Test
    void test_serializer_nested_lb_in_item_keeps_break() {
        Element tree = Etree.fromstring("<doc><list><item><p>para</p><p><lb/>second</p></item></list></doc>");
        assertTrue(Xml.xmltotxt(tree, true).contains("\nsecond"));
    }

    /** Text after a nested list belongs to the parent item, not to the last sub-item. */
    @Test
    void test_serializer_nested_list_tail_stays_in_parent_item() {
        Element tree = Etree.fromstring("<doc><list><item>L1<list><item>L2<list><item>L3</item></list> t2</item></list> t1</item><item>n</item></list></doc>");
        assertEquals("- L1\n  - L2\n    - L3\n\n    t2\n\n  t1\n- n\n", Xml.xmltotxt(tree, true));
        assertEquals("- L1\n  - L2\n    - L3\nt2\nt1\n- n", Xml.xmltotxt(tree, false));
    }

    static java.util.stream.Stream<Arguments> test_extract_snippet_params() {
        return java.util.stream.Stream.of(
            Arguments.of("<ul><li>\n  <br>tail after break</li><li>x</li></ul>", T.map("output_format", "markdown"), "- tail after break\n- x"),
            Arguments.of("<table><tr><td><strong>A</strong><br>\n<strong>B</strong>, x</td></tr></table>", T.map("output_format", "markdown", "include_tables", true), "| **A** **B**, x |"),
            Arguments.of("<p><strong>A</strong><br><strong>B</strong></p>", T.map("output_format", "markdown"), "**A**\n\n**B**"),
            Arguments.of("<p><u>a</u><u>b</u> c</p>", T.map("output_format", "markdown"), "<u>ab</u> c"),
            Arguments.of("<p>long<wbr>word and more</p>", T.map("output_format", "txt"), "longword and more"));
    }

    /** Line breaks before inline content, underline and <wbr> on intro-less snippets. */
    @ParameterizedTest
    @MethodSource("test_extract_snippet_params")
    void test_extract_snippet(String body, Map<String, Object> kwargs, String expected) {
        assertEquals(expected, _extract_doc(body, false, withKwargs(zkw(), kwargs)));
    }

    /** XML/TEI keep a tail-less <lb/> before inline content. */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "xmltei"})
    void test_linebreak_before_inline_kept_in_xml(String fmt) {
        String out = _extract_doc("<p>Rutgers University<br>\n<strong>Linda</strong></p>", false, zkw().output_format(fmt).include_formatting(true));
        assertTrue(out.contains("<p>Rutgers University<lb/><hi rend=\"#b\">Linda</hi></p>"));
    }

    /** sup/sub must keep a boundary, else 100<sup>2</sup> reads as 1002 (issue #889) */
    @Test
    void test_markdown_sup_sub_keep_boundary() {
        String sup = "<html><body><article><p>The layer has 100<sup>2</sup>=10000 nodes.</p></article></body></html>";
        String sub = "<html><body><article><p>Written 2011<sub>15ya</sub> in winter.</p></article></body></html>";
        assertEquals("The layer has 100<sup>2</sup>=10000 nodes.", Core.extract(sup, Core.kw().output_format("markdown").config(ZERO_CONFIG)));
        assertEquals("Written 2011<sub>15ya</sub> in winter.", Core.extract(sub, Core.kw().output_format("markdown").config(ZERO_CONFIG)));
        String spaced = "<html><body><article><p>x <sup> 2 </sup> y</p></article></body></html>";
        String bold = "<html><body><article><p>x <b> 2 </b> y</p></article></body></html>";
        assertEquals("x  <sup>2</sup>  y", Core.extract(spaced, Core.kw().output_format("markdown").config(ZERO_CONFIG)));
        assertEquals("x  **2**  y", Core.extract(bold, Core.kw().output_format("markdown").config(ZERO_CONFIG)));
    }

    static java.util.stream.Stream<Arguments> test_nested_element_tail_kept_params() {
        return java.util.stream.Stream.of(
            Arguments.of("<blockquote>lead <ul><li>one</li></ul> after</blockquote>", "<quote>lead <list rend=\"ul\"><item>one</item></list> after</quote>"),
            Arguments.of("<ul><li>First<ul><li>sub</li></ul>tail text</li><li>second</li></ul>", "<item>First<list rend=\"ul\"><item>sub</item></list>tail text</item>"),
            Arguments.of("<ul><li><p>para <b>x</b></p> trailing</li></ul>", "<item><p>para <hi rend=\"#b\">x</hi></p> trailing</item>"),
            Arguments.of("<blockquote><p>para <b>x</b></p> trailing</blockquote>", "<quote><p>para <hi rend=\"#b\">x</hi></p> trailing</quote>"),
            Arguments.of("<ul><li><img alt='x'> caption</li></ul>", "<item> caption</item>"),
            Arguments.of("<blockquote><img alt='x'> caption</blockquote>", "<quote> caption</quote>"),
            Arguments.of("<blockquote>text<br><b>Author</b>, Site</blockquote>", "<quote>text<lb/><hi rend=\"#b\">Author</hi>, Site</quote>"),
            Arguments.of("<blockquote><p></p>embed caption</blockquote>", "<quote>embed caption</quote>"));
    }

    /** Nested lists, formatted paragraphs and dropped src-less images inside an item or a quote keep their structure and tail. */
    @ParameterizedTest
    @MethodSource("test_nested_element_tail_kept_params")
    void test_nested_element_tail_kept(String body, String expected) {
        assertTrue(_extract_doc(body, true, zkw().output_format("xml").include_formatting(true).include_images(true)).contains(expected));
    }

    /** A src-less image is dropped from a quote, the text around it in a paragraph is kept. */
    @Test
    void test_srcless_graphic_dropped_text_kept() {
        Element quote = MainExtractor.handle_quotes(Etree.fromstring("<quote>a<graphic/></quote>"), new Extractor());
        assertEquals("<quote>a</quote>", Etree.tostring(quote));
        Set<String> tags = new java.util.HashSet<>(Settings.TAG_CATALOG);
        tags.add("graphic");
        Element para = MainExtractor.handle_paragraphs(Etree.fromstring("<p>a<graphic/>b</p>"), tags, new Extractor());
        assertEquals("ab", String.join("", para.itertext()));
    }

    static java.util.stream.Stream<Arguments> test_nested_block_tail_kept_params() {
        return java.util.stream.Stream.of(
            Arguments.of("<quote>a<list><item/></list> b</quote>", "<quote>a b</quote>"),
            Arguments.of("<quote>a<p><hi/></p> b</quote>", "<quote>a b</quote>"),
            Arguments.of("<quote>a<list><item/></list> </quote>", "<quote>a</quote>"),
            Arguments.of("<quote>a<list><item/></list>b</quote>", "<quote>a b</quote>"),
            Arguments.of("<quote>a <list><item/></list>b</quote>", "<quote>a b</quote>"),
            Arguments.of("<quote>a<graphic/>b</quote>", "<quote>ab</quote>"),
            Arguments.of("<table><tr><td>x<list><item/></list> b</td></tr></table>", "<table><row><cell>x b</cell></row></table>"),
            Arguments.of("<table><tr><td>x<list><item/></list> </td></tr></table>", "<table><row><cell>x</cell></row></table>"),
            Arguments.of("<table><tr><td>x<list><item>i</item></list> b</td></tr></table>", "<table><row><cell>x<list><item>i</item></list> b</cell></row></table>"));
    }

    /** The tail of a nested list or paragraph is kept, whether the block itself is kept or dropped. */
    @ParameterizedTest
    @MethodSource("test_nested_block_tail_kept_params")
    void test_nested_block_tail_kept(String block, String expected) {
        Element element = Etree.fromstring(block);
        Element result;
        if (element.tag.equals("quote")) {
            result = MainExtractor.handle_quotes(element, new Extractor());
        } else {
            Set<String> tags = new java.util.HashSet<>(Settings.TAG_CATALOG);
            tags.add("table");
            result = MainExtractor.handle_table(element, tags, new Extractor(Extractor.kw().recall(true)));
        }
        assertEquals(expected, Etree.tostring(result));
    }

    static java.util.stream.Stream<Arguments> test_block_in_inline_wrapper_in_cell_params() {
        return java.util.stream.Stream.of(
            Arguments.of("<b><div>Block in bold</div></b>", false, "| Block in bold | y |"),
            Arguments.of("<a href=\"/x\"><p>Block in link</p></a>", true, "| Block in link | y |"),
            Arguments.of("<a href=\"/x\"><b>bold</b><p>after</p></a>", true, "| [**bold**](/x) after | y |"));
    }

    /** A block wrapped in a link or bold inside a cell is kept. */
    @ParameterizedTest
    @MethodSource("test_block_in_inline_wrapper_in_cell_params")
    void test_block_in_inline_wrapper_in_cell(String cell, boolean links, String expected) {
        String output = _table_md("<table><tr><td>" + cell + "</td><td>y</td></tr></table>", zkw().include_links(links));
        List<String> lines = Py.splitlines(output);
        assertEquals(expected, lines.get(lines.size() - 1));
    }

    /** An empty sup/sub must be removed without taking the text after it (issue #889) */
    @Test
    void test_markdown_empty_sup_sub_are_dropped() {
        for (String tag : List.of("sup", "sub")) {
            String doc = "<html><body><article><p>a<" + tag + "></" + tag + ">b</p></article></body></html>";
            assertEquals("ab", Core.extract(doc, Core.kw().output_format("markdown").config(ZERO_CONFIG)));
        }
        String footnote = "<html><body><article><p>Fact<sup><img src=\"x.png\"/></sup> follows here.</p></article></body></html>";
        assertEquals("Fact follows here.", Core.extract(footnote, Core.kw().output_format("markdown").config(ZERO_CONFIG)));
        String tailed = "<html><body><article><p>alpha<sup></sup>beta gamma.</p></article></body></html>";
        assertEquals("alphabeta gamma.", Core.extract(tailed, Core.kw().output_format("markdown").config(ZERO_CONFIG)));
        assertEquals("alphabeta gamma.", Core.extract(tailed, Core.kw().output_format("markdown").favor_precision(true).config(ZERO_CONFIG)));
    }

    /** Test extract_with_metadata method */
    @Test
    void test_extract_with_metadata() {
        String url = "http://aa.bb/cc.html";
        Element my_document = Etree.html_fromstring("<html>\n        <head></head>\n        <body>\n        <article>\n        <p>AAA, <p>BBB</p>, CCC.</p>\n        </article>\n        </body>\n        </html>\n    ");
        Document parsed_doc = Core.extract_with_metadata(my_document, Core.kw().output_format("txt").include_formatting(true).fast(true).url(url));
        String content = parsed_doc.text;
        assertTrue(content.contains("AAA"));
        assertTrue(content.contains("BBB"));
        assertTrue(content.contains("CCC"));
        assertEquals(parsed_doc.url, url);
        assertNull(parsed_doc.date);
        assertNull(parsed_doc.title);
        my_document = Etree.html_fromstring("<html>\n        <head><title>title</title></head>\n        <body>\n        <article>\n        <div>May 24, 2021</div>\n        <p>AAA, <p>BBB</p>, CCC.</p>\n        </article>\n        </body>\n        </html>\n    ");
        parsed_doc = Core.extract_with_metadata(my_document, Core.kw().output_format("txt").include_formatting(true).fast(true).url(url));
        content = parsed_doc.text;
        assertTrue(content.contains("AAA"));
        assertTrue(content.contains("BBB"));
        assertTrue(content.contains("CCC"));
        assertEquals(parsed_doc.url, url);
        // the free-text date "May 24, 2021": see test_extract_with_metadata_free_text_date (known deviation)
        assertEquals(parsed_doc.title, "title");
        parsed_doc = Core.extract_with_metadata(my_document, Core.kw().output_format("xml").config(ZERO_CONFIG));
        assertEquals(parsed_doc.raw_text, "AAA, BBB , CCC.");
        assertEquals(parsed_doc.fingerprint, "ee7d2fb6fcf2837d");
        content = parsed_doc.text;
        assertTrue(content.contains("AAA"));
        assertTrue(content.contains("BBB"));
        assertTrue(content.contains("CCC"));
        my_document = Etree.html_fromstring("<html>\n        <head><meta http-equiv=\"content-language\" content=\"es\"></head>\n        <body>\n        <article>\n        <p>AAA, <p>BBB</p>, CCC.</p>\n        </article>\n        </body>\n        </html>\n    ");
        parsed_doc = Core.extract_with_metadata(my_document, Core.kw().target_language("en").fast(true));
        assertNull(parsed_doc);
        Element python_doc = my_document;
        assertThrows(IllegalArgumentException.class, () -> Core.extract_with_metadata(python_doc, Core.kw().output_format("python")));
    }

    /** Fallback images get handle_image: src from data-src, stray attributes dropped, src-less images removed. */
    @Test
    void test_sanitize_tree_normalizes_images() {
        Extractor options = new Extractor();
        options.images = true;
        Element mydoc = Etree.html_fromstring("<html><body><p>a<img data-src=\"x.jpg\" class=\"c\" alt=\"Arm, 8\" Dynamic Links, 12\" Post\">b<img alt=\"y\">c</p></body></html>");
        Element mytree = External.sanitize_tree(mydoc, options).first();
        List<Element> graphics = mytree.findall(".//graphic");
        assertEquals(List.of(Map.of("src", "x.jpg", "alt", "Arm, 8")), graphics.stream().map(g -> Map.copyOf(g.attrib)).toList());
        assertEquals("abc", String.join("", mytree.itertext()));
    }

    /** Test external components */
    @Test
    void test_external() {
        Extractor options = new Extractor();
        options.tables = true;
        Element mydoc = Etree.html_fromstring("<html><body><footer>Test text</footer></body></html>");
        String text = External.sanitize_tree(mydoc, options).second();
        int mylen = text.length();
        assertEquals(0, mylen);
        mydoc = Etree.html_fromstring("<html><body><table><th>Test text</th><tr><td>Test</td></tr></table></body></html>");
        text = External.sanitize_tree(mydoc, options).second();
        mylen = text.length();
        assertTrue(mylen > 0);
        mydoc = Etree.html_fromstring("<html><body><p>Text here <fancy>Test text</fancy><a href=\"\">with a link</a>.</p><img src=\"test.jpg\"/></body></html>");
        Element mytree = External.sanitize_tree(mydoc, options).first();
        assertEquals(1, mytree.size());
        mydoc = Etree.html_fromstring("<html><body><p>Text here <fancy>Test text</fancy><a href=\"\">with a link</a>.</p><img src=\"test.jpg\"/></body></html>");
        options.links = true;
        options.images = true;
        mytree = External.sanitize_tree(mydoc, options).first();
        Set<String> myelems = new java.util.HashSet<>();
        for (Element element : mytree.iter()) {
            myelems.add(element.tag);
        }
        assertTrue(myelems.contains("graphic"));
        assertTrue(myelems.contains("ref"));
        if (Utils.LANGID_FLAG) {
            Element doc = Etree.html_fromstring("<html><body>" + "<p>Non è inglese.</p>".repeat(20) + "</body></html>");
            assertNull(Core.extract(doc, Core.kw().fast(false).target_language("en").deduplicate(false)));
        }
        String teststring = Conftest.read_text(Conftest.RESOURCES_DIR.resolve("apache.html"));
        assertTrue(Core.extract(teststring, Core.kw().fast(false).include_tables(true)).contains("localhost:80"));
        assertFalse(Core.extract(teststring, Core.kw().fast(false).include_tables(false)).contains("localhost:80"));
        teststring = Conftest.read_text(Conftest.RESOURCES_DIR.resolve("scam.html"));
        assertEquals("", Core.extract(teststring, Core.kw().fast(true).include_tables(false).config(ZERO_CONFIG)));
        assertEquals("", Core.extract(teststring, Core.kw().fast(false).include_tables(false).config(ZERO_CONFIG)));
        String bad_xml = "<p>Testing</p><ul style=\"\" padding:1px; margin:15px\"\"><b>Features:</b> <li>Saves the cost of two dedicated phone lines.</li> al station using Internet or cellular technology.</li> <li>Requires no change to the existing Fire Alarm Control Panel configuration. The IPGSM-4G connects directly to the primary and secondary telephone ports.</li>";
        String res = Core.extract(bad_xml, Core.kw().output_format("xml"));
        assertTrue(res.contains("Features"));
    }

    /** content whose detected language differs from target_language is discarded (the filter raises, caught -> None). */
    @Test
    void test_wrong_language_discarded() {
        // @pytest.mark.skipif(not LANGID_FLAG, reason="py3langid not installed")
        org.junit.jupiter.api.Assumptions.assumeTrue(Utils.LANGID_FLAG, "py3langid not installed");
        String doc = "<html><body>" + "<p>Questo testo non è affatto in lingua inglese.</p>".repeat(20) + "</body></html>";
        assertNull(Core.extract(doc, Core.kw().target_language("en").config(ZERO_CONFIG)));
    }

    /** Regression: sanitize_tree must absolutize relative links when url is set (convert_tags fix). */
    @Test
    void test_sanitize_tree_absolutizes_links() {
        Element doc = Etree.html_fromstring("<html><body><p><a href=\"/path/page\">link</a> " + "padding ".repeat(10) + "</p></body></html>");
        Extractor options = new Extractor(Extractor.kw().url("https://www.example.org").links(true));
        Element tree = External.sanitize_tree(doc, options).first();
        List<String> targets = new ArrayList<>();
        for (Element elem : tree.iter("ref")) {
            targets.add(elem.get("target"));
        }
        assertTrue(targets.contains("https://www.example.org/path/page"));
    }

    /** Test image extraction function */
    @Test
    void test_images() {
        Extractor options = new Extractor();
        assertFalse(Utils.is_image_file(null));
        assertFalse(Utils.is_image_file(""));
        assertTrue(Utils.is_image_file("test.jpg"));
        assertTrue(Utils.is_image_file("test.JPG"));
        assertTrue(Utils.is_image_file("PIC.PNG"));
        assertTrue(Utils.is_image_file("photo.JPEG"));
        assertFalse(Utils.is_image_file("test.txt"));
        assertFalse(Utils.is_image_file("test.TXT"));
        assertFalse(Utils.is_image_file("test.jpg".repeat(2000)));
        assertNotNull(MainExtractor.handle_image(Etree.html_fromstring("<img src=\"test.jpg\"/>")));
        assertNotNull(MainExtractor.handle_image(Etree.html_fromstring("<img data-src=\"test.jpg\" alt=\"text\" title=\"a title\"/>")));
        assertNull(MainExtractor.handle_image(Etree.html_fromstring("<img other=\"test.jpg\"/>")));
        assertNull(MainExtractor.handle_textelem(Etree.Element("graphic"), Set.of(), options));
        String teststring = Conftest.read_text(Conftest.RESOURCES_DIR.resolve("http_sample.html"));
        assertFalse(Core.extract(teststring).contains("![Example image](test.jpg)"));
        assertTrue(Core.extract(teststring, Core.kw().include_images(true).fast(true)).contains("![Example image](test.jpg)"));
        assertTrue(Core.extract(teststring, Core.kw().include_images(true).fast(true).output_format("xml").config(ZERO_CONFIG)).contains("<graphic src=\"test.jpg\" title=\"Example image\"/>"));
        java.util.function.BiFunction<String, String, String> img = (body, url) ->
                _extract_doc(body, false, zkw().include_images(true).fast(true).url(url));
        assertEquals("![a title text](test.jpg)", img.apply("<img data-src=\"test.jpg\" alt=\"text\" title=\"a title\"/>", null));
        assertEquals("![a title text](test.jpg)", img.apply("<p><img data-src=\"test.jpg\" alt=\"text\" title=\"a title\"/></p>", null));
        assertEquals("", img.apply("<p><img other=\"test.jpg\" alt=\"text\" title=\"a title\"/></p>", null));
        assertEquals("![a title text](test.jpg)", img.apply("<div><p><img data-src=\"test.jpg\" alt=\"text\" title=\"a title\"/></p></div>", null));
        assertEquals("![a title text](test.jpg)", img.apply("<div><p><img data-src-small=\"test.jpg\" alt=\"text\" title=\"a title\"/></p></div>", null));
        assertEquals("![a title text](https://a.b/test.jpg)",
                img.apply("<div><p><img src=\"https://a.b/test.jpg\" alt=\"text\" title=\"a title\"/></p></div>", null));
        String url = "http://a.b/c/d.html";
        assertEquals("![a title text](http://a.b/test.jpg)",
                img.apply("<div><p><img src=\"//a.b/test.jpg\" alt=\"text\" title=\"a title\"/></p></div>", url));
        assertEquals("![a title text](http://a.b/a.b/test.jpg)",
                img.apply("<div><p><img src=\"/a.b/test.jpg\" alt=\"text\" title=\"a title\"/></p></div>", url));
        assertEquals("![a title text](http://a.b/c/a.b/test.jpg)",
                img.apply("<div><p><img src=\"./a.b/test.jpg\" alt=\"text\" title=\"a title\"/></p></div>", url));
        assertEquals("![a title text](http://a.b/a.b/test.jpg)",
                img.apply("<div><p><img src=\"../a.b/test.jpg\" alt=\"text\" title=\"a title\"/></p></div>", url));
        assertNull(MainExtractor.handle_image(Etree.html_fromstring("<img src=\"data:image/jpeg;base64,iVBORw0KGgoAAAANSUhEUgAAAAUAAAAFCAYAAACNbyblAAAAHElEQVQI12P4//8/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg==\" alt=\"text\"></img>")));
        Element mydoc = Etree.html_fromstring("<img class=\"media__image media__image--responsive\" alt=\"Harry and Meghan last March, in their final royal engagement.\" data-src-mini=\"//cdn.cnn.com/cnnnext/dam/assets/210307091919-harry-meghan-commonwealth-day-small-169.jpg\" data-src-xsmall=\"//cdn.cnn.com/cnnnext/dam/assets/210307091919-harry-meghan-commonwealth-day-medium-plus-169.jpg\" data-src-small=\"//cdn.cnn.com/cnnnext/dam/assets/210307091919-harry-meghan-commonwealth-day-large-169.jpg\" data-src-medium=\"//cdn.cnn.com/cnnnext/dam/assets/210307091919-harry-meghan-commonwealth-day-exlarge-169.jpg\" data-src-large=\"//cdn.cnn.com/cnnnext/dam/assets/210307091919-harry-meghan-commonwealth-day-super-169.jpg\" data-src-full16x9=\"//cdn.cnn.com/cnnnext/dam/assets/210307091919-harry-meghan-commonwealth-day-full-169.jpg\" data-src-mini1x1=\"//cdn.cnn.com/cnnnext/dam/assets/210307091919-harry-meghan-commonwealth-day-small-11.jpg\" data-demand-load=\"loaded\" data-eq-pts=\"mini: 0, xsmall: 221, small: 308, medium: 461, large: 781\" src=\"//cdn.cnn.com/cnnnext/dam/assets/210307091919-harry-meghan-commonwealth-day-exlarge-169.jpg\" data-eq-state=\"mini xsmall small medium\" data-src=\"//cdn.cnn.com/cnnnext/dam/assets/210307091919-harry-meghan-commonwealth-day-exlarge-169.jpg\">");
        Element myimage = MainExtractor.handle_image(mydoc);
        assertNotNull(myimage);
        assertTrue(myimage.attrib.containsKey("alt"));
        assertTrue(myimage.attrib.containsKey("src"));
        mydoc = Etree.html_fromstring("<img class=\"media__image media__image--responsive\" alt=\"Harry and Meghan last March, in their final royal engagement.\" data-src-mini=\"//cdn.cnn.com/cnnnext/dam/assets/210307091919-harry-meghan-commonwealth-day-small-169.jpg\" data-src-xsmall=\"//cdn.cnn.com/cnnnext/dam/assets/210307091919-harry-meghan-commonwealth-day-medium-plus-169.jpg\" data-src-small=\"//cdn.cnn.com/cnnnext/dam/assets/210307091919-harry-meghan-commonwealth-day-large-169.jpg\" data-src-medium=\"//cdn.cnn.com/cnnnext/dam/assets/210307091919-harry-meghan-commonwealth-day-exlarge-169.jpg\" data-src-large=\"//cdn.cnn.com/cnnnext/dam/assets/210307091919-harry-meghan-commonwealth-day-super-169.jpg\" data-src-full16x9=\"//cdn.cnn.com/cnnnext/dam/assets/210307091919-harry-meghan-commonwealth-day-full-169.jpg\" data-src-mini1x1=\"//cdn.cnn.com/cnnnext/dam/assets/210307091919-harry-meghan-commonwealth-day-small-11.jpg\" data-demand-load=\"loaded\" data-eq-pts=\"mini: 0, xsmall: 221, small: 308, medium: 461, large: 781\">");
        myimage = MainExtractor.handle_image(mydoc);
        assertNotNull(myimage);
        assertTrue(myimage.attrib.containsKey("alt"));
        assertTrue(myimage.attrib.containsKey("src"));
        assertTrue(myimage.get("src").startsWith("http"));
    }

    /** Test link extraction function */
    @Test
    void test_links() {
        Extractor options = new Extractor(Extractor.kw().config(ZERO_CONFIG));
        assertNull(MainExtractor.handle_textelem(Etree.Element("ref"), Set.of(), options));
        assertNotNull(MainExtractor.handle_formatting(Etree.html_fromstring("<a href=\"testlink.html\">Test link text.</a>"), options));
        Element mydoc = Etree.html_fromstring("<html><body><p><a></a><b>Some text.</b></p></body></html>");
        assertNotNull(Core.extract(mydoc));
        mydoc = Etree.html_fromstring("<html><body><p><a href=\"testlink.html\">Test link text.</a> This part of the text has to be long enough.</p></body></html>");
        assertFalse(Core.extract(mydoc.copy()).contains("testlink.html"));
        assertTrue(Core.extract(mydoc.copy(), Core.kw().include_links(true).fast(true).config(ZERO_CONFIG))
                .contains("[Test link text.](testlink.html) This part of the text has to be long enough."));
        assertTrue(Core.extract(mydoc.copy(), Core.kw().url("https://www.example.com/").include_links(true).fast(true).config(ZERO_CONFIG))
                .contains("[Test link text.](https://www.example.com/testlink.html) This part of the text has to be long enough."));
        mydoc = Etree.html_fromstring("<html><body><p><a href=\"http://[::1\">Test link text.</a> This part of the text has to be long enough.</p></body></html>");
        for (String url : List.of("https://www.example.com/", "https://ex]ample.org/")) {
            assertTrue(Core.extract(mydoc.copy(), Core.kw().url(url).include_links(true).fast(true).config(ZERO_CONFIG)).contains("[Test link text.](http://[::1) This part of the text has to be long enough."));
        }
        mydoc = Etree.html_fromstring("<html><body><p><a>Test link text.</a> This part of the text has to be long enough.</p></body></html>");
        assertTrue(Core.extract(mydoc.copy(), Core.kw().include_links(true).fast(true).config(ZERO_CONFIG)).contains("[Test link text.] This part of the text has to be long enough."));
        mydoc = Etree.html_fromstring("<html><body><article><a>Segment 1</a><h1><a>Segment 2</a></h1><p>Segment 3</p></article></body></html>");
        String result = Core.extract(mydoc.copy(), Core.kw().output_format("xml").include_links(true).fast(true).config(ZERO_CONFIG));
        assertTrue(result.contains("1"));
        assertTrue(result.contains("2"));
        assertTrue(result.contains("3"));
        String teststring = Conftest.read_text(Conftest.RESOURCES_DIR.resolve("http_sample.html"));
        assertFalse(Core.extract(teststring, Core.kw().config(ZERO_CONFIG)).contains("testlink.html"));
        assertTrue(Core.extract(teststring, Core.kw().include_links(true).fast(true).config(ZERO_CONFIG)).contains("[link](testlink.html)"));
        assertTrue(Core.extract(teststring, Core.kw().include_links(true).fast(true).output_format("xml").config(ZERO_CONFIG)).contains("<ref target=\"testlink.html\">link</ref>"));
        mydoc = Etree.html_fromstring("<html><body><p>Test text under <a rel=\"license\" href=\"\">CC BY-SA license</a>.</p></body></html>");
        assertTrue(Core.extract(mydoc, Core.kw().include_links(true).fast(true).output_format("xml").config(ZERO_CONFIG).with_metadata(true)).contains("license=\"CC BY-SA license\""));
        mydoc = Etree.html_fromstring("<html><body><article><p><a>f" + ("abcd".repeat(20)) + "</a></p></article></body></html>");
        assertTrue(Core.extract(mydoc.copy(), Core.kw().fast(true).config(ZERO_CONFIG).favor_precision(false)).contains("abc"));
        assertEquals("", Core.extract(mydoc, Core.kw().fast(true).config(ZERO_CONFIG).favor_precision(true)));
    }

    /** test TEI-related functions */
    @Test
    void test_tei() {
        String teststring = Conftest.read_text(Conftest.RESOURCES_DIR.resolve("httpbin_sample.html"));
        String result1 = Core.extract(teststring, Core.kw().url("mocked").fast(true).output_format("xmltei").tei_validation(false));
        String result2 = Core.extract(teststring, Core.kw().url("mocked").fast(true).output_format("xmltei").tei_validation(true));
        assertNotNull(result1);
        assertEquals(result2, result1);
        assertTrue(Xml.validate_tei(Etree.fromstring(result1)));
        assertFalse(Xml.validate_tei(Etree.fromstring(teststring)));
        teststring = Conftest.read_text(Conftest.RESOURCES_DIR.resolve("http_sample.html"));
        String result = Core.extract(teststring, Core.kw().url("mocked").fast(true).include_comments(true).output_format("xmltei").tei_validation(false));
        assertNotNull(result);
        assertTrue(Xml.validate_tei(Etree.fromstring(result)));
        result = Core.extract(teststring, Core.kw().url("mocked").fast(true).include_comments(false).output_format("xmltei").tei_validation(false));
        assertNotNull(result);
        assertTrue(Xml.validate_tei(Etree.fromstring(result)));
        result = Core.extract(teststring, Core.kw().url("mocked").fast(true).output_format("xmltei").tei_validation(false).record_id("0001"));
        assertNotNull(result);
        assertTrue(Xml.validate_tei(Etree.fromstring(result)));
        Element tei = Etree.Element("TEI", "xmlns", "http://www.tei-c.org/ns/1.0");
        Element header = Etree.SubElement(tei, "teiHeader");
        Document docmeta = new Document();
        docmeta.categories = List.of();
        docmeta.tags = List.of();
        docmeta.title = "Title";
        assertNotNull(Xml.write_fullheader(header, docmeta));
        docmeta.sitename = "Site Name";
        docmeta.date = "2021-01-01";
        assertNotNull(Xml.write_fullheader(header, docmeta));
        docmeta.date = null;
        assertNotNull(Xml.write_fullheader(header, docmeta));
        docmeta.hostname = "hostname";
        assertNotNull(Xml.write_fullheader(header, docmeta));
        docmeta.sitename = null;
        docmeta.license = "CC BY-SA";
        docmeta.url = "https://test.org/";
        docmeta.categories = List.of("cat1", "cat2");
        assertNotNull(Xml.write_fullheader(header, docmeta));
        docmeta.date = "2021-01-01";
        assertNotNull(Xml.write_fullheader(header, docmeta));
        docmeta.title = null;
        docmeta.sitename = null;
        assertNotNull(Xml.write_fullheader(header, docmeta));
        Element htmlstring = Etree.html_fromstring("<html><head/><body><div><h2><p>text</p></h2></div></body></html>");
        String extracted = Core.extract(htmlstring, Core.kw().url("mocked").fast(true).output_format("xmltei").config(ZERO_CONFIG));
        assertTrue(Xml.validate_tei(Etree.fromstring(extracted)));
        htmlstring = Etree.html_fromstring("<html><body><article><h1>title</h1><h2>subtitle</h2><p>text</p></article></body></html>");
        extracted = Core.extract(htmlstring, Core.kw().url("mocked").fast(true).output_format("xmltei").config(ZERO_CONFIG));
        assertTrue(extracted.contains("<ab rend=\"h1\" type=\"header\">title</ab>"));
        assertTrue(extracted.contains("<ab rend=\"h2\" type=\"header\">subtitle</ab>"));
        htmlstring = Etree.html_fromstring("<html>\n        <body><article>\n            <h2><div>\n              <p>content</p>\n              <ul>\n                <li>text1</li>\n                <li>text2</li>\n              </ul>\n            </div></h2>\n            <p>Following paragraph.</p>\n        </article></body>\n        </html>");
        extracted = Core.extract(htmlstring, Core.kw().url("mocked").fast(true).output_format("xmltei").config(ZERO_CONFIG));
        assertTrue(extracted.replace("\n", "").contains("<ab rend=\"h2\" type=\"header\">content<list rend=\"ul\"><item>text1"));
        // nested <p> as parsed by libxml2: see test_tei_nested_paragraphs (known deviation)
        Element tree = Etree.html_fromstring("\n    <html>\n        <body>\n            <div>\n                <div>\n                    <p>\n                        <p>text</p>\n                    <p>\n                </div>\n            </div>\n        </body>\n    </html>\n    ");
        Xml.strip_double_tags(tree);
        assertNotNull(tree.find(".//div/div"));
        assertNull(tree.find(".//p/p"));
        tree = Etree.fromstring("\n    <html><body>\n        <div>\n            <p>text1<lb/>text2<p>text3</p><lb/>text4</p>\n            <p>text5<p>text6</p></p>\n        </div>\n    </body></html>\n    ");
        Xml.strip_double_tags(tree);
        assertNull(tree.find(".//p/p"));
        tree = Etree.fromstring("\n    <html><body>\n        <div>\n            <p>text1<lb/>text2<p>text3</p><lb/>text4</p>\n            <p>text5<p>text6<p>text7</p></p></p>\n        </div>\n    </body></html>\n    ");
        Xml.strip_double_tags(tree);
        assertNull(tree.find(".//p/p"));
        assertTrue(Etree.tostring(tree, "unicode").contains("text7"));
        // paragraphs nested in list items, cells, quotes, notes and figures: see test_tei_nested_paragraphs
    }

    static java.util.stream.Stream<Arguments> test_check_tei_loose_text_params() {
        return java.util.stream.Stream.of(
            Arguments.of("text", "<p>text</p>"),
            Arguments.of("<div>text1<p>text2</p></div>", "<div><p>text1 text2</p></div>"),
            Arguments.of("<div>text1<head>text2</head></div>", "<div><p>text1</p><ab type=\"header\">text2</ab></div>"),
            Arguments.of("<div>text1<p>text2</p></div>has to be there", "<div><p>text1 text2 has to be there</p></div>"),
            Arguments.of("<div>text1<quote>text2</quote></div>has to be there", "<div><p>text1</p><quote>text2</quote><p>has to be there</p></div>"),
            Arguments.of("<div>text1<p>text2</p>has to be there</div>", "<div><p>text1 text2 has to be there</p></div>"));
    }

    /** check_tei wraps loose text and merges it into the neighbouring paragraph. */
    @ParameterizedTest
    @MethodSource("test_check_tei_loose_text_params")
    void test_check_tei_loose_text(String div, String expected) {
        Element cleaned = Xml.check_tei(Etree.fromstring("<TEI><text><body><div>" + div + "</div></body></text></TEI>"), "fake_url");
        assertEquals("<div>" + expected + "</div>", Etree.tostring(cleaned.find(".//body/div"), "unicode"));
    }

    /** test html-related functions */
    @Test
    void test_htmlprocessing() {
        Extractor options = new Extractor();
        assertEquals("", Xml.xmltotxt(null, false));
        options.tables = true;
        assertNotNull(Htmlprocessing.tree_cleaning(Etree.Element("html"), options));
        assertNotNull(Htmlprocessing.prune_html(Etree.Element("unwanted")));
        Element mydoc = Etree.html_fromstring("<html><body><table><a href=\"\">Link</a></table><img src=\"test.jpg\"/><u>Underlined</u><tt>True Type</tt><sub>Text</sub><sup>Text</sup></body></html>");
        options.formatting = true;
        options.images = true;
        options.links = true;
        Element myconverted = Htmlprocessing.convert_tags(mydoc, options);
        assertTrue(Py.truthy(myconverted.xpath(".//ref")));
        assertTrue(Py.truthy(myconverted.xpath(".//graphic")));
        assertTrue(Py.truthy(myconverted.xpath(".//hi[@rend=\"#t\"]")));
        assertTrue(Py.truthy(myconverted.xpath(".//table")));
        Element multi_img = Etree.html_fromstring("<html><body><a href=\"/x\"><img src=\"a.jpg\"/><img src=\"b.jpg\"/><img src=\"c.jpg\"/></a></body></html>");
        options.images = true;
        options.links = true;
        Element multi_converted = Htmlprocessing.convert_tags(multi_img, options);
        List<String> srcs = new ArrayList<>();
        for (Element g : multi_converted.iter("graphic")) {
            srcs.add(g.get("src"));
        }
        assertEquals(List.of("a.jpg", "b.jpg", "c.jpg"), srcs);
        options.images = true;
        options.tables = false;
        myconverted = Htmlprocessing.tree_cleaning(mydoc, options);
        assertTrue(Py.truthy(myconverted.xpath(".//graphic")));
        assertFalse(Py.truthy(myconverted.xpath(".//table")));
        mydoc = Etree.html_fromstring("<html><body><article><h1>Test headline</h1><p>Test</p></article></body></html>");
        assertTrue(Core.extract(mydoc.copy(), Core.kw().output_format("xml").config(ZERO_CONFIG).fast(true))
                .contains("<head rend=\"h1\">Test headline</head>"));
        assertTrue(Core.extract(mydoc.copy(), Core.kw().output_format("xmltei").config(ZERO_CONFIG).fast(true))
                .contains("<ab rend=\"h1\" type=\"header\">Test headline</ab>"));
        Element element = Etree.Element("test");
        Xml.delete_element(element);
        assertEquals("<test/>", Etree.tostring(element));
        element = Etree.Element("test");
        Xml.merge_with_parent(element);
        assertEquals("<test/>", Etree.tostring(element));
        mydoc = Etree.html_fromstring("<html><body><p><span>A</span><span>B</span><span>C</span></p></body></html>");
        for (Element span : mydoc.iter("span")) {
            Xml.merge_with_parent(span);
        }
        assertTrue(Etree.tostring(mydoc).contains("<p>A B C</p>"));
        mydoc = Etree.html_fromstring("<html><body><p><span>A</span><span>B</span> tail<span>C</span></p></body></html>");
        for (Element span : mydoc.iter("span")) {
            Xml.merge_with_parent(span);
        }
        assertTrue(Etree.tostring(mydoc).contains("<p>A B tail C</p>"));
        String my_html = "<html><body><main><p>1</p><p id=\"premium\">2</p><p>3</p></main></body></html>";
        assertEquals("1\n3", Core.extract(my_html, Core.kw().config(ZERO_CONFIG).fast(true)));
        assertEquals("1\n3", Core.extract(my_html, Core.kw().config(ZERO_CONFIG).fast(false)));
        String fenced_html = "<html><body><article><p>real</p><fencedframe><p>ad</p></fencedframe><p>more</p></article></body></html>";
        assertFalse(Core.extract(fenced_html, Core.kw().config(ZERO_CONFIG).fast(true)).contains("ad"));
        assertFalse(Core.extract(fenced_html, Core.kw().config(ZERO_CONFIG).fast(false)).contains("ad"));
        String fenced_outer = "<html><body><fencedframe><article><h1>ad</h1><p>buy now buy now buy now buy now</p></article></fencedframe></body></html>";
        assertFalse(Core.extract(fenced_outer, Core.kw().config(ZERO_CONFIG).fast(false)).contains("ad"));
        assertNull(Core.extract(fenced_outer));
        Element node = Etree.fromstring("<div><p></p>tail</div>").get(0);
        Htmlprocessing.process_node(node, options);
        assertEquals("tail", node.text);
        assertNull(node.tail);
        node = Etree.fromstring("<list><item></item>text in tail</list>").get(0);
        Htmlprocessing.process_node(node, options);
        assertEquals("text in tail", node.text);
        assertNull(node.tail);
        Element line_break = Etree.fromstring("<p><lb/>tail</p>").get(0);
        Htmlprocessing.process_node(line_break, options);
        assertNull(line_break.text);
        assertEquals("tail", line_break.tail);
        node = Etree.fromstring("<div><p>some text</p>tail</div>").get(0);
        Htmlprocessing.process_node(node, options);
        assertEquals("some text", node.text);
        assertEquals("tail", node.tail);
        node = Etree.fromstring("<p><ref target='url'><hi rend='#b'>bold</hi>inner</ref>outer</p>").get(0);
        Element processed = Htmlprocessing.handle_textnode(node, options);
        assertEquals("outer", processed.tail);
        node = Etree.fromstring("<p><ref target='url'>text</ref>tail</p>").get(0);
        processed = Htmlprocessing.handle_textnode(node, options);
        assertEquals("tail", processed.tail);
        assertEquals("text", processed.text);
        node = Etree.fromstring("<p><ref target='url'></ref>tail</p>").get(0);
        processed = Htmlprocessing.handle_textnode(node, options);
        assertEquals("", processed.tail);
        assertEquals("tail", processed.text);
        node = Etree.fromstring("<p><ref target='url'>text<hi rend='#b'>bold</hi></ref>tail</p>").get(0);
        processed = Htmlprocessing.handle_textnode(node, options);
        assertEquals("tail", processed.tail);
        assertEquals("text", processed.text);
        node = Etree.fragment_fromstring("<div><p><span>span</span> span tail</p> p tail </div>");
        assertEquals("span span tail p tail ", node.text_content());
        XPath prune = new XPath(".//span");
        processed = Htmlprocessing.prune_unwanted_nodes(node, List.of(prune));
        assertEquals(" span tail p tail ", node.text_content());
        Element linkless_table = Etree.html_fromstring("<table><cell>" + "word ".repeat(50) + "<ref target=\"/x\"></ref></cell></table>");
        assertFalse(Htmlprocessing.link_density_test_tables(linkless_table));
        assertFalse(Htmlprocessing.link_density_test_tables(Etree.html_fromstring("<table><cell>short " + "<ref target=\"/x\">link</ref> ".repeat(5) + "</cell></table>")));
        assertEquals("", Xml.replace_element_text(Etree.Element("ref"), false));
        Element code = Etree.fromstring("<code>print(1)<lb/></code>");
        String code_md = Xml.replace_element_text(code, true);
        assertFalse(code_md.contains("None"));
        assertTrue(code_md.contains("print(1)"));
        Element para = Etree.fromstring("<p>text<lb>x</lb></p>");
        processed = MainExtractor.handle_paragraphs(para, Set.of("p", "lb"), options);
        assertNotNull(processed);
        assertFalse(Py.truthy(processed.findall(".//lb")));
        Extractor fmt_opts = new Extractor(Extractor.kw().formatting(true));
        para = Etree.fromstring("<p><hi rend=\"#b\">pre<quote>mid</quote>end</hi></p>");
        Element hi = MainExtractor.handle_paragraphs(para, Settings.TAG_CATALOG, fmt_opts).find("hi");
        assertNotNull(hi);
        assertTrue(hi.text.contains("pre"));
        assertTrue(hi.text.contains(" mid"));
        para = Etree.fromstring("<p><hi rend=\"#b\">start<lb/>tail</hi></p>");
        Set<String> with_lb = new java.util.HashSet<>(Settings.TAG_CATALOG);
        with_lb.add("lb");
        hi = MainExtractor.handle_paragraphs(para, with_lb, fmt_opts).find("hi");
        assertNotNull(hi);
        assertTrue(hi.text.contains(" tail"));
    }

    static java.util.stream.Stream<Arguments> test_prune_unwanted_nodes_guard_params() {
        return java.util.stream.Stream.of(
            Arguments.of(300, false),
            Arguments.of(3, true));
    }

    /** An over-pruning trip leaves the same subtree in place, recovery matches elements by identity. */
    @ParameterizedTest
    @MethodSource("test_prune_unwanted_nodes_guard_params")
    void test_prune_unwanted_nodes_guard(int widget_words, boolean pruned) {
        Element doc = Etree.html_fromstring("<html><body><div><p>real text</p><div class='widget'>" + "x ".repeat(widget_words) + "</div></div></body></html>");
        Element node = doc.find(".//div");
        String before = doc.text_content();
        Element result = Htmlprocessing.prune_unwanted_nodes(node, List.of(new XPath(".//div[@class='widget']")), true);
        assertSame(node, result);
        assertSame(doc.find(".//body"), result.getparent());
        assertSame(pruned, !java.util.Objects.equals(doc.text_content(), before));
    }

    /** A later BODY_XPATH pass sees the restored subtree with its consumed paragraph marked done. */
    @Test
    void test_extract_restored_subtree_not_extracted_twice() {
        String hidden = "<div style='display:none'>" + "Hidden block sentence. ".repeat(30) + "</div>";
        String page = "<html><body><main id='content'><article><p>Lead paragraph of the story.</p>" + hidden + "</article><p>Outside paragraph one.</p></main></body></html>";
        String result = Core.extract(page, Core.kw().fast(true).config(ZERO_CONFIG));
        assertEquals(1, T.count(result, "Lead paragraph of the story."));
        assertTrue(result.contains("Outside paragraph one."));
    }

    /** An inline anchor must not split a div's sentence into separate paragraphs (#585). */
    @ParameterizedTest
    @ValueSource(strings = {"<a href=\"https://example.org/plots\">three plots</a>", "<a href=\"https://example.org/plots\"><b>three plots</b></a>"})
    void test_readability_div_keeps_inline_link(String link) {
        String sentence = "The garden team measured " + link + " before planting the seeds.";
        Element tree = Etree.html_fromstring("<html><body><div>" + sentence + "</div></body></html>");
        new ReadabilityLxml.Document(tree).transform_misused_divs_into_paragraphs();
        Element paragraph = tree.find(".//body/p");
        assertNotNull(paragraph);
        assertEquals("The garden team measured three plots before planting the seeds.", paragraph.text_content());
        assertEquals("https://example.org/plots", paragraph.find("a").get("href"));
        assertNull(paragraph.find("p"));
    }

    /** A link wrapping a block still prevents conversion of its containing div to a paragraph. */
    @ParameterizedTest
    @ValueSource(strings = {"div", "p", "blockquote", "article", "aside", "address"})
    void test_readability_div_keeps_linked_blocks(String block) {
        Element tree = Etree.html_fromstring("<html><body><div><a href=\"https://example.org/plots\"><" + block + ">Plot measurements</" + block + "></a></div></body></html>");
        new ReadabilityLxml.Document(tree).transform_misused_divs_into_paragraphs();
        assertNotNull(tree.find(".//body/div/a"));
    }

    /** Containers without loose text, or with a block below an inline child, retain their role in Readability's scoring. */
    @ParameterizedTest
    @ValueSource(strings = {"<a href=\"https://example.org/plots\">Plot measurements</a>", "<span>Garden plan</span><span><a href=\"https://example.org/plots\">Plot measurements</a></span>", "<span><img src='a.jpg'/></span> caption", "<span><picture></picture></span> caption"})
    void test_readability_div_keeps_link_wrappers(String content) {
        Element tree = Etree.html_fromstring("<html><body><div>" + content + "</div></body></html>");
        new ReadabilityLxml.Document(tree).transform_misused_divs_into_paragraphs();
        assertNotNull(tree.find(".//body/div"));
    }

    /** Tails wrapped into paragraphs stay in document order. */
    @Test
    void test_readability_div_tails_keep_order() {
        Element tree = Etree.html_fromstring("<html><body><div><p>one</p>two<p>three</p>four</div></body></html>");
        new ReadabilityLxml.Document(tree).transform_misused_divs_into_paragraphs();
        assertEquals(List.of("one", "two", "three", "four"), tree.find(".//body/div").getchildren().stream().map(p -> p.text).toList());
    }

    /** Dropping an element does not stop the iteration over the remaining ones. */
    @Test
    void test_readability_sanitize_drops_all_matches() {
        Element tree = Etree.html_fromstring("<div><h2 class=\"comment\"><span><h3 class=\"comment\">a</h3></span></h2><h3 class=\"comment\">b</h3><form>c</form><textarea>d</textarea><form>e</form>text</div>");
        assertEquals("<div>text</div>", new ReadabilityLxml.Document(tree).sanitize(tree, Map.of()));
    }

    /** Unlikely classes are removed case-insensitively, content containers are kept. */
    @Test
    void test_readability_unlikely_candidates() {
        Element tree = Etree.html_fromstring("<html><body><div class=\"Sidebar\">a</div><div class=\"sidebar\">b</div><div class=\"sidebar-content\">c</div></body></html>");
        new ReadabilityLxml.Document(tree).remove_unlikely_candidates();
        assertEquals(List.of("c"), tree.iter("div").list().stream().map(div -> div.text).toList());
    }

    /** Parentless paragraphs are not scored, list-heavy blocks are removed. */
    @Test
    void test_readability_scoring_edge_cases() {
        Element paragraph = Etree.Element("p");
        paragraph.text = "Some long enough paragraph text. ".repeat(3);
        assertTrue(new ReadabilityLxml.Document(paragraph).score_paragraphs().isEmpty());
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            items.append("<li>item number ").append(i).append(" with some words</li>");
        }
        Element tree = Etree.html_fromstring("<div><div><ul>" + (items) + "</ul></div></div>");
        assertEquals("<div/>", new ReadabilityLxml.Document(tree).sanitize(tree, Map.of()));
    }

    static java.util.stream.Stream<Arguments> test_readability_sanitize_textless_block_between_long_neighbours_params() {
        return java.util.stream.Stream.of(
            Arguments.of(150, true),
            Arguments.of(10, false));
    }

    /** A textless block stays only if its nearest non-empty siblings are long. */
    @ParameterizedTest
    @MethodSource("test_readability_sanitize_textless_block_between_long_neighbours_params")
    void test_readability_sanitize_textless_block_between_long_neighbours(int words, boolean kept) {
        String text = "word ".repeat(words);
        Element tree = Etree.html_fromstring("<article><p>" + text + "</p><div><img src=\"a.png\"/></div><p>" + text + "</p></article>");
        assertSame(kept, new ReadabilityLxml.Document(tree).sanitize(tree, Map.of()).contains("a.png"));
    }

    /** Exercise the Readability fallback with default size thresholds and self-created HTML. */
    @Test
    void test_extract_div_keeps_inline_link() {
        String following = " before planting the seeds. They recorded the width of each plot, checked the soil, and marked every corner with a wooden stake. The measurements will help the volunteers leave enough space between rows when they return to plant beans next week.";
        String document = "<html><body><div>The garden team measured <a href=\"https://example.org/plots\">three plots</a>" + following + "</div></body></html>";
        assertEquals("The garden team measured [three plots](https://example.org/plots)" + following, Core.extract(document, Core.kw().output_format("markdown").include_links(true)));
        Element result = Etree.fromstring(Core.extract(document, Core.kw().output_format("xml").include_links(true)));
        Element link = result.find(".//ref");
        assertNotNull(link);
        assertEquals("p", link.getparent().tag);
        assertEquals("The garden team measured ", link.getparent().text);
        assertEquals(following, link.tail);
    }

    /** Test the different parameters available in extract() and bare_extraction() */
    @Test
    void test_extraction_options() {
        String my_html = "<html><head><meta http-equiv=\"content-language\" content=\"EN\"/></head><body><div=\"article-body\"><p>Text.<!-- comment --><?php echo \"This is a PHP processing instruction\"; ?></p></div></body></html>";
        String python_html = my_html;
        assertThrows(IllegalArgumentException.class, () -> Core.extract(python_html, Core.kw().output_format("python")));
        assertNull(Core.extract(my_html, Core.kw().config(NEW_CONFIG)));
        assertNotNull(Core.extract(my_html, Core.kw().config(ZERO_CONFIG)));
        assertNotNull(Core.extract(my_html, Core.kw().only_with_metadata(false).output_format("xml").config(ZERO_CONFIG)));
        assertNull(Core.extract(my_html, Core.kw().only_with_metadata(true).output_format("xml").config(ZERO_CONFIG)));
        assertNull(Core.extract(my_html, Core.kw().target_language("de").config(ZERO_CONFIG)));
        assertNull(Core.extract(my_html, Core.kw().target_language("de").fast(true).config(ZERO_CONFIG)));
        assertEquals("<body/>", Etree.tostring(External.try_justext(Etree.html_fromstring(my_html), null, "de")));
        assertEquals("<body/>", Etree.tostring(External.try_justext(null, null, "de")));
        my_html = "<html><body><p>" + "Text. ".repeat(10) + "</p></body></html>";
        String result = Etree.tostring(External.try_readability(Etree.html_fromstring(my_html)));
        assertTrue(result.length() > 10);
        assertTrue(result.contains("Text"));
        my_html = "<html><body><div><p>" + "Text. ".repeat(10) + "</p><div><p>A caption that is long enough to count.</p><embed>Test</embed></div></div></body></html>";
        result = Etree.tostring(External.try_readability(Etree.html_fromstring(my_html)));
        assertTrue(result.contains("Text"));
        assertFalse(result.contains("Test"));
        my_html = "<html><head/><body>" + "<p>ABC def ghi jkl.</p>".repeat(1000) + "<p>Posted on 1st Dec 2019<.</p></body></html>";
        // "Posted on 1st Dec 2019" found with the default config: see test_extraction_options_free_text_date
        assertNull(Core.bare_extraction(my_html, Core.kw().config(NEW_CONFIG).with_metadata(true)).date);
        assertNull(Core.bare_extraction(my_html, Core.kw().config(NEW_CONFIG).with_metadata(false)).date);
    }

    static java.util.stream.Stream<Arguments> test_extractor_parity_params() {
        Map<String, Object> blacklists = new LinkedHashMap<>();
        blacklists.put("favor_precision", true);
        blacklists.put("config", ZERO_CONFIG);
        blacklists.put("only_with_metadata", true);
        blacklists.put("url_blacklist", Set.of("https://example.org/blocked"));
        blacklists.put("author_blacklist", Set.of("Jane Doe"));
        // no blacklists here: they would mask a dropped with_metadata
        Map<String, Object> settingsfile = new LinkedHashMap<>();
        settingsfile.put("favor_recall", true);
        settingsfile.put("settingsfile", RESOURCES_DIR.resolve("newsettings.cfg").toString());
        settingsfile.put("with_metadata", true);
        return java.util.stream.Stream.of(Arguments.of(blacklists), Arguments.of(settingsfile));
    }

    /** extract(), extract_with_metadata() and bare_extraction() must translate the same arguments into the same Extractor. */
    @ParameterizedTest
    @MethodSource("test_extractor_parity_params")
    void test_extractor_parity(Map<String, Object> case_) throws IllegalAccessException {
        List<Extractor> built = new ArrayList<>();
        java.util.function.Consumer<Extractor> saved = Core.EXTRACTOR_CREATED;
        Core.EXTRACTOR_CREATED = built::add;
        try {
            Map<String, Object> kwargs = new LinkedHashMap<>();
            kwargs.put("url", "https://example.org/page");
            kwargs.put("fast", true);
            kwargs.put("include_comments", false);
            kwargs.put("output_format", "xml");
            kwargs.put("target_language", "en");
            kwargs.put("include_tables", false);
            kwargs.put("include_images", true);
            kwargs.put("include_formatting", true);
            kwargs.put("include_links", true);
            kwargs.put("deduplicate", new Deduplication.LRUCache(2));
            kwargs.put("date_extraction_params", Map.of("extensive_search", false));
            kwargs.putAll(case_);
            Map<String, Object> bare_kwargs = new LinkedHashMap<>(kwargs);
            if (bare_kwargs.containsKey("settingsfile")) {
                bare_kwargs.put("config", Settings.use_config((String) bare_kwargs.remove("settingsfile")));
            }
            String my_html = "<html><body><article><p>Some text.</p></article></body></html>";
            Core.extract(my_html, withKwargs(Core.kw(), kwargs));
            Core.bare_extraction(my_html, withKwargs(Core.kw(), bare_kwargs));
            if (!kwargs.containsKey("only_with_metadata")) {
                Map<String, Object> ewm = new LinkedHashMap<>(kwargs);
                ewm.remove("with_metadata");
                Core.extract_with_metadata(my_html, withKwargs(Core.kw(), ewm));
            }
            assertEquals(kwargs.containsKey("only_with_metadata") ? 2 : 3, built.size());
            for (Extractor other : built.subList(1, built.size())) {
                for (java.lang.reflect.Field slot : Extractor.class.getFields()) {
                    if (java.lang.reflect.Modifier.isStatic(slot.getModifiers()) || slot.getName().equals("config")) {
                        continue;
                    }
                    assertEquals(slot.get(built.get(0)), slot.get(other), slot.getName());
                }
            }
        } finally {
            Core.EXTRACTOR_CREATED = saved;
        }
    }

    /** test precision- and recall-oriented settings */
    @Test
    void test_precision_recall() {
        Element my_document = Etree.html_fromstring("<html><body><p>This here is the text.</p></body></html>");
        assertNotNull(Core.extract(my_document.copy(), Core.kw().favor_precision(true).config(ZERO_CONFIG).fast(true)));
        assertNotNull(Core.extract(my_document.copy(), Core.kw().favor_recall(true).config(ZERO_CONFIG).fast(true)));
        my_document = Etree.html_fromstring("<html><body><div class=\"article-body\"><div class=\"teaser-content\"><p>This here is a teaser text.</p></div><div><p>This here is the text.</p></div></body></html>");
        assertTrue(Core.extract(my_document.copy(), Core.kw().favor_recall(true).config(ZERO_CONFIG).fast(true)).contains("teaser text"));
        assertFalse(Core.extract(my_document.copy(), Core.kw().config(ZERO_CONFIG).fast(true)).contains("teaser text"));
        assertFalse(Core.extract(my_document.copy(), Core.kw().favor_precision(true).config(ZERO_CONFIG).fast(true)).contains("teaser text"));
        my_document = Etree.html_fromstring("<html><body><article><div><p><a href=\"test.html\">1.</a><br/><a href=\"test2.html\">2.</a></p></div></article></body></html>");
        String result = Core.extract(my_document.copy(), Core.kw().favor_recall(true).config(ZERO_CONFIG).fast(true));
        assertFalse(result.contains("1"));
        result = Core.extract(my_document.copy(), Core.kw().favor_precision(true).config(ZERO_CONFIG).fast(true));
        assertFalse(result.contains("1"));
        my_document = Etree.html_fromstring("<html><body><div class=\"article-body\"><p>content</p><p class=\"link\">Test</p></div></body></html>");
        result = Core.extract(my_document.copy(), Core.kw().favor_precision(false).config(ZERO_CONFIG).fast(true));
        assertTrue(result.contains("content"));
        assertTrue(result.contains("Test"));
        result = Core.extract(my_document.copy(), Core.kw().favor_precision(true).config(ZERO_CONFIG).fast(true));
        assertTrue(result.contains("content"));
        assertFalse(result.contains("Test"));
        my_document = Etree.html_fromstring("<html><body><article><aside><p>Here is the text.</p></aside></article></body></html>");
        result = Core.extract(my_document.copy(), Core.kw().favor_recall(false).config(ZERO_CONFIG).fast(true));
        assertNotEquals("Here is the text.", result);
        result = Core.extract(my_document.copy(), Core.kw().favor_recall(true).config(ZERO_CONFIG).fast(true));
        assertEquals("Here is the text.", result);
        my_document = Etree.html_fromstring("<html><body><div><h2>Title</h2><small>Text.</small></div></body></html>");
        result = Core.extract(my_document.copy(), Core.kw().favor_recall(true).config(ZERO_CONFIG).fast(false));
        assertTrue(result.length() > 0);
        my_document = Etree.html_fromstring("<html><body><div><span>Text.</span></div></body></html>");
        assertEquals("", Core.extract(my_document.copy(), Core.kw().favor_precision(true).fast(true).config(ZERO_CONFIG)));
        assertEquals("Text.", Core.extract(my_document.copy(), Core.kw().favor_recall(true).fast(true).config(ZERO_CONFIG)));
    }

    /** A document whose canonical URL is blacklisted is rejected. */
    @Test
    void test_url_blacklist() {
        String doc = "<html><head><link rel=\"canonical\" href=\"https://example.org/page\"/></head><body><article><p>Some real article body text here.</p></article></body></html>";
        assertNotNull(Core.extract(doc, Core.kw().config(ZERO_CONFIG)));
        assertNull(Core.extract(doc, Core.kw().url_blacklist(Set.of("https://example.org/page")).config(ZERO_CONFIG)));
    }

    /** recover_wild_text with default tags in recall mode must not crash (frozenset .update()) */
    @Test
    void test_recover_wild_text_default_tags() {
        Extractor options = new Extractor(Extractor.kw().recall(true));
        Element tree = Etree.html_fromstring("<body><div>some wild text outside the main frame, long enough to be recovered</div></body>");
        Element result = MainExtractor.recover_wild_text(tree, Etree.Element("body"), options);
        assertNotNull(result);
    }

    static final String _MED_REF = "<ref target=\"/x\">" + "x".repeat(250) + "</ref>";
    static final String _BIG_REF = "<ref target=\"/x\">" + "x".repeat(600) + "</ref>";
    static final String _STRADDLE_REF = "<ref target=\"/x\">" + "x".repeat(360) + "</ref>";

    static java.util.stream.Stream<Arguments> test_link_density_tables_threshold_params() {
        return java.util.stream.Stream.of(
            Arguments.of("<table><cell>" + ("y".repeat(50)) + (_MED_REF) + "</cell></table>", true),
            Arguments.of("<table><cell>" + ("y".repeat(200)) + (_MED_REF) + "</cell></table>", false),
            Arguments.of("<table><cell>" + ("y".repeat(240)) + (_STRADDLE_REF) + "</cell></table>", false),
            Arguments.of("<table><cell>" + ("y".repeat(400)) + (_BIG_REF) + "</cell></table>", true),
            Arguments.of("<table><cell>" + ("y".repeat(600)) + (_BIG_REF) + "</cell></table>", false));
    }

    @ParameterizedTest
    @MethodSource("test_link_density_tables_threshold_params")
    void test_link_density_tables_threshold(String html_str, boolean expected) {
        assertSame(expected, Htmlprocessing.link_density_test_tables(Etree.html_fromstring(html_str)));
    }

    /** A text-rich table whose only own-depth ref is a textless icon link is not boilerplate (#lineups). */
    @Test
    void test_link_density_tables_textless_links_kept() {
        Element icon_table = Etree.html_fromstring("<table><cell>" + "data ".repeat(50) + "<ref target=\"/x\"><graphic src=\"/i.png\"/></ref></cell></table>");
        assertFalse(Htmlprocessing.link_density_test_tables(icon_table));
    }

    /** {@code "".join(template.format(i) for i in range(n))} with {i} placeholders. */
    static String joinRange(String template, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(template.replace("{i}", String.valueOf(i)));
        }
        return sb.toString();
    }

    static final String _HEADLINES = joinRange("<ref target=\"/n{i}\">Latest news headline number {i} about some topic today</ref> ", 20);
    static final String _LISTING = joinRange("<p><ref target=\"/d{i}.pdf\">Instruktionsbok MC 258 part {i}</ref></p>", 14);
    static final String _CARD = "Align: a widget that aligns its child within itself and optionally sizes itself based on the child's given size";

    static java.util.stream.Stream<Arguments> test_link_density_test_params() {
        return java.util.stream.Stream.of(
            // 100 to 150 chars of recommendation links (mixed.de): the length limit stays at 100
            Arguments.of(joinRange("<ref target=\"/p{i}\">Recommended product number {i}: a nice gadget</ref> ", 3), true, false),
            // a big "latest news" sidebar, above limitlen
            Arguments.of(_HEADLINES, false, true),
            // one long link per catalog card (avg link text >= 100 chars) is content
            Arguments.of(joinRange("<ref target=\"/w{i}\">" + _CARD + "</ref> ", 8), false, false),
            // a document listing, one short link per paragraph
            Arguments.of(_LISTING, false, false),
            // a farm wrapped in a single paragraph buys no exemption
            Arguments.of("<p>" + _HEADLINES + "</p>", false, true),
            // the exemption is all-or-nothing: one paragraph with two links makes it a farm again
            Arguments.of(_LISTING + "<p><ref target=\"/a\">Also see this page</ref> <ref target=\"/b\">and this other one</ref></p>", false, true));
    }

    /** Link-density verdict on a div followed by a content sibling (#584, #900). */
    @ParameterizedTest
    @MethodSource("test_link_density_test_params")
    void test_link_density_test(String items, boolean short_, boolean expected) {
        Element element = Etree.html_fromstring("<body><div>" + items + "</div><p>real article sibling here</p></body>").get(0);
        int length = Utils.trim(element.text_content()).length();
        assertTrue(short_ ? 100 < length && length < 150 : length > 300, String.valueOf(length));
        assertSame(expected, Htmlprocessing.link_density_test(element).first());
    }

    /** a short div with 3+ children and some link text is not dense, but backtracking still drops it */
    @Test
    void test_delete_by_link_density_backtracking() {
        String htmlstring = "<html><body><div><p>Some intro text</p><p>More words</p><ref>A link text</ref></div></body></html>";
        assertNotNull(Htmlprocessing.delete_by_link_density(Etree.html_fromstring(htmlstring), "div").find(".//div"));
        assertNull(Htmlprocessing.delete_by_link_density(Etree.html_fromstring(htmlstring), "div", true, false).find(".//div"));
    }
    /** regression on the legacy single-PR discard tokens, each decided by a full-WMB single-token A/B     (see xpaths.py audit note): 'yin' STAYS (net-positive despite English '-ying'/'y+Info'     collisions -- a prior removal was reverted after a sign-error), 'xg1' was REMOVED (net-negative     -- it discarded the real content of a Chinese forum page to nothing). */
    @Test
    void test_overall_discard_legacy_tokens() {
        for (String cls : List.of("yin", "zlylin", "mol-factbox")) {
            assertTrue(_xpath_hits(Xpaths.OVERALL_DISCARD_XPATH, "class=\"" + cls + "\""), cls);
        }
        assertFalse(_xpath_hits(Xpaths.OVERALL_DISCARD_XPATH, "class=\"xg1\""));
    }

    /** regression: discard tokens must match whichever of id/class carries them, regardless of     attribute order in the source (re:test(@id|@class,...) alone only tests the source-FIRST one).     Exception: 'cookie' stays first-attribute-only -- pages about cookies carry it on real content. */
    @Test
    void test_overall_discard_matches_both_attributes() {
        assertTrue(_xpath_hits(Xpaths.OVERALL_DISCARD_XPATH, "class=\"x\" id=\"author-box\""));
        assertTrue(_xpath_hits(Xpaths.OVERALL_DISCARD_XPATH, "id=\"x\" class=\"sidebar\""));
        assertFalse(_xpath_hits(Xpaths.OVERALL_DISCARD_XPATH, "class=\"hidden-x\" id=\"cookieBanner\""));
    }

    /** regression: the concept-token composer _alt() must refuse an empty group -- '' would make     re:test(@id|@class, '') match every element (a sole-token removal would silently over-discard).     Uses ValueError not assert so the guard survives python -O. */
    @Test
    void test_xpath_alt_rejects_empty_group() {
        assertEquals("a|b", Xpaths._alt("a", "b"));
        assertThrows(IllegalArgumentException.class, () -> Xpaths._alt());
    }

    /** regression: PRECISION_DISCARD_XPATH matches 'link' as a whole class TOKEN, not a bare     substring (2026-07-11). class='link' is still dropped (intended, cf. test_precision_recall),     but compound classes containing 'link' (permalink/headline-link/featured-link) must NOT be     -- the bare substring discarded real content in precision mode. 'bottom'/'header' unchanged. */
    @Test
    void test_precision_discard_link_token_only() {
        assertTrue(_xpath_hits(Xpaths.PRECISION_DISCARD_XPATH, "class=\"link\""));
        assertTrue(_xpath_hits(Xpaths.PRECISION_DISCARD_XPATH, "class=\"nav link\""));
        assertFalse(_xpath_hits(Xpaths.PRECISION_DISCARD_XPATH, "class=\"article-permalink\""));
        assertFalse(_xpath_hits(Xpaths.PRECISION_DISCARD_XPATH, "class=\"headline-link\""));
        assertFalse(_xpath_hits(Xpaths.PRECISION_DISCARD_XPATH, "class=\"featured-link--wrap\""));
        assertTrue(_xpath_hits(Xpaths.PRECISION_DISCARD_XPATH, "class=\"article-bottom\""));
        assertTrue(_xpath_hits(Xpaths.PRECISION_DISCARD_XPATH, "class=\"site-header\"", "header"));
    }

    /** regression: PRECISION_DISCARD_XPATH must match 'bottom' only where it starts or ends a     class token. As a bare substring it also matched CSS spacing utilities -- alsumaria.tv wraps     the article column in 'Padding-bottom-lg-30', so precision mode discarded the whole article. */
    @Test
    void test_precision_discard_bottom_token_boundary() {
        assertTrue(_xpath_hits(Xpaths.PRECISION_DISCARD_XPATH, "class=\"bottom\""));
        assertTrue(_xpath_hits(Xpaths.PRECISION_DISCARD_XPATH, "class=\"article-bottom\""));
        assertTrue(_xpath_hits(Xpaths.PRECISION_DISCARD_XPATH, "class=\"bottom-bar\""));
        assertTrue(_xpath_hits(Xpaths.PRECISION_DISCARD_XPATH, "id=\"bottom\""));
        assertTrue(_xpath_hits(Xpaths.PRECISION_DISCARD_XPATH, "class=\"wrapper bottom\""));
        assertFalse(_xpath_hits(Xpaths.PRECISION_DISCARD_XPATH, "class=\"Padding-bottom-lg-30\""));
        assertFalse(_xpath_hits(Xpaths.PRECISION_DISCARD_XPATH, "class=\"col-8 Padding-bottom-lg-30 floatL\""));
        assertFalse(_xpath_hits(Xpaths.PRECISION_DISCARD_XPATH, "class=\"border-bottom-0\""));
        assertFalse(_xpath_hits(Xpaths.PRECISION_DISCARD_XPATH, "class=\"mb-bottom-3\""));
    }

    /** regression: ASP.NET WebForms and similar wrap the entire page in one <form id='aspnetForm'>.     tree_cleaning() deletes every form, so on those sites (alsumaria.tv) the whole document was     discarded and extraction fell back to unrelated sidebar chrome. A form holding most of the     text is a layout wrapper and must survive; genuine widget forms must still be removed. */
    @Test
    void test_wrapper_form_kept() {
        String article = "<p>" + "This is the actual article body with enough text to be extracted. ".repeat(4) + "</p>";
        Extractor options = new Extractor(Extractor.kw().config(ZERO_CONFIG));
        String wrapped = "<html><body><form method=\"post\" id=\"aspnetForm\"><div>" + article + "</div></form></body></html>";
        Element cleaned = Htmlprocessing.tree_cleaning(Utils.load_html(wrapped), options);
        assertTrue(cleaned.text_content().contains("the actual article body"));
        assertNull(cleaned.find(".//form"));
        assertTrue(Core.extract(wrapped, Core.kw().config(ZERO_CONFIG)).contains("the actual article body"));
        String with_widget = "<html><body><div>" + article + "</div><form><p>Search this site</p><input type=\"text\"/></form></body></html>";
        cleaned = Htmlprocessing.tree_cleaning(Utils.load_html(with_widget), options);
        assertTrue(cleaned.text_content().contains("the actual article body"));
        assertFalse(cleaned.text_content().contains("Search this site"));
        Settings.MANUALLY_CLEANED.remove("form");
        try {
            cleaned = Htmlprocessing.tree_cleaning(Utils.load_html(with_widget), options);
            assertTrue(cleaned.text_content().contains("Search this site"));
        } finally {
            Settings.MANUALLY_CLEANED.add(5, "form");
        }
        assertTrue(Settings.MANUALLY_CLEANED.contains("form"));
    }

    /** A text-bearing div moved into result_body is not revisited (alsumaria.tv), whitespace-only <lb> tails are dropped. */
    @Test
    void test_no_duplicate_paragraph_from_lb_tail() {
        String html = "<html><body><article><div>First paragraph of the article, long enough to be kept by the extractor.<br/>        <div class=\"advert\">        </div>        <br/>Second paragraph of the article, also long enough to be kept here.</div></article></body></html>";
        String result = Core.extract(html, Core.kw().config(ZERO_CONFIG));
        assertEquals(1, T.count(result, "Second paragraph of the article"));
        assertTrue(result.contains("First paragraph of the article"));
        assertFalse(Arrays.stream(result.split("\n", -1)).anyMatch(line -> !line.isEmpty() && Py.isspace(line)));
    }

    /** A body subtree holding only <lb> is emitted as one paragraph, not one <p> per line. */
    @Test
    void test_lb_only_subtree_stays_one_paragraph() {
        String html_str = "<html><body><div class=\"entry-content\"><br/>First line of text long enough to be kept by the extractor here.<br/>Second line of text long enough to be kept by the extractor here.</div></body></html>";
        String result = Core.extract(html_str, Core.kw().config(ZERO_CONFIG).output_format("xml"));
        assertEquals(1, T.count(result, "<p>"));
        assertEquals(2, T.count(result, "<lb/>"));
    }

    /** Caller-supplied trees have no depth cap, so the content walk must not recurse. */
    @Test
    void test_extract_deep_caller_supplied_tree() {
        Element tree = Etree.html_fromstring("<html><body><p>root text</p></body></html>");
        Element node = tree.find("body");
        for (int i = 0; i < 3000; i++) {
            node = Etree.SubElement(node, "div");
        }
        node.text = "Deep text long enough to be kept by the extractor, appearing at the very bottom.";
        String result = Core.extract(tree, Core.kw().config(ZERO_CONFIG));
        assertTrue(result.contains("Deep text long enough"));
    }

    /** A caller-supplied tree must come back unchanged, prune_xpath included. */
    @Test
    void test_extract_leaves_caller_tree_intact() {
        Element tree = Etree.html_fromstring("<html><body><p>First paragraph.</p><p>Second paragraph.</p></body></html>");
        String before = Etree.tostring(tree);
        String result = Core.extract(tree, Core.kw().prune_xpath("//p[1]").config(ZERO_CONFIG));
        assertNotNull(result);
        assertFalse(result.contains("First paragraph"));
        assertEquals(before, Etree.tostring(tree));
    }

    /** regression: text directly after a list, table, quote or code block (its tail) was dropped:     _extract() rebuilds those blocks as new elements and left the source tail behind. It is now     kept as a paragraph of its own, exactly once. */
    @ParameterizedTest
    @ValueSource(strings = {"<ul><li>Improve the tram network</li><li>Expand the bicycle lanes</li></ul>", "<table><tr><td>Budget</td><td>40</td></tr><tr><td>Staff</td><td>12</td></tr></table>", "<blockquote><p>We will deliver the plan on time and on budget.</p></blockquote>", "<pre>total = sum(values)</pre>"})
    void test_text_after_block_is_kept(String block) {
        String html = "<html><body><article><h1>Headline</h1><p>" + ("The committee reviewed the proposal over several sessions. ".repeat(4)) + "</p>" + block + "These totals include every department that submitted figures in time.<p>" + ("The findings were published together with the underlying data. ".repeat(4)) + "</p></article></body></html>";
        String result = Core.extract(html, Core.kw().config(ZERO_CONFIG));
        assertEquals(1, T.count(result, "These totals include every department"));
    }

    /** GH#951: inline code in a div is copied whole, tail included, so the tail must not also be     emitted as a paragraph of its own. */
    @Test
    void test_text_after_inline_code_not_duplicated() {
        String html = "<html><body><article><p>" + ("The committee reviewed the proposal over several sessions. ".repeat(4)) + "</p><div>Tool events such as <code>PreToolUse</code> and <code>PostToolUse</code> fire the same hooks.</div><p>" + ("The findings were published together with the underlying data. ".repeat(4)) + "</p></article></body></html>";
        String result = Core.extract(html, Core.kw().config(ZERO_CONFIG));
        assertTrue(result.contains("PreToolUse and PostToolUse fire the same hooks."));
        assertEquals(1, T.count(result, "fire the same hooks"));
    }

    /** GH#780: BODY_XPATH's fulltext-class rule (re:test(@class,'fulltext','i'), replacing an     obscure translate()-based case-fold hack) must still match every capitalization of a     genuine 'fulltext' content class. #780's actual complaint -- the bare substring ALSO     matching a wrapper div ('FulltextWrapper') that isn't the content itself -- is NOT fixed     here: word-boundary anchoring can't separate that from a legitimate 'FullText' class, since     both are equally well-bounded tokens (documented limitation, not a regression). */
    @Test
    void test_body_xpath_fulltext_class() {
        for (String cls : List.of("fulltext", "FullText", "fullText", "FULLTEXT", "article-fulltext")) {
            assertTrue(_xpath_hits(Xpaths.BODY_XPATH, "class=\"" + cls + "\""), cls);
        }
        assertTrue(_xpath_hits(Xpaths.BODY_XPATH, "class=\"FulltextWrapper\""));
    }

    /** regression: BASIC_CLEAN_XPATH's cookie/consent tokens are ANCHORED banner/CMP compounds, not     bare substrings -- the substrings matched WP Cookie Notice BODY classes (cookies-not-set) and     topical content classes, deleting up to 97% of a page from baseline()/html2txt() and zeroing     the escalation-gate denominator. Banner containers (incl. CMP vendors) must still be pruned. */
    @Test
    void test_basic_cleaning_cookie_banner_scope() {
        String content = "<p>" + "Real article text about a subject. ".repeat(5) + "</p>";
        String banners = "<div id='onetrust-consent-sdk'><p>By clicking Accept you agree we can store cookies.</p></div><div class='cookie-notice-container'><p>We use cookies to improve our service.</p></div>";
        Element doc = Etree.html_fromstring("<html><body class='single-post cookies-not-set'><div class='cookie-recipe-content'>" + content + "</div>" + banners + "</body></html>");
        String text = Baseline.baseline(doc).second();
        assertTrue(text.contains("Real article text"));
        assertFalse(text.contains("cookies"));
        String page_measure = Baseline.html2txt(doc);
        assertTrue(page_measure.contains("Real article text"));
        assertFalse(page_measure.contains("cookies"));
    }

    /** Cell handling must follow real ancestry, not 'a cell exists somewhere' (#767). */
    @Test
    void test_cell_context_follows_real_ancestry() {
        Element tree = Etree.fromstring("<body><table><row><cell><p>inside</p></cell></row></table><p>outside</p></body>");
        assertEquals("| inside | \n\noutside\n", Xml.xmltotxt(tree, true));
        assertEquals("| inside | \noutside", Xml.xmltotxt(tree, false));
    }

    static final String _COLSPAN_CONTENT_BASE = "<tr><td>a</td><td>b</td><td>c</td></tr>";

    static final String _COLSPAN_CONTENT_ROW = "<tr><td>a</td><td colspan='2'><p>b</p><p>c</p></td></tr>";

    static java.util.stream.Stream<Arguments> test_table_colspan_content_params() {
        return java.util.stream.Stream.of(
            Arguments.of(1, "| a | b | c | \n| a | b c |  |"),
            Arguments.of(2, "| a | b | c | \n| a | b c |  | \n| a | b c |  |"));
    }

    /** A colspan=2 cell with block children is flattened; the placeholder fills to max_cols. */
    @ParameterizedTest
    @MethodSource("test_table_colspan_content_params")
    void test_table_colspan_content(int rows, String expected) {
        String html_str = "<html><body><article><table>" + _COLSPAN_CONTENT_BASE + _COLSPAN_CONTENT_ROW.repeat(rows) + "</table></article></body></html>";
        assertEquals(expected, Core.extract(html_str, Core.kw().fast(true).output_format("txt").config(ZERO_CONFIG).include_tables(true)));
    }

    static java.util.stream.Stream<Arguments> test_handle_table_params() {
        return java.util.stream.Stream.of(
            Arguments.of("<table><!-- c1 --><tr><td>cell text<!-- c2 --></td></tr></table>", false, "<row><cell>cell text</cell></row>"),
            Arguments.of("<table><tr><!-- ignored --><td>visible</td></tr></table>", false, "<row><cell>visible</cell></row>"),
            Arguments.of("<table><tr><td>cell1</td><td>cell2</td></tr><tr><td>cell3</td><td>cell4</td></tr></table>", false, "<row><cell>cell1</cell><cell>cell2</cell></row><row><cell>cell3</cell><cell>cell4</cell></row>"),
            Arguments.of("<table><tr><td><p>text</p><p>more text</p></td></tr></table>", false, "<row><cell><p>text</p><p>more text</p></cell></row>"),
            Arguments.of("<table><tr><td>text<lb/><p>more text</p></td></tr></table>", false, "<row><cell>text<p>more text</p></cell></row>"),
            Arguments.of("<table><tr><td><ref target='test'>link</ref></td></tr></table>", false, "<row><cell><ref target=\"test\">link</ref></cell></row>"),
            Arguments.of("<table><tr><th>Month</th><th>Days</th></tr><tr><td>January</td><td>31</td></tr></table>", false, "<row><cell role=\"head\">Month</cell><cell role=\"head\">Days</cell></row><row><cell>January</cell><cell>31</cell></row>"),
            Arguments.of("<table><tr><th>Name</th><th colspan='2'>Phone</th></tr><tr><td>J</td><td>p1</td><td>p2</td></tr></table>", false, "<row><cell role=\"head\">Name</cell><cell role=\"head\">Phone</cell><cell role=\"head\"/></row><row><cell>J</cell><cell>p1</cell><cell>p2</cell></row>"),
            Arguments.of("<table><tr><td><hi>highlighted text</hi></td></tr></table>", false, "<row><cell><hi>highlighted text</hi></cell></row>"),
            Arguments.of("<table><tr><td>\n<table><tr><td>1</td></tr></table>\n</td></tr></table>", false, "<row><cell>\n</cell></row>"),
            Arguments.of("<table><tr><td>\n<table><tr><td>1</td></tr></table>\n</td><td>text1</td></tr><tr><td>text2</td></tr></table>", false, "<row><cell>\n</cell><cell>text1</cell></row><row><cell>text2</cell><cell/></row>"),
            Arguments.of("<table><tr><td>A</td></tr><tr><td><table><tr><td>inner</td></tr></table></td></tr><tr><td>AFTER</td></tr></table>", false, "<row><cell>A</cell></row><row><cell>AFTER</cell></row>"),
            Arguments.of("<table><tr><td>before<table><tr><td>i</td></tr></table>after</td></tr></table>", false, "<row><cell>before after</cell></row>"),
            Arguments.of("<table><tr><td><del>struck</del><table><tr><td>i</td></tr></table>after</td></tr></table>", false, "<row><cell><del>struck</del> after</cell></row>"),
            Arguments.of("<table><tr><td><p>a list</p><list><item>one</item></list></td></tr></table>", false, "<row><cell><p>a list</p></cell></row>"),
            Arguments.of("<table><tr><td><p>a list</p><list><item>one</item></list></td></tr></table>", true, "<row><cell><p>a list</p><list><item>one</item></list></cell></row>"),
            Arguments.of("<table><td>cell1</td><tr><td>cell2</td></tr></table>", false, "<row><cell>cell1</cell></row><row><cell>cell2</cell></row>"),
            Arguments.of("<table><td>a</td><td>b</td></table>", false, "<row><cell>a</cell><cell>b</cell></row>"),
            Arguments.of("<table><colgroup><col style='width:50%'><col style='width:50%'></colgroup><tr><td>a</td><td>b</td></tr></table>", false, "<row><cell>a</cell><cell>b</cell></row>"),
            Arguments.of("<table><col span='2'><tr><td>x</td><td>y</td></tr></table>", false, "<row><cell>x</cell><cell>y</cell></row>"),
            Arguments.of("<table><col><td>orphan</td><tr><td>normal</td></tr></table>", false, "<row><cell>orphan</cell></row><row><cell>normal</cell></row>"),
            Arguments.of("<table><caption>  </caption><tr><td>x</td></tr></table>", false, "<row><cell>x</cell></row>"));
    }

    /** handle_table output structure on small and broken tables. */
    @ParameterizedTest
    @MethodSource("test_handle_table_params")
    void test_handle_table(String table, boolean recall, String expected) {
        assertEquals("<table>" + expected + "</table>", Etree.tostring(_handle_table(table, recall), "unicode"));
    }

    @Test
    void test_table_processing() {
        Element htmlstring = Etree.html_fromstring("<html>\n              <body><article>\n                <table>\n                  <tbody>\n                    <tr>\n                      <td>\n                        <small>text<br></small>\n                        <h4>more_text</h4>\n                      </td>\n                      <td><a href='link'>linktext</a></td>\n                    </tr>\n                  </tbody>\n                </table>\n              </article></body>\n            </html>");
        String processed = Core.extract(htmlstring, Core.kw().fast(true).output_format("xml").config(ZERO_CONFIG).include_links(true));
        String result = processed.replace("\n", "").replace(" ", "");
        assertTrue(result.contains("<table><row><cell>text<headrend=\"h4\">more_text</head></cell><cell><reftarget=\"link\">linktext</ref></cell></row></table>"));
        String multi_row_links = "<html><body><article>\n        <p>Enough intro text to satisfy trafilatura's minimum extraction length requirements for this test.</p>\n        <table>\n            <tr><th>Key</th><th>Value</th></tr>\n            <tr><td><a href=\"/k1\">Coord</a>:</td><td><a href=\"/v1\">48 N</a></td></tr>\n            <tr><td><a href=\"/k2\">State</a>:</td><td><a href=\"/v2\">BW</a></td></tr>\n            <tr><td><a href=\"/k3\">Region</a>:</td><td><a href=\"/v3\">Stuttgart</a></td></tr>\n        </table>\n    </article></body></html>";
        String md = Core.extract(multi_row_links, Core.kw().output_format("markdown").include_tables(true).include_links(true).config(ZERO_CONFIG));
        for (Object _r : T.list(List.of("Coord", "/k1", "48 N"), List.of("State", "/k2", "BW"), List.of("Region", "/k3", "Stuttgart"))) {
            List<?> _row = (List<?>) _r;
            String key = (String) _row.get(0);
            String href = (String) _row.get(1);
            String value = (String) _row.get(2);
            assertTrue(md.contains("[" + key + "](" + href + ")"));
            assertTrue(md.contains(value));
        }
        String present = "<html><body><article>\n<table>\n<tr><td><b>Present Tense</b></td>\n<td>I buy</td>\n<td>you buy</td>\n<td>he/she/it buys</td>\n<td>we buy</td>\n<td>you buy</td>\n<td>they buy</td>\n</tr>\n    </table></article></body></html>";
        String my_result = Core.extract(present, Core.kw().fast(true).output_format("xml").include_formatting(true).config(ZERO_CONFIG));
        assertTrue(my_result.contains("<row>\n        <cell>\n          <hi rend=\"#b\">Present Tense</hi>\n        </cell>\n        <cell>I buy</cell>\n        <cell>you buy</cell>\n        <cell>he/she/it buys</cell>\n        <cell>we buy</cell>\n        <cell>you buy</cell>\n        <cell>they buy</cell>\n      </row>"));
        assertTrue(Core.extract(present, Core.kw().fast(true).output_format("txt").config(ZERO_CONFIG)).startsWith("| Present Tense | I buy | you buy |"));
        String table = "<table><tr><td><a href=\"test.html\">" + "ABCD".repeat(100) + "</a></td></tr></table>";
        result = _extract_doc(table, false, zkw().fast(true).output_format("xml").include_tables(true).include_links(true));
        assertFalse(result.contains("ABCD"));
        table = "<table><th>1</th><table><tr><td>2</td></tr></table></table>";
        result = _extract_doc(table, false, zkw().fast(true).output_format("xml").include_tables(true));
        assertTrue(result.contains("<cell role=\"head\">1</cell>"));
        assertTrue(result.contains("<cell>2</cell>"));
        table = "<figure><table><th>1</th><tr><td>2</td></tr></table></figure>";
        result = _extract_doc(table, false, zkw().fast(true).output_format("xml").include_tables(true));
        assertTrue(result.contains("1"));
        assertTrue(result.contains("2"));
        table = "<table><tr><th>head 1</th><th>head 2</th></tr><tr><td>1</td><td>2</td></tr></table>";
        assertTrue(_table_txt(table).contains("|---|---|"));
        String single_header = "<table><tr><th>a</th><th>b</th><th>c</th></tr></table>";
        assertTrue(_table_txt(single_header).contains("|---|---|---|"));
        table = "<table><tr><td>cell<br>1</td><td>cell<p>2</p></td></tr></table>";
        assertTrue(_table_txt(table).contains("| cell 1 | cell 2 |"));
        table = "<table><tr><th>a</th><th>b</th></tr><tr><th>c</th><th>d</th></tr></table>";
        assertEquals(2, T.count(_table_txt(table), "---|"));
        table = "<table><tr><td><a href=\"link.html\">a</a></td></tr></table>";
        assertEquals("| a |", _table_txt(table));
        assertEquals("", _table_txt("<table><tr><td><a href=\"link.html\">" + ("abc".repeat(100)) + "</a></td></tr></table>"));
        assertEquals("", _table_txt("<table><tr><td><a href=\"link.html\">" + (" ".repeat(100)) + "</a></td></tr></table>"));
    }

    static final String _IMG_URL = "http://aa.bb/c.jpg";

    static final String _IMG_TABLE_HEADER = "<tr><td>a</td><td>b</td><td>c</td></tr>";

    static final String _IMG_TABLE_TAIL = "<td><p>b</p><p>c</p></td><td>d</td>";

    static java.util.stream.Stream<Arguments> test_table_image_in_cell_params() {
        return java.util.stream.Stream.of(
            Arguments.of("<td>a<img src=\"" + (_IMG_URL) + "\" alt=\"img\"/><span>a</span></td>", "a ![img](" + (_IMG_URL) + ") a"),
            Arguments.of("<td><a href=\"" + (_IMG_URL) + "\"><img src=\"" + (_IMG_URL) + "\" alt=\"img\"/><span>a</span></a></td>", "![img](" + (_IMG_URL) + ") a"),
            Arguments.of("<td><img src=\"" + (_IMG_URL) + "\" alt=\"img\"/><span>a</span></td>", "![img](" + (_IMG_URL) + ") a"),
            Arguments.of("<td><img src=\"" + (_IMG_URL) + "\" alt=\"img1\"/><span>a</span><img src=\"" + (_IMG_URL) + "\" alt=\"img2\"/></td>", "![img1](" + (_IMG_URL) + ") a ![img2](" + (_IMG_URL) + ")"));
    }

    /** Images in table cells render as GFM inline images; link wrapping is preserved. */
    @ParameterizedTest
    @MethodSource("test_table_image_in_cell_params")
    void test_table_image_in_cell(String cell1, String expected_cell1) {
        String html_str = "<html><body><article><table>" + (_IMG_TABLE_HEADER) + "<tr>" + cell1 + (_IMG_TABLE_TAIL) + "</tr></table></article></body></html>";
        String result = Core.extract(html_str, Core.kw().fast(true).output_format("markdown").config(ZERO_CONFIG).include_images(true).include_tables(true));
        assertEquals("| a | b | c | \n| " + expected_cell1 + " | b c | d |", result);
    }

    static final String _COMBO_DOC = "<html><body><article><p>Intro with a <a href=\"http://x.io/p\">link</a> and <b>bold</b> word.</p><table><tr><td>h1</td><td>h2</td></tr><tr><td><a href=\"http://x.io/c\"><b>bold link</b></a></td><td><img src=\"http://x.io/i.jpg\" alt=\"pic\"/></td></tr></table></article></body></html>";

    static final Map<String, Object> _COMBO_ALL_ON = Map.of("include_links", true, "include_formatting", true, "include_images", true, "include_tables", true);

    /** All four flags on: cells keep formatting + images, and a link wrapping formatting keeps its target. */
    @Test
    void test_combined_links_formatting_images_tables() {
        String result = Core.extract(_COMBO_DOC, withKwargs(Core.kw().output_format("markdown").config(ZERO_CONFIG), _COMBO_ALL_ON));
        assertEquals("Intro with a [link](http://x.io/p) and **bold** word.\n\n| h1 | h2 | \n| [**bold link**](http://x.io/c) | ![pic](http://x.io/i.jpg) |", result);
    }

    static java.util.stream.Stream<Arguments> test_combined_flags_toggle_off_params() {
        return java.util.stream.Stream.of(
            Arguments.of("include_links", "Intro with a link and **bold** word.\n\n| h1 | h2 | \n| **bold link** | ![pic](http://x.io/i.jpg) |"),
            Arguments.of("include_formatting", "Intro with a [link](http://x.io/p) and bold word.\n| h1 | h2 | \n| [bold link](http://x.io/c) | ![pic](http://x.io/i.jpg) |"),
            Arguments.of("include_images", "Intro with a [link](http://x.io/p) and **bold** word.\n\n| h1 | h2 | \n| [**bold link**](http://x.io/c) |  |"),
            Arguments.of("include_tables", "Intro with a [link](http://x.io/p) and **bold** word."));
    }

    /** Pin current behavior: disabling one flag changes only its feature (combined doc). */
    @ParameterizedTest
    @MethodSource("test_combined_flags_toggle_off_params")
    void test_combined_flags_toggle_off(String off, String expected) {
        Map<String, Object> flags = new LinkedHashMap<>(_COMBO_ALL_ON);
        flags.put(off, false);
        String result = Core.extract(_COMBO_DOC, withKwargs(Core.kw().output_format("markdown").config(ZERO_CONFIG), flags));
        assertEquals(expected, result);
    }

    static final String _COLSPAN_ROWS = "<td>b</td></tr><tr><td>c</td><td>d</td><td>e</td></tr></table>";

    /** A colspan=2 cell materializes an inline placeholder; the row fills to the table's column count. */
    @Test
    void test_table_colspan_padding() {
        assertTrue(_table_txt("<table><tr><td colspan='2'>a</td>" + (_COLSPAN_ROWS)).contains("| a |  | b |"));
    }

    /** Non-colspan span attributes are ignored (colspan=1); trailing pad fills to max_cols. */
    @ParameterizedTest
    @ValueSource(strings = {"span=\"2\"", "span=\"2.1\"", "span=\"-1\"", "span=\"abc\""})
    void test_table_bad_span_attr_treated_as_colspan1(String bad_span) {
        assertTrue(_table_txt("<table><tr><td " + bad_span + ">a</td>" + (_COLSPAN_ROWS)).contains("| a | b |  |"));
    }

    /** Huge or non-numeric colspan must not crash or discard the document (#657). */
    @ParameterizedTest
    @ValueSource(strings = {"<td colspan=\"9007199254740991\">a</td>", "<th colspan=\"9007199254740991\">a</th>", "<td colspan=\"2x\">a</td>"})
    void test_table_huge_or_bad_colspan_no_crash(String first_cell) {
        assertNotNull(_table_txt("<table><tr>" + first_cell + (_COLSPAN_ROWS)));
    }

    static java.util.stream.Stream<Arguments> test_colspan_zero_trust_params() {
        return java.util.stream.Stream.of(
            Arguments.of("1", 1),
            Arguments.of("3", 3),
            Arguments.of("12", 12),
            Arguments.of("²", 1),
            Arguments.of("1²", 1),
            Arguments.of("2x", 1),
            Arguments.of("", 1),
            Arguments.of("-2", 1));
    }

    /** _span must default to 1 for non-decimal values (isdigit() admits superscripts that int() rejects). */
    @ParameterizedTest
    @MethodSource("test_colspan_zero_trust_params")
    void test_colspan_zero_trust(String colspan, int expected) {
        assertEquals(expected, MainExtractor._span(Etree.html_fromstring("<td colspan=\"" + colspan + "\">x</td>"), "colspan"));
    }

    static java.util.stream.Stream<Arguments> test_table_rowspan_params() {
        return java.util.stream.Stream.of(
            Arguments.of("<tr><td rowspan='2'>x</td><td>a</td></tr><tr><td>b</td></tr>", "| x | a | \n|  | b |"),
            Arguments.of("<tr><td rowspan='2' colspan='2'>big</td><td>c</td></tr><tr><td>x</td></tr>", "|  |  | x |"),
            Arguments.of("<tr><td>a</td><td rowspan='2'>b</td><td>c</td></tr><tr><td>x</td></tr><tr><td>d</td><td>e</td><td>f</td></tr>", "| d | e | f |"),
            Arguments.of("<tr><td rowspan='3'>a</td><td>b</td></tr><tr></tr><tr><td>c</td></tr>", "| a | b | \n|  | c |"),
            Arguments.of("<tr><td rowspan='2'>a</td><td>b</td></tr><tr></tr><tr><td>c</td><td>d</td></tr>", "| a | b | \n| c | d |"));
    }

    /** Rowspan placeholders keep the columns aligned. */
    @ParameterizedTest
    @MethodSource("test_table_rowspan_params")
    void test_table_rowspan(String table, String suffix) {
        assertTrue(_table_md("<table>" + table + "</table>", zkw()).endsWith(suffix));
    }

    static java.util.stream.Stream<Arguments> test_table_empty_cells_and_rows_params() {
        return java.util.stream.Stream.of(
            Arguments.of("<table><tr><td></td><td>b</td></tr></table>", "|  | b |"),
            Arguments.of("<table><tr><td>a</td><td></td></tr></table>", "| a |  |"),
            Arguments.of("<table><tr><td>a</td><td>b</td></tr><tr><td></td><td></td></tr></table>", "| a | b |"),
            Arguments.of("<table><tr><td>a</td><td>c</td></tr><tr><td></td><td></td></tr><tr><td>d</td><td>e</td></tr></table>", "| a | c | \n| d | e |"),
            Arguments.of("<table><tr><td>a</td><td>c</td></tr><tr></tr><tr><td>d</td><td>e</td></tr></table>", "| a | c | \n| d | e |"));
    }

    /** Empty cells are kept blank to keep columns aligned; rows that are entirely empty are dropped. */
    @ParameterizedTest
    @MethodSource("test_table_empty_cells_and_rows_params")
    void test_table_empty_cells_and_rows(String html, String suffix) {
        assertTrue(_table_md(html, zkw()).endsWith(suffix));
    }

    /** XML cleanup keeps blank cells so the following values stay in their columns. */
    static java.util.stream.Stream<Arguments> test_xml_table_preserves_empty_cells_params() {
        List<Arguments> cases = new ArrayList<>();
        for (String output_format : List.of("xml", "xmltei")) {
            cases.add(Arguments.of(output_format, "<td></td><td>b</td><td>c</td>", T.t(null, "b", "c")));
            cases.add(Arguments.of(output_format, "<td>a</td><td></td><td>c</td>", T.t("a", null, "c")));
            cases.add(Arguments.of(output_format, "<td>a</td><td>b</td><td></td>", T.t("a", "b", null)));
            cases.add(Arguments.of(output_format, "<td colspan='2'>a</td><td>c</td>", T.t("a", null, "c")));
            cases.add(Arguments.of(output_format, "<td>a</td><td><p></p></td><td>c</td>", T.t("a", null, "c")));
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("test_xml_table_preserves_empty_cells_params")
    void test_xml_table_preserves_empty_cells(String output_format, String first_row, List<String> expected) {
        String result = _extract_doc("<table><tr>" + (first_row) + "</tr><tr><td>d</td><td>e</td><td>f</td></tr><tr><td></td><td></td></tr></table>", true, zkw().output_format(output_format).include_tables(true));
        List<Element> rows = Etree.fromstring(result).xpath(".//*[local-name()='table']/*[local-name()='row']");
        assertEquals(List.of(expected, List.of("d", "e", "f")),
                rows.stream().map(row -> row.getchildren().stream().map(cell -> cell.text).toList()).toList());
    }

    /** A <ul> in a cell (no recall mode) must not inject a row-breaking newline. */
    @Test
    void test_table_cell_list_no_row_break() {
        String[] parts = _table_md("<table><tr><td><ul><li>i1</li><li>i2</li></ul></td><td>b</td></tr></table>", zkw()).split("\n\n", -1);
        String row = parts[parts.length - 1];
        assertFalse(row.contains("\n"));
        assertTrue(row.endsWith("| b |"));
    }

    static java.util.stream.Stream<Arguments> test_table_cell_block_elements_flattened_params() {
        return java.util.stream.Stream.of(
            Arguments.of("<table><tr><td><h2>Title</h2></td><td>b</td></tr></table>", "| Title | b |", T.map()),
            Arguments.of("<table><tr><td><p>para</p></td><td>b</td></tr></table>", "| para | b |", T.map()),
            Arguments.of("<table><tr><td><h2>Title</h2>txt</td><td>b</td></tr></table>", "| Title txt | b |", T.map()),
            Arguments.of("<table><tr><td><ul><li>i1</li><li>i2</li></ul></td><td>b</td></tr></table>", "| i1 i2 | b |", T.map("favor_recall", true)));
    }

    /** Block elements in a cell are flattened to inline: no ## marker, space-separated, no mashing. */
    @ParameterizedTest
    @MethodSource("test_table_cell_block_elements_flattened_params")
    void test_table_cell_block_elements_flattened(String html, String suffix, Map<String, Object> kwargs) {
        assertTrue(_table_md(html, withKwargs(zkw(), kwargs)).endsWith(suffix));
    }

    static java.util.stream.Stream<Arguments> test_table_cell_keeps_nested_formatting_params() {
        return java.util.stream.Stream.of(
            Arguments.of("**bold**", "<p><b>bold</b></p>", T.map("include_formatting", true)),
            Arguments.of("![a](/i.jpg)", "<p><img src='/i.jpg' alt='a'></p>", T.map("include_images", true)),
            Arguments.of("pre **mid** post", "<p>pre <b>mid</b> post</p>", T.map("include_formatting", true)),
            Arguments.of("x ~~gone~~ y", "<p>x <del>gone</del> y</p>", T.map("include_formatting", true)),
            Arguments.of("x `c` y", "<p>x <code>c</code> y</p>", T.map("include_formatting", true)));
    }

    /** regression #829/#396: formatting/images wrapped in a block inside a cell must survive. */
    @ParameterizedTest
    @MethodSource("test_table_cell_keeps_nested_formatting_params")
    void test_table_cell_keeps_nested_formatting(String expected, String cell, Map<String, Object> kwargs) {
        assertTrue(_table_md("<table><tr><td>" + cell + "</td><td>x</td></tr></table>", withKwargs(zkw(), kwargs)).contains(expected));
    }

    /** GFM cell escaping is markdown-only: a nested element in a cell keeps its literal pipe. */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "xmltei"})
    void test_table_cell_escaping_stays_out_of_xml(String fmt) {
        String doc = "<html><body><article><p>" + ("A paragraph long enough to clear the minimum extracted size without any trouble. ".repeat(3)) + "</p><table><tr><td><code>x | y<code>z | w</code></code></td></tr></table></article></body></html>";
        String result = Py.or(Core.extract(doc, Core.kw().output_format(fmt).include_tables(true).include_formatting(true).fast(true)), "");
        assertTrue(result.contains("x | y z | w"));
        assertFalse(result.contains("\\|"));
    }

    /** regression #194/#842: a lead image plus one paragraph must not let _extract break early and drop later content. */
    @Test
    void test_include_images_does_not_truncate() {
        String lead = "This single lead paragraph is deliberately long enough to exceed the minimum extracted size. ".repeat(4);
        StringBuilder continuation = new StringBuilder();
        for (int i = 1; i < 5; i++) {
            continuation.append("<p>Continuation paragraph ").append(i).append(" that must also survive extraction in full here.</p>");
        }
        String doc = "<html><body><article><img src='/lead.jpg' alt='lead'><p>" + lead + "</p></article><div id='content'>"
                + continuation + "</div></body></html>";
        String result = Py.or(Core.extract(doc, Core.kw().output_format("txt").include_images(true)), "");
        assertTrue(result.contains("/lead.jpg"));
        for (int i = 1; i < 5; i++) {
            assertTrue(result.contains("Continuation paragraph " + i));
        }
    }

    /** A text-only fallback must not erase a requested image when it recovers no text. */
    @Test
    void test_short_document_fallback_preserves_image() {
        String first = "Some reasonably long paragraph of body text that trafilatura will keep because it is clearly the main content of this document and not navigation furniture.";
        String second = "A second paragraph that follows the figure and continues the argument at length.";
        String doc = "<html><body><article><p>" + first + "</p><img src=\"https://cdn.test/a.png\" alt=\"a chart\"/><p>" + second + "</p></article></body></html>";
        String result = Py.or(Core.extract(doc, Core.kw().output_format("xml").include_images(true).include_links(true)), "");
        assertTrue(result.contains("<graphic src=\"https://cdn.test/a.png\" alt=\"a chart\"/>"));
        String img = "<img src=\"https://cdn.test/a.png\" alt=\"a chart\"/>";
        doc = doc.replace(img, "<p>" + img + "</p>");
        for (boolean fast : new boolean[] {true, false}) {
            result = Py.or(Core.extract(doc, Core.kw().output_format("xml").include_images(true).fast(fast)), "");
            assertEquals(1, T.count(result, "<graphic"));
        }
        doc = "<html><body><div>Intro text <table><tr><td><img src=\"https://cdn.test/t.png\" alt=\"cell\"/></td></tr></table></div><p>Short page text.</p></body></html>";
        for (boolean fast : new boolean[] {true, false}) {
            result = Py.or(Core.extract(doc, Core.kw().output_format("xml").include_images(true).fast(fast).favor_recall(true)), "");
            assertEquals(1, T.count(result, "<graphic src=\"https://cdn.test/t.png\""));
        }
        doc = "<html><body><article><p>First paragraph with enough words here.</p><span>Some words <b><code><img src=\"https://cdn.test/c.png\" alt=\"code\"/></code></b> more words.</span></article></body></html>";
        result = Py.or(Core.extract(doc, Core.kw().output_format("xml").include_images(true).include_formatting(true)), "");
        assertEquals(1, T.count(result, "<graphic src=\"https://cdn.test/c.png\""));
    }

    /** A real content recovery still wins even when its source cannot preserve an image. */
    @Test
    void test_longer_fallback_can_replace_requested_image() {
        String recovered = "Recovered canonical article body with meaningful information and complete sentences. ".repeat(12);
        String payload = Json.dumps(T.map("@type", "Article", "articleBody", recovered));
        String doc = "<html><head><script type=\"application/ld+json\">" + payload + "</script></head><body><article><p>A short visible article paragraph below the extraction threshold.</p><img src=\"chart.png\" alt=\"chart\"/></article></body></html>";
        String result = Py.or(Core.extract(doc, Core.kw().output_format("xml").include_images(true)), "");
        assertTrue(result.contains("Recovered canonical article body"));
        assertFalse(result.contains("<graphic"));
    }

    /** regression #768/#817: content must not be emitted twice (overlapping candidates / wild-text recovery). */
    @Test
    void test_no_duplicate_content() {
        String dup768 = "<!doctype html><body><main><article><div><br>Line that has to have at least 125 characters for the bug to appear so here is some filler text text text text text text text</div></article></main></body></html>";
        assertEquals(1, T.count(Py.or(Core.extract(dup768, Core.kw().output_format("txt")), ""), "Line that has to have"));
        String dup817 = "<html><body><div id='content'><p>Authoritative taxonomy of but let us leave it as it is 1 2 3</p></div><p>some text long enough not to skip and printed twice on this line some text long enough not to skip and printed twice on this line</p></body></html>";
        assertEquals(1, T.count(Py.or(Core.extract(dup817, Core.kw().output_format("txt")), ""), "Authoritative taxonomy"));
        String dup879 = "<html><body><nav>menu chrome</nav><article><h1>The Example Chronicle</h1><p>First synthetic paragraph of adequate length for extraction to engage properly.</p><p>Second synthetic paragraph, also long enough to matter for the extractor.</p></article><footer>footer chrome</footer></body></html>";
        for (String doc : List.of(dup879, dup879.replace("article>", "main>"))) {
            String out = Py.or(Core.extract(doc, Core.kw().output_format("txt")), "");
            assertEquals(1, T.count(out, "First synthetic paragraph"));
            assertEquals(1, T.count(out, "Second synthetic paragraph"));
        }
    }

    /** regression #896: the baseline rescue must not flatten a valid short extraction it cannot improve on. */
    @Test
    void test_short_document_keeps_structure() {
        String doc = "<html><body><article><h1>Notice Title</h1><p>This municipal notice concerns the closure of the main road for repairs.</p><ul><li>Detour signposted east</li><li>Works end on Friday</li></ul></article></body></html>";
        String result = Py.or(Core.extract(doc, Core.kw().output_format("markdown").include_formatting(true)), "");
        assertTrue(result.contains("# Notice Title"));
        assertTrue(result.contains("- Detour signposted east"));
    }

    /** the other side of the #896 gate: when the rescue does add text, it must still win. */
    @Test
    void test_short_document_baseline_rescue_accepted() {
        String body = T.joinRange(" ", 0, 6, i -> "Sentence " + (i) + " of a body that arrives only as structured data.");
        String doc = "<html><body><script type=\"application/ld+json\">{\"@type\": \"Article\", \"articleBody\": \"" + (body) + "\"}</script></body></html>";
        assertTrue(Py.or(Core.extract(doc, Core.kw().output_format("txt")), "").contains("Sentence 3 of a body"));
    }

    /** regression T6: a <p> already folded into a <list> item by the main pass must not be re-added by     recover_wild_text — backup_tree is a snapshot taken before the main pass marks consumed tags 'done'. */
    @Test
    void test_no_duplicate_content_list_item() {
        Extractor options = new Extractor();
        String para = "This is a moderately long description paragraph exceeding the fifty character dedup threshold here.";
        String htmlstring = "<html><body><article><dl><dt>Term</dt><dd><p>" + para + "</p></dd></dl></article></body></html>";
        Element cleaned = Htmlprocessing.convert_tags(Htmlprocessing.tree_cleaning(Etree.html_fromstring(htmlstring), options), options);
        String text = MainExtractor.extract_content(cleaned, options).second();
        assertEquals(1, T.count(text, para));
    }

    /** regression T6 (non-adjacent case): the duplicate is not the element immediately preceding it in     result_body once genuine wild text is recovered in between — an adjacent-only dedup check would miss it. */
    @Test
    void test_no_duplicate_content_nonadjacent() {
        String dup = "X".repeat(30) + " short duplicate description text for the list item here right now please.";
        String wild = "Y".repeat(30) + " this is genuinely separate wild text living outside the article container elsewhere in the page body content over here, quite far removed from it.";
        String htmlstring = "<html><body><p>" + wild + "</p><article><dl><dt>Term</dt><dd><p>" + dup + "</p></dd></dl></article></body></html>";
        String result = Py.or(Core.extract(htmlstring, Core.kw().output_format("txt").fast(true)), "");
        assertEquals(1, T.count(result, dup));
        assertEquals(1, T.count(result, wild));
        assertTrue(result.contains("Term"));
    }

    /** regression #634: on short pages, recovery must not re-add elements the main pass already     extracted, even when their text is below the substring-dedup length gate. */
    @Test
    void test_no_duplicate_content_short_elements() {
        String body = "<p>this is sample intro</p><h3 class=\"wp-block-heading\">intro 2</h3><p>table below</p><figure class=\"wp-block-table\"><table><tbody><tr><td>a</td><td>b</td><td></td></tr><tr><td>f</td><td>s</td><td>s</td></tr><tr><td>g</td><td></td><td>b</td></tr></tbody></table></figure><p>header table below</p><figure class=\"wp-block-table\"><table><thead><tr><th>b</th><th>s</th><th>h</th></tr></thead><tbody><tr><td>a</td><td>b</td><td></td></tr><tr><td>f</td><td>s</td><td>s</td></tr><tr><td>g</td><td></td><td>b</td></tr></tbody></table></figure><p>list below</p><ul><li>this is 1</li><li>this is 2</li><li>this is 3</li></ul><p>numbered list below</p><ol><li>this is 1</li><li>this is 2</li><li>this is 3</li></ol>";
        String doc = "<html><body><div class=\"entry-content\">" + body + "</div></body></html>";
        String result = Py.or(Core.extract(doc, Core.kw().output_format("txt").fast(true)), "");
        assertEquals(1, T.count(result, "this is sample intro"));
    }

    /** regression: the recovery dedup must render accumulated text per element with plain     concatenation, exactly like the candidates -- ' '.join across all text nodes invented     spaces at inline-tag boundaries (Hyper<b>link</b>ed -> 'Hyper link ed'), so a paragraph     folded into a list item escaped the substring check and was emitted twice. */
    @Test
    void test_recover_wild_text_inline_formatting_dedup() {
        String para = "This paragraph has Hyper<b>link</b>ed formatting inside and needs to be comfortably longer than the fifty character dedup gate to be caught by the substring check.";
        String doc = "<html><body><article><dl><dt>Term one</dt><dd><p>" + para + "</p></dd></dl></article></body></html>";
        String result = Py.or(Core.extract(doc, Core.kw().output_format("txt").include_formatting(true).fast(true)), "");
        assertEquals(1, T.count(result, "formatting inside"));
    }

    /** regression: DEDUPE_SCAN_CAP gates the O(n) substring scan against `existing` -- a     >MIN_DUPLICATE_LENGTH-char substring duplicate is caught while `existing` is under the     cap, and kept (scan skipped, exact-match set is the only check left) once past it. */
    @Test
    void test_recover_wild_text_dedup_scan_cap() {
        Extractor options = new Extractor(Extractor.kw().recall(true));
        String container = "The quick brown fox jumps over the lazy dog while the sun was setting slowly over the meadow today.";
        String substring_dup = "quick brown fox jumps over the lazy dog while the sun was setting slowly";
        assertTrue(container.contains(substring_dup));
        assertTrue(substring_dup.length() > Settings.MIN_DUPLICATE_LENGTH);
        String filler = "Zebra quokka platypus wallaby echidna kookaburra numbat bilby quoll dingo marsupial. ".repeat(4);
        java.util.function.IntFunction<List<String>> run = cap -> {
            int saved = MainExtractor.DEDUPE_SCAN_CAP;
            MainExtractor.DEDUPE_SCAN_CAP = cap;
            try {
                String htmlstring = "<html><body><div>" + filler + "</div><div>" + container + "</div><div>" + substring_dup + "</div></body></html>";
                Element tree = Etree.html_fromstring(htmlstring);
                Element result_body = Etree.Element("body");
                MainExtractor.recover_wild_text(tree, result_body, options);
                return result_body.getchildren().stream().map(el -> Utils.trim(String.join("", el.itertext()))).toList();
            } finally {
                MainExtractor.DEDUPE_SCAN_CAP = saved;
            }
        };
        assertFalse(run.apply(filler.length() + container.length() + 100).contains(substring_dup));
        assertTrue(run.apply(filler.length() / 4).contains(substring_dup));
    }

    /** regression: prune_unwanted_sections must not skip a boilerplate table that follows a deleted     table containing a nested one (deleting mid tree.iter() made the iterator lose the following sibling). */
    @Test
    @Tag("known-deviation") // <row>/<cell> inside <table>: HTML5 tree building moves them out of the table, libxml2 keeps them
    void test_prune_boilerplate_table_after_nested() {
        Extractor options = new Extractor(Extractor.kw().tables(true));
        String link = "<ref target=\"/x\">link text that is reasonably long over here</ref>";
        String rows = T.joinRange("", 0, 6, unused -> "<row><cell>" + link + "</cell></row>");
        String nested = "<row><cell><table><row><cell>x</cell></row></table></cell></row>";
        String real = "Real article content paragraph that should always survive the pruning pass intact here now.";
        String htmlstring = "<html><body><table>" + (rows) + nested + "</table><table>" + (rows) + "</table><p>" + real + "</p></body></html>";
        Element pruned = MainExtractor.prune_unwanted_sections(Etree.html_fromstring(htmlstring), Set.of("table", "p"), options);
        assertEquals(List.of(), pruned.findall(".//table"));
        assertNotNull(pruned.find(".//p"));
    }

    /** spans are always flattened, refs only when links are not kept */
    @Test
    void test_prune_strips_span_and_ref() {
        Extractor options = new Extractor();
        String htmlstring = "<html><body><p>One <span>two</span> <ref target=\"/x\">three</ref> four.</p></body></html>";
        Element pruned = MainExtractor.prune_unwanted_sections(Etree.html_fromstring(htmlstring), Set.of("p"), options);
        assertNull(pruned.find(".//span"));
        assertNull(pruned.find(".//ref"));
        assertEquals("One two three four.", Utils.trim(pruned.find(".//p").text_content()));
        pruned = MainExtractor.prune_unwanted_sections(Etree.html_fromstring(htmlstring), Set.of("p", "ref"), options);
        assertNull(pruned.find(".//span"));
        assertNotNull(pruned.find(".//ref"));
    }

    /** regression: keep_teasers spares teaser-class blocks (some sites wrap real article text in them);     the recovery path uses it in fast mode so content isn't lost after the main extractor already failed. */
    @Test
    void test_prune_keep_teasers() {
        Extractor options = new Extractor(Extractor.kw().tables(true));
        String htmlstring = "<html><body><div class=\"teaser\"><p>Real article body text that is long enough to count as genuine content here now.</p></div></body></html>";
        assertNull(MainExtractor.prune_unwanted_sections(Etree.html_fromstring(htmlstring), Set.of("p"), options).find(".//p"));
        assertNotNull(MainExtractor.prune_unwanted_sections(Etree.html_fromstring(htmlstring), Set.of("p"), options, true).find(".//p"));
    }

    /** a short balanced extraction covering little of the page is retried in recall mode:     wild paragraphs block the baseline rescue while the bulk of the content sits in bare divs. */
    @Test
    void test_recall_escalation() {
        String ps = T.joinRange("", 0, 3, i -> "<p>Wild paragraph number " + (i) + " directly under body, with enough words to pass the paragraph checks in place.</p>");
        String divs = T.joinRange("", 0, 30, i -> "<div>Main content block " + (i) + " living in a bare div element with plenty of meaningful words to matter here today.</div>");
        String doc = "<html><body>" + (ps) + (divs) + "</body></html>";
        String result = Py.or(Core.extract(doc, Core.kw().output_format("txt").fast(true)), "");
        assertEquals(3, T.count(result, "Wild paragraph"));
        assertEquals(30, T.count(result, "Main content block"));
    }

    static final String _ESCALATION_INTRO = "<p>" + "Introductory paragraph with regular prose content that is moderately long and clearly meaningful, providing enough text to exceed the minimum extraction size threshold used by the extractor. ".repeat(2) + "</p>";

    /** the recall escalation also tries justext, which reaches content the rule-based retry     cannot: 'sidebar'-classed divs are discarded by both the main extractor/recall retry     (OVERALL_DISCARD_XPATH) and readability (unlikelyCandidatesRe), but justext classifies     them on paragraph density alone and keeps them. */
    @Test
    void test_recall_escalation_justext() {
        String messages = T.joinRange("", 0, 8, i -> "<div class='sidebar'>Message number " + (i) + " contains substantial discussion content with plenty of genuine words and enough length to be recognized as real text by a paragraph density classifier, not boilerplate at all today, said the author.</div>");
        String doc = "<html><body>" + (_ESCALATION_INTRO) + (messages) + "</body></html>";
        String result = Py.or(Core.extract(doc, Core.kw().output_format("txt")), "");
        assertTrue(result.contains("Introductory paragraph"));
        assertEquals(8, T.count(result, "Message number"));
    }

    static final String _DFP_JSONLD = "<script type=\"application/ld+json\">{\"@context\":\"https://schema.org\",\"@type\":\"DiscussionForumPosting\",\"headline\":\"Test thread\"}</script>";

    /** Thread page: article intro plus 8 replies in a comments-classed container. */
    static String _thread_doc(String jsonld, String reply_tag, String intro, boolean wrap) {
        String replies = T.joinRange("", 0, 8, i -> "<" + reply_tag + " class='comment-body'>Reply number " + i + " contains substantial discussion content with plenty of genuine words and enough length to be recognized as real text by a paragraph density classifier, not boilerplate at all today, said the commenter.</" + reply_tag + ">");
        // unwrapped: no article-region marker at all, so body selection stays broad
        String article = wrap ? "<article>" + intro + "</article>" : "<div>" + intro + "</div>";
        return "<html><head>" + jsonld + "</head><body>" + article + "<div id='comments' class='comments-area'>" + replies + "</div></body></html>";
    }

    static String _thread_doc(String jsonld, String reply_tag, String intro) {
        return _thread_doc(jsonld, reply_tag, intro, true);
    }

    static String _thread_doc(String jsonld, String reply_tag) {
        return _thread_doc(jsonld, reply_tag, _ESCALATION_INTRO, true);
    }

    static String _thread_doc(String jsonld) {
        return _thread_doc(jsonld, "div");
    }

    static java.util.stream.Stream<Arguments> test_recall_escalation_justext_comment_scoping_params() {
        return java.util.stream.Stream.of(
            Arguments.of("", false, false),
            // (_DFP_JSONLD, true, false): see test_recall_escalation_justext_forum_posts_kept (known deviation)
            Arguments.of("", false, true));
    }

    /** regression: justext has no article-region scoping of its own and will absorb a reader-     comments thread as if it were content (AEB regression: a short article inviting comments,     justext grabbed the replies too) -- comment containers must be stripped before it runs,     UNLESS this is a thread-forum (schema.org DiscussionForumPosting), where those same     containers hold the actual posts, not boilerplate replies (WCXB regression fixed by     _forum_thread_page). The fast=True case is the rule-based retry alone (justext is skipped     in fast mode): it must apply the same comment-scoping, or a reader-comments thread leaks     into the body precisely when the caller asked to exclude comments. */
    @ParameterizedTest
    @MethodSource("test_recall_escalation_justext_comment_scoping_params")
    void test_recall_escalation_justext_comment_scoping(String jsonld, boolean expect_replies, boolean fast) {
        String doc = _thread_doc(jsonld);
        String result = Py.or(Core.extract(doc, Core.kw().output_format("txt").include_comments(false).fast(fast)), "");
        assertTrue(result.contains("Introductory paragraph"));
        assertEquals(expect_replies ? 8 : 0, T.count(result, "Reply number"));
    }

    /** regression: with include_comments=True on a thread-forum page, posts must not appear in     both text and comments (the escalation stage sees the raw tree, whose comment containers     extract_comments already consumed). Policy: comment capture on a DFP page is re-routed     into the body (the 'comments' are the posts); partial captures must not lose the rest     of the thread either (WCXB regression: pruning all containers because a fragment was     captured tanked 3 forum pages). <p> replies are capturable, <div> replies are not     (extract_comments rejects divs) -- either way: exactly one occurrence per post. */
    @ParameterizedTest
    @ValueSource(strings = {"p"}) // "div": see test_recall_escalation_no_comment_doubling_div (known deviation)
    void test_recall_escalation_no_comment_doubling(String reply_tag) {
        String doc = _thread_doc(_DFP_JSONLD, reply_tag);
        String result = Py.or(Core.extract(doc, Core.kw().output_format("txt").include_comments(true)), "");
        assertTrue(result.contains("Introductory paragraph"));
        assertEquals(8, T.count(result, "Reply number"));
    }

    static final String _LONG_OP = T.joinRange("", 0, 16, i -> "<p>Opening post paragraph " + i + " lays out the question in full detail, with context, "
            + "history and several worked examples that make the thread starter alone longer than "
            + "the escalation length gate, so no rescue pass can be relied upon for the replies.</p>");

    /** regression (review round 3): the DFP re-route emptied the captured comments betting on     the stage-4 escalation to restore the posts into the body, but the escalation is gated     on len_text < ESCALATION_MAX_LENGTH -- a thread whose opening post alone exceeds the     gate lost every reply from both channels. Captured posts must survive regardless of gates. */
    @Test
    void test_dfp_long_opening_post_keeps_replies() {
        String doc = _thread_doc(_DFP_JSONLD, "p", _LONG_OP);
        String result = Py.or(Core.extract(doc, Core.kw().output_format("txt").include_comments(true)), "");
        assertTrue(result.contains("Opening post paragraph"));
        assertEquals(8, T.count(result, "Reply number"));
    }

    /** regression (review round 3): precision mode pruned REMOVE_COMMENTS_XPATH from the very     tree the forum re-route had just restored the posts into, and precision closes the     stage-3/4 rescues, so a DFP page returned no posts at all. */
    @Test
    void test_dfp_precision_keeps_posts() {
        String doc = _thread_doc(_DFP_JSONLD, "p");
        String result = Py.or(Core.extract(doc, Core.kw().output_format("txt").include_comments(true).favor_precision(true)), "");
        assertEquals(8, T.count(result, "Reply number"));
    }

    static java.util.stream.Stream<Arguments> test_recall_escalation_blog_comment_leak_params() {
        return java.util.stream.Stream.of(
            Arguments.of(false),
            Arguments.of(true));
    }

    /** regression (review round 3): on a wrapper-less blog layout (broad body selection) with     include_comments=True and an uncapturable div-based reply thread, the recall retry kept     the comment containers (its prune gate checked options.comments, the justext branch     checked len_comments) and emitted the reader comments as main body text. */
    @ParameterizedTest
    @MethodSource("test_recall_escalation_blog_comment_leak_params")
    void test_recall_escalation_blog_comment_leak(boolean fast) {
        String doc = _thread_doc("", "div", _ESCALATION_INTRO, false);
        String result = Py.or(Core.extract(doc, Core.kw().output_format("txt").include_comments(true).fast(fast)), "");
        assertTrue(result.contains("Introductory paragraph"));
        assertEquals(0, T.count(result, "Reply number"));
    }

    static java.util.stream.Stream<Arguments> test_recall_escalation_captured_comment_list_params() {
        return java.util.stream.Stream.of(
            Arguments.of(false),
            Arguments.of(true));
    }

    /** regression (#881): a captured ol/ul comment list must not come back in the body through the recall escalation. */
    @ParameterizedTest
    @MethodSource("test_recall_escalation_captured_comment_list_params")
    void test_recall_escalation_captured_comment_list(boolean fast) {
        String intro = T.joinRange("", 0, 5, i -> "<p>Article paragraph " + (i) + " stays above the rescue floor but under the escalation gate.</p>");
        String replies = T.joinRange("", 0, 20, i -> "<li><p>Reply number " + (i) + " contains substantial discussion content with plenty of genuine words and enough length to be recognized as real text, said the commenter.</p></li>");
        String doc = "<html><body><div>" + (intro) + "</div><ol class='commentlist'>" + (replies) + "</ol></body></html>";
        String result = Py.or(Core.extract(doc, Core.kw().output_format("xml").include_comments(true).fast(fast)), "");
        String[] parts = result.split("<comments", -1);
        assertEquals(2, parts.length);
        String body = parts[0];
        String comments = parts[1];
        assertTrue(body.contains("Article paragraph"));
        assertEquals(0, T.count(body, "Reply number"));
        assertEquals(20, T.count(comments, "Reply number"));
    }

    /** regression (review round 3): the stage-4 retry re-ran extract_comments although the     caller discards the retry's comments triple -- a container matching COMMENTS_XPATH but     not REMOVE_COMMENTS_XPATH had its text captured and deleted inside the retry, so it     vanished from both channels. The retry must not capture comments at all. */
    @Test
    void test_escalation_retry_no_comment_capture() {
        String intro = T.joinRange("", 0, 4, i -> "<li>Point number " + (i) + " of the short visible article summary text here.</li>");
        String cont = T.joinRange("", 0, 12, i -> "<p>Continuation paragraph " + (i) + " with substantial article prose that only the recall retry can recover from inside the form wrapper element.</p>");
        String replies = T.joinRange("", 0, 8, i -> "<p>Reply " + (i) + " ZQXJKVREPLY says something reasonably long about the topic at hand in this discussion thread below the article body.</p>");
        String doc = "<html><body><article><h1>Title of the page</h1><ul>" + (intro) + "</ul><form>" + (cont) + "<div class='user-comment-area'>" + (replies) + "</div></form></article></body></html>";
        String result = Py.or(Core.extract(doc, Core.kw().output_format("txt").include_comments(true).fast(true)), "");
        assertTrue(result.contains("Continuation paragraph 5"));
        assertTrue(result.contains("ZQXJKVREPLY"));
    }

    /** regression: with include_comments=False, comment containers must be pruned before the MAIN     pass, not only in precision mode -- balanced/recall let the div-ladder pull a short article's     comment thread into the body (a bare-div thread the comment extractor cannot capture). */
    @Test
    void test_main_pass_excludes_comments_when_disabled() {
        String intro = "<p>Short intro under the escalation and rescue thresholds here.</p>";
        String replies = T.joinRange("", 0, 8, i -> "<div>Reader comment number " + (i) + " that must never appear when comments are excluded, long enough to matter.</div>");
        String doc = "<html><body><article>" + intro + "</article><div id='comments' class='comments-area'>" + (replies) + "</div></body></html>";
        String items = replies.replace("<div>", "<li>").replace("</div>", "</li>");
        List<String> pages = new ArrayList<>(List.of(doc));
        for (String[] tagcls : new String[][] {{"ol", "commentlist"}, {"ul", "comment-list"}}) {
            pages.add("<html><body><article>" + intro + "</article><" + tagcls[0] + " class='" + tagcls[1] + "'>" + items
                    + "</" + tagcls[0] + "></body></html>");
        }
        for (String page : pages) {
            for (boolean fast : new boolean[] {false, true}) {
                String result = Py.or(Core.extract(page, Core.kw().output_format("txt").include_comments(false).fast(fast)), "");
                assertTrue(result.contains("Short intro"));
                assertEquals(0, T.count(result, "Reader comment number"));
            }
        }
    }

    /** regression (#850): comment containers wrapped in <details> must also be pruned when     include_comments=False -- REMOVE_COMMENTS_XPATH matched only div/list/section, so a     <details id='comments'> thread leaked into the body outside precision mode. A <details>     without a comment id/class must be kept (no over-pruning). */
    @Test
    void test_main_pass_excludes_details_wrapped_comments() {
        String body = "<article>" + "<p>Real article paragraph with enough content to be extracted normally here.</p>".repeat(3) + "</article>";
        String comments = "<details id='comments'><summary>Comments</summary>" + T.joinRange("", 0, 6, i -> "<p>Reader comment number " + (i) + " that must never leak into the body text.</p>") + "</details>";
        String doc = "<html><body>" + body + (comments) + "</body></html>";
        for (boolean fast : new boolean[] {false, true}) {
            String result = Py.or(Core.extract(doc, Core.kw().output_format("txt").include_comments(false).fast(fast)), "");
            assertTrue(result.contains("Real article paragraph"));
            assertEquals(0, T.count(result, "Reader comment number"));
        }
        String faq = "<details class='faq'><summary>More</summary><p>Kept expandable content paragraph that is genuine.</p></details>";
        String doc_faq = "<html><body>" + body + faq + "</body></html>";
        assertTrue(Py.or(Core.extract(doc_faq, Core.kw().output_format("txt")), "").contains("Kept expandable content"));
        for (String attr : List.of("id", "class")) {
            String commentary = "<section " + attr + "='commentary'><p>Expert commentary paragraph that is part of the article.</p></section>";
            String doc_commentary = "<html><body>" + body + commentary + "</body></html>";
            assertTrue(Py.or(Core.extract(doc_commentary, Core.kw().output_format("txt").include_comments(false)), "").contains("Expert commentary"));
        }
    }

    /** regression: the #911 prune deleted the real article when the scroll container holds the h1 (BGR). */
    @Test
    void test_infinite_scroll_container_with_h1_kept() {
        String real = "<p>Real article paragraph with enough content to be extracted normally here.</p>".repeat(4);
        String appended = "<p>Appended follow-up story text that belongs to another article entirely.</p>".repeat(4);
        for (boolean fast : new boolean[] {false, true}) {
            String doc = "<html><body><div class='site-content infinite-scroll-container'><article><h1>Real title</h1>" + real + "</article></div></body></html>";
            assertTrue(Py.or(Core.extract(doc, Core.kw().output_format("txt").fast(fast)), "").contains("Real article paragraph"));
            for (Object _r : T.list(List.of("infinite-scroll", "h2"), List.of("mvp-post-add-box", "h1"))) {
                List<?> _row = (List<?>) _r;
                String cls = (String) _row.get(0);
                String head = (String) _row.get(1);
                doc = "<html><body><article><h1>Real title</h1>" + real + "<div class='" + cls + "'><" + head + ">Next story</" + head + ">" + appended + "</div></article></body></html>";
                String result = Py.or(Core.extract(doc, Core.kw().output_format("txt").fast(fast)), "");
                assertTrue(result.contains("Real article paragraph"));
                assertFalse(result.contains("Appended follow-up"));
            }
        }
    }

    /** the justext override must not replace text more than JUSTEXT_OVERRIDE_RATIO times longer     than its own output (calibrated 3x: at the previous 4x it replaced longer, closer-to-target     extractions with shorter, comment-contaminated justext output). */
    @Test
    void test_compare_extraction_justext_ratio() {
        String jt_text = "j".repeat(100);
        Element jt_body = Etree.Element("body");
        Etree.SubElement(jt_body, "p").text = jt_text;
        java.util.function.BiFunction<Element, Extractor, Pair<Element, String>> saved = External.JUSTEXT_RESCUE;
        External.JUSTEXT_RESCUE = (tree, opts) -> Pair.of(jt_body, jt_text);
        try {
            Extractor options = new Extractor();
            Element empty_tree = Etree.html_fromstring("<html><body></body></html>");
            Element body = Etree.html_fromstring("<body><p>text</p><aside>x</aside></body>");
            String kept = "a".repeat(350);
            String text = External.compare_extraction(empty_tree, empty_tree, body, kept, options).second();
            assertEquals(kept, text);
            String replaced = "a".repeat(250);
            text = External.compare_extraction(empty_tree, empty_tree, body, replaced, options).second();
            assertEquals(jt_text, text);
        } finally {
            External.JUSTEXT_RESCUE = saved;
        }
    }

    /** on a merely-short body the justext override must add text; JUSTEXT_OVERRIDE_RATIO's     tolerance for a shorter result applies only to an unclean body (GH #896, one stage earlier) */
    @Test
    void test_compare_extraction_justext_short_trigger() {
        java.util.function.Function<String, java.util.function.BiFunction<Element, Extractor, Pair<Element, String>>> fake_justext = text -> {
            Element body = Etree.Element("body");
            Etree.SubElement(body, "p").text = text;
            return (tree, opts) -> Pair.of(body, text);
        };
        java.util.function.BiFunction<Element, Extractor, Pair<Element, String>> saved = External.JUSTEXT_RESCUE;
        try {
            Extractor options = new Extractor();
            options.min_extracted_size = 250;
            Element empty_tree = Etree.html_fromstring("<html><body></body></html>");
            Element clean_body = Etree.html_fromstring("<body><p>text</p></body>");
            String own = "a".repeat(150);
            External.JUSTEXT_RESCUE = fake_justext.apply("j".repeat(149));
            String text = External.compare_extraction(empty_tree, empty_tree, clean_body, own, options).second();
            assertEquals(own, text);
            External.JUSTEXT_RESCUE = fake_justext.apply("j".repeat(300));
            text = External.compare_extraction(empty_tree, empty_tree, clean_body, own, options).second();
            assertEquals("j".repeat(300), text);
        } finally {
            External.JUSTEXT_RESCUE = saved;
        }
    }

    /** readability output that is raw JSON must not win on length alone (GH #632) */
    @Test
    void test_prefer_readability_rejects_raw_json() {
        Element body = Etree.fromstring("<body><p>own text</p></body>");
        Element algo_body = Etree.fromstring("<body><p>x</p></body>");
        String prose = "a".repeat(300);
        String json_text = "{" + "a".repeat(299);
        for (boolean recall : new boolean[] {false, true}) {
            Extractor options = new Extractor(Extractor.kw().recall(recall));
            options.min_extracted_size = 250;
            assertTrue(External._prefer_readability(body, algo_body, prose, 100, 300, options));
            assertFalse(External._prefer_readability(body, algo_body, json_text, 100, 300, options));
        }
    }

    static java.util.stream.Stream<Arguments> test_markdown_inline_rendering_params() {
        return java.util.stream.Stream.of(
            Arguments.of("<ul><li><p>x <b>bold</b> y</p></li></ul>", "- x **bold** y"),
            Arguments.of("<p>a <b> x </b> b</p>", "a  **x**  b"),
            Arguments.of("<p>a <i> x </i> b</p>", "a  *x*  b"),
            Arguments.of("<p>a <del> x </del> b</p>", "a  ~~x~~  b"),
            Arguments.of("<p>a <code> x </code> b</p>", "a  `x`  b"),
            Arguments.of("<p>a <b>   </b> b</p>", "a     b"),
            Arguments.of("<p>see <a href='/x'> bold link </a> ok</p>", "see  [bold link](/x)  ok"),
            Arguments.of("<ol><li>one</li><li>two</li><li>three</li></ol>", "1. one\n2. two\n3. three"),
            Arguments.of("<ol><li>only</li></ol>", "1. only"),
            Arguments.of("<ul><li>a</li><li>b</li></ul>", "- a\n- b"),
            Arguments.of("<ul><li>a<ul><li>b</li><li>c</li></ul></li><li>d</li></ul>", "- a\n  - b\n  - c\n- d"),
            Arguments.of("<ul><li>a<ol><li>b</li></ol></li></ul>", "- a\n  1. b"),
            Arguments.of("<ul><li><ul><li>b</li></ul></li><li>d</li></ul>", "  - b\n- d"),
            Arguments.of("<ul><li><p>a</p></li><li>b</li></ul>", "- a\n- b"),
            Arguments.of("<ul><li><br/>a</li><li>b<br/>c</li></ul>", "- a\n- b\nc"),
            Arguments.of("<ul><li><a href=\"/x\"></a></li><li><a href=\"/y\">b</a></li><li>c</li></ul>", "- [b](/y)\n- c"),
            Arguments.of("<ul><li>see <a href='http://x.com'><b>bold link</b></a> here</li></ul>", "- see [**bold link**](http://x.com) here"),
            Arguments.of("<ul><li>see <a href='http://x.com'>link <b>bold</b></a> here</li></ul>", "- see [link **bold**](http://x.com) here"),
            Arguments.of("<p>see <a href='http://x.com'><b>bold</b></a> more</p>", "see [**bold**](http://x.com) more"),
            Arguments.of("<p>text <b><i>nested</i></b> end</p>", "text ***nested*** end"),
            Arguments.of("<p><b>prefix <i>italic</i></b></p>", "**prefix *italic***"),
            Arguments.of("<ul><li>text <b><i>nested</i></b> end</li></ul>", "- text ***nested*** end"),
            Arguments.of("<blockquote><b>bold</b> text here</blockquote>", "**bold** text here"),
            Arguments.of("<del>gone</del>", "~~gone~~"),
            Arguments.of("<ul><li>text <del>struck</del> more</li></ul>", "- text ~~struck~~ more"),
            Arguments.of("<blockquote>text <del>struck</del> more</blockquote>", "text ~~struck~~ more"),
            Arguments.of("<ul><li>use <code>func()</code> here</li></ul>", "- use `func()` here"),
            Arguments.of("<p>before <b>bold <del>struck</del></b></p>", "before **bold ~~struck~~**"),
            Arguments.of("<ol><li>Foo <em>bar</em> baz.</li></ol>", "1. Foo *bar* baz."),
            Arguments.of("<blockquote><p>A <b>bold</b> word</p></blockquote>", "A **bold** word"),
            Arguments.of("<blockquote><p>see <a href='http://x.com'>link</a></p></blockquote>", "see [link](http://x.com)"),
            Arguments.of("<blockquote><p>text</p><img src='x.jpg' alt='img'/></blockquote>", "text\n\n![img](x.jpg)"),
            Arguments.of("<ul><li><img src='/i.jpg' alt='a'></li><li>plain</li></ul>", "- ![a](/i.jpg)\n- plain"));
    }

    /** Inline markers, links and list markers in markdown (regressions #843, A5, marker composition). */
    @ParameterizedTest
    @MethodSource("test_markdown_inline_rendering_params")
    void test_markdown_inline_rendering(String body, String expected) {
        assertEquals("" + (_INTRO) + "\n\n" + expected, _md_inline(body, zkw().include_links(true).include_images(true)));
    }

    /** regression #661: a block's loose-text tail must not mash onto the next sibling block. */
    @Test
    void test_loose_text_tail_not_squished() {
        String html_string = "<!DOCTYPE html><html lang=\"en-us\"><body><main><section>\n        <p>First</p>\n        This gets Squished\n        <div>\n            <h4>There should be a space</h4>\n            <p>Another sentence</p>\n            This also gets Squished\n        </div>\n        <div>\n            <h4>Where is the space</h4>\n            <p>This sentence has to be long enough. If it's long enough the duplication stops, but if it's not long enough then you'll get an extra first. This sentence has to be long enough.</p>\n        </div>\n    </section></main></body></html>";
        String result = Py.or(Core.extract(html_string), "");
        assertFalse(result.contains("SquishedThere"));
        assertFalse(result.contains("SquishedWhere"));
    }

    static java.util.stream.Stream<Arguments> test_heading_level_zero_trust_params() {
        return java.util.stream.Stream.of(
            Arguments.of("h1", "# T"),
            Arguments.of("h2", "## T"),
            Arguments.of("h6", "###### T"),
            Arguments.of(null, "## T"),
            Arguments.of("", "## T"),
            Arguments.of("h", "## T"),
            Arguments.of("hx", "## T"),
            Arguments.of("h²", "## T"),
            Arguments.of("h٤", "## T"),
            Arguments.of("h0", "## T"),
            Arguments.of("h9", "## T"));
    }

    /** replace_element_text must never crash on a malformed head rend and defaults to level 2. */
    @ParameterizedTest
    @MethodSource("test_heading_level_zero_trust_params")
    void test_heading_level_zero_trust(String rend, String expected) {
        Element el = Etree.Element("head");
        el.text = "T";
        if (rend != null) {
            el.set("rend", rend);
        }
        assertEquals(expected, Xml.replace_element_text(el, true));
    }

    /** End-to-end: nested table is extracted as a separate table by the main walk, not inlined — no duplication. */
    @Test
    void test_table_nested_in_cell_pipeline() {
        String outer_text = "This is the outer row with plenty of text to survive readability. ".repeat(2);
        String inner_text = "Inner nested table cell with sufficient content for extraction. ".repeat(2);
        String doc = "<table><tr><td>" + outer_text + "</td></tr><tr><td><table><tr><td>" + inner_text + "</td></tr></table></td></tr></table>";
        String result = _extract_doc(doc, true, zkw().output_format("xml").include_tables(true));
        assertTrue(result.contains(Py.slice(Py.strip(outer_text), null, 20)));
        assertTrue(result.contains(Py.slice(Py.strip(inner_text), null, 20)));
        assertEquals(1, T.count(result, Py.strip(inner_text)));
    }

    /** Table caption is emitted as a header row above the table body. */
    @Test
    void test_table_caption() {
        String result = _table_md("<table><caption>My Caption</caption><tr><td>a</td><td>b</td></tr></table>", zkw());
        assertTrue(result.contains("My Caption"));
        assertTrue(result.indexOf("My Caption") < result.indexOf("| a |"));
        assertTrue(result.contains("|---|---|"));
    }

    /** An image in a caption is emitted once, its caption text is not repeated. */
    @Test
    void test_table_caption_image_kept() {
        String words = "Article words go here and they are plentiful. ".repeat(3);
        String doc = "<html><body><article><p>Intro " + words + "</p><table><caption><img src='https://e.com/c.png' alt='Chart'/> Caption words</caption><tr><td>Cell one " + words + "</td><td>Cell two</td></tr></table><p>Outro " + words + "</p></article></body></html>";
        String result = Core.extract(doc, Core.kw().include_images(true).output_format("markdown").fast(true));
        assertEquals(1, T.count(result, "![Chart](https://e.com/c.png)"));
        assertEquals(1, T.count(result, "Caption words"));
    }

    /** Any table with role=presentation/none is reclassified as div (explicit WAI-ARIA layout declaration). */
    @ParameterizedTest
    @ValueSource(strings = {"presentation", "none"})
    void test_aria_layout_table_reclassified(String role) {
        Extractor opts = new Extractor();
        opts.tables = true;
        Element doc = Etree.html_fromstring("<html><body><table role=\"" + role + "\"><tr><td><table><tr><td>data</td></tr></table></td></tr></table></body></html>");
        Htmlprocessing.tree_cleaning(doc, opts);
        assertNull(doc.find(".//table[@role]"));
        assertNotNull(doc.find(".//table"));
        doc = Etree.html_fromstring("<html><body><table role=\"" + role + "\"><tr><td>text</td></tr></table></body></html>");
        Htmlprocessing.tree_cleaning(doc, opts);
        assertNull(doc.find(".//table"));
    }

    /** sanitize_tree marks only the first <th>-containing row per parent as role=head; subsequent th-rows are plain cells. */
    @Test
    void test_sanitize_tree_th_dedup() {
        Extractor opts = new Extractor();
        opts.tables = true;
        Element doc = Etree.html_fromstring("<html><body><table><tr><th>A</th><th>B</th></tr><tr><th>C</th><th>D</th></tr><tr><td>1</td><td>2</td></tr></table></body></html>");
        Element tree = External.sanitize_tree(doc, opts).first();
        List<Element> head_cells = tree.xpath(".//cell[@role=\"head\"]");
        List<Element> plain_cells = tree.xpath(".//cell").stream().filter(c -> !"head".equals(c.get("role"))).toList();
        assertEquals(2, head_cells.size());
        assertTrue(head_cells.stream().allMatch(c -> List.of("A", "B").contains(c.text)));
        assertTrue(plain_cells.stream().allMatch(c -> List.of("C", "D", "1", "2").contains(c.text)));
        Element doc2 = Etree.html_fromstring("<html><body><table><tr><td>x</td></tr></table></body></html>");
        Element tree2 = External.sanitize_tree(doc2, opts).first();
        assertEquals(List.of(), tree2.xpath(".//cell[@role=\"head\"]"));
    }

    /** Nested elements in a list item keep meaningful attrs only (no stray class/width leak). */
    @Test
    void test_list_item_attr_whitelist() {
        String body = "<ul><li>x <img src=\"p.jpg\" class=\"c\" width=\"9\" alt=\"a\"/> <a href=\"http://x.io\" class=\"q\">lnk</a> y</li></ul>";
        String out = _extract_doc(body, true, zkw().output_format("xml").include_links(true).include_images(true).favor_recall(true));
        assertTrue(out.contains("<graphic src=\"p.jpg\" alt=\"a\"/>"));
        assertFalse(out.contains("class="));
        assertFalse(out.contains("width="));
        assertTrue(out.contains("<ref target=\"http://x.io\">"));
    }

    /** regression: an image's tail text must not be emitted twice outside a table cell. */
    @Test
    void test_image_tail_not_duplicated() {
        String result = _extract_doc("<ul><li>a <img src='i.jpg' alt='A'/> b</li></ul>", true, zkw().include_images(true));
        assertTrue(result.endsWith("a ![A](i.jpg) b"));
    }

    static java.util.stream.Stream<Arguments> test_handle_lists_params() {
        return java.util.stream.Stream.of(
            Arguments.of("Description:<item>a</item><item>b</item>", "<item>Description:</item><item>a</item><item>b</item>"),
            Arguments.of("header<item>text</item>", "<item>header</item><item>text</item>"),
            Arguments.of("   <item>text</item>", "<item>text</item>"),
            Arguments.of("<item><p>text</p></item>", "<item><p>text</p></item>"),
            Arguments.of("<item>text1<p>text2</p></item>", "<item>text1<p>text2</p></item>"),
            Arguments.of("<item>text<lb/>more text</item>", "<item>text<lb/>more text</item>"),
            Arguments.of("<item>text</item>tail", "<item>text tail</item>"),
            Arguments.of("<item><p>text</p></item>tail", "<item><p>text</p>tail</item>"),
            Arguments.of("<item><p>text</p>tail1</item>tail", "<item><p>text</p>tail1 tail</item>"),
            Arguments.of("<item><p>text</p>\n</item>tail", "<item><p>text</p>tail</item>"),
            Arguments.of("<item><list><item>text</item></list></item>tail", "<item><list><item>text</item></list>tail</item>"),
            Arguments.of("<item>a<list><item>b<list><item>c</item></list></item></list></item>", "<item>a<list><item>b<list><item>c</item></list></item></list></item>"));
    }

    /** handle_lists output structure on small and malformed lists. */
    @ParameterizedTest
    @MethodSource("test_handle_lists_params")
    void test_handle_lists(String items, String expected) {
        Extractor options = new Extractor();
        assertEquals("<list>" + expected + "</list>", Etree.tostring(MainExtractor.handle_lists(Etree.fromstring("<list>" + items + "</list>"), options), "unicode"));
    }

    @Test
    void test_list_processing() {
        String my_doc = "<html><body><article><p>P 1</p><ul><li>Item 1</li><li>Item 2</li></ul><p>P 2</p></article></body></html>";
        String my_result = Core.extract(my_doc, Core.kw().fast(true).output_format("txt").config(ZERO_CONFIG));
        assertEquals("P 1\n- Item 1\n- Item 2\nP 2", my_result);
        String htmlstring = "<html><body><article>\n<ul>\n  <li>Coffee</li>\n  <li>Tea\n    <ul>\n      <li>Black tea</li>\n      <li>Green tea</li>\n    </ul>\n  </li>\n  <li>Milk</li>\n</ul>\n</article></body></html>";
        my_result = Core.extract(htmlstring, Core.kw().fast(true).output_format("xml").config(ZERO_CONFIG));
        String expected = "\n    <list rend=\"ul\">\n      <item>Coffee</item>\n      <item>Tea\n        <list rend=\"ul\">\n          <item>Black tea</item>\n          <item>Green tea</item>\n        </list>\n      </item>\n      <item>Milk</item>\n    </list>".replace("\n", "").replace(" ", "");
        assertTrue(my_result.replace("\n", "").replace(" ", "").contains(expected));
        htmlstring = "<html><body><article>\n <dl>\n  <dt>Coffee</dt>\n  <dd>Black hot drink</dd>\n  <dt>Milk</dt>\n  <dd>White cold drink</dd>\n</dl>\n</article></body></html>";
        my_result = Core.extract(htmlstring, Core.kw().fast(true).output_format("xml").config(ZERO_CONFIG));
        assertTrue(my_result.contains("\n    <list rend=\"dl\">\n      <item rend=\"dt-1\">Coffee</item>\n      <item rend=\"dd-1\">Black hot drink</item>\n      <item rend=\"dt-2\">Milk</item>\n      <item rend=\"dd-2\">White cold drink</item>\n    </list>"));
        htmlstring = "<html><body><article>\n<p>If your eye is twitching and you do not have other symptoms, here are some common everyday causes worth knowing about.</p>\n<ul>\n<li><p><a href=\"https://example.org/stress\">Stress</a></p></li>\n<li><p>Fatigue</p></li>\n<li><p><a href=\"https://example.org/strain\">Eye strain</a></p></li>\n</ul>\n</article></body></html>";
        my_result = Core.extract(htmlstring, Core.kw().fast(true).output_format("markdown").include_links(true).config(ZERO_CONFIG));
        assertTrue(my_result.contains("[Stress](https://example.org/stress)"));
        assertTrue(my_result.contains("Fatigue"));
        assertTrue(my_result.contains("[Eye strain](https://example.org/strain)"));
    }

    /** regression: the first item of a nested list must not come back as an extra item of the parent list. */
    @Test
    void test_nested_list_first_item_not_duplicated() {
        String three_levels = "<ul><li>a<ul><li>b<ul><li>c</li></ul></li><li>b2</li></ul></li><li>d</li></ul>";
        assertEquals("" + (_INTRO) + "\n- a\n  - b\n    - c\n  - b2\n- d", _extract_doc(three_levels, true, zkw()));
        assertFalse(_extract_doc(three_levels, true, zkw().output_format("xml")).contains("<item>b</item>"));
        String with_link = "<ul><li>a<ul><li>see <a href='https://example.org/x'>link</a></li><li>c</li></ul></li><li>d</li></ul>";
        assertEquals("" + (_INTRO) + "\n- a\n  - see [link](https://example.org/x)\n  - c\n- d", _extract_doc(with_link, true, zkw().include_links(true)));
    }

    @Test
    void test_code_blocks() {
        String highlightjs = "<div class=\"s-prose js-post-body\" itemprop=\"text\">\n<p>Code:</p>\n<pre class=\"lang-sql s-code-block\"><code class=\"hljs language-sql\">code\n\n<span class=\"hljs-keyword\">highlighted</span> more <span class=\"hljs-keyword\">code</span>\n</code></pre>\n</div>";
        String testresult = Core.extract(highlightjs, Core.kw().config(ZERO_CONFIG).output_format("xml"));
        assertTrue(testresult.contains("<code>code\n\nhighlighted more code\n</code>"));
        assertFalse(testresult.contains("quote"));
        String github = "<div class=\"highlight highlight-source-shell notranslate position-relative overflow-auto\" dir=\"auto\"><pre>$ pip install PyGithub</pre><div class=\"zeroclipboard-container position-absolute right-0 top-0\">\n    <clipboard-copy aria-label=\"Copy\" class=\"ClipboardButton btn js-clipboard-copy m-2 p-0 tooltipped-no-delay\" data-copy-feedback=\"Copied!\" data-tooltip-direction=\"w\" value=\"$ pip install PyGithub\" tabindex=\"0\" role=\"button\" style=\"display: inherit;\">\n      <svg aria-hidden=\"true\" height=\"16\" viewBox=\"0 0 16 16\" version=\"1.1\" width=\"16\" data-view-component=\"true\" class=\"octicon octicon-copy js-clipboard-copy-icon m-2\">\n    <path d=\"M0 6.75C0 5.784.784 5 1.75 5h1.5a.75.75 0 0 1 0 1.5h-1.5a.25.25 0 0 0-.25.25v7.5c0 .138.112.25.25.25h7.5a.25.25 0 0 0 .25-.25v-1.5a.75.75 0 0 1 1.5 0v1.5A1.75 1.75 0 0 1 9.25 16h-7.5A1.75 1.75 0 0 1 0 14.25Z\"></path><path d=\"M5 1.75C5 .784 5.784 0 6.75 0h7.5C15.216 0 16 .784 16 1.75v7.5A1.75 1.75 0 0 1 14.25 11h-7.5A1.75 1.75 0 0 1 5 9.25Zm1.75-.25a.25.25 0 0 0-.25.25v7.5c0 .138.112.25.25.25h7.5a.25.25 0 0 0 .25-.25v-7.5a.25.25 0 0 0-.25-.25Z\"></path>\n</svg>\n      <svg aria-hidden=\"true\" height=\"16\" viewBox=\"0 0 16 16\" version=\"1.1\" width=\"16\" data-view-component=\"true\" class=\"octicon octicon-check js-clipboard-check-icon color-fg-success d-none m-2\">\n    <path d=\"M13.78 4.22a.75.75 0 0 1 0 1.06l-7.25 7.25a.75.75 0 0 1-1.06 0L2.22 9.28a.751.751 0 0 1 .018-1.042.751.751 0 0 1 1.042-.018L6 10.94l6.72-6.72a.75.75 0 0 1 1.06 0Z\"></path>\n</svg>\n    </clipboard-copy>\n  </div></div>\n    ";
        testresult = Core.extract(github, Core.kw().config(ZERO_CONFIG).output_format("xml"));
        assertTrue(testresult.contains("<code>$ pip install PyGithub</code>"));
        assertFalse(testresult.contains("quote"));
        String inline_code = "<div><p>paragraph</p><p>here is <code>some</code> code</p></div>";
        testresult = Core.extract(inline_code, Core.kw().config(ZERO_CONFIG).output_format("xml"));
        assertTrue(testresult.contains("<code>some</code>"));
        assertFalse(testresult.contains("quote"));
        String w3schools = "<div class=\"w3-example\"><h3>Example</h3>\n<p>Create a class named Person, use the __init__() function to assign values\nfor name and age:</p>\n<div class=\"w3-code notranslate pythonHigh\"><span class=\"pythoncolor\" style=\"color:black\"><span class=\"pythonnumbercolor\" style=\"color:red\">\n</span>  <span class=\"pythonkeywordcolor\" style=\"color:mediumblue\">class</span> Person:<br>&nbsp; <span class=\"pythonkeywordcolor\" style=\"color:mediumblue\">def</span> __init__(self, name, age):<br>&nbsp;&nbsp;&nbsp; <span class=\"pythonnumbercolor\" style=\"color:red\">\n</span>  self.name = name<br>&nbsp;&nbsp;&nbsp; self.age = age<br><br>p1 = Person(<span class=\"pythonstringcolor\" style=\"color:brown\">\"John\"</span>, <span class=\"pythonnumbercolor\" style=\"color:red\">\n</span>  <span class=\"pythonnumbercolor\" style=\"color:red\">36</span>)<br><span class=\"pythonnumbercolor\" style=\"color:red\">\n</span>  <br><span class=\"pythonkeywordcolor\" style=\"color:mediumblue\">print</span>(p1.name)<br><span class=\"pythonkeywordcolor\" style=\"color:mediumblue\">print</span>(p1.age) </span></div>\n</div>";
        testresult = Core.extract(w3schools, Core.kw().config(ZERO_CONFIG).output_format("xml"));
        String expected = "<code>\n  class Person:<lb/>  def __init__(self, name, age):<lb/>    \n  self.name = name<lb/>    self.age = age<lb/><lb/>p1 = Person(\"John\", \n  36)<lb/>\n  <lb/>print(p1.name)<lb/>print(p1.age) </code>";
        assertTrue(testresult.contains(expected));
        assertFalse(testresult.contains("quote"));
        String pip = "<div><p>Code:</p>\n<pre lang=\"python3\"><span class=\"kn\">import</span> <span class=\"nn\">openai</span>\n<span class=\"kn\">from</span> <span class=\"nn\">openai_function_call</span> <span class=\"kn\">import</span> <span class=\"n\">openai_function</span></pre></div>";
        expected = "<code>import openai\nfrom openai_function_call import openai_function</code>";
        testresult = Core.extract(pip, Core.kw().config(ZERO_CONFIG).output_format("xml"));
        assertTrue(testresult.contains(expected));
        assertFalse(testresult.contains("quote"));
        String medium_js = "<div><p>Code:</p>\n    <pre class=\"lw lx ly lz ma nq nr ns bo nt ba bj\"><span id=\"fe48\" class=\"nu mo ev nr b bf nv nw l nx ny\" data-selectable-paragraph=\"\"><span class=\"hljs-keyword\">import</span> openai_function<br><br><span class=\"hljs-meta\">@openai_function</span></span></pre>";
        expected = "<code>import openai_function<lb/><lb/>@openai_function</code>";
        testresult = Core.extract(medium_js, Core.kw().config(ZERO_CONFIG).output_format("xml"));
        assertTrue(testresult.contains(expected));
        assertFalse(testresult.contains("quote"));
        String medium_ssr = "<div><p>Code:</p>\n    <pre class=\"lw lx ly lz ma nq nr ns bo nt ba bj\"><span id=\"fe48\" class=\"nu mo ev nr b bf nv nw l nx ny\">import openai_function<br><br>@openai_function<br>def sum(a:int, b:int):<br>  &quot;&quot;&quot;Sum description adds a + b&quot;&quot;&quot;</span></pre>";
        expected = "<code>import openai_function<lb/><lb/>@openai_function<lb/>def sum(a:int, b:int):<lb/>  \"\"\"Sum description adds a + b\"\"\"</code>";
        testresult = Core.extract(medium_ssr, Core.kw().config(ZERO_CONFIG).output_format("xml"));
        assertTrue(testresult.contains(expected));
        assertFalse(testresult.contains("quote"));
        String code_el = "<div><p>Code:</p>\n    <pre><code><span>my code</span></code></pre>";
        expected = "<code>my code</code>";
        testresult = Core.extract(code_el, Core.kw().config(ZERO_CONFIG).output_format("xml"));
        assertTrue(testresult.contains(expected));
        assertFalse(testresult.contains("quote"));
        String bq_text = "<html><body><article><blockquote>see <code>x</code> above</blockquote></article></body></html>";
        assertTrue(Core.extract(bq_text, Core.kw().output_format("xml").config(ZERO_CONFIG)).contains("<quote>"));
        String bq_tail = "<html><body><article><blockquote><code>x</code> tail</blockquote></article></body></html>";
        assertTrue(Core.extract(bq_tail, Core.kw().output_format("xml").config(ZERO_CONFIG)).contains("<quote>"));
    }

    /** Markdown-mode output escapes metacharacters that would corrupt GFM structure. */
    @Test
    void test_markdown_escaping() {
        assertTrue(_md(T.b("<body><table><row><cell>a|b</cell><cell>c</cell></row></table></body>")).contains("a\\|b"));
        assertTrue(_md(T.b("<body><table><row><cell><hi rend=\"#b\">x|y</hi></cell></row></table></body>")).contains("x\\|y"));
        assertTrue(_md(T.b("<body><p><ref target=\"http://a b/c\">link</ref></p></body>")).contains("[link](<http://a b/c>)"));
        assertTrue(_md(T.b("<body><p><ref target=\"http://x\">a[b]c</ref></p></body>")).contains("[a\\[b\\]c](http://x)"));
        assertTrue(_md(T.b("<body><graphic src=\"img.png\" alt=\"a[b]c\"/></body>")).contains("![a\\[b\\]c](img.png)"));
        assertEquals("[txt]", _md(T.b("<body><p><ref>txt</ref></p></body>")));
        assertEquals("[txt]", _md(T.b("<body><p><ref target=\"\">txt</ref></p></body>")));
        assertEquals("![a]()", _md(T.b("<body><graphic alt=\"a\"/></body>")));
        assertTrue(_md(T.b("<body><p><hi rend=\"#t\">a`b</hi></p></body>")).contains("``a`b``"));
        assertEquals("`` `x ``", _md(T.b("<body><p><hi rend=\"#t\">`x</hi></p></body>")));
        assertEquals("`` x` ``", _md(T.b("<body><p><hi rend=\"#t\">x`</hi></p></body>")));
        assertEquals("`` ` ``", _md(T.b("<body><p><hi rend=\"#t\">`</hi></p></body>")));
        String result = _md(T.b("<body><code>a```b</code></body>"));
        assertTrue(result.contains("````"));
        assertTrue(result.contains("a```b"));
        assertTrue(_md(T.b("<body><p><del>a~~b</del></p></body>")).contains("~~a\\~\\~b~~"));
        result = Core.extract("<html><body><table><tr><td><del>gone</del></td></tr></table></body>", Core.kw().output_format("markdown").include_formatting(true).config(ZERO_CONFIG));
        assertTrue(Py.truthy(result));
        assertTrue(result.contains("~~gone~~"));
        assertTrue(_md(T.b("<body><p><del><hi rend=\"#b\">bold</hi></del></p></body>")).contains("~~**bold**~~"));
        assertTrue(_md(T.b("<body><table><row><cell><graphic src=\"img.png\" alt=\"img\"/>tail|text</cell></row></table></body>")).contains("tail\\|text"));
        assertTrue(_md(T.b("<body><table><row><cell><hi rend=\"#b\">bold</hi>tail|pipe</cell></row></table></body>")).contains("tail\\|pipe"));
        result = _md(T.b("<body><table><row><cell>x \\[E=mc^2\\] y</cell></row></table></body>"));
        assertFalse(Py.strip(result).contains("\n"));
        assertTrue(result.contains("$$ E=mc^2 $$"));
        assertTrue(_md(T.b("<body><p><hi rend=\"#b\"><ref target=\"http://x.com\">link</ref></hi></p></body>")).contains("**[link](http://x.com)**"));
        byte[] bold_img = T.b("<body><p><hi rend=\"#b\">x <graphic src=\"i.jpg\" alt=\"A\"/> y</hi></p></body>");
        assertEquals("**x ![A](i.jpg) y**", _md(bold_img));
        byte[] struck_img = T.b("<body><p><del>x <graphic src=\"i.jpg\" alt=\"A\"/> y</del></p></body>");
        assertEquals("~~x ![A](i.jpg) y~~", _md(struck_img));
    }

    /** Literal '*' in source text must be escaped so CommonMark renders it literally, not as emphasis. */
    @Test
    void test_markdown_asterisk_escaping() {
        assertEquals("This \\*should\\* not be italic and 3\\*4=12", _md(T.b("<body><p>This *should* not be italic and 3*4=12</p></body>")));
        assertEquals("**bold** and \\*literal\\* asterisks", _md(T.b("<body><p><hi rend=\"#b\">bold</hi> and *literal* asterisks</p></body>")));
        assertEquals("## Title \\*with\\* asterisk", _md(T.b("<body><head rend=\"h2\">Title *with* asterisk</head></body>")));
        assertEquals("- list item \\*text\\*", _md(T.b("<body><list><item>list item *text*</item></list></body>")));
        assertTrue(_md(T.b("<body><table><row><cell>a*b|c</cell></row></table></body>")).contains("a\\*b\\|c"));
        assertTrue(_md(T.b("<body><p><ref target=\"http://x.com\">link *text*</ref></p></body>")).contains("[link \\*text\\*](http://x.com)"));
        assertTrue(_md(T.b("<body><graphic src=\"i.png\" alt=\"a*b\"/></body>")).contains("![a\\*b](i.png)"));
        assertEquals("~~a\\*b~~", _md(T.b("<body><p><del>a*b</del></p></body>")));
        assertEquals("[**bold link**](http://x.com)", _md(T.b("<body><p><ref target=\"http://x.com\"><hi rend=\"#b\">bold link</hi></ref></p></body>")));
        assertEquals("`a * b`", _md(T.b("<body><code>a * b</code></body>")));
        assertEquals("`a*b`", _md(T.b("<body><p><hi rend=\"#t\">a*b</hi></p></body>")));
        assertEquals("\\*\\*\\* section break \\*\\*\\*", _md(T.b("<body><p>*** section break ***</p></body>")));
        assertEquals("\\*\\*not bold\\*\\*", _md(T.b("<body><p>**not bold**</p></body>")));
        for (Object _r : T.list(List.of("#i", "*"), List.of("#b", "**"))) {
            List<?> _row = (List<?>) _r;
            String rend = (String) _row.get(0);
            String marker = (String) _row.get(1);
            String hi = "<hi rend=\"" + rend + "\">";
            assertEquals("\\*" + marker + "x" + marker, _md("<body><p>*" + hi + "x</hi></p></body>"));
            assertEquals(marker + "\\*x" + marker, _md("<body><p>" + hi + "*x</hi></p></body>"));
            assertEquals(marker + "x" + marker + "\\*", _md("<body><p>" + hi + "x</hi>*</p></body>"));
            assertEquals(marker + "x\\*" + marker, _md("<body><p>" + hi + "x*</hi></p></body>"));
            assertEquals(marker + "\\*x\\*" + marker, _md("<body><p>" + hi + "*x*</hi></p></body>"));
            assertEquals("\\*" + marker + "x" + marker + "\\*", _md("<body><p>*" + hi + "x</hi>*</p></body>"));
            assertEquals(marker + "\\*\\*x\\*\\*" + marker, _md("<body><p>" + hi + "**x**</hi></p></body>"));
            assertEquals("\\*\\*" + marker + "x" + marker + "\\*\\*", _md("<body><p>**" + hi + "x</hi>**</p></body>"));
        }
        assertEquals("Edge case: a\\\\\\*b", _md(T.b("<body><p>Edge case: a\\*b</p></body>")));
        assertEquals("This *stays* as-is", _md(T.b("<body><p>This *stays* as-is</p></body>"), false));
    }

    /** Literal CommonMark metacharacters other than '*' must also be escaped so they render as
     * themselves: '_', '`', '[', ']', '<', '~' are ambiguous wherever they appear in prose, while
     * '#', '-', '+', '.'/')' after digits and '>' only matter at the very start of a fresh block
     * line (heading/list/blockquote syntax). Everything else (quotes, parens, punctuation) is inert
     * in body text and must be left alone. */
    @Test
    void test_markdown_special_char_escaping() {
        assertEquals("a\\_b\\_c and \\`code\\` and \\[bracket\\] and \\<tag> text", _md(T.b("<body><p>a_b_c and `code` and [bracket] and &lt;tag&gt; text</p></body>")));
        assertEquals("\\~single\\~ tilde", _md(T.b("<body><p>~single~ tilde</p></body>")));
        assertEquals("\\# not a heading", _md(T.b("<body><p># not a heading</p></body>")));
        assertEquals("\\> not a quote", _md(T.b("<body><p>&gt; not a quote</p></body>")));
        assertEquals("\\- not a list", _md(T.b("<body><p>- not a list</p></body>")));
        assertEquals("\\+ not a list", _md(T.b("<body><p>+ not a list</p></body>")));
        assertEquals("1\\. not a list", _md(T.b("<body><p>1. not a list</p></body>")));
        assertEquals("1\\) not a list", _md(T.b("<body><p>1) not a list</p></body>")));
        assertEquals("mid-line - and 3.14 and 100% and word) stay put", _md(T.b("<body><p>mid-line - and 3.14 and 100% and word) stay put</p></body>")));
        assertEquals("- \\- nested looking", _md(T.b("<body><list><item>- nested looking</item></list></body>")));
        assertEquals("1. 2\\. nested ordered looking", _md(T.b("<body><list rend=\"ol\"><item>2. nested ordered looking</item></list></body>")));
        assertEquals("\\> quoted text with - marker", _md(T.b("<body><quote>&gt; quoted text with - marker</quote></body>")));
        assertEquals("## # nested hash in heading", _md(T.b("<body><head rend=\"h2\"># nested hash in heading</head></body>")));
        assertTrue(_md(T.b("<body><table><row><cell>- looks like a list</cell></row></table></body>")).contains("| - looks like a list |"));
        byte[] inert = T.b("<body><p>quote &quot; apostrophe ' paren ( ) percent % dollar $ at @ caret ^ eq = brace { } colon : semi ; comma , bang !</p></body>");
        assertEquals("quote \" apostrophe ' paren ( ) percent % dollar $ at @ caret ^ eq = brace { } colon : semi ; comma , bang !", _md(inert));
        assertEquals("! not an image on its own", _md(T.b("<body><p>! not an image on its own</p></body>")));
        assertEquals("``a_b`c[d]e<f>``", _md(T.b("<body><code>a_b`c[d]e&lt;f&gt;</code></body>")));
        assertEquals("``a_b`c``", _md(T.b("<body><p><hi rend=\"#t\">a_b`c</hi></p></body>")));
        assertEquals("[!\\[img\\]-like text](http://x.com)", _md(T.b("<body><p><ref target=\"http://x.com\">![img]-like text</ref></p></body>")));
        assertEquals("Edge case: a\\\\\\_b", _md(T.b("<body><p>Edge case: a\\_b</p></body>")));
        assertEquals("This _stays_ as-is with `code` [b] <tag>", _md(T.b("<body><p>This _stays_ as-is with `code` [b] &lt;tag&gt;</p></body>"), false));
    }

    /** Backslash escapes are literal inside a code span, so brackets in inline code within link text stay as-is. */
    @Test
    void test_markdown_link_code_brackets() {
        String target = " target=\"http://x.com\"";
        Map<String, Object> cases = T.map("<code>a[b]c</code>", "[`a[b]c`](http://x.com)", "<code>a]b</code>", "[`a]b`](http://x.com)", "<hi rend=\"#t\">x[0]</hi>", "[`x[0]`](http://x.com)", "<code>a`b[1]</code>", "[``a`b[1]``](http://x.com)", "f <code>a]b</code> [q]", "[f `a]b` \\[q\\]](http://x.com)");
        cases.forEach((inner, expected) -> assertEquals(expected, _md("<body><p><ref" + target + ">" + inner + "</ref></p></body>")));
    }

    /** A '<' or '>' in a link/image target must stay inside the angle-bracket destination. */
    @Test
    void test_markdown_link_angle_bracket_targets() {
        Map<String, Object> cases = T.map("http://x.com/a>b", "[t](<http://x.com/a\\>b>)", "http://x.com/a<b", "[t](<http://x.com/a\\<b>)", "http://x.com/a<b>c", "[t](<http://x.com/a\\<b\\>c>)", "http://x.com/a b>c", "[t](<http://x.com/a b\\>c>)", "http://x.com/end>", "[t](<http://x.com/end\\>>)", "<start.example/p", "[t](<\\<start.example/p>)", "http://x.com/a\\>b", "[t](<http://x.com/a\\\\\\>b>)", "http://x.com/a>>b", "[t](<http://x.com/a\\>\\>b>)", ">x.com/p", "[t](<\\>x.com/p>)", "http://x.com/<>", "[t](<http://x.com/\\<\\>>)", "http://x.com/a b/c", "[t](<http://x.com/a b/c>)", "http://x.com/a(b)", "[t](<http://x.com/a(b)>)", "http://x.com/a)b(c", "[t](<http://x.com/a)b(c>)", "http://x.com/a(b", "[t](<http://x.com/a(b>)", "http://x.com/plain", "[t](http://x.com/plain)");
        cases.forEach((target, expected) -> {
            assertEquals(expected, Xml._md_link("t", target));
            // images share the destination handling, only gaining a '!' prefix
            assertEquals("!" + expected, Xml._md_link("t", target, true));
        });
        assertEquals("[t]", Xml._md_link("t", null));
        assertEquals("![t]", Xml._md_link("t", null, true));
        assertEquals("[a\\[b\\]c](<http://x.com/p\\>q>)", Xml._md_link("a[b]c", "http://x.com/p>q"));
        byte[] gt = T.b("<body><p><ref target=\"http://x.com/a&gt;b\">t</ref></p></body>");
        assertEquals("[t](<http://x.com/a\\>b>)", _md(gt));
        byte[] lt = T.b("<body><graphic src=\"http://x.com/a&lt;b\" alt=\"A\"/></body>");
        assertEquals("![A](<http://x.com/a\\<b>)", _md(lt));
    }

    /** xmltotxt must not mutate its input tree (math/emphasis passes run on a deepcopy). */
    @Test
    void test_xmltotxt_no_mutation() {
        Element tree = Etree.fromstring("<body><p>formula \\(x\\) <hi rend=\"#b\"><hi rend=\"#i\">y</hi></hi></p></body>");
        String before = Etree.tostring(tree, "unicode");
        String out = Xml.xmltotxt(tree, true);
        assertTrue(out.contains("$x$"));
        assertTrue(out.contains("***y***"));
        assertEquals(before, Etree.tostring(tree, "unicode"));
    }

    /** LaTeX math delimiters are converted to $ notation; unmatched delimiters and code are left alone. */
    @Test
    void test_math_conversion() {
        assertEquals("$x^2$", _md(T.b("<body><p>\\(x^2\\)</p></body>")));
        assertEquals("regex \\( open", _md(T.b("<body><p>regex \\( open</p></body>")));
        Element tree = Etree.fromstring("<body><code>prefix\n\\[E=mc^2\\]\nsuffix</code></body>");
        assertTrue(Xml.xmltotxt(tree, true).contains("\\[E=mc^2\\]"));
        tree = Etree.fromstring("<body><p>use <hi rend=\"#t\">a\\(x\\)b</hi> here</p></body>");
        assertTrue(Xml.xmltotxt(tree, true).contains("`a\\(x\\)b`"));
        tree = Etree.fromstring("<body><p><code>k</code> then \\(t\\) end</p></body>");
        assertTrue(Xml.xmltotxt(tree, true).contains("`k` then $t$ end"));
    }

    /** Inside a formula '_', '*', '[', ']', '<', '`' and '~' are LaTeX notation, so the
     * CommonMark escaping pass must step over math spans while still escaping the prose
     * around them. */
    @Test
    void test_math_is_not_commonmark_escaped() {
        assertEquals("we write $a_1 < a_2$ here", _md(T.b("<body><p>we write \\(a_1 &lt; a_2\\) here</p></body>")));
        assertEquals("$$\nx_i = \\sum_j A_{ij} y_j^*\n$$", _md(T.b("<body><p>\\[ x_i = \\sum_j A_{ij} y_j^* \\]</p></body>")));
        assertEquals("$\\left[u_0\\right]$", _md(T.b("<body><p>\\(\\left[u_0\\right]\\)</p></body>")));
        assertEquals("file\\_one has $n_1$ rows in dir\\_two", _md(T.b("<body><p>file_one has \\(n_1\\) rows in dir_two</p></body>")));
        assertEquals("regex \\( a\\_b open", _md(T.b("<body><p>regex \\( a_b open</p></body>")));
    }

    /** MathML is replaced by its LaTeX source instead of being discarded with the <math> subtree. */
    @Test
    void test_math_recovery() {
        Extractor options = new Extractor();
        options.tables = true;
        java.util.function.Function<String, String> clean = html_string ->
                Etree.tostring(Htmlprocessing.tree_cleaning(Etree.html_fromstring(html_string), options), "unicode");
        // the annotation holds the original LaTeX and takes precedence over alttext
        String result = clean.apply("<html><body><p>a <math alttext=\"rendered\"><semantics>"
                + "<annotation encoding=\"application/x-tex\">x^2</annotation>"
                + "</semantics></math> b</p></body></html>");
        assertTrue(result.contains("\\(x^2\\)"));
        assertFalse(result.contains("rendered"));
        // alttext is used when there is no LaTeX annotation
        assertTrue(clean.apply("<html><body><p>a <math alttext=\"y_1\"><mi>y</mi></math> b</p></body></html>").contains("\\(y_1\\)"));
        // display="block" yields block delimiters
        assertTrue(clean.apply("<html><body><p><math display=\"block\" alttext=\"z\"><mi>z</mi></math></p></body></html>").contains("\\[z\\]"));
        // nothing to recover: unchanged behaviour, the element is dropped
        result = clean.apply("<html><body><p>a <math><mi>q</mi></math> b</p></body></html>");
        assertFalse(result.contains("math"));
        assertFalse(result.contains("q"));
        // the tail of the formula survives alongside the recovered source
        assertTrue(clean.apply("<html><body><p><math alttext=\"u\"><mi>u</mi></math> tail</p></body></html>").contains("\\(u\\) tail"));
        // a formula in a table cell is no longer silently emptied
        result = clean.apply("<html><body><table><tr><td>name</td>"
                + "<td><math alttext=\"1.33\\times 10^{-4}\"><mi>v</mi></math></td></tr></table></body></html>");
        assertTrue(result.contains("1.33\\times 10^{-4}"));

        // end to end: Markdown output renders the recovered formula as $...$
        String doc = "<html><body><article><p>Some introductory prose that is long enough to be retained here.</p><p>The error was <math alttext=\"1.33\\times 10^{-4}\"><semantics><annotation encoding=\"application/x-tex\">1.33\\times 10^{-4}</annotation></semantics></math> overall.</p></article></body></html>";
        assertTrue(Core.extract(doc, Core.kw().output_format("markdown").config(ZERO_CONFIG)).contains("$1.33\\times 10^{-4}$"));
    }

    /** Cover lb in inline context, structural-tag fallback, and redundant emphasis collapse. */
    @Test
    void test_inline_edge_cases() {
        assertTrue(Xml.xmltotxt(Etree.fromstring("<body><p><hi rend=\"#b\">A<lb/>B</hi></p></body>"), true).contains("**A\nB**"));
        assertTrue(Xml.xmltotxt(Etree.fromstring("<body><p><hi rend=\"#b\"><span>carried</span></hi></p></body>"), true).contains("**carried**"));
        assertEquals("**x**", Py.strip(Xml.xmltotxt(Etree.fromstring("<body><p><hi rend=\"#b\"><hi rend=\"#b\">x</hi></hi></p></body>"), true)));
    }

    /** A heading keeps its # prefix even when it starts with an inline child (e.g. <h2><strong>...). */
    @Test
    void test_heading_inline_formatting() {
        assertEquals("## **B** text", _md(T.b("<body><head rend=\"h2\"><hi rend=\"#b\">B</hi> text</head></body>")));
        assertEquals("## **B**", _md(T.b("<body><head rend=\"h2\"><hi rend=\"#b\">B</hi></head></body>")));
        assertEquals("### *I* rest", _md(T.b("<body><head rend=\"h3\"><hi rend=\"#i\">I</hi> rest</head></body>")));
        assertEquals("## **B** t", _md(T.b("<body><head><hi rend=\"#b\">B</hi> t</head></body>")));
        assertEquals("## x **B**", _md(T.b("<body><head rend=\"h2\">x <hi rend=\"#b\">B</hi></head></body>")));
        assertEquals("## plain", _md(T.b("<body><head rend=\"h2\">plain</head></body>")));
        assertEquals("| **H** |", _md(T.b("<body><table><row><cell><head rend=\"h3\"><hi rend=\"#b\">H</hi></head></cell></row></table></body>")));
    }

    /** Test extraction from HTML with mixed content. */
    @Test
    void test_mixed_content_extraction() {
        String html_content = "<html><body><p>Text here</p><img src=\"img.jpg\"/><video src=\"video.mp4\"/></body></html>";
        assertEquals("Text here", Core.extract(html_content, Core.kw().fast(false).config(ZERO_CONFIG)));
    }

    /** Test handling non-standard HTML entities. */
    @Test
    void test_nonstd_html_entities() {
        String html_content = "<html><body><p>Text &customentity; more text</p></body></html>";
        assertEquals("Text &customentity; more text", Core.extract(html_content, Core.kw().fast(false).config(ZERO_CONFIG)));
    }

    /** Performance test on large HTML documents. */
    @Test
    void test_large_doc_performance() {
        String large_html = "<html><body>" + "<p>Sample text</p>".repeat(10000) + "</body></html>";
        long start = System.nanoTime();
        Core.extract(large_html, Core.kw().fast(false).config(ZERO_CONFIG));
        long end = System.nanoTime();
        assertTrue((end - start) / 1e9 < 5, "Large document performance issue");
    }

    /** Accuracy of language detection. */
    @Test
    void test_lang_detection() {
        // @pytest.mark.skipif(not LANGID_FLAG, reason="py3langid not installed")
        org.junit.jupiter.api.Assumptions.assumeTrue(Utils.LANGID_FLAG, "py3langid not installed");
        List<Map<String, String>> samples = List.of(
                Map.of("html", "<html><body><p>Texto en español</p></body></html>", "expected", "es"),
                Map.of("html", "<html><body><p>Texte en français</p></body></html>", "expected", "fr"));
        for (Map<String, String> sample : samples) {
            String result = Core.extract(sample.get("html"), Core.kw().fast(false).config(ZERO_CONFIG));
            String detected = Utils.language_classifier(result, "");
            assertEquals(sample.get("expected"), detected);
        }
    }

    /** Check if the config file is read correctly. */
    @Test
    void test_config_loading() throws java.io.IOException {
        // FileNotFoundError
        java.io.UncheckedIOException missing = assertThrows(java.io.UncheckedIOException.class,
                () -> Settings.use_config("/bogus-dir/bogus-file.txt"));
        assertTrue(missing.getCause() instanceof java.io.FileNotFoundException);

        ConfigParser config = Settings.use_config(RESOURCES_DIR.resolve("newsettings.cfg").toString());
        assertNotNull(config);

        // an explicit config object short-circuits and is returned unchanged
        assertSame(config, Settings.use_config(null, config));

        // the settingsfile= argument must actually be applied, not silently ignored:
        // newsettings.cfg sets MIN_OUTPUT_SIZE/MIN_EXTRACTED_SIZE far above this doc's length
        String settingsfile = RESOURCES_DIR.resolve("newsettings.cfg").toString();
        String html = "<html><body><article><p>Short paragraph of content here.</p></article></body></html>";
        assertNotNull(Core.extract(html));
        assertNull(Core.extract(html, Core.kw().settingsfile(settingsfile)));
        assertNull(Core.extract_with_metadata(html, Core.kw().settingsfile(settingsfile)));
        // an explicit config object is still honored when no settingsfile is given
        assertNull(Core.extract(html, Core.kw().config(NEW_CONFIG)));
        // a missing settingsfile is reported, not silently ignored
        assertThrows(java.io.UncheckedIOException.class, () -> Core.extract(html, Core.kw().settingsfile("/bogus-dir/bogus-file.txt")));

        // a partial settings file must not raise NoOptionError: defaults are seeded, user keys override
        Path tmp = java.nio.file.Files.createTempFile(null, ".cfg");
        java.nio.file.Files.writeString(tmp, "[DEFAULT]\nMIN_EXTRACTED_SIZE = 9999\n");
        String partial_path = tmp.toString();
        ConfigParser partial = Settings.use_config(partial_path);
        Extractor options = new Extractor(Extractor.kw().config(partial));
        assertEquals(9999, options.min_extracted_size); // from the partial file
        // unset key falls back to default; read fresh as DEFAULT_CONFIG may be mutated in-place elsewhere
        assertEquals(Settings.use_config().getint("DEFAULT", "MIN_OUTPUT_SIZE"), options.min_output_size);
    }

    /** Modifying MANUALLY_CLEANED/MANUALLY_STRIPPED in place affects extraction (issue #746). */
    @Test
    void test_settings_element_lists() {
        String html = "<html><body><div><p>Alpha paragraph number one with more than enough length to be considered real body content.</p><blockquote>Quoted block of text which is normally retained within the trafilatura extraction output.</blockquote><p>Beta paragraph number two also with more than enough length to be treated as real body content.</p><p>Gamma paragraph number three providing extra padding for the document body content section here.</p><p>Delta paragraph number four keeping the extracted text comfortably above the minimum size limit.</p></div></body></html>";
        assertTrue(Core.extract(html, Core.kw().fast(true)).contains("Quoted block"));
        Settings.MANUALLY_CLEANED.add("blockquote");
        try {
            assertFalse(Core.extract(html, Core.kw().fast(true)).contains("Quoted block"));
        } finally {
            Settings.MANUALLY_CLEANED.remove("blockquote");
        }
        assertTrue(Core.extract(html, Core.kw().fast(true)).contains("Quoted block"));
    }

    /** Test is_probably_readerable function. */
    @Test
    void test_is_probably_readerable() {
        assertFalse(ReadabilityLxml.is_probably_readerable("ABC"));
        String very_small_str = "hello there";
        String small_str = "hello there ".repeat(11);
        String large_str = "hello there ".repeat(12);
        String very_large_str = "hello there ".repeat(50);
        String linebreaks_str = (large_str + " <br>").repeat(10);
        Element very_small_doc = Utils.load_html("<html><p id='main'>" + very_small_str + "</p></html>");
        Element small_doc = Utils.load_html("<html><p id='main'>" + small_str + "</p></html>");
        Element large_doc = Utils.load_html("<html><p id='main'>" + large_str + "</p></html>");
        Element very_large_doc = Utils.load_html("<html><p id='main'>" + very_large_str + "</p></html>");
        Element likely_doc = Utils.load_html("<html><p id='main' class='header'>" + very_large_str + "</p><p id='header' class='article'>" + very_large_str + "</p><p id='footer' class='body'>" + very_large_str + "</p></html>");
        Element unlikely_doc = Utils.load_html("<html><p id='header'>" + very_large_str + "</p><p class='footer'>" + very_large_str + "</p></html>");
        Element visible_doc = Utils.load_html("<html><p id='main' style='display: block'>" + very_large_str + "</p><p id='main'>" + very_large_str + "</p><p id='main' aria-hidden='false'>" + very_large_str + "</p></html>");
        Element invisible_doc = Utils.load_html("<html><p id='main' style='display: none'>" + very_large_str + "</p><p id='main' hidden>" + very_large_str + "</p><p id='main' aria-hidden='true'>" + very_large_str + "</p></html>");
        Element linebreaks_doc = Utils.load_html("<html><div>" + (linebreaks_str.repeat(10)) + "</div></html>");
        Element no_linebreaks_doc = Utils.load_html("<html><div>" + (large_str.repeat(10)) + "</div></html>");
        List<Element> docs = List.of(very_small_doc, small_doc, large_doc, very_large_doc);
        List<Map<String, Object>> option_sets = Arrays.asList(null, T.map("min_content_length", 120, "min_score", 0),
                T.map("min_content_length", 200, "min_score", 0), T.map("min_content_length", 0, "min_score", 4),
                T.map("min_content_length", 0, "min_score", 11.5));
        List<List<Boolean>> expectations = List.of(List.of(false, false, false, true), List.of(false, true, true, true),
                List.of(false, false, false, true), List.of(false, true, true, true), List.of(false, false, true, true));
        for (int i = 0; i < option_sets.size(); i++) {
            Map<String, Object> options = option_sets.get(i) == null ? Map.of() : option_sets.get(i);
            assertEquals(expectations.get(i), docs.stream().map(doc -> ReadabilityLxml.is_probably_readerable(doc, options)).toList(),
                    String.valueOf(option_sets.get(i)));
        }
        assertTrue(ReadabilityLxml.is_probably_readerable(likely_doc));
        assertFalse(ReadabilityLxml.is_probably_readerable(unlikely_doc));
        assertFalse(ReadabilityLxml.is_probably_readerable(Utils.load_html("<html><p class='FOOTER'>" + very_large_str + "</p></html>")));
        assertTrue(ReadabilityLxml.is_probably_readerable(linebreaks_doc));
        assertFalse(ReadabilityLxml.is_probably_readerable(no_linebreaks_doc));
        for (boolean visible : new boolean[] {false, true}) {
            List<Element> calls = new ArrayList<>();
            Map<String, Object> options = Map.of("visibility_checker", (java.util.function.Predicate<Element>) node -> {
                calls.add(node);
                return visible;
            });
            assertSame(visible, ReadabilityLxml.is_probably_readerable(very_large_doc, options));
            assertTrue(Py.truthy(calls));
        }
        assertTrue(ReadabilityLxml.is_probably_readerable(visible_doc));
        assertFalse(ReadabilityLxml.is_probably_readerable(invisible_doc));
        String teststring = Conftest.read_text(Conftest.RESOURCES_DIR.resolve("mozilla.org.firefox.developer.html"));
        Element doc = Utils.load_html(teststring);
        assertFalse(ReadabilityLxml.is_probably_readerable(doc));
    }

    /** Test conversion from internal XML to HTML output. */
    @Test
    void test_html_conversion() {
        String xml = "<xml>\n    <list>\n        <item>Item 1</item>\n        <item>Item 2</item>\n    </list>\n    <p>Text</p>\n    <head rend=\"h1\">Heading 1</head>\n    <head rend=\"h2\">Heading 2</head>\n    <head>No attribute</head>\n    <hi rend=\"#i\">Italic</hi>\n    <hi rend=\"#b\">Bold</hi>\n    <hi>No rend</hi>\n    <ref target=\"https://example.com\">Link</ref>\n    <ref>No href</ref>\n</xml>";
        Element tree = Etree.fromstring(xml);
        Element html_tree = Htmlprocessing.convert_to_html(tree.copy());
        String expected_html = "<html><body>\n    <ul>\n        <li>Item 1</li>\n        <li>Item 2</li>\n    </ul>\n    <p>Text</p>\n    <h1>Heading 1</h1>\n    <h2>Heading 2</h2>\n    <h3>No attribute</h3>\n    <i>Italic</i>\n    <strong>Bold</strong>\n    <i>No rend</i>\n    <a href=\"https://example.com\">Link</a>\n    <a href=\"\">No href</a>\n</body></html>";
        assertEquals(expected_html, Etree.tostring(html_tree, null, "html", false, true));
        String html = "<html><body><article><h1>Title</h1><p>Text.</p></article></body></html>";
        String excepted_html = "<html>\n  <body>\n    <h1>Title</h1>\n    <p>Text.</p>\n  </body>\n</html>";
        String result = Core.extract(html, Core.kw().output_format("html").config(ZERO_CONFIG));
        assertEquals(excepted_html, result);
        html = "<html><body><article><h1>Title 1</h1><p>Text.</p></article></body></html>";
        excepted_html = "<html>\n  <head>\n    <meta name=\"title\" content=\"Title 1\"/>\n    <meta name=\"fingerprint\" content=\"f6fd180b8fbe3670\"/>\n  </head>\n  <body>\n    <h1>Title 1</h1>\n    <p>Text.</p>\n  </body>\n</html>";
        result = Core.extract(html, Core.kw().output_format("html").config(ZERO_CONFIG).with_metadata(true));
        assertEquals(excepted_html, result);
        html = "<html><head><meta name=\"keywords\" content=\"k1\"/><meta property=\"article:tag\" content=\"k2\"/></head><body><article><h1>Title 1</h1><p>Text.</p></article></body></html>";
        result = Core.extract(html, Core.kw().output_format("html").config(ZERO_CONFIG).with_metadata(true));
        assertTrue(result.contains("<meta name=\"tags\" content=\"k1;k2\"/>"));
        String table_xml = "<body><table><row span=\"3\"><cell role=\"head\">Name</cell><cell role=\"head\">Phone</cell></row><row span=\"3\"><cell>Jane</cell><cell>p1</cell><cell>p2</cell></row></table></body>";
        String table_html = Etree.tostring(Htmlprocessing.convert_to_html(Etree.fromstring(table_xml)), "unicode");
        assertEquals("<html><body><table><tr><th>Name</th><th>Phone</th></tr><tr><td>Jane</td><td>p1</td><td>p2</td></tr></table></body></html>", table_html);
        String img_xml = "<body><graphic src=\"a.jpg\" alt=\"cap\" title=\"t\"/></body>";
        String img_html = Etree.tostring(Htmlprocessing.convert_to_html(Etree.fromstring(img_xml)), "unicode");
        assertEquals("<html><body><img src=\"a.jpg\" alt=\"cap\" title=\"t\"/></body></html>", img_html);
        String doc = "<html><body><article><p>Body text here.</p><img src=\"pic.jpg\" alt=\"a\"/></article></body></html>";
        String out = Core.extract(doc, Core.kw().output_format("html").include_images(true).config(ZERO_CONFIG));
        assertTrue(out.contains("<img src=\"pic.jpg\" alt=\"a\"/>"));
        assertFalse(out.contains("<graphic"));
    }

    /** {@code pytest.deprecated_call()}: the Java port logs a "DeprecationWarning:" message instead of warning. */
    static void deprecated_call(Runnable body) {
        String err = CliTests.captureStderr(body);
        assertTrue(err.contains("DeprecationWarning"), err);
    }

    /** Test deprecated function parameters. */
    @Test
    void test_deprecations() {
        String htmlstring = "<html><body><article>ABC</article></body></html>";
        deprecated_call(() -> assertNotNull(Core.extract(htmlstring, Core.kw().no_fallback(true).config(ZERO_CONFIG))));
        deprecated_call(() -> assertNotNull(Core.bare_extraction(htmlstring, Core.kw().no_fallback(true).config(ZERO_CONFIG))));
        deprecated_call(() -> assertNotNull(Core.bare_extraction(htmlstring, Core.kw().as_dict(true).config(ZERO_CONFIG))));
        // ValueError
        assertThrows(IllegalArgumentException.class, () -> Core.extract(htmlstring, Core.kw().max_tree_size(100)));
        assertThrows(IllegalArgumentException.class, () -> Core.bare_extraction(htmlstring, Core.kw().max_tree_size(100)));

        // single source of truth for the effective "fast" flag
        deprecated_call(() -> assertTrue(Core._check_deprecation(false, true, false, null)));
        assertTrue(Core._check_deprecation(true, false, false, null));
        assertFalse(Core._check_deprecation(false, false, false, null));

        // regression: no_fallback=True must reach Extractor.fast via both entry points
        List<Boolean> captured = new ArrayList<>();
        java.util.function.Consumer<Extractor> saved = Core.EXTRACTOR_CREATED;
        Core.EXTRACTOR_CREATED = options -> captured.add(options.fast);
        try {
            deprecated_call(() -> Core.extract(htmlstring, Core.kw().no_fallback(true).config(ZERO_CONFIG)));
            deprecated_call(() -> Core.bare_extraction(htmlstring, Core.kw().no_fallback(true).config(ZERO_CONFIG)));
        } finally {
            Core.EXTRACTOR_CREATED = saved;
        }
        assertFalse(captured.isEmpty());
        assertTrue(captured.stream().allMatch(fast -> fast));
    }

    /** Incoherent option combinations warn (but do not raise) when building an Extractor. */
    @Test
    void test_incompatible_options() {
        // caplog: warnings are captured from the logger output on stderr
        String[] focus = new String[1];
        // precision + recall: recall wins
        String log = CliTests.captureStderr(() -> focus[0] = new Extractor(Extractor.kw().precision(true).recall(true)).focus);
        assertEquals("recall", focus[0]);
        assertTrue(log.contains("mutually exclusive"));

        // tei_validation only meaningful with xmltei output
        log = CliTests.captureStderr(() -> new Extractor(Extractor.kw().tei_validation(true).output_format("txt")));
        assertTrue(log.contains("tei_validation"));

        // include_formatting flattened away by JSON serialization
        log = CliTests.captureStderr(() -> new Extractor(Extractor.kw().formatting(true).output_format("json")));
        assertTrue(log.contains("JSON"));

        // coherent combinations stay silent
        log = CliTests.captureStderr(() -> {
            new Extractor(Extractor.kw().tei_validation(true).output_format("xmltei"));
            new Extractor(Extractor.kw().formatting(true).output_format("html"));
            new Extractor(Extractor.kw().formatting(true).output_format("markdown"));
        });
        assertEquals("", log);
    }

    /** Recall undoes the deletions when they would remove every paragraph. */
    @Test
    void test_tree_cleaning_recall_keeps_paragraphs() {
        Extractor options = new Extractor();
        String doc = "<html><body><aside><p>only text</p></aside></body></html>";
        assertNull(Htmlprocessing.tree_cleaning(Etree.html_fromstring(doc), options).find(".//p"));
        options.focus = "recall";
        assertNotNull(Htmlprocessing.tree_cleaning(Etree.html_fromstring(doc), options).find(".//p"));
        List<String> saved = new ArrayList<>(Settings.MANUALLY_CLEANED);
        Settings.MANUALLY_CLEANED.clear();
        Settings.MANUALLY_CLEANED.add("p");
        try {
            doc = "<html><body><div><p>only text</p></div></body></html>";
            assertNotNull(Htmlprocessing.tree_cleaning(Etree.html_fromstring(doc), options).find(".//p"));
        } finally {
            Settings.MANUALLY_CLEANED.clear();
            Settings.MANUALLY_CLEANED.addAll(saved);
        }
    }

    /** Without links, only links inside div, li, p (or tables) stay as ref, the tree root does not count. */
    @Test
    void test_convert_tags_links_off_keeps_contained_links() {
        Extractor options = new Extractor();
        String doc = "<div><a>root</a><span><a>loose</a></span><p><a>para</a></p><table><tr><td><a>cell</a></td></tr></table></div>";
        options.links = false;
        options.tables = true;
        Element converted = Htmlprocessing.convert_tags(Etree.html_fromstring(doc), options);
        assertEquals(List.of("para", "cell"), converted.iter("ref").list().stream().map(ref -> ref.text).toList());
        options.tables = false;
        converted = Htmlprocessing.convert_tags(Etree.html_fromstring(doc), options);
        assertEquals(List.of("para"), converted.iter("ref").list().stream().map(ref -> ref.text).toList());
    }

    // ------------------------------------------------------------------ known deviations
    // Assertions that hold in Python only through libxml2's HTML parser, the full htmldate
    // package or charset_normalizer. Excluded by default, run with -Dsurefire.excludedGroups=

    /** charset_normalizer finds gb18030 in 7 characters; juniversalchardet needs a longer sample. */
    @Test
    @Tag("known-deviation")
    void test_input_short_gb18030() {
        byte[] teststring = "高山云雾出好茶".getBytes(java.nio.charset.Charset.forName("gb18030"));
        assertTrue(Utils.detect_encoding(teststring).contains("gb18030"));
    }

    /** Free-text dates need the full htmldate package. */
    @Test
    @Tag("known-deviation")
    void test_extract_with_metadata_free_text_date() {
        Element my_document = Etree.html_fromstring("<html>\n        <head><title>title</title></head>\n        <body>\n        <article>\n        <div>May 24, 2021</div>\n        <p>AAA, <p>BBB</p>, CCC.</p>\n        </article>\n        </body>\n        </html>\n    ");
        Document parsed_doc = Core.extract_with_metadata(my_document, Core.kw().output_format("txt").include_formatting(true).fast(true).url("http://aa.bb/cc.html"));
        assertEquals("2021-05-24", parsed_doc.date);
    }

    /** Free-text dates need the full htmldate package. */
    @Test
    @Tag("known-deviation")
    void test_extraction_options_free_text_date() {
        String my_html = "<html><head/><body>" + "<p>ABC def ghi jkl.</p>".repeat(1000) + "<p>Posted on 1st Dec 2019<.</p></body></html>";
        assertNotNull(Core.bare_extraction(my_html, Core.kw().config(ZERO_CONFIG).with_metadata(true)).date);
    }

    /** libxml2 nests <p> in <p>, the HTML5 tree builder closes the first paragraph. */
    @Test
    @Tag("known-deviation")
    void test_tei_nested_paragraphs() {
        Element tree = Etree.html_fromstring("<html>\n        <body>\n            <p><p>\n              <span><p>content</p></span>\n            </p></p>\n        </body>\n        </html>");
        tree = Xml.remove_empty_elements(Xml.strip_double_tags(tree));
        String result = Utils.sanitize(Etree.tostring(tree, "unicode")).replace("\n", "");
        assertEquals("<html><body><p><span>content</span></p></body></html>", result);
        tree = Etree.html_fromstring("<html>\n        <body>\n            <div>\n                <p>\n                  <list>\n                    <item>\n                        <p>text</p>\n                    </item>\n                  </list>\n                </p>\n                <p>\n                    <table>\n                      <row>\n                        <cell>\n                          <p>text1</p>\n                         </cell>\n                      </row>\n                    </table>\n                </p>\n                <p>\n                    <note>\n                      <p>text2</p>\n                    </note>\n                </p>\n                <p>\n                    <quote>\n                        <p>text3</p>\n                    </quote>\n                </p>\n                <p>\n                    <figure>\n                        <p>text4</p>\n                    </figure>\n                </p>\n            </div>\n        </body>\n    </html>");
        Xml.strip_double_tags(tree);
        for (String parent_tag : List.of("item", "cell", "quote", "note", "figure")) {
            assertNotNull(tree.find(".//" + parent_tag + "/p"));
        }
    }

    static java.util.stream.Stream<Arguments> test_handle_table_html5_params() {
        return java.util.stream.Stream.of(
            Arguments.of("<table><tr><td>a</td></tr><td>b</td><tr><td>c</td></tr></table>", false, "<row><cell>a</cell><cell>b</cell></row><row><cell>c</cell></row>"),
            Arguments.of("<table><tr><p>text</p></tr><tr><td>cell</td></tr></table>", false, "<row><cell>cell</cell></row>"),
            Arguments.of("<table><tr><td><div><td>inner</td></div></td></tr></table>", false, "<row><cell><cell>inner</cell></cell></row>"));
    }

    /** Broken tables as libxml2 parses them: the HTML5 tree builder opens rows and moves content out of the table. */
    @ParameterizedTest
    @MethodSource("test_handle_table_html5_params")
    @Tag("known-deviation")
    void test_handle_table_html5(String table, boolean recall, String expected) {
        assertEquals("<table>" + expected + "</table>", Etree.tostring(_handle_table(table, recall), "unicode"));
    }

    /** On a forum thread the posts are recovered by jusText. */
    @Test
    void test_recall_escalation_justext_forum_posts_kept() {
        test_recall_escalation_justext_comment_scoping(_DFP_JSONLD, true, false);
    }

    /** <div> replies are not captured as comments, they come back through jusText. */
    @Test
    void test_recall_escalation_no_comment_doubling_div() {
        test_recall_escalation_no_comment_doubling("div");
    }
}
