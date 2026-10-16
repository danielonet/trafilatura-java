// Java port of tests/spider_tests.py
package io.github.danielonet.trafilatura.tests;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.danielonet.trafilatura.compat.Pair;
import io.github.danielonet.trafilatura.compat.RobotFileParser;
import io.github.danielonet.trafilatura.compat.Triple;
import io.github.danielonet.trafilatura.courlan.UrlStore;
import io.github.danielonet.trafilatura.Settings;
import io.github.danielonet.trafilatura.Spider;
import io.github.danielonet.trafilatura.Spider.CrawlParameters;
import io.github.danielonet.trafilatura.Utils.Response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the spidering part of the trafilatura library. */
@ExtendWith(Conftest.MockNetwork.class)
class SpiderTests {

    static CrawlParameters crawl(String start, String lang, String prune_xpath) {
        return new CrawlParameters(start, lang, null, prune_xpath == null ? null : List.of(prune_xpath), Settings.DEFAULT_CONFIG);
    }

    /** Test redirection detection. */
    @Test
    void test_redirections() {
        assertNull(Spider.probe_alternative_homepage("xyz").third());
        assertEquals("https://example.org",
                Spider.probe_alternative_homepage("https://httpbun.com/redirect-to?url=https://example.org").third());
        // meta-refresh to an unreachable target -> failed redirect -> all None
        assertEquals(Triple.of(null, null, null), Spider.probe_alternative_homepage("https://example.com/refresh-home"));
    }

    /** Test redirection detection using meta tag. */
    @Test
    void test_meta_redirections() {
        List<List<String>> tests = List.of(
                // empty
                T.t("\"refresh\"", "https://httpbun.com/", "https://httpbun.com/"),
                T.t("<html></html>", "https://httpbun.com/", "https://httpbun.com/"),
                // unusable
                T.t("<html>REDIRECT!</html>", "https://httpbun.com/", "https://httpbun.com/"),
                // malformed
                T.t("<html><meta http-equiv=\"refresh\" content=\"3600\n&lt;meta http-equiv=\" content-type=\"\"></html>",
                        "https://httpbun.com/", "https://httpbun.com/"),
                // wrong URL
                T.t("<html><meta http-equiv=\"refresh\" content=\"0; url=1234\"/></html>", "https://httpbun.com/", null),
                // normal
                T.t("<html><meta http-equiv=\"refresh\" content=\"0; url=https://httpbun.com/html\"/></html>",
                        "http://test.org/", "https://httpbun.com/html"),
                // no "url=" prefix
                T.t("<html><meta http-equiv=\"refresh\" content=\"0; https://httpbun.com/html\"/></html>",
                        "http://test.org/", "https://httpbun.com/html"),
                // relative URLs, resolved against the page
                T.t("<html><meta http-equiv=\"refresh\" content=\"0; url=/html\"/></html>",
                        "https://httpbun.com/status/404", "https://httpbun.com/html"),
                T.t("<html><meta http-equiv=\"refresh\" content=\"0; url=200\"/></html>",
                        "https://httpbun.com/status/404", "https://httpbun.com/status/200"));

        for (List<String> test : tests) {
            String htmlstring = test.get(0);
            String homepage = test.get(1);
            String expected_homepage = test.get(2);
            Pair<String, String> result = Spider.refresh_detection(htmlstring, homepage);
            assertEquals(expected_homepage, result.second());
            if (expected_homepage != null) {
                if (expected_homepage.equals(homepage)) {
                    assertEquals(htmlstring, result.first());
                } else {
                    assertNotEquals(htmlstring, result.first());
                }
            }
        }
    }

    /** Test link extraction procedures. */
    @Test
    void test_process_links() {
        Spider.URL_STORE = new UrlStore(false, false);
        String base_url = "https://example.org";
        CrawlParameters params = new CrawlParameters(base_url);
        String htmlstring = "<html><body><a href=\"https://example.org/page1\"/><a href=\"https://example.org/page1/\"/><a href=\"https://test.org/page1\"/></body></html>";

        // 1 internal link in total
        Spider.process_links(htmlstring, params);
        assertEquals(1, Spider.URL_STORE.find_known_urls(base_url).size());
        assertEquals(1, Spider.URL_STORE.find_unvisited_urls(base_url).size());

        // same with content already seen
        Spider.process_links(htmlstring, params);
        assertEquals(1, Spider.URL_STORE.find_unvisited_urls(base_url).size());
        assertEquals(1, Spider.URL_STORE.find_known_urls(base_url).size());

        // test navigation links
        String url1 = "https://example.org/tag/number1";
        String url2 = "https://example.org/page2";
        htmlstring = "<html><body><a href=\"" + url1 + "\"/><a href=\"" + url2 + "\"/></body></html>";
        Spider.process_links(htmlstring, params);
        List<String> todo = Spider.URL_STORE.find_unvisited_urls(base_url);
        List<String> known_links = Spider.URL_STORE.find_known_urls(base_url);
        assertEquals(3, known_links.size());
        assertEquals(3, todo.size());
        assertEquals(url1, todo.get(0));

        // malformed relative link
        Spider.process_links("<html><body><a href=\"//[::1\"/></body></html>", params);
        assertEquals(3, Spider.URL_STORE.find_known_urls(base_url).size());

        // test cleaning and language
        String url = "https://example.org/en/page1/?";
        String target = "https://example.org/en/page1/";
        htmlstring = "<html><body><p>This is an English page.</p><a href=\"" + url + "\"/></body></html>";
        params = crawl(base_url, "en", null);
        Spider.process_links(htmlstring, params);
        todo = Spider.URL_STORE.find_unvisited_urls(base_url);
        known_links = Spider.URL_STORE.find_known_urls(base_url);
        assertEquals(4, known_links.size());
        assertEquals(4, todo.size());
        assertTrue(todo.contains(target));

        // test rejection of URLs out of scope
        url = "https://example.org/section2/page2";
        htmlstring = "<html><body><a href=\"" + url + "\"/></body></html>";
        params = new CrawlParameters("https://example.org/section1/");
        Spider.process_links(htmlstring, params);
        todo = Spider.URL_STORE.find_unvisited_urls(base_url);
        known_links = Spider.URL_STORE.find_known_urls(base_url);
        assertFalse(todo.contains(url));
        assertEquals(4, known_links.size());

        // wrong language
        url = "https://example.org/en/page2";
        htmlstring = "<html><body><a href=\"" + url + "\"/></body></html>";
        params = crawl(base_url, "de", null);
        Spider.process_links(htmlstring, params);
        todo = Spider.URL_STORE.find_unvisited_urls(base_url);
        known_links = Spider.URL_STORE.find_known_urls(base_url);
        assertFalse(todo.contains(url));
        assertEquals(4, known_links.size());

        // invalid links
        params = new CrawlParameters(base_url);
        htmlstring = "<html><body><a href=\"#anchor\"/><a href=\"mailto:user@example.org\"/><a href=\"tel:1234567890\"/></body></html>";
        Spider.process_links(htmlstring, params);
        assertEquals(4, known_links.size());
        assertEquals(4, todo.size());

        // not crawlable
        htmlstring = "<html><body><a href=\"https://example.org/login\"/></body></html>";
        Spider.process_links(htmlstring, params);
        assertEquals(4, known_links.size());
        assertEquals(4, todo.size());

        // test queue evaluation
        List<String> queue = new ArrayList<>();
        assertFalse(Spider.is_still_navigation(queue));
        queue.add("https://example.org/en/page1");
        assertFalse(Spider.is_still_navigation(queue));
        queue.add("https://example.org/tag/1");
        assertTrue(Spider.is_still_navigation(queue));
    }

    /** Test functions related to crawling sequence and consistency. */
    @Test
    void test_crawl_logic() {
        String url = "https://httpbun.com/html";
        Spider.URL_STORE = new UrlStore(false, false);

        // erroneous webpage
        assertThrows(IllegalArgumentException.class, () -> new CrawlParameters("xyz"));
        assertEquals(0, Spider.URL_STORE.get_known_domains().size());

        // empty request
        CrawlParameters params = new CrawlParameters("https://example.org");
        Spider.process_response(null, params);
        assertEquals(0, Spider.URL_STORE.get_known_domains().size());
        assertEquals("https://example.org", params.start);
        assertEquals("https://example.org", params.base);
        assertEquals("https://example.org", params.ref);
        assertEquals(0, params.i);
        assertEquals(0, params.known_num);
        assertTrue(params.is_on);
        assertNull(params.lang);
        assertNull(params.rules);

        // already visited
        params = Spider.init_crawl(url, null, null, null, List.of(url), null, Settings.DEFAULT_CONFIG);
        assertEquals("https://httpbun.com", params.base);
        assertEquals(0, params.i);
        assertEquals(1, params.known_num);
        assertFalse(params.is_on);
        assertTrue(Spider.URL_STORE.find_unvisited_urls(params.base).isEmpty());
        assertEquals(List.of("https://httpbun.com/html"), Spider.URL_STORE.find_known_urls(params.base));

        // normal webpage
        Spider.URL_STORE = new UrlStore(false, false);
        params = Spider.init_crawl(url);
        assertTrue(Spider.URL_STORE.find_unvisited_urls(params.base).isEmpty());
        assertEquals(List.of(url), Spider.URL_STORE.find_known_urls(params.base));
        assertEquals("https://httpbun.com", params.base);
        assertEquals(1, params.i);
        assertFalse(params.is_on);

        // delay between requests
        assertEquals(5.0, Spider.URL_STORE.get_crawl_delay("https://httpbun.com"));
        assertEquals(2.0, Spider.URL_STORE.get_crawl_delay("https://httpbun.com", 2.0));

        // existing todo
        params = Spider.init_crawl(url, null, null, List.of(url, "http://irrelevant.com"), null, null, Settings.DEFAULT_CONFIG);
        assertTrue(Spider.URL_STORE.find_unvisited_urls(params.base).isEmpty());
        assertEquals("https://httpbun.com", params.base);
        assertEquals(0, params.i);
        assertFalse(params.is_on);

        // new todo
        params = Spider.init_crawl(url, null, null, List.of("https://httpbun.com/links/1/1"), null, null, Settings.DEFAULT_CONFIG);
        assertEquals("https://httpbun.com", params.base);
        assertEquals(List.of("https://httpbun.com/links/1/1"), Spider.URL_STORE.find_unvisited_urls(params.base));
        assertEquals(0, params.i);
        assertTrue(params.is_on);
        assertEquals(2, params.known_num);
    }

    /** Test page-by-page processing. */
    @Test
    void test_crawl_page() {
        String base_url = "https://httpbun.com";

        Spider.URL_STORE = new UrlStore(false, false);
        Spider.URL_STORE.add_urls(List.of("https://httpbun.com/links/2/2"));
        CrawlParameters params = new CrawlParameters(base_url);
        params = Spider.crawl_page(params);
        List<String> todo = Spider.URL_STORE.find_unvisited_urls(base_url);
        assertEquals(List.of("https://httpbun.com/links/2/0", "https://httpbun.com/links/2/1"), todo.stream().sorted().toList());
        assertEquals(1, params.i);
        assertTrue(params.is_on);
        assertEquals(3, params.known_num);

        // prune path
        Spider.URL_STORE = new UrlStore(false, false);
        Spider.URL_STORE.add_urls(List.of("https://httpbun.com/links/2/2"));
        params = crawl(base_url, null, "//a");
        params = Spider.crawl_page(params);
        assertEquals(0, Spider.URL_STORE.find_unvisited_urls(base_url).size());
        assertEquals(1, params.i);

        // prune path with initial page
        Spider.URL_STORE = new UrlStore(false, false);
        Spider.URL_STORE.add_urls(List.of("https://httpbun.com/links/2/2"));
        params = crawl(base_url, null, "//a");
        params = Spider.crawl_page(params, true);
        assertEquals(0, Spider.URL_STORE.find_unvisited_urls(base_url).size());
        assertEquals(1, params.i);

        // initial page
        Spider.URL_STORE = new UrlStore(false, false);
        Spider.URL_STORE.add_urls(List.of("https://httpbun.com/html"));
        params = crawl(base_url, "de", null);
        params = Spider.crawl_page(params, true);
        assertEquals(0, Spider.URL_STORE.find_unvisited_urls(base_url).size());
        assertEquals(1, Spider.URL_STORE.find_known_urls(base_url).size());
        assertEquals(1, params.i);
    }

    /** Patch spider.fetch_response and spider.get_rules for the duration of the action. */
    static void withPatchedSpider(java.util.function.BiFunction<String, io.github.danielonet.trafilatura.compat.ConfigParser, Response> fetch,
            Runnable action) {
        var savedFetch = Spider.FETCH_RESPONSE;
        var savedRules = Spider.GET_RULES;
        Spider.FETCH_RESPONSE = fetch;
        Spider.GET_RULES = (base, config) -> null;
        try {
            action.run();
        } finally {
            Spider.FETCH_RESPONSE = savedFetch;
            Spider.GET_RULES = savedRules;
        }
    }

    /** An http crawl of a site redirecting to https must follow the upgrade and keep its links. */
    @Test
    void test_scheme_upgrade() {
        Spider.URL_STORE = new UrlStore();
        String links = IntStream.range(0, 3).mapToObj(i -> "<a href=\"https://scheme-upgrade.org/p" + i + ".html\">x</a>")
                .collect(Collectors.joining());
        String html = "<html><body>" + links + "</body></html>";
        List<List<String>> result = new ArrayList<>();
        withPatchedSpider((url, config) -> new Response(html.getBytes(StandardCharsets.UTF_8), 200, url.replace("http://", "https://")),
                () -> {
                    Pair<List<String>, List<String>> crawled = Spider.focused_crawler("http://scheme-upgrade.org", 10,
                            Spider.MAX_KNOWN_URLS);
                    result.add(crawled.second());
                });
        assertEquals(List.of("https://scheme-upgrade.org/", "https://scheme-upgrade.org/p0.html",
                "https://scheme-upgrade.org/p1.html", "https://scheme-upgrade.org/p2.html"),
                result.get(0).stream().sorted().toList());
    }

    /** A homepage redirecting to another host keeps the crawl on the start host, links resolved against the start URL. */
    @Test
    void test_host_redirect() {
        Spider.URL_STORE = new UrlStore();
        String html = "<html><body><a href=\"/p1.html\">x</a><a href=\"https://www.host-redirect.org/p2.html\">y</a></body></html>";
        List<CrawlParameters> result = new ArrayList<>();
        withPatchedSpider((url, config) -> new Response(html.getBytes(StandardCharsets.UTF_8), 200, "https://www.host-redirect.org/"),
                () -> result.add(Spider.init_crawl("http://host-redirect.org")));
        CrawlParameters params = result.get(0);
        assertEquals("http://host-redirect.org", params.base);
        assertEquals(List.of("http://host-redirect.org/p1.html"), Spider.URL_STORE.find_unvisited_urls(params.base));
    }

    /** Test the whole focused crawler mechanism. */
    @Test
    void test_focused_crawler() {
        Spider.URL_STORE = new UrlStore();
        Pair<List<String>, List<String>> crawled = Spider.focused_crawler("https://httpbun.com/links/2/2", 2, Spider.MAX_KNOWN_URLS);
        assertTrue(crawled.second().size() > 0);
    }

    /** Test robots.txt parsing. */
    @Test
    void test_robots() {
        assertNull(Spider.get_rules("1234"));

        String robots_url = "https://example.org/robots.txt";

        assertNull(Spider.parse_robots(robots_url, null));
        assertNull(Spider.parse_robots(robots_url, 123));
        assertNull(Spider.parse_robots(robots_url, "123".getBytes(StandardCharsets.UTF_8)));

        RobotFileParser rules = Spider.parse_robots(robots_url, "Allow: *");
        assertNotNull(rules);
        assertTrue(rules.can_fetch("*", "https://example.org/1"));

        rules = Spider.parse_robots(robots_url, "User-agent: *\nDisallow: /");
        assertNotNull(rules);
        assertFalse(rules.can_fetch("*", "https://example.org/1"));

        rules = Spider.parse_robots(robots_url, "User-agent: *\nDisallow: /private");
        assertNotNull(rules);
        assertFalse(rules.can_fetch("*", "https://example.org/private"));
        assertTrue(rules.can_fetch("*", "https://example.org/public"));

        rules = Spider.parse_robots(robots_url, "Allow: *\nUser-agent: *\nCrawl-delay: 10");
        assertNotNull(rules);
        assertEquals(10.0, rules.crawl_delay("*"));
    }
}
