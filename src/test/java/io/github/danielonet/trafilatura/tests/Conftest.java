// Java port of tests/conftest.py
package io.github.danielonet.trafilatura.tests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import io.github.danielonet.trafilatura.compat.ConfigParser;
import io.github.danielonet.trafilatura.compat.Pair;
import io.github.danielonet.trafilatura.compat.Py;
import io.github.danielonet.trafilatura.courlan.UrlStore;
import io.github.danielonet.trafilatura.Downloads;
import io.github.danielonet.trafilatura.Feeds;
import io.github.danielonet.trafilatura.Settings;
import io.github.danielonet.trafilatura.Sitemaps;
import io.github.danielonet.trafilatura.Spider;
import io.github.danielonet.trafilatura.Utils.Response;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Suite-wide fixtures, registered for every test through JUnit's extension auto-detection
 * (the equivalent of pytest's autouse fixtures): politeness delays are disabled and the
 * default configuration must not be mutated by a test.
 *
 * The canned-response fixture is opted in per test class with {@code @ExtendWith(Conftest.MockNetwork.class)}.
 */
public class Conftest implements BeforeEachCallback, AfterEachCallback {
    /** Directory holding the test data ({@code resources/}, {@code cache/}). */
    public static final Path TEST_DIR = locateTestDir();
    public static final Path RESOURCES_DIR = TEST_DIR.resolve("resources");

    private static Path locateTestDir() {
        try {
            return Path.of(Conftest.class.getResource("/resources").toURI()).getParent();
        } catch (URISyntaxException | NullPointerException err) {
            return Path.of("src/test/resources");
        }
    }

    public static byte[] _resource(String name) {
        try {
            return Files.readAllBytes(RESOURCES_DIR.resolve(name));
        } catch (IOException err) {
            throw new UncheckedIOException(err);
        }
    }

    public static String read_text(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException err) {
            throw new UncheckedIOException(err);
        }
    }

    public static byte[] read_bytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException err) {
            throw new UncheckedIOException(err);
        }
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** URL -> response bytes, or (bytes, final_url) to model a redirect. */
    public static final Map<String, Object> CANNED_RESPONSES = new LinkedHashMap<>();

    static {
        CANNED_RESPONSES.put("https://example.org/", _resource("httpbin_sample.html"));
        CANNED_RESPONSES.put("https://httpbun.com/html", _resource("httpbin_sample.html"));
        CANNED_RESPONSES.put("https://httpbun.com/status/200", b("<html><body><p>status 200 ok</p></body></html>"));
        CANNED_RESPONSES.put("https://httpbun.com/links/2/2", b(
                "<html><head><title>Links</title></head><body><a href=\"/links/2/0\">0</a> <a href=\"/links/2/1\">1</a> 2</body></html>"));
        CANNED_RESPONSES.put("https://www.w3.org/blog/feed/", b(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                        + "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>W3C Blog</title>"
                        + "<entry><link href=\"https://www.w3.org/blog/2024/a-post/\"/></entry></feed>"));
        CANNED_RESPONSES.put("https://httpbun.com/redirect-to?url=https://example.org",
                Pair.of(b("<html><body>redirected</body></html>"), "https://example.org"));
        CANNED_RESPONSES.put("https://www.sitemaps.org/robots.txt", b("Sitemap: https://www.sitemaps.org/sitemap.xml"));
        CANNED_RESPONSES.put("https://www.sitemaps.org/sitemap.xml", _resource("sitemap.xml"));
        CANNED_RESPONSES.put("https://sitemaps.org/sitemap.xml", _resource("sitemap.xml"));
        // feed discovery: a web page advertising a feed via <link rel="alternate">
        CANNED_RESPONSES.put("https://example.com/blog", b(
                "<html><head><title>Blog</title>"
                        + "<link rel=\"alternate\" type=\"application/rss+xml\" href=\"https://example.com/feed.xml\"/>"
                        + "</head><body><p>posts</p></body></html>"));
        CANNED_RESPONSES.put("https://example.com/feed.xml", b(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                        + "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>Blog feed</title>"
                        + "<entry><link href=\"https://example.com/blog/post-1\"/></entry></feed>"));
        // two candidate feeds
        CANNED_RESPONSES.put("https://multi.example.com/", b(
                "<html><head><title>Multi</title>"
                        + "<link rel=\"alternate\" type=\"application/rss+xml\" href=\"https://multi.example.com/feed1.xml\"/>"
                        + "<link rel=\"alternate\" type=\"application/atom+xml\" href=\"https://multi.example.com/feed2.xml\"/>"
                        + "</head><body><p>posts</p></body></html>"));
        CANNED_RESPONSES.put("https://multi.example.com/feed1.xml", b(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                        + "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>First feed</title>"
                        + "<entry><link href=\"https://multi.example.com/post-1\"/></entry></feed>"));
        CANNED_RESPONSES.put("https://multi.example.com/feed2.xml", b(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                        + "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>Second feed</title>"
                        + "<entry><link href=\"https://multi.example.com/post-2\"/></entry></feed>"));
        // a plain page with no feeds at all (exercises the "no usable feed links" path)
        CANNED_RESPONSES.put("https://example.com/plain", b("<html><head><title>Plain</title></head><body><p>nothing</p></body></html>"));
        // Google News fallback feed
        CANNED_RESPONSES.put("https://news.google.com/rss/search?q=site:handelsblatt.com&hl=de&scoring=n&num=100", b(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                        + "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>News</title>"
                        + "<entry><link href=\"https://www.handelsblatt.com/article-1\"/></entry></feed>"));
        // homepage with a meta-refresh pointing at an unreachable URL (failed redirect)
        CANNED_RESPONSES.put("https://example.com/refresh-home", b(
                "<html><head><meta http-equiv=\"refresh\" content=\"0; url=https://example.com/missing\"/>"
                        + "</head><body><p>redirecting</p></body></html>"));
    }

    @SuppressWarnings("unchecked")
    static Response _fake_send(String url, boolean no_ssl, ConfigParser config) {
        Object canned = CANNED_RESPONSES.get(url);
        if (canned == null) {
            return null;
        }
        if (canned instanceof Pair<?, ?> pair) {
            Pair<byte[], String> redirect = (Pair<byte[], String>) pair;
            return new Response(redirect.first(), 200, redirect.second());
        }
        return new Response((byte[]) canned, 200, url);
    }

    static boolean _fake_is_live(String url, ConfigParser config) {
        String prefix = Py.rstrip(url, "/");
        return CANNED_RESPONSES.keySet().stream().anyMatch(known -> known.startsWith(prefix));
    }

    /** {@code @pytest.fixture mock_network}: canned responses instead of network requests. */
    public static class MockNetwork implements BeforeEachCallback, AfterEachCallback {
        private Downloads.RequestSender savedSender;
        private Downloads.LivenessProbe savedProbe;

        @Override
        public void beforeEach(ExtensionContext context) {
            savedSender = Downloads.SEND_REQUEST;
            savedProbe = Downloads.IS_LIVE_PAGE;
            Downloads.SEND_REQUEST = Conftest::_fake_send;
            Downloads.IS_LIVE_PAGE = Conftest::_fake_is_live;
        }

        @Override
        public void afterEach(ExtensionContext context) {
            Downloads.SEND_REQUEST = savedSender;
            Downloads.IS_LIVE_PAGE = savedProbe;
        }
    }

    static ConfigParser _zero_sleep(ConfigParser config) {
        config.set("DEFAULT", "SLEEP_TIME", "0");
        return config;
    }

    static final Map<String, Map<String, String>> _PRISTINE_CONFIG = Settings.DEFAULT_CONFIG.state();

    private DoubleConsumer savedFeedsSleep;
    private DoubleConsumer savedSitemapsSleep;
    private DoubleConsumer savedSpiderSleep;
    private UnaryOperator<ConfigParser> savedConfigHook;
    private UrlStore savedUrlStore;

    /**
     * no_politeness_delay: politeness waits only slow the suite down.
     * Every test also starts with an empty crawler URL store: Spider.URL_STORE is module-global, and URLs
     * left by one test class leak into the crawl output of the next one depending on the class order.
     */
    @Override
    public void beforeEach(ExtensionContext context) {
        savedFeedsSleep = Feeds.SLEEP;
        savedSitemapsSleep = Sitemaps.SLEEP;
        savedSpiderSleep = Spider.SLEEP;
        savedConfigHook = Settings.USE_CONFIG_HOOK;
        savedUrlStore = Spider.URL_STORE;
        Spider.URL_STORE = new UrlStore(false, false);
        Feeds.SLEEP = s -> { };
        Sitemaps.SLEEP = s -> { };
        Spider.SLEEP = s -> { };
        Settings.USE_CONFIG_HOOK = Conftest::_zero_sleep;
    }

    /** pristine_default_config: tests using the default thresholds rely on nobody mutating DEFAULT_CONFIG. */
    @Override
    public void afterEach(ExtensionContext context) {
        Feeds.SLEEP = savedFeedsSleep;
        Sitemaps.SLEEP = savedSitemapsSleep;
        Spider.SLEEP = savedSpiderSleep;
        Settings.USE_CONFIG_HOOK = savedConfigHook;
        Spider.URL_STORE = savedUrlStore;
        assertEquals(_PRISTINE_CONFIG, Settings.DEFAULT_CONFIG.state(),
                "DEFAULT_CONFIG was mutated, copy it with use_config()");
    }
}
