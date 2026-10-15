// Java port of tests/feeds_tests.py
package trafilatura.tests;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import trafilatura.trafilatura.Cli;
import trafilatura.trafilatura.Feeds;
import trafilatura.trafilatura.Feeds.FeedParameters;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static trafilatura.tests.Conftest.RESOURCES_DIR;
import static trafilatura.tests.Conftest.read_text;
import static trafilatura.trafilatura.Feeds.determine_feed;
import static trafilatura.trafilatura.Feeds.extract_links;
import static trafilatura.trafilatura.Feeds.find_feed_urls;
import static trafilatura.trafilatura.Feeds.find_links;
import static trafilatura.trafilatura.Feeds.handle_link_list;
import static trafilatura.trafilatura.Feeds.probe_gnews;
import static trafilatura.trafilatura.Feeds.try_homepage;
import static trafilatura.trafilatura.Settings.DEFAULT_CONFIG;

/** Unit tests for feeds reading and parsing. */
@ExtendWith(Conftest.MockNetwork.class)
class FeedsTests {
    static final String XMLDECL = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";

    /** Test link extraction from an Atom feed. */
    @Test
    void test_atom_extraction() {
        FeedParameters params = new FeedParameters("https://example.org", "example.org", "");
        assertTrue(extract_links(null, params).isEmpty());
        assertEquals(0, extract_links("<html></html>", params).size());

        String teststring = read_text(RESOURCES_DIR.resolve("feed1.atom"));
        assertTrue(extract_links(teststring, params).size() > 0);

        params = new FeedParameters("https://www.dwds.de", "dwds.de", "");
        assertEquals(0, extract_links(XMLDECL
                + "<link type=\"application/atom+xml\" rel=\"self\" href=\"https://www.dwds.de/api/feed/themenglossar/Corona\"/>",
                params).size());

        params = new FeedParameters("http://example.org", "example.org", "http://example.org");
        assertEquals(0, extract_links(XMLDECL + "<link rel=\"self\" href=\"http://example.org/article1/\"/>", params).size());

        params = new FeedParameters("https://example.org", "example.org", "");
        assertEquals(0, extract_links(XMLDECL + "<link type=\"application/atom+xml\" rel=\"self\" href=\"123://api.exe\"/>",
                params).size());

        params = new FeedParameters("http://example.org/", "example.org", "http://example.org");
        assertEquals(List.of("http://example.org/article1/"),
                extract_links(XMLDECL + "<link href=\"http://example.org/article1/\"rest\"/>", params)); // TODO: remove slash?
    }

    static Stream<Arguments> linkCases() {
        return Stream.of(
                Arguments.of("<link href='https://example.org/post' rel='alternate'/>", List.of("https://example.org/post")),
                Arguments.of("<link\nhref=\"https://example.org/post\"/>", List.of("https://example.org/post")),
                Arguments.of("<link href = \"https://example.org/post\"/>", List.of("https://example.org/post")),
                Arguments.of("<link title=\"One > zero\" href=\"https://example.org/post\"/>", List.of("https://example.org/post")),
                Arguments.of("<link href=\"https://example.org/post\" title=\"See href='https://example.org/other'\"/>",
                        List.of("https://example.org/post")),
                Arguments.of("<link href=\"https://example.org/updates/latest\" rel=\"self\"/>", List.of()),
                Arguments.of("<link rel='self' href='https://example.org/updates/latest'/>", List.of()),
                Arguments.of("<link href=\"https://example.org/updates/latest\" type=\"application/atom+xml\"/>", List.of()));
    }

    @ParameterizedTest
    @MethodSource("linkCases")
    void test_atom_link_attributes(String link, List<String> expected) {
        FeedParameters params = new FeedParameters("https://example.org", "example.org", "");
        assertEquals(expected, extract_links("<feed>" + link + "</feed>", params));
    }

    /** Test link extraction from a RSS feed. */
    @Test
    void test_rss_extraction() {
        FeedParameters params = new FeedParameters("http://example.org/", "example.org", "");
        assertEquals(1, extract_links(XMLDECL + "<link>http://example.org/article1/</link>", params).size());
        // CDATA
        assertEquals(List.of("http://example.org/article1/"),
                extract_links(XMLDECL + "<link><![CDATA[http://example.org/article1/]]></link>", params)); // TODO: remove slash?

        // spaces
        params = new FeedParameters("https://www.ak-kurier.de/", "ak-kurier.de", "");
        assertEquals(1, extract_links(XMLDECL
                + "<link>\r\n    https://www.ak-kurier.de/akkurier/www/artikel/108815-sinfonisches-blasorchester-spielt-1500-euro-fuer-kinder-in-drk-krankenhaus-kirchen-ein    </link>",
                params).size());

        params = new FeedParameters("http://example.org", "example.org", "http://example.org");
        assertEquals(0, extract_links(XMLDECL + "<link>http://example.org/</link>", params).size());

        params = new FeedParameters("http://example.org", "example.org", "");
        assertEquals(0, extract_links(XMLDECL + "<link>https://example.org</link>", params).size());

        // malformed link is skipped
        params = new FeedParameters("https://example.org", "example.org", "");
        assertEquals(List.of("https://example.org/article1"),
                extract_links(XMLDECL + "<link>http://[::1</link><link>https://example.org/article1</link>", params));

        params = new FeedParameters("https://www.dwds.de", "dwds.de", "https://www.dwds.de");
        assertEquals(List.of("https://www.dwds.de/api/feed/themenglossar/Corona"),
                extract_links(XMLDECL + "<link>/api/feed/themenglossar/Corona</link>", params));

        params = new FeedParameters("https://example.org", "example.org", "");
        String teststring = read_text(RESOURCES_DIR.resolve("feed2.rss"));
        assertTrue(extract_links(teststring, params).size() > 0);
    }

    /** Test link extraction from a JSON feed. */
    @Test
    void test_json_extraction() {
        // find link
        FeedParameters params = new FeedParameters("https://www.jsonfeed.org", "jsonfeed.org", "");
        assertEquals(1, determine_feed(
                "<html><meta><link rel=\"alternate\" type=\"application/json\" title=\"JSON Feed\" href=\"https://www.jsonfeed.org/feed.json\" />></meta><body/></html>",
                params).size());

        // extract data
        assertTrue(extract_links("{/}", params).isEmpty());

        String teststring = read_text(RESOURCES_DIR.resolve("feed.json"));
        params = new FeedParameters("https://npr.org", "npr.org", "");
        assertEquals(25, extract_links(teststring, params).size());

        // id as a backup
        params = new FeedParameters("https://example.org", "example.org", "");
        List<String> links = extract_links(
                "{\"version\":\"https:\\/\\/jsonfeed.org\\/version\\/1\",\"items\":[{\"id\":\"https://www.example.org/1\",\"title\":\"Test\"}]}",
                params);
        assertEquals(1, links.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "false", "\"invalid\"", "{}"})
    void test_json_feed_invalid_items_container(String items) {
        FeedParameters params = new FeedParameters("https://example.org", "example.org", "");
        assertEquals(List.of(), extract_links("{\"items\": " + items + "}", params));
    }

    @Test
    void test_json_feed_skips_invalid_entries() {
        FeedParameters params = new FeedParameters("https://example.org", "example.org", "");
        String feed = """
                {"items": [
                        null, 42, "invalid", {},
                        {"url": ["https://example.org/not-a-string"]},
                        {"url": 123},
                        {"url": "https://example.org/first"},
                        {"url": false, "id": "https://example.org/second"}
                    ]}""";
        assertEquals(List.of("https://example.org/first", "https://example.org/second"), extract_links(feed, params));
    }

    /** Test helper functions for feed extraction. */
    @Test
    void test_feeds_helpers() {
        FeedParameters params = new FeedParameters("https://example.org", "example.org", "https://example.org");
        Pair<String, String> hostinfo = Courlan.get_hostinfo("https://example.org");
        assertEquals(params.domain, hostinfo.first());
        assertEquals(params.base, hostinfo.second());

        // empty page
        assertEquals(List.of(), find_links("<feed></feed>", params));

        // nothing useful
        assertEquals(0, determine_feed("", params).size());
        assertEquals(List.of(),
                determine_feed("<html><link rel=\"alternate\" type=\"application/rss+xml\" href=\"http://[::1\"/></html>", params));
        assertEquals(0, determine_feed(
                "<html><meta><link rel=\"alternate\" type=\"application/rss+xml\" title=\"Feed\"/></meta><body/></html>", params).size());
        // useful
        assertEquals(1, determine_feed(
                "<html><meta><link rel=\"alternate\" type=\"application/rss+xml\" title=\"Feed\" href=\"https://example.org/blog/feed/\"/></meta><body/></html>",
                params).size());
        assertEquals(1, determine_feed(
                "<html><meta><link rel=\"alternate\" type=\"application/atom+xml\" title=\"Feed\" href=\"https://example.org/blog/feed/\"/></meta><body/></html>",
                params).size());
        assertEquals(1, determine_feed(
                "<html><meta><link rel=\"alternate\" title=\"Feed\" href=\"https://example.org/blog/feed/\" type=\"application/atom+xml\"/></meta><body/></html>",
                params).size());
        assertEquals(1, determine_feed(
                "<html><meta><link rel=\"alternate\" title=\"Feed\" href=\"https://example.org/blog/atom/\"/></meta><body/></html>",
                params).size());
        assertEquals(1, determine_feed(
                "<html><meta><link rel=\"alternate\" href=\"https://www.theguardian.com/international/rss\" title=\"RSS\" type=\"application/rss+xml\"></meta><body/></html>",
                params).size());

        // no comments wanted
        assertEquals(0, determine_feed(
                "<html><meta><link rel=\"alternate\" type=\"application/rss+xml\" title=\"Feed\" href=\"https://example.org/blog/comments-feed/\"/></meta><body/></html>",
                params).size());

        // invalid links
        params = new FeedParameters("example.org", "example.org", "https://example.org"); // fix
        assertEquals(0, determine_feed(
                "<html><meta><link rel=\"alternate\" href=\"12345tralala\" title=\"RSS\" type=\"application/rss+xml\"></meta><body/></html>",
                params).size());

        // detecting in <a>-elements
        params = new FeedParameters("https://example.org", "example.org", "https://example.org");
        assertEquals(List.of("https://example.org/feed.xml"),
                determine_feed("<html><body><a href=\"https://example.org/feed.xml\"><body/></html>", params));
        assertEquals(List.of("https://example.org/feed.atom"),
                determine_feed("<html><body><a href=\"https://example.org/feed.atom\"><body/></html>", params));
        assertEquals(List.of("https://example.org/rss"),
                determine_feed("<html><body><a href=\"https://example.org/rss\"><body/></html>", params));
        assertEquals(List.of("https://example.org/feeds/posts/default/"),
                determine_feed("<html><body><a href=\"https://example.org/feeds/posts/default/\"><body/></html>", params));
        assertEquals(1, determine_feed("<html><body><a href=\"https://www.test.org/cat/?feed=rss\" /><body/></html>", params).size());
        assertEquals(List.of("https://example.org/?feed=rss"),
                determine_feed("<html><body><a href=\"?feed=rss\" /><body/></html>", params));

        // feed discovery
        assertTrue(find_feed_urls("http://").isEmpty());
        assertTrue(find_feed_urls("https://httpbun.com/status/404").isEmpty());

        // feed download and processing
        assertTrue(find_feed_urls("https://www.w3.org/blog/feed/").size() > 0);

        // web page (not a feed) -> discover feed via <link rel="alternate"> then fetch it
        assertEquals(List.of("https://example.com/blog/post-1"), find_feed_urls("https://example.com/blog"));

        // several candidate feeds, paused in between
        List<Double> sleeps = new ArrayList<>();
        var savedSleep = Feeds.SLEEP;
        Feeds.SLEEP = sleeps::add;
        List<String> links;
        try {
            links = find_feed_urls("https://multi.example.com/", null, false, 0.5, DEFAULT_CONFIG);
        } finally {
            Feeds.SLEEP = savedSleep;
        }
        assertEquals(List.of("https://multi.example.com/post-1", "https://multi.example.com/post-2"), links);
        assertEquals(List.of(0.5), sleeps);

        // web page that advertises no feed -> no usable feed links
        assertEquals(List.of(), find_feed_urls("https://example.com/plain"));

        // Feedburner/Google links
        assertEquals(List.of("https://feedproxy.google.com/ABCD"), handle_link_list(List.of("https://feedproxy.google.com/ABCD"), params));
        // override failed checks
        assertEquals(List.of("https://feedburner.com/kat/1"), handle_link_list(List.of("https://feedburner.com/kat/1"), params));
        // diverging domain names
        assertTrue(handle_link_list(List.of("https://www.software.info/1"), params).isEmpty());

        // Gnews
        params = new FeedParameters("https://www.handelsblatt.com", "handelsblatt.com", "https://www.handelsblatt.com", false, "de");
        assertEquals(List.of("https://www.handelsblatt.com/article-1"), probe_gnews(params, null));
    }

    /** Type attributes with charset suffixes or uppercase must still be recognized. */
    @Test
    void test_determine_feed_type_normalization() {
        FeedParameters params = new FeedParameters("https://example.org", "example.org", "https://example.org");
        String htmlstring = "<html><head>"
                + "<link rel=\"alternate\" type=\"application/rss+xml; charset=UTF-8\" href=\"/messy.xml\"/>"
                + "<link rel=\"alternate\" type=\"APPLICATION/ATOM+XML\" href=\"/upper.xml\"/>"
                + "</head><body/></html>";
        assertEquals(List.of("https://example.org/messy.xml", "https://example.org/upper.xml"), determine_feed(htmlstring, params));
    }

    /** Regression: try_homepage must forward external and sleep_time, not reset them. */
    @Test
    void test_try_homepage_forwards_args() {
        Map<String, Object> captured = new LinkedHashMap<>();
        var saved = Feeds.FIND_FEED_URLS;
        Feeds.FIND_FEED_URLS = (url, target_lang, external, sleep_time, config) -> {
            captured.put("url", url);
            captured.put("target_lang", target_lang);
            captured.put("external", external);
            captured.put("sleep_time", sleep_time);
            captured.put("config", config);
            return List.of();
        };
        try {
            try_homepage("https://example.org", "en", true, 9.0);
        } finally {
            Feeds.FIND_FEED_URLS = saved;
        }
        assertEquals(T.map("url", "https://example.org", "target_lang", "en", "external", true, "sleep_time", 9.0,
                "config", DEFAULT_CONFIG), captured);
    }

    /** Test command-line interface with respect to feeds. */
    @Test
    void test_cli_behavior() {
        assertDoesNotThrow(() -> Cli.main_argv(List.of("", "--list", "--feed", "https://httpbun.com/xml")));
    }
}
