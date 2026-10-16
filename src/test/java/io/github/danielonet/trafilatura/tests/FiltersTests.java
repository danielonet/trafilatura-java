// Java port of tests/filters_tests.py
package io.github.danielonet.trafilatura.tests;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.danielonet.trafilatura.compat.ConfigParser;
import io.github.danielonet.trafilatura.lxml.Element;
import io.github.danielonet.trafilatura.lxml.Etree;
import io.github.danielonet.trafilatura.Core;
import io.github.danielonet.trafilatura.Settings.Document;
import io.github.danielonet.trafilatura.Settings.Extractor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.danielonet.trafilatura.Core.extract;
import static io.github.danielonet.trafilatura.Settings.use_config;
import static io.github.danielonet.trafilatura.Utils.LANGID_FLAG;
import static io.github.danielonet.trafilatura.Utils.check_html_lang;
import static io.github.danielonet.trafilatura.Utils.language_filter;

/** Unit tests for the trafilatura's text filters. */
class FiltersTests {
    static final ConfigParser ZERO_CONFIG = use_config();

    static {
        ZERO_CONFIG.set("DEFAULT", "MIN_OUTPUT_SIZE", "0");
        ZERO_CONFIG.set("DEFAULT", "MIN_EXTRACTED_SIZE", "0");
    }

    static final Document SAMPLE_META = new Document();

    static Element html(String s) {
        return Etree.html_fromstring(s);
    }

    /** Test content filtering. */
    @Test
    void test_filters() {
        if (LANGID_FLAG) {
            // main text
            assertFalse(language_filter("Hier ist ein Text auf Deutsch", "", "de", SAMPLE_META).first());
            assertTrue(language_filter("Hier ist ein Text auf Deutsch", "", "en", SAMPLE_META).first());
            // comments
            assertFalse(language_filter("Hier ist ein Text.", "Die Kommentare sind aber etwas länger.", "de", SAMPLE_META).first());
            // lang detection on the content
            Element doc = html("<html><body><article><p>How many ages hence/Shall this our lofty scene be acted over,/In states unborn and accents yet unknown!</p></article></body></html>");
            assertNull(extract(doc, Core.kw().config(ZERO_CONFIG).target_language("de")));
            assertNotNull(extract(doc, Core.kw().config(ZERO_CONFIG).target_language("en")));
        } else {
            // no detection
            assertFalse(language_filter("Hier ist ein Text.", "", "en", SAMPLE_META).first());
        }
        // test URL blacklist (non-empty body so the None is caused by the blacklist, not missing content)
        String page = "<html><head><link rel=\"canonical\" href=\"https://example.org\"/></head><body><article><p>Real body text here.</p></article></body></html>";
        assertNotNull(extract(page, Core.kw().output_format("xml").config(ZERO_CONFIG)));
        assertNull(extract(page, Core.kw().output_format("xml").url_blacklist(Set.of("https://example.org")).config(ZERO_CONFIG)));

        // recursion limit
        Extractor options = new Extractor();
        options.max_tree_size = 500;
        String my_p = "<p>abc</p>";
        Element doc = html("<html><body>" + my_p.repeat(50) + "</body></html>");
        assertNotNull(extract(doc, Core.kw().options(options)));
        doc = html("<html><body>" + my_p.repeat(501) + "</body></html>");
        assertNull(extract(doc, Core.kw().options(options)));

        options.formatting = true;
        my_p = "<p><hi rend=\"#i\">abc</hi></p>";
        doc = html("<html><body>" + my_p.repeat(501) + "</body></html>");
        assertNull(extract(doc, Core.kw().options(options)));
        doc = html("<html><body>" + my_p.repeat(499) + "</body></html>");
        assertNotNull(extract(doc, Core.kw().options(options)));

        // MAX_TREE_SIZE is read from the config (empty value disables the limit)
        assertNull(new Extractor().max_tree_size);
        ConfigParser config = use_config();
        config.set("DEFAULT", "MAX_TREE_SIZE", "500");
        assertEquals(500, new Extractor(Extractor.kw().config(config)).max_tree_size);
        my_p = "<p>abc</p>";
        assertNull(extract(html("<html><body>" + my_p.repeat(501) + "</body></html>"), Core.kw().config(config)));
        assertNotNull(extract(html("<html><body>" + my_p.repeat(50) + "</body></html>"), Core.kw().config(config)));

        // HTML lang filter
        // no lang
        assertTrue(check_html_lang(html("<html><body></body></html>"), "en"));
        // text + lang
        my_p = "<p>In sleep a king, but waking no such matter.</p>";
        if (LANGID_FLAG) {
            assertNotNull(extract(html("<html lang=\"en-US\"><body>" + my_p.repeat(50) + "</body></html>"),
                    Core.kw().fast(true).target_language("en")));
            assertNull(extract(html("<html lang=\"en-US\"><body>" + my_p.repeat(50) + "</body></html>"),
                    Core.kw().fast(true).target_language("de")));
            // caught
            assertNull(extract(html("<html lang=\"de-DE\"><body>" + my_p.repeat(50) + "</body></html>"),
                    Core.kw().fast(false).target_language("de")));
        } else {
            // not caught, HTML tag used
            assertNotNull(extract(html("<html lang=\"de-DE\"><body>" + my_p.repeat(50) + "</body></html>"),
                    Core.kw().fast(false).target_language("de")));
        }
        assertTrue(check_html_lang(html("<html lang=\"de_DE, en_US\"><body></body></html>"), "de"));
        assertTrue(check_html_lang(html("<html lang=\"de_DE, en_US\"><body></body></html>"), "en"));
        assertTrue(check_html_lang(html("<html lang=\"de_DE, en_US\"><body></body></html>"), "de", true));
        assertTrue(check_html_lang(html("<html lang=\"de_DE, en_US\"><body></body></html>"), "en", true));
        assertTrue(check_html_lang(
                html("<html><head><meta http-equiv=\"content-language\" content=\"en\"></head><body></body></html>"), "en"));
        assertFalse(check_html_lang(
                html("<html><head><meta http-equiv=\"content-language\" content=\"en\"></head><body></body></html>"), "de"));
        assertTrue(check_html_lang(
                html("<html><head><meta http-equiv=\"content-language\" content=\"DE\"></head><body></body></html>"), "de"));
        // html lang attribute superseded by og:locale
        assertTrue(check_html_lang(
                html("<html lang=\"en-US\"><head><meta property=\"og:locale\" content=\"de_DE\" /></head><body></body></html>"),
                "de"));
        assertFalse(check_html_lang(
                html("<html lang=\"en-US\"><head><meta property=\"og:locale\" content=\"de_DE\" /></head><body></body></html>"),
                "en"));
        assertFalse(check_html_lang(html("<html lang=\"en\"><body></body></html>"), "it", true));
        assertTrue(check_html_lang(html("<html lang=\"en\"><body></body></html>"), "it", false));
        assertTrue(check_html_lang(
                html("<html lang=\"en-US\"><head><meta property=\"og:locale\" content=\"de_DE\" /></head><body></body></html>"),
                "de", false));
        assertTrue(check_html_lang(
                html("<html lang=\"en-US\"><head><meta property=\"og:locale\" content=\"de_DE\" /></head><body></body></html>"),
                "de", true));
    }

    /** test xpath pruning (parameter in extract and bare_extraction). */
    @Test
    void test_prune_xpath() {
        // create example html
        Supplier<Element> doc = () -> html("<html><body>" + "<p>abc</p>".repeat(50) + "</body></html>");
        Supplier<Element> doc2 = () -> html("<html><body>" + "<h1>ABC</h1>" + "<p>abc</p>".repeat(50) + "</body></html>");
        Supplier<Element> doc3 = () -> html("<html><body>" + "<h1>ABC</h1>" + "<h2>42</h2>" + "<p>abc</p>".repeat(50)
                + "</body></html>");
        Supplier<Element> doc4 = () -> html("<html><body>" + "<p>abc</p>" + "</body></html><!-- comment -->");

        // test xpath pruning
        assertEquals("", extract(doc.get(), Core.kw().prune_xpath("//p").config(ZERO_CONFIG)));
        assertEquals("ABC", extract(doc2.get(), Core.kw().prune_xpath("//p").config(ZERO_CONFIG)));
        assertEquals("", extract(doc2.get(), Core.kw().prune_xpath(List.of("//p", "//h1")).config(ZERO_CONFIG)));
        assertEquals("42", extract(doc3.get(), Core.kw().prune_xpath(List.of("//p", "//h1")).config(ZERO_CONFIG)));
        assertEquals("abc", extract(doc4.get(), Core.kw().prune_xpath(List.of("//comment()"))));
        // sanity check
        assertNotEquals("", extract(doc.get(), Core.kw().config(ZERO_CONFIG)));
        assertNotEquals("", extract(doc2.get(), Core.kw().config(ZERO_CONFIG)));
        assertNotEquals("", extract(doc3.get(), Core.kw().config(ZERO_CONFIG)));
        assertNotEquals("", extract(doc4.get(), Core.kw().config(ZERO_CONFIG)));
    }
}
