// Java port of tests/metadata_tests.py
package io.github.danielonet.trafilatura.tests;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.danielonet.trafilatura.compat.ConfigParser;
import io.github.danielonet.trafilatura.lxml.XPath;
import io.github.danielonet.trafilatura.Settings;
import io.github.danielonet.trafilatura.Settings.Extractor;

import io.github.danielonet.trafilatura.compat.Json;
import io.github.danielonet.trafilatura.compat.Py;
import io.github.danielonet.trafilatura.compat.Triple;
import io.github.danielonet.trafilatura.lxml.Element;
import io.github.danielonet.trafilatura.lxml.Etree;
import io.github.danielonet.trafilatura.Baseline;
import io.github.danielonet.trafilatura.Core;
import io.github.danielonet.trafilatura.Deduplication;
import io.github.danielonet.trafilatura.Htmlprocessing;
import io.github.danielonet.trafilatura.JsonMetadata;
import io.github.danielonet.trafilatura.Metadata;
import io.github.danielonet.trafilatura.ReadabilityLxml;
import io.github.danielonet.trafilatura.Settings.Document;
import io.github.danielonet.trafilatura.Utils;
import io.github.danielonet.trafilatura.Xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the metadata parts. */
class MetadataTests {
    @Test
    void test_tags_with_complete_opengraph_metadata() {
        String document = "<html><head>\n        <meta property=\"og:title\" content=\"Example article\"/>\n        <meta property=\"og:author\" content=\"Jane Smith\"/>\n        <meta property=\"og:url\" content=\"https://example.org/post\"/>\n        <meta property=\"og:description\" content=\"Example description\"/>\n        <meta property=\"og:site_name\" content=\"Example News\"/>\n        <meta property=\"og:image\" content=\"https://example.org/image.jpg\"/>\n        <meta name=\"keywords\" content=\"science, research\"/>\n        <meta property=\"article:tag\" content=\"astronomy\"/>\n        <meta name=\"twitter:title\" content=\"Alternative title\"/>\n        </head><body><article>Example article content.</article></body></html>";
        Document metadata = Metadata.extract_metadata(document, null, null, true, null);
        assertEquals(List.of("science, research", "astronomy"), metadata.tags);
        assertEquals("Example article", metadata.title);
        assertEquals("Jane Smith", metadata.author);
        assertEquals("https://example.org/image.jpg", metadata.image);
    }

    /** Test the extraction of titles */
    @Test
    void test_titles() {
        List<Object> tests = T.list(T.t("<html><body><h3 class=\"title\">T</h3><h3 id=\"title\"></h3></body></html>", null), List.of("<html><head><title>Test Title</title><meta property=\"og:title\" content=\" \" /></head><body><h1>First</h1></body></html>", "First"), List.of("<html><head><title>Test Title</title><meta name=\"title\" content=\" \" /></head><body><h1>First</h1></body></html>", "First"), List.of("<html><head><title>Test Title</title></head><body></body></html>", "Test Title"), List.of("<html><body><h1>First</h1><h1>Second</h1></body></html>", "First"), List.of("<html><body><h1>   </h1><div class=\"post-title\">Test Title</div></body></html>", "Test Title"), List.of("<html><body><h2 class=\"block-title\">Main menu</h2><h1 class=\"article-title\">Test Title</h1></body></html>", "Test Title"), List.of("<html><body><h2>First</h2><h1>Second</h1></body></html>", "Second"), List.of("<html><body><h2>First</h2><h2>Second</h2></body></html>", "First"), T.t("<html><body><title></title></body></html>", null), List.of("<html><head><title>Head Title</title></head><body><h1>First</h1><h1>Second</h1></body></html>", "Head Title"), List.of("<html><head><title>example.com</title></head><body><h1>First</h1><h1>Second</h1></body></html>", "First"), List.of("<html><body><h1>   </h1><h1>Real Title</h1></body></html>", "Real Title"), T.t("<html><body><h1>   </h1><h1>   </h1></body></html>", null));
        for (Object _r : tests) {
            List<?> _row = (List<?>) _r;
            String doc = (String) _row.get(0);
            String expected_title = (String) _row.get(1);
            Document metadata = Metadata.extract_metadata(doc, null, null, true, null);
            assertEquals(expected_title, metadata.title);
        }
        Document metadata = Metadata.extract_metadata("<html><body><script type=\"application/ld+json\">{\"@context\":\"https:\\/\\/schema.org\",\"@type\":\"Article\",\"name\":\"Semantic satiation\",\"url\":\"https:\\/\\/en.wikipedia.org\\/wiki\\/Semantic_satiation\",\"sameAs\":\"http:\\/\\/www.wikidata.org\\/entity\\/Q226007\",\"mainEntity\":\"http:\\/\\/www.wikidata.org\\/entity\\/Q226007\",\"author\":{\"@type\":\"Organization\",\"name\":\"Contributors to Wikimedia projects\"},\"publisher\":{\"@type\":\"Organization\",\"name\":\"Wikimedia Foundation, Inc.\",\"logo\":{\"@type\":\"ImageObject\",\"url\":\"https:\\/\\/www.wikimedia.org\\/static\\/images\\/wmf-hor-googpub.png\"}},\"datePublished\":\"2006-07-12T09:27:14Z\",\"dateModified\":\"2020-08-31T23:55:26Z\",\"headline\":\"psychological phenomenon in which repetition causes a word to temporarily lose meaning for the listener\"}</script>\n<script>(RLQ=window.RLQ||[]).push(function(){mw.config.set({\"wgBackendResponseTime\":112,\"wgHostname\":\"mw2373\"});});</script></html>", null, null, true, null);
        assertEquals("Semantic satiation", metadata.title);
        metadata = Metadata.extract_metadata("<html><head><title> - Home</title></head><body/></html>", null, null, true, null);
        assertEquals("- Home", metadata.title);
        metadata = Metadata.extract_metadata("<html><head><title>My Title » My Website</title></head><body/></html>", null, null, true, null);
        assertEquals("My Title", metadata.title);
    }

    /** Filtering of author names against a blacklist */
    @Test
    void test_author_blacklist() {
        Document metadata = Metadata.extract_metadata("<html><head><meta itemprop=\"author\" content=\"Jenny Smith\"/></head><body></body></html>", null, null, true, Set.of("Jenny Smith"));
        assertNull(metadata.author);
        metadata = Metadata.extract_metadata("<html><body><script type=\"application/ld+json\">{\"@context\":\"https://schema.org\",\"@type\":\"Article\",\"author\":{\"name\":\"Jane Doe\"}}</script></body></html>", null, null, true, Set.of("Jane Doe"));
        assertNull(metadata.author);
        metadata = Metadata.extract_metadata("<html><body><a class=\"author\">Jane Doe</a></body></html>", null, null, true, Set.of("Jane Doe"));
        assertNull(metadata.author);
        assertNull(Metadata.extract_metadata("<html><head><meta name=\"author\" content=\"Cher\"/></head><body/></html>", null, null, true, null).author);
        Set<String> blacklist = Set.of("A", "b");
        assertEquals("c; d", Metadata.check_authors("a; B; c; d", blacklist));
        assertEquals("c; d", Metadata.check_authors("a;B;c;d", blacklist));
        assertTrue(new Extractor(Extractor.kw().config(Settings.use_config()).author_blacklist(Set.of("X"))).with_metadata);
        assertFalse(new Extractor(Extractor.kw().config(Settings.use_config())).with_metadata);
    }

    /** Author extraction from <head> meta tags */
    @Test
    void test_author_from_meta() {
        String begin = "<html><head>";
        String end = "</head><body></body></html>";
        List<Object> cases = T.list(List.of("" + begin + "<meta itemprop=\"author\" content=\"Jenny Smith\"/>" + end, "Jenny Smith"), List.of("" + begin + "<meta itemprop=\"author\" content=\"Jenny Smith\"/><meta itemprop=\"author\" content=\"John Smith\"/>" + end, "Jenny Smith; John Smith"), List.of("" + begin + "<meta itemprop=\"author\" content=\"Jenny Smith und John Smith\"/>" + end, "Jenny Smith; John Smith"), List.of("" + begin + "<meta name=\"author\" content=\"Jenny Smith\"/><meta name=\"author\" content=\"John Smith\"/>" + end, "Jenny Smith; John Smith"), List.of("" + begin + "<meta name=\"author\" content=\"Jenny Smith and John Smith\"/>" + end, "Jenny Smith; John Smith"), List.of("" + begin + "<meta name=\"author\" content=\"Jenny Smith\"/>" + end, "Jenny Smith"), List.of("" + begin + "<meta name=\"author\" content=\"Hank O&#39;Hop\"/>" + end, "Hank O'Hop"), List.of("" + begin + "<meta name=\"author\" content=\"Jenny Smith ❤️\"/>" + end, "Jenny Smith"), List.of("" + begin + "<meta name=\"citation_author\" content=\"Jenny Smith and John Smith\"/>" + end, "Jenny Smith; John Smith"), List.of("" + begin + "<meta property=\"author\" content=\"Jenny Smith\"/><meta property=\"author\" content=\"John Smith\"/>" + end, "Jenny Smith; John Smith"), List.of("" + begin + "<meta itemprop=\"author\" content=\"Jenny Smith and John Smith\"/>" + end, "Jenny Smith; John Smith"), List.of("" + begin + "<meta name=\"article:author\" content=\"Jenny Smith\"/>" + end, "Jenny Smith"));
        for (Object _r : cases) {
            List<?> _row = (List<?>) _r;
            String doc = (String) _row.get(0);
            String expected = (String) _row.get(1);
            assertEquals(expected, Metadata.extract_metadata(doc, null, null, true, null).author);
        }
    }

    /** Author extraction from body markup */
    @Test
    void test_author_from_markup() {
        String begin = "<html><body>";
        String end = "</body></html>";
        List<Object> cases = T.list(List.of("" + begin + "<a href=\"\" rel=\"author\">Jenny Smith</a>" + end, "Jenny Smith"), List.of("" + begin + "<a href=\"\" rel=\"author\">Jenny \"The Author\" Smith</a>" + end, "Jenny Smith"), List.of("" + begin + "<span class=\"author\">Jenny Smith</span>" + end, "Jenny Smith"), List.of("" + begin + "<h4 class=\"author\">Jenny Smith</h4>" + end, "Jenny Smith"), List.of("" + begin + "<h4 class=\"author\">Jenny Smith — Trafilatura</h4>" + end, "Jenny Smith"), List.of("" + begin + "<span class=\"wrapper--detail__writer\">Jenny Smith</span>" + end, "Jenny Smith"), List.of("" + begin + "<span id=\"author-name\">Jenny Smith</span>" + end, "Jenny Smith"), T.t("" + begin + "<figure data-component=\"Figure\"><div class=\"author\">Jenny Smith</div></figure>" + end, null), T.t("" + begin + "<div class=\"sidebar\"><div class=\"author\">Jenny Smith</div></figure>" + end, null), T.t("" + begin + "<div class=\"quote\"><p>My quote here</p><p class=\"quote-author\"><span>—</span> Jenny Smith</p></div>" + end, null), List.of("" + begin + "<span class=\"author\">Jenny Smith and John Smith</span>" + end, "Jenny Smith; John Smith"), List.of("" + begin + "<a class=\"author\">Jenny Smith</a>" + end, "Jenny Smith"), List.of("" + begin + "<a class=\"author\">Jenny Smith <div class=\"title\">Editor</div></a>" + end, "Jenny Smith"), List.of("" + begin + "<a class=\"author\">Jenny Smith from Trafilatura</a>" + end, "Jenny Smith"), List.of("" + begin + "<meta itemprop=\"author\" content=\"Fake Author\"/><a class=\"author\">Jenny Smith from Trafilatura</a>" + end, "Jenny Smith"), List.of("" + begin + "<a class=\"username\">Jenny Smith</a>" + end, "Jenny Smith"), List.of("" + begin + "<div class=\"submitted-by\"><a>Jenny Smith</a></div>" + end, "Jenny Smith"), List.of("" + begin + "<div class=\"byline-content\"><div class=\"byline\"><a>Jenny Smith</a></div><time>July 12, 2021 08:05</time></div>" + end, "Jenny Smith"), List.of("" + begin + "<h3 itemprop=\"author\">Jenny Smith</h3>" + end, "Jenny Smith"), List.of("" + begin + "<div class=\"article-meta article-meta-byline article-meta-with-photo article-meta-author-and-reviewer\" itemprop=\"author\" itemscope=\"\" itemtype=\"http://schema.org/Person\"><span class=\"article-meta-photo-wrap\"><img src=\"\" alt=\"Jenny Smith\" itemprop=\"image\" class=\"article-meta-photo\"></span><span class=\"article-meta-contents\"><span class=\"article-meta-author\">By <a href=\"\" itemprop=\"url\"><span itemprop=\"name\">Jenny Smith</span></a></span><span class=\"article-meta-date\">May 18 2022</span><span class=\"article-meta-reviewer\">Reviewed by <a href=\"\">Robert Smith</a></span></span></div>" + end, "Jenny Smith"), List.of("" + begin + "<div data-component=\"Byline\">Jenny Smith</div>" + end, "Jenny Smith"), List.of("" + begin + "<span id=\"author\">Jenny Smith</span>" + end, "Jenny Smith"), List.of("" + begin + "<span id=\"author\">Jenny Smith – The Moon</span>" + end, "Jenny Smith"), List.of("" + begin + "<span id=\"author\">Jenny_Smith</span>" + end, "Jenny Smith"), List.of("" + begin + "<span itemprop=\"author name\">Shannon Deery, Mitch Clarke, Susie O’Brien, Laura Placella, Kara Irving, Jordy Atkinson, Suzan Delibasic</span>" + end, "Shannon Deery; Mitch Clarke; Susie O’Brien; Laura Placella; Kara Irving; Jordy Atkinson; Suzan Delibasic"), List.of("" + begin + "<address class=\"author\">Jenny Smith</address>" + end, "Jenny Smith"), List.of("" + begin + "<author>Jenny Smith</author>" + end, "Jenny Smith"), List.of("<html><head><meta data-rh=\"true\" property=\"og:author\" content=\"By &lt;a href=&quot;/profiles/amir-vera&quot;&gt;Amir Vera&lt;/a&gt;, Seán Federico O&#x27;Murchú, &lt;a href=&quot;/profiles/tara-subramaniam&quot;&gt;Tara Subramaniam&lt;/a&gt; and Adam Renton, CNN\"/></head><body>" + end, "Amir Vera; Seán Federico O'Murchú; Tara Subramaniam; Adam Renton; CNN"), List.of("" + begin + "<div class=\"author\"><span class=\"profile__name\"> Jenny Smith </span> <a href=\"https://twitter.com/jenny_smith\" class=\"profile__social\" target=\"_blank\"> @jenny_smith </a> <span class=\"profile__extra lg:hidden\"> 11:57AM </span> </div>" + end, "Jenny Smith"), List.of("" + begin + "<p class=\"author-section byline-plain\">By <a class=\"author\" rel=\"nofollow\">Jenny Smith For Daily Mail Australia</a></p>" + end, "Jenny Smith"), List.of("" + begin + "<div class=\"o-Attribution__a-Author\"><span class=\"o-Attribution__a-Author--Label\">By:</span><span class=\"o-Attribution__a-Author--Prefix\"><span class=\"o-Attribution__a-Name\"><a href=\"//web.archive.org/web/20210707074846/https://www.discovery.com/profiles/ian-shive\">Ian Shive</a></span></span></div>" + end, "Ian Shive"), List.of("" + begin + "<div class=\"ArticlePage-authors\"><div class=\"ArticlePage-authorName\" itemprop=\"name\"><span class=\"ArticlePage-authorBy\">By&nbsp;</span><a aria-label=\"Ben Coxworth\" href=\"https://newatlas.com/author/ben-coxworth/\"><span>Ben Coxworth</span></a></div></div>" + end, "Ben Coxworth"), List.of("" + begin + "<div><strong><a class=\"d1dba0c3091a3c30ebd6\" data-testid=\"AuthorURL\" href=\"/by/p535y1\">AUTHOR NAME</a></strong></div>" + end, "AUTHOR NAME"));
        for (Object _r : cases) {
            List<?> _row = (List<?>) _r;
            String doc = (String) _row.get(0);
            String expected = (String) _row.get(1);
            assertEquals(expected, Metadata.extract_metadata(doc, null, null, true, null).author);
        }
    }

    /** Test URL extraction */
    @Test
    void test_url() {
        String expected = "https://example.org";
        List<Object> cases = T.list(T.t("<html><head><meta property=\"og:url\" content=\"https://example.org\"/></head><body></body></html>", null), T.t("<html><head><link rel=\"canonical\" href=\"https://example.org\"/></head><body></body></html>", null), T.t("<html><head><meta name=\"twitter:url\" content=\"https://example.org\"/></head><body></body></html>", null), T.t("<html><head><link rel=\"alternate\" hreflang=\"x-default\" href=\"https://example.org\"/></head><body></body></html>", null), List.of("<html><head><link rel=\"canonical\" href=\"/article/medical-record\"/></head><body></body></html>", "https://example.org"), T.t("<html><head><base href=\"https://example.org\" target=\"_blank\"/></head><body></body></html>", null));
        for (Object _r : cases) {
            List<?> _row = (List<?>) _r;
            String doc = (String) _row.get(0);
            String default_url = (String) _row.get(1);
            assertEquals(expected, Metadata.extract_metadata(doc, default_url, null, true, null).url);
        }
        assertEquals("https://example.org/article/medical-record", Metadata.extract_url(Etree.html_fromstring("<html><head><link rel=\"canonical\" href=\"/article/medical-record\"/><meta name=\"twitter:url\" content=\"https://example.org\"/></head><body></body></html>")));
        assertEquals("https://example.org/p", Metadata.extract_url(Etree.html_fromstring("<html><head><link rel=\"canonical\" href=\"/p\"/><meta property=\"og:url\" content=\"https://example.org\"/></head><body></body></html>")));
        assertEquals("https://example.org/p", Metadata.extract_url(Etree.html_fromstring("<html><head><link rel=\"canonical\" href=\"/p\"/><meta property=\"og:url\" content=\"//[::1\"/><meta name=\"twitter:url\" content=\"https://example.org\"/></head><body></body></html>")));
        for (String doc : List.of("<html><head><link rel=\"canonical\" href=\"//example.org/p\"/></head><body></body></html>", "<html><head><link rel=\"canonical\" href=\"//example.org/p\"/><meta property=\"og:image\" content=\"https://cdn.example.net/i.png\"/></head><body></body></html>")) {
            assertEquals("https://example.org/p", Metadata.extract_url(Etree.html_fromstring(doc)));
        }
    }

    /** Test the extraction of descriptions */
    @Test
    void test_description() {
        Document metadata = Metadata.extract_metadata("<html><head><meta itemprop=\"description\" content=\"Description\"/></head><body></body></html>", null, null, true, null);
        assertEquals("Description", metadata.description);
        metadata = Metadata.extract_metadata("<html><head><meta property=\"og:description\" content=\"&amp;#13; A Northern Territory action plan, which includes plans to support development and employment on Aboriginal land, has received an update. &amp;#13...\" /></head><body></body></html>", null, null, true, null);
        assertEquals("A Northern Territory action plan, which includes plans to support development and employment on Aboriginal land, has received an update. ...", metadata.description);
    }

    /** Simple tests for date extraction (most of the tests are carried out externally for htmldate module) */
    @Test
    void test_dates() {
        List<Object> tests = T.list(T.list("<html><head><meta property=\"og:published_time\" content=\"2017-09-01\"/></head><body></body></html>", "2017-09-01", false), T.list("<html><head><meta property=\"og:url\" content=\"https://example.org/2017/09/01/content.html\"/></head><body></body></html>", "2017-09-01", false), T.list("<html><head><meta property=\"og:url\" content=\"https://example.org/2017/09/01/content.html\"/></head><body></body></html>", "2017-09-01", false));
        for (Object _r : tests) {
            List<?> _row = (List<?>) _r;
            String doc = (String) _row.get(0);
            String expected = (String) _row.get(1);
            Boolean extensive = (Boolean) _row.get(2);
            Document metadata = Metadata.extract_metadata(doc, null, null, extensive, null);
            assertEquals(expected, metadata.date);
        }
    }

    /** test_dates, free-text cases: they need htmldate's text heuristics, not part of the stand-in. */
    @Test
    @Tag("known-deviation")
    void test_dates_text_heuristics() {
        for (boolean extensive : List.of(true, false)) {
            Document metadata = Metadata.extract_metadata("<html><body><p>Veröffentlicht am 1.9.17</p></body></html>", null, null, extensive, null);
            assertEquals("2017-09-01", metadata.date);
        }
    }

    /** Test extraction of site name */
    @Test
    void test_sitename() {
        List<Object> tests = T.list(T.t("<html><head><meta name=\"article:publisher\" content=\"@\"/></head><body/></html>", null), List.of("<html><head><meta name=\"article:publisher\" content=\"The Newspaper\"/></head><body/></html>", "The Newspaper"), List.of("<html><head><meta property=\"article:publisher\" content=\"The Newspaper\"/></head><body/></html>", "The Newspaper"), List.of("<html><head><title>sitemaps.org - Home</title></head><body/></html>", "sitemaps.org"), List.of("<html><head><meta property=\"og:url\" content=\"https://www.example.org/article\"/></head><body/></html>", "example.org"), List.of("<html><head><meta name=\"application-name\" content=\"MySite\"/></head><body/></html>", "MySite"), List.of("<html><head><meta name=\"article:publisher\" content=\"newspaper\"/></head><body/></html>", "Newspaper"));
        for (Object _r : tests) {
            List<?> _row = (List<?>) _r;
            String doc = (String) _row.get(0);
            String expected = (String) _row.get(1);
            Document metadata = Metadata.extract_metadata(doc, null, null, true, null);
            assertEquals(expected, metadata.sitename);
        }
    }

    /** Test extraction out of meta-elements */
    @Test
    void test_meta() {
        Element doc = Etree.html_fromstring("<html><p class='test'>a</p><p class='other'>b</p><p type='this'>cde</p></html>");
        assertNull(Metadata.extract_metainfo(doc, List.of(new XPath(".//p[@class]"))));
        assertEquals("cde", Metadata.extract_metainfo(doc, List.of(new XPath(".//p[@type]"))));
        Document metadata = Metadata.extract_metadata("<html><head><meta property=\"og:title\" content=\"Open Graph Title\"/><meta property=\"og:author\" content=\"Jenny Smith\"/><meta property=\"og:description\" content=\"This is an Open Graph description\"/><meta property=\"og:site_name\" content=\"My first site\"/><meta property=\"og:url\" content=\"https://example.org/test\"/><meta property=\"og:type\" content=\"Open Graph Type\"/></head><body><a rel=\"license\" href=\"https://creativecommons.org/\">Creative Commons</a></body></html>", null, null, true, null);
        assertEquals("Open Graph Type", metadata.pagetype);
        assertEquals("Open Graph Title", metadata.title);
        assertEquals("Jenny Smith", metadata.author);
        assertEquals("This is an Open Graph description", metadata.description);
        assertEquals("My first site", metadata.sitename);
        assertEquals("https://example.org/test", metadata.url);
        assertEquals("Creative Commons", metadata.license);
        metadata = Metadata.extract_metadata("<html><head><meta property=\"og:title\" content=\"T\"/><meta property=\"og:author\" content=\"A B\"/><meta property=\"og:url\" content=\"https://e.org\"/><meta property=\"og:description\" content=\"D\"/><meta property=\"og:site_name\" content=\"S\"/><meta property=\"og:image\" content=\"https://e.org/i.jpg\"/></head><body/></html>", null, null, true, null);
        assertEquals("T", metadata.title);
        assertEquals("https://e.org/i.jpg", metadata.image);
        metadata = Metadata.extract_metadata("<html><head><meta name=\"dc.title\" content=\"Open Graph Title\"/><meta name=\"dc.creator\" content=\"Jenny Smith\"/><meta name=\"dc.description\" content=\"This is an Open Graph description\"/></head><body></body></html>", null, null, true, null);
        assertEquals("Open Graph Title", metadata.title);
        assertEquals("Jenny Smith", metadata.author);
        assertEquals("This is an Open Graph description", metadata.description);
        metadata = Metadata.extract_metadata("<html><head><meta itemprop=\"headline\" content=\"Title\"/></head><body></body></html>", null, null, true, null);
        assertEquals("Title", metadata.title);
        metadata = Metadata.extract_metadata("", null, null, true, null);
        for (String a : Document.__slots__) {
            if (!a.equals("body") && !a.equals("commentsbody")) {
                assertNull(metadata.getattr(a), a);
            }
        }
        metadata = Metadata.extract_metadata("<html><title></title></html>", null, null, true, null);
        assertNull(metadata.sitename);
        metadata = Metadata.extract_metadata("<html><head><title>" + "AAA".repeat(10000) + "</title></head></html>", null, null, true, null);
        assertTrue(metadata.title.endsWith("…"));
        assertEquals(10000, metadata.title.length());
        assertNull(Metadata.extract_metadata("<html><head><meta otherkey=\"example\" content=\"Unknown text\"/></head></html>", null, null, true, null).title);
        assertNull(Metadata.extract_metadata("<html><head><title></title><title></title><title></title></head></html>", null, null, true, null).title);
        assertNull(Metadata.extract_metadata("<html><body><script type=\"application/ld+json\"></script></body></html>", null, null, true, null).title);
    }

    /** Test extraction of categories and tags */
    @Test
    void test_catstags() {
        assertEquals("", Metadata.normalize_tags("   "));
        assertEquals("1 & 2", Metadata.normalize_tags(" 1 &amp; 2 "));
        List<Object> cases = T.list(T.list("<html><body><p class=\"entry-categories\"><a href=\"https://example.org/category/cat1/\">Cat1</a>, <a href=\"https://example.org/category/cat2/\">Cat2</a></p></body></html>", "categories", List.of("Cat1", "Cat2")), T.list("<html><body><div class=\"postmeta\"><a href=\"https://example.org/category/cat1/\">Cat1</a></div></body></html>", "categories", List.of("Cat1")), T.list("<html><body><p class=\"entry-tags\"><a href=\"https://example.org/tags/tag1/\">Tag1</a>, <a href=\"https://example.org/tags/tag2/\">Tag2</a></p></body></html>", "tags", List.of("Tag1", "Tag2")), T.list("<html><head><meta name=\"keywords\" content=\"sodium, salt, paracetamol, blood, pressure, high, heart, &amp;quot, intake, warning, study, &amp;quot, medicine, dissolvable, cardiovascular\" /></head></html>", "tags", List.of("sodium, salt, paracetamol, blood, pressure, high, heart, intake, warning, study, medicine, dissolvable, cardiovascular")), T.list("<html><body><p class=\"entry-categories\"><a href=\"https://example.org/categories/cat1/\">Cat1</a></p></body></html>", "categories", List.of("Cat1")), T.list("<html><head><meta property=\"article:tag\" content=\"Sports\"/></head><body/></html>", "tags", List.of("Sports")), T.list("<html><head><meta property=\"article:section\" content=\"Politics\"/></head><body/></html>", "categories", List.of("Politics")));
        for (Object _r : cases) {
            List<?> _row = (List<?>) _r;
            String doc = (String) _row.get(0);
            String attr = (String) _row.get(1);
            List<String> expected = (List<String>) _row.get(2);
            assertEquals(expected, Metadata.extract_metadata(doc, null, null, true, null).getattr(attr));
        }
    }

    /** A user date_config without 'max_date' must not raise, and must not be mutated. */
    @Test
    void test_date_config() {
        Map<String, Object> cfg = T.map("original_date", true, "extensive_search", false);
        String doc = "<html><head><title>T</title></head><body><p>x</p></body></html>";
        assertNotNull(Metadata.extract_metadata(doc, null, cfg, true, null));
        assertFalse(cfg.containsKey("url"));
    }

    /** Fallback titles are trimmed; nothing found returns None. */
    @Test
    void test_extract_title_fallbacks() {
        assertEquals("A", Metadata.extract_title(Etree.html_fromstring("<html><body><h1>  A  </h1><h1>B</h1></body></html>")));
        assertNull(Metadata.extract_title(Etree.html_fromstring("<html><body><p>x</p></body></html>")));
        assertEquals("Real Title", Metadata.extract_title(Etree.html_fromstring("<html><body><h1></h1><h2>Real Title</h2></body></html>")));
    }

    /** regression #854: an escaped backslash must not strip whitespace from adjacent JSON-LD fields. */
    @Test
    void test_json_minify() {
        String minified = Metadata.JSON_MINIFY.matcher("{\"author\": \"Curtis Heyen\", \"keywords\": [\"fatal stabbing\\\\\", \"homicide\"]}")
                .replaceAll(m -> m.group(1) != null ? java.util.regex.Matcher.quoteReplacement(m.group(1)) : "");
        assertTrue(minified.contains("\"Curtis Heyen\""));
    }

    /** regression: a non-whitespace control character (not neutralized by normalize_json's     trim, unlike \n/\r/\t) must not make the JSON-LD parse fail and fall back to the     crude regex extractor, which cannot see past the first (non-article) @graph node. */
    @Test
    void test_extract_meta_json_control_char() {
        String payload = "{\"@context\": \"https://schema.org\", \"@graph\": [{\"@type\": \"Organization\", \"name\": \"Acme Corp\"}, {\"@type\": \"Article\", \"headline\": \"Graph based headline\", \"description\": \"Body text with a stray control char\u0001 inside it.\"}]}";
        String doc = "<html><head><script type=\"application/ld+json\">" + payload + "</script></head><body></body></html>";
        Document metadata = Metadata.extract_meta_json(Etree.html_fromstring(doc), new Document());
        assertEquals("article", metadata.pagetype);
        assertEquals("Graph based headline", metadata.title);
    }

    /** regression: a flat JSON-LD object must not short-circuit the loop and drop later @graph blocks. */
    @Test
    void test_extract_json_multiple_blocks() {
        List<Object> schema = T.list(T.map("@context", "https://schema.org", "@type", "WebPage", "name", "Flat object"), T.map("@context", "https://schema.org", "@graph", T.list(T.map("@type", "Article", "headline", "Graph Headline"))));
        Document metadata = JsonMetadata.extract_json(schema, new Document());
        assertEquals("Graph Headline", metadata.title);
    }

    /** regression: "author": null must not raise -- process_parent's author loop expects an     iterable, not None. */
    @Test
    void test_extract_json_null_author() {
        Map<String, Object> schema = T.map("@context", "https://schema.org", "@type", "Article", "headline", "Some Headline", "author", null);
        Document metadata = JsonMetadata.extract_json(schema, new Document());
        assertEquals("Some Headline", metadata.title);
        assertNull(metadata.author);
    }

    /** Crude-regex fallback for malformed JSON-LD extracts each field; comma-publisher is rejected. */
    @Test
    void test_extract_json_parse_error() {
        String blob = "{\"@type\":\"Article\",\"author\":{\"name\":\"Jane Doe\"},\"publisher\":{\"@type\":\"Organization\",\"name\":\"Example Publisher\"},\"articleSection\":\"World News\",\"headline\":\"Big Story\",} BROKEN";
        Document m = JsonMetadata.extract_json_parse_error(blob, new Document());
        assertEquals(T.list("Jane Doe", "article", "Example Publisher", List.of("World News"), "Big Story"), T.list(m.author, m.pagetype, m.sitename, m.categories, m.title));
        assertNull(JsonMetadata.extract_json_parse_error("{\"publisher\":{\"name\":\"Acme, Inc\"}} X", new Document()).sitename);
    }

    /** regression: a bare publisher dict must not clobber an already-plausible sitename. */
    @Test
    void test_process_parent_keeps_good_sitename() {
        Document metadata = new Document();
        metadata.sitename = "A Long Established Site Name";
        JsonMetadata.process_parent(T.list(T.map("publisher", T.map("@type", "Organization", "name", "Pub"))), metadata);
        assertEquals("A Long Established Site Name", metadata.sitename);
    }

    @ParameterizedTest
    @ValueSource(strings = {"license noopener", "noopener license noreferrer", "LICENSE", "\tlicense\nnoopener"})
    void test_license_rel_tokens(String rel) {
        Document metadata = Metadata.extract_metadata("<html><body><a href=\"https://example.org/terms\" rel=\"" + rel + "\">Publication terms</a></body></html>", null, null, true, null);
        assertEquals("Publication terms", metadata.license);
    }

    @Test
    void test_license_rel_requires_complete_token() {
        Document metadata = Metadata.extract_metadata("<html><body><a href=\"https://example.org/terms\" rel=\"not-license\">Publication terms</a></body></html>", null, null, true, null);
        assertNull(metadata.license);
    }

    /** Test extraction of CC licenses */
    @Test
    void test_license() {
        Document metadata = Metadata.extract_metadata("<html><body><p><a href=\"https://creativecommons.org/licenses/by-sa/4.0/\" rel=\"license\">CC BY-SA</a></p></body></html>", null, null, true, null);
        assertEquals("CC BY-SA 4.0", metadata.license);
        metadata = Metadata.extract_metadata("<html><body><p><a href=\"https://licenses.org/unknown\" rel=\"license\">Unknown</a></p></body></html>", null, null, true, null);
        assertEquals("Unknown", metadata.license);
        metadata = Metadata.extract_metadata("<html><body><footer><a href=\"https://creativecommons.org/licenses/by-sa/4.0/\">CC BY-SA</a></footer></body></html>", null, null, true, null);
        assertEquals("CC BY-SA 4.0", metadata.license);
        metadata = Metadata.extract_metadata("<html><body>\n<div class=\"footer__navigation\">\n<p class=\"footer__licence\">\n            <strong>Lizenz: </strong>\n            Die von uns verfassten Inhalte stehen, soweit nicht anders vermerkt, unter der Lizenz\n            <a href=\"http://creativecommons.org/licenses/by-nc-sa/4.0/\">Creative Commons BY-NC-SA 4.0.</a>\n        </p>\n    </div>\n</body></html>", null, null, true, null);
        assertEquals("CC BY-NC-SA 4.0", metadata.license);
        metadata = Metadata.extract_metadata("<html><body><footer class=\"entry-footer\">\n\t<span class=\"cat-links\">Posted in <a href=\"https://sallysbakingaddiction.com/category/seasonal/birthday/\" rel=\"category tag\">Birthday</a></span>\n\t</footer></body></html>", null, null, true, null);
        assertNull(metadata.license);
        metadata = Metadata.extract_metadata("<html><body><footer class=\"entry-footer\">\n\t<span>The license is <a href=\"https://example.org/1\">CC BY-NC</a></span>\n\t</footer></body></html>", null, null, true, null);
        assertEquals("CC BY-NC", metadata.license);
        metadata = Metadata.extract_metadata("<html><body><footer><a href=\"/x\"><span>CC BY-SA 4.0</span></a></footer></body></html>", null, null, true, null);
        assertEquals("CC BY-SA 4.0", metadata.license);
        metadata = Metadata.extract_metadata("<html><body><p><a rel=\"license\" href=\"/x\"></a></p></body></html>", null, null, true, null);
        assertNull(metadata.license);
    }

    /** Image extraction from meta SEO tags */
    @Test
    void test_images() {
        List<Object> cases = T.list(List.of("<html><head><meta property=\"image\" content=\"https://example.org/example.jpg\"></html>", "https://example.org/example.jpg"), List.of("<html><head><meta property=\"og:image:url\" content=\"example.jpg\"></html>", "example.jpg"), List.of("<html><head><meta property=\"og:image\" content=\"https://example.org/example-opengraph.jpg\" /><body/></html>", "https://example.org/example-opengraph.jpg"), List.of("<html><head><meta property=\"twitter:image\" content=\"https://example.org/example-twitter.jpg\"></html>", "https://example.org/example-twitter.jpg"), List.of("<html><head><meta property=\"twitter:image:src\" content=\"example-twitter.jpg\"></html>", "example-twitter.jpg"), List.of("<html><head><meta name=\"twitter:image\" content=\"https://example.org/example-name.jpg\"></html>", "https://example.org/example-name.jpg"), T.t("<html><head><meta name=\"robots\" content=\"index, follow, max-image-preview:large, max-snippet:-1, max-video-preview:-1\" /></html>", null));
        for (Object _r : cases) {
            List<?> _row = (List<?>) _r;
            String doc = (String) _row.get(0);
            String expected = (String) _row.get(1);
            assertEquals(expected, Metadata.extract_metadata(doc, null, null, true, null).image);
        }
    }

    /** Tests that the dict serialization works and preserves data. */
    @Test
    void test_document_as_dict() {
        String htmldoc = "\n    <html>\n    <head>\n        <title>Test Title</title>\n        <meta itemprop=\"author\" content=\"Jenny Smith\" />\n        <meta property=\"og:url\" content=\"https://example.org\" />\n        <meta itemprop=\"description\" content=\"Description\" />\n        <meta property=\"og:published_time\" content=\"2017-09-01\" />\n        <meta name=\"article:publisher\" content=\"The Newspaper\" />\n        <meta property=\"image\" content=\"https://example.org/example.jpg\" />\n    </head>\n    <body>\n        <p class=\"entry-categories\">\n        <a href=\"https://example.org/category/cat1/\">Cat1</a>,\n        <a href=\"https://example.org/category/cat2/\">Cat2</a>\n        </p>\n        <p>\n        <a href=\"https://creativecommons.org/licenses/by-sa/4.0/\" rel=\"license\"\n            >CC BY-SA</a\n        >\n        </p>\n    </body>\n    </html>\n    ";
        Document document = Metadata.extract_metadata(htmldoc, null, null, true, null);
        Map<String, Object> dict_ = document.as_dict();
        assertEquals("Test Title", dict_.get("title"));
        assertEquals("Jenny Smith", dict_.get("author"));
        assertEquals("https://example.org", dict_.get("url"));
        assertEquals("Description", dict_.get("description"));
        assertEquals("The Newspaper", dict_.get("sitename"));
        assertEquals("2017-09-01", dict_.get("date"));
        assertEquals(List.of("Cat1", "Cat2"), dict_.get("categories"));
        assertEquals("CC BY-SA 4.0", dict_.get("license"));
        assertEquals("https://example.org/example.jpg", dict_.get("image"));
    }

    /** block children must not fuse on a minified page (GH #896); inline children must stay joined */
    @Test
    void test_block_boundaries_not_fused() {
        Document title = Metadata.extract_metadata("<html><body><h1><div>Kicker</div><div>Main headline</div></h1></body></html>", null, null, true, null);
        assertEquals("Kicker Main headline", title.title);
        Document lic = Metadata.extract_metadata("<html><body><h1>T</h1><footer><a rel=\"license\" href=\"/legal\"><div>Content</div><div>under our terms</div></a></footer></body></html>", null, null, true, null);
        assertEquals("Content under our terms", lic.license);
        Document inline = Metadata.extract_metadata("<html><body><h1>Hyper<span>link</span>ed</h1></body></html>", null, null, true, null);
        assertEquals("Hyperlinked", inline.title);
    }

    /**
     * test_block_boundaries_not_fused, categories case: jsoup applies the HTML5 rule that a div
     * closes an open p, libxml2 keeps the div inside the p, so the category XPath finds nothing here.
     */
    @Test
    @Tag("known-deviation")
    void test_block_boundaries_not_fused_categories() {
        Document cats = Metadata.extract_metadata("<html><body><h1>T</h1><p class=\"entry-categories\"><a href=\"/category/local\"><div>Local</div><div>News</div></a></p></body></html>", null, null, true, null);
        assertEquals(List.of("Local News"), cats.categories);
    }
}
