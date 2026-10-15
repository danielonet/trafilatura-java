// Java port of tests/sitemaps_tests.py
package trafilatura.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import trafilatura.compat.Pair;
import trafilatura.courlan.Courlan;
import trafilatura.trafilatura.Settings;
import trafilatura.trafilatura.Sitemaps;
import trafilatura.trafilatura.Sitemaps.SitemapObject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static trafilatura.tests.Conftest.RESOURCES_DIR;
import static trafilatura.tests.Conftest.read_bytes;
import static trafilatura.tests.Conftest.read_text;
import static trafilatura.trafilatura.Deduplication.is_similar_domain;
import static trafilatura.trafilatura.Utils.decode_file;

/** Unit tests for sitemaps parsing. */
@ExtendWith(Conftest.MockNetwork.class)
class SitemapsTests {

    static SitemapObject sitemap(String baseurl, String domain, List<String> urls) {
        return new SitemapObject(baseurl, domain, new ArrayList<>(urls));
    }

    static SitemapObject sitemap(String baseurl, String domain, List<String> urls, String target_lang) {
        return new SitemapObject(baseurl, domain, new ArrayList<>(urls), target_lang, false, Settings.DEFAULT_CONFIG);
    }

    /** (sitemap.sitemap_urls, sitemap.urls) */
    static List<List<String>> result(SitemapObject sitemap) {
        return List.of(sitemap.sitemap_urls, sitemap.urls);
    }

    /** Test search for sitemaps. */
    @Test
    void test_search() {
        assertTrue(Sitemaps.sitemap_search("12345").isEmpty());
        assertTrue(Sitemaps.sitemap_search("12345.xml.gz").isEmpty());
        assertTrue(Sitemaps.sitemap_search("https://1.net/sitemap.xml.gz").isEmpty());
        assertTrue(Sitemaps.sitemap_search("https://bogusdomain.net/").isEmpty());
    }

    /** Test simple link extraction. */
    @Test
    void test_extraction() {
        // link handling
        String url = "https://www.sitemaps.org/sitemap.xml";
        String domain = "sitemaps.org";
        String baseurl = "https://www.sitemaps.org";
        SitemapObject sitemap = sitemap(baseurl, domain, List.of());
        sitemap.handle_link(url);
        assertEquals(1, sitemap.sitemap_urls.size());
        assertTrue(sitemap.urls.isEmpty());

        // same URL
        sitemap = sitemap(baseurl, domain, List.of(url));
        sitemap.current_url = url;
        sitemap.handle_link(url);
        assertEquals(1, sitemap.sitemap_urls.size());
        assertTrue(sitemap.urls.isEmpty());

        // malformed link
        sitemap = sitemap("https://example.org", "example.org", List.of("https://example.org/sitemap.xml"));
        sitemap.handle_link("http://[::1");
        assertEquals(1, sitemap.sitemap_urls.size());
        assertTrue(sitemap.urls.isEmpty());

        sitemap = sitemap("https://example.org", "example.org", List.of("https://example.org/sitemap.xml"));
        sitemap.handle_link("https://mydomain");
        assertEquals(1, sitemap.sitemap_urls.size());
        assertTrue(sitemap.urls.isEmpty());

        sitemap = sitemap("https://example.org", "example.org", List.of("https://example.org/sitemap.xml"));
        sitemap.handle_link("https://mydomain.wordpress.com/1");
        assertEquals(1, sitemap.sitemap_urls.size());
        assertEquals(List.of("https://mydomain.wordpress.com/1"), sitemap.urls);

        sitemap = sitemap("https://programtalk.com", "programtalk.com", List.of("https://programtalk.com/sitemap.xml"));
        sitemap.handle_link("http://programtalk.com/java-api-usage-examples/org.apache.xml.security.stax.securityEvent.SecurityEvent");
        assertEquals(1, sitemap.sitemap_urls.size());
        assertEquals(List.of("http://programtalk.com/java-api-usage-examples/org.apache.xml.security.stax.securityEvent.SecurityEvent"),
                sitemap.urls);

        // similar domain names
        assertFalse(is_similar_domain("kleins-weindepot.de", "eurosoft.net"));
        assertTrue(is_similar_domain("kleins-weindepot.de", "weindepot.info"));
        assertTrue(is_similar_domain("airport-frankfurt.de", "frankfurt-airport.com"));

        // subdomain vs. domain: de.sitemaps.org / sitemaps.org
        url = "https://de.sitemaps.org/1";
        Pair<String, String> hostinfo = Courlan.get_hostinfo("https://de.sitemaps.org/sitemap.xml");
        sitemap = sitemap(hostinfo.second(), hostinfo.first(), List.of());
        sitemap.handle_link(url);
        assertTrue(sitemap.sitemap_urls.isEmpty());
        assertEquals(List.of(url), sitemap.urls);

        // diverging domains
        url = "https://www.software.info/1";
        hostinfo = Courlan.get_hostinfo("https://example.org/sitemap.xml");
        domain = hostinfo.first();
        baseurl = hostinfo.second();
        sitemap = sitemap(baseurl, domain, List.of("https://example.org/sitemap.xml"));
        sitemap.handle_link(url);
        assertEquals(1, sitemap.sitemap_urls.size());
        assertTrue(sitemap.urls.isEmpty());

        // safety belts
        assertFalse(Sitemaps.is_plausible_sitemap("http://example.org/sitemap.xml.gz", null));
        // bytes content: b"\x1f\x8bABC" is not a str, so it cannot match the sitemap format
        assertFalse(Sitemaps.is_plausible_sitemap("http://example.org/sitemap.xml.gz", "\u001f\u008bABC"));
        assertFalse(Sitemaps.is_plausible_sitemap("http://example.org/sitemap.xml", "ABC"));
        assertFalse(Sitemaps.is_plausible_sitemap("http://test.org/sitemap.xml", "<!DOCTYPE html><html><body/></html>"));
        assertFalse(Sitemaps.is_plausible_sitemap("http://test.org/sitemap", "<!DOCTYPE html><html><body/></html>"));
        // invalid
        sitemap = sitemap(baseurl, domain, List.of());
        sitemap.content = "<html>\n</html>";
        sitemap.extract_sitemap_links();
        assertTrue(sitemap.sitemap_urls.isEmpty());
        assertTrue(sitemap.urls.isEmpty());

        // parsing a file
        domain = "sitemaps.org";
        baseurl = "http://www.sitemaps.org";
        String teststring = read_text(RESOURCES_DIR.resolve("sitemap.xml"));
        assertTrue(Sitemaps.is_plausible_sitemap("http://sitemaps.org/sitemap.xml", teststring));
        sitemap = sitemap(baseurl, domain, List.of());
        sitemap.content = teststring;
        sitemap.extract_sitemap_links();
        assertTrue(sitemap.sitemap_urls.isEmpty());
        assertEquals(84, sitemap.urls.size());
        // hreflang
        sitemap.urls = new ArrayList<>();
        sitemap.extract_sitemap_langlinks();
        assertTrue(sitemap.sitemap_urls.isEmpty());
        assertTrue(sitemap.urls.isEmpty());

        // nested sitemaps
        url = "http://www.example.com/sitemap.xml";
        domain = "example.com";
        baseurl = "http://www.example.com";
        teststring = read_text(RESOURCES_DIR.resolve("sitemap2.xml"));
        sitemap = sitemap(baseurl, domain, List.of(url));
        sitemap.content = teststring;
        sitemap.extract_sitemap_links();
        assertEquals(List.of("http://www.example.com/sitemap.xml", "http://www.example.com/sitemap1.xml.gz",
                "http://www.example.com/sitemap2.xml.gz"), sitemap.sitemap_urls);
        assertTrue(sitemap.urls.isEmpty());

        // hreflang
        sitemap = sitemap("https://test.org/", "test.org", List.of(), "en");
        sitemap.content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><urlset><url><loc>http://www.test.org/english/page.html</loc></url></urlset>";
        sitemap.process();
        assertEquals(List.of(List.of(), List.of("http://www.test.org/english/page.html")), result(sitemap));
        teststring = read_text(RESOURCES_DIR.resolve("sitemap-hreflang.xml"));
        sitemap = sitemap(baseurl, domain, List.of(), "de");
        sitemap.content = teststring;
        sitemap.extract_sitemap_langlinks();
        assertEquals(List.of("http://www.example.com/sitemap-de.xml.gz"), sitemap.sitemap_urls);
        assertTrue(sitemap.urls.size() > 0);

        // target_lang is escaped: a regex-hostile value stays inert
        sitemap = sitemap(baseurl, domain, List.of(), "de)|(.");
        sitemap.content = teststring;
        sitemap.extract_sitemap_langlinks();
        assertTrue(sitemap.sitemap_urls.isEmpty());
        assertTrue(sitemap.urls.isEmpty());

        // GZ-compressed sitemaps
        url = "https://www.sitemaps.org/sitemap.xml";
        domain = "sitemaps.org";
        baseurl = "https://www.sitemaps.org";
        teststring = decode_file(read_bytes(RESOURCES_DIR.resolve("sitemap.xml.gz")));
        assertTrue(Sitemaps.is_plausible_sitemap("http://example.org/sitemap.xml.gz", teststring));
        sitemap = sitemap(baseurl, domain, List.of(url));
        sitemap.content = teststring;
        sitemap.extract_sitemap_links();
        assertEquals(1, sitemap.sitemap_urls.size());
        assertEquals(84, sitemap.urls.size());

        // check contents
        assertTrue(Sitemaps.is_plausible_sitemap("http://example.org/sitemap.xml.gz?value=1", teststring));

        // TXT links
        String content = "Tralala\nhttps://test.org/1\nhttps://test.org/2";
        assertTrue(Sitemaps.is_plausible_sitemap("http://example.org/sitemap", content));
        sitemap = sitemap("https://test.org/", "test.org", List.of());
        sitemap.content = "Tralala\nhttps://test.org/1\nhttps://test.org/2";
        sitemap.process();
        assertEquals(List.of(List.of(), List.of("https://test.org/1", "https://test.org/2")), result(sitemap));

        // TXT links + language
        sitemap = sitemap("https://test.org/", "test.org", List.of(), "en");
        sitemap.content = "Tralala\nhttps://test.org/en/1\nhttps://test.org/en/2\nhttps://test.org/es/3";
        sitemap.process();
        assertEquals(List.of(List.of(), List.of("https://test.org/en/1", "https://test.org/en/2")), result(sitemap));

        // XML sitemap with matching hreflang links: return after the langlink pass
        sitemap = sitemap("https://example.org", "example.org", List.of(), "de");
        sitemap.current_url = "https://example.org/sitemap.xml";
        sitemap.content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\" xmlns:xhtml=\"http://www.w3.org/1999/xhtml\">"
                + "<url><loc>https://example.org/page</loc>"
                + "<xhtml:link rel=\"alternate\" hreflang=\"de\" href=\"https://example.org/de/page\"/>"
                + "</url></urlset>";
        sitemap.process();
        assertEquals(List.of("https://example.org/de/page"), sitemap.urls);
    }

    /** Check if sitemaps can be found over robots.txt. */
    @Test
    void test_robotstxt() {
        assertTrue(Sitemaps.find_robots_sitemaps("https://http.org").isEmpty());
        String baseurl = "https://httpbun.com";
        assertTrue(Sitemaps.find_robots_sitemaps(baseurl).isEmpty());
        assertTrue(Sitemaps.extract_robots_sitemaps("# test", baseurl).isEmpty());
        assertTrue(Sitemaps.extract_robots_sitemaps("# test".repeat(10000), baseurl).isEmpty());
        assertEquals(List.of("https://example.org/sitemap.xml"),
                Sitemaps.extract_robots_sitemaps("sitemap: https://example.org/sitemap.xml", baseurl));
        assertEquals(List.of("https://httpbun.com/sitemap.xml"),
                Sitemaps.extract_robots_sitemaps("sitemap: http://[::1\nsitemap: /sitemap.xml", baseurl));
    }

    /** Patch sitemaps.fetch_url and sitemaps.is_live_page for the duration of the action. */
    static void withPatchedNetwork(java.util.function.BiFunction<String, trafilatura.compat.ConfigParser, String> fetch,
            Runnable action) {
        var savedFetch = Sitemaps.FETCH_URL;
        var savedLive = Sitemaps.IS_LIVE_PAGE;
        Sitemaps.FETCH_URL = fetch;
        Sitemaps.IS_LIVE_PAGE = (u, c) -> true;
        try {
            action.run();
        } finally {
            Sitemaps.FETCH_URL = savedFetch;
            Sitemaps.IS_LIVE_PAGE = savedLive;
        }
    }

    /** Regression: with target_lang, sitemaps processed while others are queued must keep their links. */
    @Test
    void test_sitemap_index_with_target_lang() {
        Map<String, Object> pages = T.map(
                "https://example.org/robots.txt", "Sitemap: https://example.org/sitemap_index.xml\n",
                "https://example.org/sitemap_index.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><sitemapindex>"
                        + "<sitemap><loc>https://example.org/sub1.xml</loc></sitemap>"
                        + "<sitemap><loc>https://example.org/sub2.xml</loc></sitemap>"
                        + "</sitemapindex>",
                "https://example.org/sub1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><urlset>"
                        + "<url><loc>https://example.org/de/a1.html</loc></url>"
                        + "<url><loc>https://example.org/de/a2.html</loc></url></urlset>",
                "https://example.org/sub2.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><urlset>"
                        + "<url><loc>https://example.org/de/b1.html</loc></url>"
                        + "<url><loc>https://example.org/de/b2.html</loc></url></urlset>");
        List<String> expected = List.of("https://example.org/de/a1.html", "https://example.org/de/a2.html",
                "https://example.org/de/b1.html", "https://example.org/de/b2.html");
        withPatchedNetwork((url, config) -> (String) pages.get(url), () -> {
            assertEquals(expected, Sitemaps.sitemap_search("https://example.org").stream().sorted().toList());
            assertEquals(expected, Sitemaps.sitemap_search("https://example.org", "de").stream().sorted().toList());
        });
    }

    /** Language-root URLs with trailing slashes must be filtered even if cleaning strips the slash. */
    @Test
    void test_lang_filter_before_cleaning() {
        SitemapObject sitemap = sitemap("https://www.sitemaps.org", "sitemaps.org", List.of(), "de");
        sitemap.handle_link("https://www.sitemaps.org/da/");
        sitemap.handle_link("https://www.sitemaps.org/da/faq.html");
        assertTrue(sitemap.urls.isEmpty());
        sitemap.handle_link("https://www.sitemaps.org/de/faq.html");
        assertEquals(List.of("https://www.sitemaps.org/de/faq.html"), sitemap.urls);
    }

    /** Without robots.txt hints, the most common sitemap location must be tried first. */
    @Test
    void test_guess_order() {
        List<String> fetched = new ArrayList<>();
        withPatchedNetwork((url, config) -> {
            fetched.add(url);
            return null;
        }, () -> Sitemaps.sitemap_search("https://example.org"));
        assertEquals("https://example.org/robots.txt", fetched.get(0));
        assertEquals("https://example.org/sitemap.xml", fetched.get(1));
    }

    /** Test whole process. */
    @Test
    void test_whole() {
        List<String> results = Sitemaps.sitemap_search("https://www.sitemaps.org", "de", false, 2.0, 1, Settings.DEFAULT_CONFIG);
        assertEquals(8, results.size());

        // trafilatura.settings.MAX_SITEMAPS_SEEN = 1 has no effect in Python either: the default
        // argument of sitemap_search() was bound at import time
        results = Sitemaps.sitemap_search("https://www.sitemaps.org", "de");
        assertEquals(8, results.size());
    }

    static Stream<Arguments> namespaceCases() {
        List<Arguments> cases = new ArrayList<>();
        for (String prefix : List.of("", "sm:", "site-map:")) {
            for (String declaration : List.of("", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>")) {
                for (boolean index : List.of(false, true)) {
                    cases.add(Arguments.of(prefix, declaration, index));
                }
            }
        }
        return cases.stream();
    }

    /** Namespace prefixes do not change page or nested sitemap discovery. */
    @ParameterizedTest
    @MethodSource("namespaceCases")
    void test_sitemap_namespaces(String prefix, String declaration, boolean index) {
        String root = index ? "sitemapindex" : "urlset";
        String child = index ? "sitemap" : "url";
        String namespace = "xmlns" + (prefix.isEmpty() ? "" : ":" + prefix.substring(0, prefix.length() - 1))
                + "=\"http://www.sitemaps.org/schemas/sitemap/0.9\"";
        String url = index ? "https://example.org/nested.xml" : "https://example.org/page";
        SitemapObject sitemap = sitemap("https://example.org", "example.org", List.of());
        sitemap.current_url = "https://example.org/sitemap.xml";
        sitemap.content = declaration + "<" + prefix + root + " " + namespace + ">"
                + "<" + prefix + child + "><" + prefix + "loc><![CDATA[" + url + "]]></" + prefix + "loc></" + prefix + child + ">"
                + "</" + prefix + root + ">";
        sitemap.process();
        assertEquals(index ? List.of(List.of(url), List.of()) : List.of(List.of(), List.of(url)), result(sitemap));
    }

    /** Ignore extension locations even when a prefix is rebound locally. */
    @Test
    void test_sitemap_namespace_scope() {
        SitemapObject sitemap = sitemap("https://example.org", "example.org", List.of());
        sitemap.current_url = "https://example.org/sitemap.xml";
        sitemap.content = "<s:urlset xmlns:s=\"http://www.sitemaps.org/schemas/sitemap/0.9\" xmlns:image=\"urn:image\">"
                + "<s:url><s:loc>https://example.org/page</s:loc>"
                + "<image:loc>https://example.org/image</image:loc>"
                + "<s:loc xmlns:s=\"urn:other\">https://example.org/other</s:loc>"
                + "</s:url></s:urlset>";
        sitemap.process();
        assertEquals(List.of("https://example.org/page"), sitemap.urls);
    }

    /** Location extraction does not resolve entities or concatenate child markup. */
    @ParameterizedTest
    @ValueSource(strings = {"&external;", "https://example.org/&external;", "https://example.org/<nested/>"})
    void test_sitemap_locations_do_not_expand_entities(String location) {
        SitemapObject sitemap = sitemap("https://example.org", "example.org", List.of());
        sitemap.content = "<!DOCTYPE urlset [<!ENTITY external SYSTEM \"file:///not-a-sitemap-resource\">]>"
                + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">"
                + "<url><loc>" + location + "</loc></url></urlset>";
        sitemap.extract_sitemap_links();
        assertTrue(sitemap.urls.isEmpty());
    }

    /** Namespaced extraction keeps the existing maximum-location bound. */
    @Test
    void test_sitemap_namespace_link_limit() {
        int saved = Sitemaps.MAX_LINKS;
        Sitemaps.MAX_LINKS = 1;
        try {
            SitemapObject sitemap = sitemap("https://example.org", "example.org", List.of());
            sitemap.content = "<s:urlset xmlns:s=\"http://www.sitemaps.org/schemas/sitemap/0.9\">"
                    + "<s:url><s:loc>https://example.org/first?a=1&amp;b=2</s:loc></s:url>"
                    + "<s:url><s:loc>https://example.org/second</s:loc></s:url></s:urlset>";
            sitemap.extract_sitemap_links();
            assertEquals(List.of("https://example.org/first?a=1&b=2"), sitemap.urls);
        } finally {
            Sitemaps.MAX_LINKS = saved;
        }
    }

    /** Malformed input cannot yield a sitemap location. */
    @ParameterizedTest
    @ValueSource(strings = {"", "not an XML document"})
    void test_sitemap_xml_without_a_root_is_ignored(String content) {
        SitemapObject sitemap = sitemap("https://example.org", "example.org", List.of());
        sitemap.content = content;
        sitemap.extract_sitemap_links();
        assertEquals(List.of(), sitemap.urls);
        assertEquals(List.of(), sitemap.sitemap_urls);
    }
}
