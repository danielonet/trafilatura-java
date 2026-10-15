// Java port of tests/downloads_tests.py
//
// The Python suite drives urllib3 through MagicMock pools; here the same request paths are
// exercised against a local com.sun.net.httpserver server (with SSRF protection off), and
// fetch_response's SSL fallback through the Downloads.SEND_REQUEST seam.
// pycurl, brotli and zstd are not available in Java: those tests and branches are skipped.
// Tests reaching httpbun.com are tagged "network" and excluded by default.
package trafilatura.tests;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleConsumer;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import trafilatura.compat.ConfigParser;
import trafilatura.compat.Pair;
import trafilatura.courlan.UrlStore;
import trafilatura.trafilatura.Cli;
import trafilatura.trafilatura.CliUtils;
import trafilatura.trafilatura.Core;
import trafilatura.trafilatura.Downloads;
import trafilatura.trafilatura.Settings;
import trafilatura.trafilatura.Settings.Extractor;
import trafilatura.trafilatura.Utils;
import trafilatura.trafilatura.Utils.Response;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static trafilatura.compat.Py.str;
import static trafilatura.tests.T.b;
import static trafilatura.trafilatura.Downloads.DEFAULT_HEADERS;
import static trafilatura.trafilatura.Downloads.USER_AGENT;
import static trafilatura.trafilatura.Downloads._determine_headers;
import static trafilatura.trafilatura.Downloads._initiate_pool;
import static trafilatura.trafilatura.Downloads._is_suitable_response;
import static trafilatura.trafilatura.Downloads._parse_curl_headers;
import static trafilatura.trafilatura.Downloads._send_urllib_request;
import static trafilatura.trafilatura.Downloads._urllib3_is_live_page;
import static trafilatura.trafilatura.Downloads.add_to_compressed_dict;
import static trafilatura.trafilatura.Downloads.fetch_url;
import static trafilatura.trafilatura.Downloads.is_live_page;
import static trafilatura.trafilatura.Downloads.load_download_buffer;
import static trafilatura.trafilatura.Settings.DEFAULT_CONFIG;
import static trafilatura.trafilatura.Settings.args_to_extractor;
import static trafilatura.trafilatura.Settings.use_config;
import static trafilatura.trafilatura.Utils.MAX_MEMBERS;
import static trafilatura.trafilatura.Utils.decode_file;
import static trafilatura.trafilatura.Utils.handle_compressed_file;
import static trafilatura.trafilatura.Utils.load_html;

public class DownloadsTests {
    // independent copy: must not mutate the session-wide DEFAULT_CONFIG
    static final ConfigParser ZERO_CONFIG = use_config();

    static {
        ZERO_CONFIG.set("DEFAULT", "MIN_OUTPUT_SIZE", "0");
        ZERO_CONFIG.set("DEFAULT", "MIN_EXTRACTED_SIZE", "0");
    }

    static final ConfigParser UA_CONFIG = use_config(Conftest.RESOURCES_DIR.resolve("newsettings.cfg").toString());

    static final Extractor DEFAULT_OPTS = new Extractor(Extractor.kw().config(DEFAULT_CONFIG));

    private Downloads.RequestSender savedSender;
    private DoubleConsumer savedSleep;
    private String savedProxy;

    /** Force global objects to be re-created. */
    static void _reset_downloads_global_objects() {
        Downloads.PROXY_URL = null;
        Downloads.HTTP_POOL = null;
        Downloads.NO_CERT_POOL = null;
    }

    @BeforeEach
    void _reset_downloads_globals() {
        savedSender = Downloads.SEND_REQUEST;
        savedSleep = Downloads.SLEEP;
        savedProxy = Downloads.PROXY_URL;
        _reset_downloads_global_objects();
    }

    @AfterEach
    void _restore_downloads_globals() {
        _reset_downloads_global_objects();
        Downloads.SEND_REQUEST = savedSender;
        Downloads.SLEEP = savedSleep;
        Downloads.PROXY_URL = savedProxy;
    }

    // --- local server helpers ---

    /** A local HTTP server; the handler gets (exchange, path). Closed with try-with-resources. */
    static final class LocalServer implements AutoCloseable {
        final HttpServer server;
        final String base;

        LocalServer(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    handler.handle(exchange);
                }
            });
            server.start();
            base = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    static void reply(HttpExchange exchange, int status, byte[] body, String... headers) throws IOException {
        for (int i = 0; i < headers.length; i += 2) {
            exchange.getResponseHeaders().add(headers[i], headers[i + 1]);
        }
        boolean head = exchange.getRequestMethod().equals("HEAD");
        exchange.sendResponseHeaders(status, head || body.length == 0 ? -1 : body.length);
        if (!head && body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    /** A copy of the default config with the SSRF guard off, so that loopback servers are reachable. */
    static ConfigParser localConfig() {
        ConfigParser config = use_config();
        config.set("DEFAULT", "SSRF_PROTECTION", "off");
        return config;
    }

    static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        } catch (IOException err) {
            throw new IllegalStateException(err);
        }
        return out.toByteArray();
    }

    static byte[] zlib(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(out, new Deflater())) {
            z.write(data);
        } catch (IOException err) {
            throw new IllegalStateException(err);
        }
        return out.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] repeat(byte[] part, int times) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < times; i++) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] zeros(int size) {
        byte[] data = new byte[size];
        Arrays.fill(data, (byte) '0');
        return data;
    }

    // --- tests ---

    /** regression: a request aborted by MAX_FILE_SIZE mid-stream fails cleanly and leaves the pool usable. */
    @Test
    void test_urllib_request_releases_conn_on_oversize() throws IOException {
        byte[] big = repeat(b("x"), 1 << 17);
        try (LocalServer server = new LocalServer(exchange -> reply(exchange, 200,
                exchange.getRequestURI().getPath().equals("/big") ? big : b("<html><body><p>ABC</p></body></html>")))) {
            ConfigParser config = localConfig();
            config.set("DEFAULT", "MAX_FILE_SIZE", "1000");
            assertNull(_send_urllib_request(server.base + "/big", false, config));
            // the pool is still usable afterwards
            assertNotNull(_send_urllib_request(server.base + "/small", false, config));
        }
    }

    /** regression: a relative redirect target must not reach Response.url. */
    @ParameterizedTest
    @CsvSource({
        // request URI, no redirect involved
        "/news/news, /news/news, /news/news",
        // relative Location header after a redirect
        "/news/news, /section/, /section/",
    })
    void test_urllib_request_resolves_relative_url(String path, String location, String expected) throws IOException {
        try (LocalServer server = new LocalServer(exchange -> {
            if (!exchange.getRequestURI().getPath().equals(location)) {
                reply(exchange, 301, new byte[0], "Location", location);
            } else {
                reply(exchange, 200, b("<html><body><p>ABC</p></body></html>"));
            }
        })) {
            Response result = _send_urllib_request(server.base + path, false, localConfig());
            assertEquals(server.base + expected, result.url);
        }
    }

    /** Test if the Response class is functioning as expected. */
    @Test
    void test_response_object() {
        byte[] my_html = b("<html><body><p>ABC</p></body></html>");
        Response resp = new Response(my_html, 200, "https://example.org");
        assertTrue(resp.isTruthy());
        resp.store_headers(Map.of("X-Header", "xyz"));
        assertTrue(resp.headers.containsKey("x-header"));
        resp.decode_data(true);
        assertEquals(new String(my_html, StandardCharsets.UTF_8), resp.html);
        assertEquals(resp.html, str(resp));
        List<String> keys = new ArrayList<>(resp.as_dict().keySet());
        Collections.sort(keys);
        assertEquals(List.of("data", "headers", "html", "status", "url"), keys);

        // response object: data, status, url
        Response response = new Response(new byte[0], 200, "https://httpbin.org/encoding/utf8");
        for (int size : new int[] {10000000, 1}) {
            response.data = repeat(b("ABC"), size);
            assertFalse(_is_suitable_response(response.url, response, DEFAULT_OPTS));
        }
        // straight handling of response object
        response.data = Conftest._resource("utf8.html");
        assertTrue(_is_suitable_response(response.url, response, DEFAULT_OPTS));
        assertNotNull(load_html(response));
        // nothing to see here
        assertNull(Core.extract(response, Core.kw().url(response.url).config(ZERO_CONFIG)));
    }

    /** Test if pages are available on the network. */
    @Test
    @Tag("network")
    void test_is_live_page() {
        assertTrue(_urllib3_is_live_page("https://httpbun.com/status/301", DEFAULT_CONFIG));
        assertFalse(_urllib3_is_live_page("https://httpbun.com/status/404", DEFAULT_CONFIG));
        assertFalse(is_live_page("https://httpbun.com/status/403"));
    }

    /** Test URL fetching. */
    @Test
    @Tag("network")
    void test_fetch() {
        // sanity check
        assertNull(_send_urllib_request("", true, DEFAULT_CONFIG));

        // fetch_url
        assertNull(fetch_url("#@1234"));
        assertNull(fetch_url("https://httpbun.com/status/404"));

        // no SSL, no decoding
        String url = "https://httpbun.com/status/200";
        for (boolean no_ssl : new boolean[] {true, false}) {
            Response response = _send_urllib_request(url, no_ssl, DEFAULT_CONFIG);
            String data = new String(response.data, StandardCharsets.UTF_8);
            assertTrue(data.contains("200"));
            assertTrue(data.contains("OK"));
            assertTrue(response.headers.get("x-powered-by").startsWith("httpbun"));
        }

        // test handling of redirects
        String res = fetch_url("https://httpbun.com/redirect/2");
        assertTrue(res.length() > 100); // We followed redirects and downloaded something in the end
        ConfigParser new_config = use_config(); // get a new config instance to avoid mutating the default one
        // patch max directs: limit to 0. We won't fetch any page as a result
        new_config.set("DEFAULT", "MAX_REDIRECTS", "0");
        assertNull(fetch_url("https://httpbun.com/redirect/1", new_config));

        // test timeout
        new_config.set("DEFAULT", "DOWNLOAD_TIMEOUT", "1");
        assertNull(_send_urllib_request("https://httpbun.com/delay/2", true, new_config));

        // test MAX_FILE_SIZE
        ConfigParser size_config = use_config();
        size_config.set("DEFAULT", "MAX_FILE_SIZE", "1");
        assertNull(_send_urllib_request("https://httpbun.com/html", true, size_config));
    }

    /** Offline counterpart of test_fetch: redirects, MAX_REDIRECTS, timeouts and MAX_FILE_SIZE on a local server. */
    @Test
    void test_fetch_local() throws IOException {
        try (LocalServer server = new LocalServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/redirect/") && !path.equals("/redirect/0")) {
                reply(exchange, 302, new byte[0], "Location", "/redirect/" + (Integer.parseInt(path.substring(10)) - 1));
            } else if (path.equals("/delay")) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException err) {
                    Thread.currentThread().interrupt();
                }
                reply(exchange, 200, b("late"));
            } else if (path.equals("/status/404")) {
                reply(exchange, 404, b("not found"));
            } else {
                reply(exchange, 200, Conftest._resource("httpbin_sample.html"), "X-Powered-By", "local");
            }
        })) {
            ConfigParser config = localConfig();
            assertNull(fetch_url(server.base + "/status/404", config));
            Response response = _send_urllib_request(server.base + "/html", true, config);
            assertEquals(200, response.status);
            assertEquals("local", response.headers.get("x-powered-by"));

            // redirects are followed up to MAX_REDIRECTS
            assertTrue(fetch_url(server.base + "/redirect/2", config).length() > 100);
            config.set("DEFAULT", "MAX_REDIRECTS", "0");
            assertNull(fetch_url(server.base + "/redirect/1", config));

            // timeout
            ConfigParser timeout_config = localConfig();
            timeout_config.set("DEFAULT", "DOWNLOAD_TIMEOUT", "1");
            assertNull(_send_urllib_request(server.base + "/delay", true, timeout_config));

            // MAX_FILE_SIZE
            ConfigParser size_config = localConfig();
            size_config.set("DEFAULT", "MAX_FILE_SIZE", "1");
            assertNull(_send_urllib_request(server.base + "/html", true, size_config));
        }
    }

    /** SSRF filter blocks loopback, private, and link-local addresses. */
    @Test
    void test_ssrf_protection() throws IOException {
        // unit: address classification (Java maps ::ffff:a.b.c.d to its IPv4 address itself)
        assertFalse(Downloads.is_global(InetAddress.getByName("127.0.0.1")));
        assertFalse(Downloads.is_global(InetAddress.getByName("::ffff:10.0.0.1")));
        assertFalse(Downloads.is_global(InetAddress.getByName("::1")));
        assertTrue(Downloads.is_global(InetAddress.getByName("93.184.216.34")));

        // unit: _vet_peer
        IOException err = assertThrows(IOException.class, () -> Downloads._vet_peer("10.0.0.1"));
        assertTrue(err.getMessage().contains("SSRF protection"));
        Downloads._vet_peer("93.184.216.34"); // public: no exception

        // local server: a live listener is needed
        try (LocalServer server = new LocalServer(exchange -> reply(exchange, 200, b("<html><body><p>local</p></body></html>")))) {
            String url = server.base + "/";
            ConfigParser config = new ConfigParser();
            config.read_dict(DEFAULT_CONFIG.state());
            config.set("DEFAULT", "SSRF_PROTECTION", "off");

            // blocked by default
            assertNull(_send_urllib_request(url, false, DEFAULT_CONFIG));
            // opt-out honored in the same process: no pool reset, the setting is not latched
            Response response = _send_urllib_request(url, false, config);
            assertNotNull(response);
            assertEquals(200, response.status);
            // flipped back on without a reset: blocked again
            assertNull(_send_urllib_request(url, false, DEFAULT_CONFIG));

            assertFalse(_urllib3_is_live_page(url, DEFAULT_CONFIG));
            assertTrue(_urllib3_is_live_page(url, config));
            assertFalse(is_live_page(url));
            assertTrue(is_live_page(url, config));
        }
    }

    /** Long redirect chains count as live, transient statuses are retried, Retry-After sleeps are capped. */
    @Test
    void test_urllib3_live_page_retries() throws IOException {
        List<String> hits = Collections.synchronizedList(new ArrayList<>());
        try (LocalServer server = new LocalServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.add(path);
            if (path.startsWith("/chain/") && !path.equals("/chain/0")) {
                reply(exchange, 301, new byte[0], "Location", "/chain/" + (Integer.parseInt(path.substring(7)) - 1));
            } else if ((path.equals("/flaky") && Collections.frequency(hits, "/flaky") == 1) || path.equals("/stall")) {
                reply(exchange, 503, new byte[0], "Retry-After", "86400");
            } else {
                reply(exchange, 200, new byte[0]);
            }
        })) {
            ConfigParser config = localConfig();
            List<Double> naps = new ArrayList<>();
            Downloads.SLEEP = naps::add;

            assertTrue(_urllib3_is_live_page(server.base + "/chain/2", config));
            assertTrue(_urllib3_is_live_page(server.base + "/chain/3", config));
            assertTrue(_urllib3_is_live_page(server.base + "/flaky", config));
            assertEquals(List.of((double) Downloads.MAX_BACKOFF), naps);
            naps.clear();
            assertFalse(_urllib3_is_live_page(server.base + "/stall", config));
            assertEquals(List.of((double) Downloads.MAX_BACKOFF, (double) Downloads.MAX_BACKOFF), naps);
        }
    }

    /** no_ssl skips cert verification in the pool; the default verifies. */
    @Test
    void test_no_ssl_pool() throws Exception {
        HttpClient insecure = _initiate_pool(DEFAULT_CONFIG, true);
        HttpClient secure = _initiate_pool(DEFAULT_CONFIG, false);
        assertNotEquals(SSLContext.getDefault(), insecure.sslContext());
        assertSame(SSLContext.getDefault(), secure.sslContext());
        // pools are cached
        assertSame(insecure, _initiate_pool(DEFAULT_CONFIG, true));
        assertSame(secure, _initiate_pool(DEFAULT_CONFIG, false));
    }

    /** An SSL error triggers a retry with no_ssl=true, handled in fetch_response. */
    @Test
    void test_urllib_request_ssl_retry() {
        Response ok = new Response(b("<html>ok</html>"), 200, "https://ssl.example/");
        List<Boolean> calls = new ArrayList<>();
        // first request raises the SSL signal, the retry succeeds
        Downloads.SEND_REQUEST = (url, no_ssl, config) -> {
            calls.add(no_ssl);
            if (!no_ssl) {
                throw new Downloads._SSLRetryError("bad cert", new SSLHandshakeException("bad cert"));
            }
            return ok;
        };
        assertNotNull(Downloads.fetch_response("https://ssl.example/"));
        assertEquals(List.of(false, true), calls);

        // both attempts fail: None, exactly one retry
        calls.clear();
        Downloads.SEND_REQUEST = (url, no_ssl, config) -> {
            calls.add(no_ssl);
            if (!no_ssl) {
                throw new Downloads._SSLRetryError("bad cert", new SSLHandshakeException("bad cert"));
            }
            return null;
        };
        assertNull(Downloads.fetch_response("https://ssl.example/"));
        assertEquals(2, calls.size());

        // fallback disabled: no unverified retry
        calls.clear();
        ConfigParser config = use_config();
        config.set("DEFAULT", "INSECURE_SSL_FALLBACK", "off");
        Downloads.SEND_REQUEST = (url, no_ssl, cfg) -> {
            calls.add(no_ssl);
            if (!no_ssl) {
                throw new Downloads._SSLRetryError("bad cert", new SSLHandshakeException("bad cert"));
            }
            return ok;
        };
        assertNull(Downloads.fetch_response("https://ssl.example/", config));
        assertEquals(1, calls.size());
    }

    /** The per-request MAX_FILE_SIZE reaches the decompression stage. */
    @Test
    void test_fetch_response_decode_cap() throws IOException {
        byte[] body = gzip(zeros(25_000_000)); // expands beyond the 20MB default cap
        // served without Content-Encoding: decompressed by handle_compressed_file
        try (LocalServer server = new LocalServer(exchange -> reply(exchange, 200, body))) {
            ConfigParser raised = localConfig();
            raised.set("DEFAULT", "MAX_FILE_SIZE", "30000000");
            assertTrue(Downloads.fetch_response(server.base + "/", true, false, raised).html.startsWith("000"));
            // default cap: decompression refused, raw bytes kept
            assertFalse(Downloads.fetch_response(server.base + "/", true, false, localConfig()).html.startsWith("000"));
        }
    }

    /** The user's MAX_FILE_SIZE reaches the decompression of bytes passed to extract. */
    @Test
    void test_extract_decode_cap() {
        String body = "<p>" + "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(5) + "</p>";
        byte[] gz = gzip(b("<html><body><article>" + body + "</article></body></html>"));
        assertTrue(Core.extract(gz).contains("Lorem ipsum"));
        ConfigParser lowered = use_config();
        lowered.set("DEFAULT", "MAX_FILE_SIZE", "100");
        assertNull(Core.extract(gz, Core.kw().config(lowered)));
    }

    /** PROXY_URL disables SSRF filtering (the SOCKS pool itself is not supported in Java). */
    @Test
    void test_proxy_plumbing() {
        assertTrue(Downloads._ssrf_active(DEFAULT_CONFIG));
        Downloads.PROXY_URL = "socks5://localhost:1080";
        assertFalse(Downloads._ssrf_active(DEFAULT_CONFIG));
    }

    /** Test how configuration options are read and stored. */
    @Test
    void test_config() {
        // default accept-encoding: brotli and zstd are not available
        assertTrue(Set.of(DEFAULT_HEADERS.get("accept-encoding").split(",")).containsAll(List.of("deflate", "gzip")));
        // default user-agent
        Map<String, String> defaults = _determine_headers(DEFAULT_CONFIG);
        assertEquals(USER_AGENT, defaults.get("User-Agent"));
        assertFalse(defaults.containsKey("Cookie"));
        // user-agents rotation
        Map<String, String> custom = _determine_headers(UA_CONFIG);
        assertTrue(List.of("Chrome", "Firefox").contains(custom.get("User-Agent")));
        assertEquals("yummy_cookie=choco; tasty_cookie=strawberry", custom.get("Cookie"));
    }

    /** Configs missing USER_AGENTS or COOKIE must not crash header selection. */
    @Test
    void test_partial_config_headers() {
        ConfigParser partial = new ConfigParser();
        partial.read_dict(Map.of("DEFAULT", Map.of("USER_AGENTS", "Firefox")));
        Map<String, String> expected = new java.util.LinkedHashMap<>(DEFAULT_HEADERS);
        expected.put("User-Agent", "Firefox");
        assertEquals(expected, _determine_headers(partial));
        // nothing set: fall back to default headers
        assertEquals(DEFAULT_HEADERS, _determine_headers(new ConfigParser()));
    }

    /** Only the last response of a redirect chain should be kept. */
    @Test
    void test_parse_curl_headers() {
        byte[] raw = b("HTTP/1.1 301 Moved Permanently\r\n"
                + "Location: /final\r\n"
                + "\r\n"
                + "HTTP/2 200\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "X-Colon: a:b:c\r\n"
                + "junk line without separator\r\n"
                + "\r\n");
        assertEquals(Map.of("Content-Type", "text/html; charset=utf-8", "X-Colon", "a:b:c"), _parse_curl_headers(raw));
        assertEquals(Map.of(), _parse_curl_headers(new byte[0]));
    }

    /** Test how responses are being decoded. */
    @Test
    void test_decode() {
        String html_string = "<html><head/><body><div>ABC</div></body></html>";
        byte[] html_bytes = b(html_string);
        assertNotNull(decode_file(b(" ")));

        for (byte[] compressed_string : List.of(gzip(html_bytes), zlib(html_bytes))) {
            assertArrayEquals(html_bytes, handle_compressed_file(compressed_string));
            assertEquals(html_string, decode_file(compressed_string));
        }

        // errors
        for (byte[] bad_file : List.of(b("äöüß"), new byte[] {0x1f, (byte) 0x8b, 0x08, 'a', 'b', 'c'},
                new byte[] {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 'a', 'b', 'c'})) {
            assertArrayEquals(bad_file, handle_compressed_file(bad_file));
        }

        // multi-member gzip streams are concatenated (pigz/bgzip output)
        byte[] multi = concat(gzip(b("<html>part1 ")), gzip(b("part2</html>")));
        assertArrayEquals(b("<html>part1 part2</html>"), handle_compressed_file(multi));

        // decompression-bomb guard: content expanding beyond MAX_FILE_SIZE is left unchanged
        byte[] bomb = gzip(zeros(25_000_000));
        assertArrayEquals(bomb, handle_compressed_file(bomb));
        // a raised per-request cap is honored
        assertArrayEquals(zeros(25_000_000), handle_compressed_file(bomb, 30_000_000));

        // member flood is rejected
        byte[] member = gzip(b("a"));
        byte[] flood = repeat(member, MAX_MEMBERS + 1);
        assertArrayEquals(flood, handle_compressed_file(flood));
        assertArrayEquals(repeat(b("a"), MAX_MEMBERS), handle_compressed_file(repeat(member, MAX_MEMBERS)));
        byte[] padded = concat(gzip(b("<html>a ")), new byte[8], gzip(b("b</html>")), new byte[8]);
        assertArrayEquals(b("<html>a b</html>"), handle_compressed_file(padded));
    }

    /** Test creation, modification and download of URL queues. */
    @Test
    @ExtendWith(Conftest.MockNetwork.class)
    void test_queue() throws InterruptedException {
        // test conversion and storage
        UrlStore url_store = add_to_compressed_dict(List.of("ftps://www.example.org/", "http://"));
        assertNotNull(url_store);

        // blacklist and URL filter
        url_store = add_to_compressed_dict(List.of("https://example.org/page", "https://example.org/skip"),
                Set.of("example.org/skip"), List.of("/page"), null, false, false);
        assertEquals(List.of("https://example.org/page"), url_store.dump_urls());

        // response download buffer (empty input, no network)
        assertFalse(Downloads.buffered_response_downloads(List.of(), 1).hasNext());

        // download buffer
        List<String> inputurls = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            for (int j = 1; j <= 3; j++) {
                inputurls.add("https://test" + i + ".org/" + j);
            }
        }
        url_store = add_to_compressed_dict(inputurls);
        Pair<List<String>, UrlStore> buffer = load_download_buffer(url_store, 5);
        assertEquals(6, buffer.first().size());
        Thread.sleep(250);
        buffer = load_download_buffer(url_store, 0.1);
        assertEquals(6, buffer.first().size());

        // CLI args
        url_store = add_to_compressed_dict(List.of("https://www.example.org/"));
        Cli.Args args = Cli.parse_args("--list");
        assertEquals(0, CliUtils.url_processing_pipeline(args, url_store));

        // single/multiprocessing
        args = Cli.parse_args("-v");
        inputurls = new ArrayList<>();
        for (int i : new int[] {301, 304, 200, 300, 400, 505}) {
            inputurls.add("https://httpbun.com/status/" + i);
        }
        url_store = add_to_compressed_dict(inputurls);
        args.archived = true;
        args.config_file = Conftest.RESOURCES_DIR.resolve("newsettings.cfg").toString();
        Extractor options = args_to_extractor(args);
        options.config.set("DEFAULT", "SLEEP_TIME", "0.2");
        Pair<List<String>, Integer> results = CliUtils.download_queue_processing(url_store, args, -1, options);
        assertEquals(5, results.first().size());
        assertEquals(-1, results.second());
    }
}
