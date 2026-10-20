// Java port of trafilatura/downloads.py
package io.github.danielonet.trafilatura;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.danielonet.trafilatura.compat.ConfigParser;
import io.github.danielonet.trafilatura.compat.Pair;
import io.github.danielonet.trafilatura.compat.Py;
import io.github.danielonet.trafilatura.courlan.Courlan;
import io.github.danielonet.trafilatura.courlan.UrlStore;
import io.github.danielonet.trafilatura.Settings.Extractor;
import io.github.danielonet.trafilatura.Utils.Response;

import static io.github.danielonet.trafilatura.Settings.DEFAULT_CONFIG;
import static io.github.danielonet.trafilatura.Utils.URL_BLACKLIST_REGEX;
import static io.github.danielonet.trafilatura.Utils.is_acceptable_length;
import static io.github.danielonet.trafilatura.Utils.make_chunks;

/** All functions needed to steer and execute downloads of web documents. */
public final class Downloads {
    private Downloads() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Downloads.class);

    // SOCKS proxies (urllib3.contrib.socks) are not supported by java.net.http
    public static String PROXY_URL = System.getenv("http_proxy");
    // pycurl is an optional dependency of the Python package, not available here
    public static final boolean HAS_PYCURL = false;

    static {
        if (PROXY_URL != null && !PROXY_URL.isEmpty()) {
            LOGGER.warn("http_proxy is set but SOCKS proxies are not supported, ignoring: {}", PROXY_URL);
        }
    }

    /** Request sender (url, no_ssl, config): replaceable in tests, like monkeypatching _send_urllib_request. */
    @FunctionalInterface
    public interface RequestSender {
        Response send(String url, boolean no_ssl, ConfigParser config);
    }

    /** Liveness probe (url, config): replaceable in tests, like monkeypatching _urllib3_is_live_page. */
    @FunctionalInterface
    public interface LivenessProbe {
        boolean test(String url, ConfigParser config);
    }

    public static RequestSender SEND_REQUEST = Downloads::_send_urllib_request;
    public static LivenessProbe IS_LIVE_PAGE = Downloads::_urllib3_is_live_page;

    public static HttpClient HTTP_POOL = null;
    public static HttpClient NO_CERT_POOL = null;

    // advertises exactly the encodings that can be decoded
    public static final Map<String, String> DEFAULT_HEADERS = new LinkedHashMap<>();
    // identifies the Java port so site owners reach this project, not upstream; robots.txt rules
    // for "trafilatura" still apply, as agents are matched by substring
    public static final String USER_AGENT = "trafilatura-java/" + TrafilaturaInit.PORT_VERSION
            + " (+https://github.com/danielonet/trafilatura-java)";

    static {
        // urllib3.util.make_headers(accept_encoding=True): lowercase key
        DEFAULT_HEADERS.put("accept-encoding", "gzip,deflate");
        DEFAULT_HEADERS.put("User-Agent", USER_AGENT);
    }

    // includes unofficial codes: https://en.wikipedia.org/wiki/List_of_HTTP_status_codes#Unofficial_codes
    public static final Set<Integer> FORCE_STATUS = Set.of(
            429, 499, 500, 502, 503, 504, 509, 520, 521, 522, 523, 524, 525, 526, 527, 530, 598);

    // cap in seconds for backoff and Retry-After sleeps
    public static final int MAX_BACKOFF = 30;

    // status retries, kept apart from the redirect budget
    public static final int MAX_STATUS_RETRIES = 2;

    private static final Random RANDOM = new Random();

    /** Internal signal: the secure transfer failed for SSL reasons, retry without verification. */
    public static class _SSLRetryError extends RuntimeException {
        public _SSLRetryError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** SSRF filtering applies only to direct connections: with a proxy, the vetted address would be the proxy's. */
    public static boolean _ssrf_active(ConfigParser config) {
        if (PROXY_URL != null && !PROXY_URL.isEmpty()) {
            return false;
        }
        try {
            return config.getboolean("DEFAULT", "SSRF_PROTECTION");
        } catch (RuntimeException err) {
            return true;
        }
    }

    /** Python's {@code ipaddress.ip_address(addr).is_global} (IPv4-mapped IPv6 handled as IPv4). */
    public static boolean is_global(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return !(first == 0 // this network
                    || (first == 100 && second >= 64 && second <= 127) // shared address space
                    || (first == 192 && second == 0 && (b[2] & 0xff) == 0) // IETF protocol assignments
                    || (first == 192 && second == 0 && (b[2] & 0xff) == 2) // TEST-NET-1
                    || (first == 198 && (second == 18 || second == 19)) // benchmarking
                    || (first == 198 && second == 51 && (b[2] & 0xff) == 100) // TEST-NET-2
                    || (first == 203 && second == 0 && (b[2] & 0xff) == 113) // TEST-NET-3
                    || first >= 240); // reserved and broadcast
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                mapped &= b[i] == 0;
            }
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return is_global(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException err) {
                    return false;
                }
            }
            return !((first & 0xfe) == 0xfc // unique local
                    || (first == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) // documentation
                    || (first == 0x01 && b[1] == 0)); // discard prefix
        }
        return false;
    }

    /** Raise on a non-global peer address (vetted before connecting, on every redirect hop). */
    public static void _vet_peer(String host) throws IOException {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        for (InetAddress address : InetAddress.getAllByName(bare)) {
            if (!is_global(address)) {
                throw new IOException("SSRF protection: connection to non-public address blocked: " + address.getHostAddress());
            }
        }
    }

    /** Trust manager accepting any certificate and host name (urllib3 cert_reqs="CERT_NONE"). */
    private static final class TrustAll extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, java.net.Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, java.net.Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** Configure the download client according to user-defined settings. */
    public static HttpClient create_pool(boolean no_ssl, ConfigParser config) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(config.getint("DEFAULT", "DOWNLOAD_TIMEOUT")));
        if (no_ssl) {
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[] {new TrustAll()}, new SecureRandom());
                builder.sslContext(context);
            } catch (java.security.GeneralSecurityException err) {
                throw new IllegalStateException(err);
            }
        }
        return builder.build();
    }

    /** Create a download client according to options in the config file and HTTPS setting. */
    public static synchronized HttpClient _initiate_pool(ConfigParser config, boolean no_ssl) {
        HttpClient pool = no_ssl ? NO_CERT_POOL : HTTP_POOL;
        if (pool == null) {
            pool = create_pool(no_ssl, config);
            if (no_ssl) {
                NO_CERT_POOL = pool;
            } else {
                HTTP_POOL = pool;
            }
        }
        return pool;
    }

    /** Overlay user-agent and cookie from the config file on the default headers. */
    public static Map<String, String> _determine_headers(ConfigParser config) {
        Map<String, String> headers = new LinkedHashMap<>(DEFAULT_HEADERS);
        // rotate over a series of user-agents
        String myagents = Py.strip(config.get("DEFAULT", "USER_AGENTS", ""));
        if (!myagents.isEmpty()) {
            List<String> agents = Py.splitlines(myagents);
            headers.put("User-Agent", agents.get(RANDOM.nextInt(agents.size())));
        }
        // https://developer.mozilla.org/en-US/docs/Web/HTTP/Cookies
        // todo: support for several cookies?
        String mycookie = config.get("DEFAULT", "COOKIE", null);
        if (mycookie != null && !mycookie.isEmpty()) {
            headers.put("Cookie", mycookie);
        }
        return headers;
    }

    private static HttpResponse<InputStream> send(HttpClient client, String method, String url,
            Map<String, String> headers, int timeout) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeout))
                .method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private static String headerValue(HttpResponse<?> response, String name) {
        List<String> values = response.headers().allValues(name);
        return values.isEmpty() ? null : String.join(", ", values);
    }

    /** Seconds to sleep before a status retry (Retry-After honoured, capped at MAX_BACKOFF). */
    private static double retryDelay(HttpResponse<?> response, double backoff_factor, int consecutive) {
        String retryAfter = headerValue(response, "retry-after");
        if (retryAfter != null && Py.isdigit(Py.strip(retryAfter))) {
            return Math.min(MAX_BACKOFF, Integer.parseInt(Py.strip(retryAfter)));
        }
        if (consecutive <= 1) {
            return 0;
        }
        return Math.min(MAX_BACKOFF, backoff_factor * Math.pow(2, consecutive - 1));
    }

    /** time.sleep() for retries and download buffers: replaceable in tests. */
    public static java.util.function.DoubleConsumer SLEEP = seconds -> {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
        }
    };

    private static void sleepSeconds(double seconds) {
        if (seconds > 0) {
            SLEEP.accept(seconds);
        }
    }

    /** Decode the body like urllib3's stream(), the size cap applying to decoded bytes. */
    private static byte[] readBody(HttpResponse<InputStream> response, int max_size) throws IOException {
        String encoding = Py.or(headerValue(response, "content-encoding"), "").toLowerCase(Locale.ROOT).strip();
        try (InputStream raw = response.body()) {
            InputStream in = raw;
            if (encoding.equals("gzip") || encoding.equals("x-gzip")) {
                in = new GZIPInputStream(raw);
            } else if (encoding.equals("deflate")) {
                in = new InflaterInputStream(raw);
            }
            byte[] buffer = new byte[1 << 17];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > max_size) {
                    throw new IllegalArgumentException("decompressed content exceeds MAX_FILE_SIZE");
                }
            }
            return out.toByteArray();
        }
    }

    /** Internal function to robustly send a request (SSL or not) and return its result. */
    public static Response _send_urllib_request(String url, boolean no_ssl, ConfigParser config) {
        try {
            HttpClient pool_manager = _initiate_pool(config, no_ssl);
            boolean ssrf = _ssrf_active(config);
            int timeout = config.getint("DEFAULT", "DOWNLOAD_TIMEOUT");
            int max_redirects = config.getint("DEFAULT", "MAX_REDIRECTS");
            double backoff_factor = timeout / 2.0;
            Map<String, String> headers = _determine_headers(config);

            // urllib3 Retry(total=max_redirects, redirect=max_redirects, connect=0, status_forcelist=FORCE_STATUS)
            int budget = max_redirects;
            int statusRetries = 0;
            String current = url;
            while (true) {
                if (ssrf) {
                    _vet_peer(URI.create(current).getHost());
                }
                HttpResponse<InputStream> response = send(pool_manager, "GET", current, headers, timeout);
                int status = response.statusCode();
                String location = headerValue(response, "location");
                if (status >= 300 && status < 400 && location != null) {
                    response.body().close();
                    if (budget-- <= 0) {
                        throw new IOException("Max retries exceeded with url: " + url + " (too many redirects)");
                    }
                    current = Courlan.urljoin(current, location);
                    continue;
                }
                if (FORCE_STATUS.contains(status)) {
                    if (budget-- <= 0) {
                        response.body().close();
                        throw new IOException("Max retries exceeded with url: " + url + " (too many " + status + " error responses)");
                    }
                    statusRetries++;
                    double delay = retryDelay(response, backoff_factor, statusRetries);
                    response.body().close();
                    sleepSeconds(delay);
                    continue;
                }
                // stop downloading as soon as MAX_FILE_SIZE is reached
                byte[] data = readBody(response, config.getint("DEFAULT", "MAX_FILE_SIZE"));
                Response resp = new Response(data, status, Courlan.urljoin(url, current));
                Map<String, String> headerdict = new LinkedHashMap<>();
                response.headers().map().forEach((name, values) -> headerdict.put(name, String.join(", ", values)));
                resp.store_headers(headerdict);
                return resp;
            }
        } catch (SSLException err) {
            if (!no_ssl) {
                throw new _SSLRetryError(err.toString(), err);
            }
            LOGGER.error("download error: {} {}", url, err.toString());
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            LOGGER.error("download error: {} {}", url, err.toString());
        } catch (IOException | RuntimeException err) {
            if (!no_ssl && err.getCause() instanceof SSLException) {
                throw new _SSLRetryError(err.toString(), err);
            }
            LOGGER.error("download error: {} {}", url, err.toString());
        }
        return null;
    }

    /** Check if the response conforms to formal criteria. */
    public static boolean _is_suitable_response(String url, Response response, Extractor options) {
        if (response.status != 200) {
            LOGGER.error("not a 200 response: {} for URL {}", response.status, url);
            return false;
        }
        int length = response.html != null && !response.html.isEmpty() ? response.html.length()
                : response.data != null ? response.data.length : 0;
        return is_acceptable_length(length, options);
    }

    /**
     * Downloads a web page and seamlessly decodes the response.
     *
     * @param url URL of the page to fetch.
     * @param no_ssl Do not try to establish a secure connection (to prevent SSLError).
     * @param config Pass configuration values for output control.
     * @param options Extraction options (supersedes config).
     * @return Unicode string or None in case of failed downloads and invalid results.
     */
    public static String fetch_url(String url, boolean no_ssl, ConfigParser config, Extractor options) {
        config = options != null ? options.config : config;
        Response response = fetch_response(url, true, no_ssl, config);
        if (response == null || response.data == null || response.data.length == 0) {
            return null;
        }
        if (options == null) {
            options = new Extractor(Extractor.kw().config(config));
        }
        return _is_suitable_response(url, response, options) ? response.html : null;
    }

    public static String fetch_url(String url) {
        return fetch_url(url, false, DEFAULT_CONFIG, null);
    }

    public static String fetch_url(String url, boolean no_ssl) {
        return fetch_url(url, no_ssl, DEFAULT_CONFIG, null);
    }

    public static String fetch_url(String url, Extractor options) {
        return fetch_url(url, false, DEFAULT_CONFIG, options);
    }

    public static String fetch_url(String url, ConfigParser config) {
        return fetch_url(url, false, config, null);
    }

    /**
     * Downloads a web page and returns a full response object.
     *
     * @param url URL of the page to fetch.
     * @param decode Use html attribute to decode the data (boolean).
     * @param no_ssl Don't try to establish a secure connection (to prevent SSLError).
     * @param config Pass configuration values for output control.
     * @return Response object or None in case of failed downloads and invalid results.
     */
    public static Response fetch_response(String url, boolean decode, boolean no_ssl, ConfigParser config) {
        LOGGER.debug("sending request: {}", url);
        Response response;
        try {
            response = SEND_REQUEST.send(url, no_ssl, config);
        } catch (_SSLRetryError err) {
            boolean fallback;
            try {
                fallback = config.getboolean("DEFAULT", "INSECURE_SSL_FALLBACK");
            } catch (RuntimeException missing) {
                fallback = true;
            }
            if (!fallback) {
                LOGGER.error("SSL error, INSECURE_SSL_FALLBACK is off: {} {}", url, err.getMessage());
                return null;
            }
            // senders raise only when verification was on, so this cannot recurse
            LOGGER.warn("retrying after SSL error: {} {}", url, err.getMessage());
            response = SEND_REQUEST.send(url, true, config);
        }
        if (response == null || !response.isTruthy()) { // None or data missing
            LOGGER.debug("request failed: {}", url);
            return null;
        }
        response.decode_data(decode, config.getint("DEFAULT", "MAX_FILE_SIZE"));
        return response;
    }

    public static Response fetch_response(String url) {
        return fetch_response(url, false, false, DEFAULT_CONFIG);
    }

    public static Response fetch_response(String url, boolean decode) {
        return fetch_response(url, decode, false, DEFAULT_CONFIG);
    }

    public static Response fetch_response(String url, ConfigParser config) {
        return fetch_response(url, false, false, config);
    }

    /** Send a HEAD request, following redirects. */
    public static boolean _urllib3_is_live_page(String url, ConfigParser config) {
        try {
            HttpClient client = _initiate_pool(config, true);
            boolean ssrf = _ssrf_active(config);
            int timeout = config.getint("DEFAULT", "DOWNLOAD_TIMEOUT");
            int budget = config.getint("DEFAULT", "MAX_REDIRECTS");
            Map<String, String> headers = _determine_headers(config);
            String current = url;
            int statusRetries = 0;
            while (true) {
                if (ssrf) {
                    _vet_peer(URI.create(current).getHost());
                }
                HttpResponse<InputStream> response = send(client, "HEAD", current, headers, timeout);
                response.body().close();
                int status = response.statusCode();
                String location = headerValue(response, "location");
                // exhausted redirects return the last 3xx, counted as live
                if (status >= 300 && status < 400 && location != null && budget > 0) {
                    budget--;
                    current = Courlan.urljoin(current, location);
                    continue;
                }
                if (FORCE_STATUS.contains(status) && budget > 0) {
                    budget--;
                    statusRetries++;
                    sleepSeconds(retryDelay(response, 1, statusRetries));
                    continue;
                }
                return status < 400;
            }
        } catch (IOException | RuntimeException err) {
            LOGGER.debug("urllib3 HEAD error: {} {}", url, err.toString());
            return false;
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Send a HTTP HEAD request without taking anything else into account. */
    public static boolean is_live_page(String url, ConfigParser config) {
        return IS_LIVE_PAGE.test(url, config);
    }

    public static boolean is_live_page(String url) {
        return is_live_page(url, DEFAULT_CONFIG);
    }

    /** Filter, convert input URLs and add them to domain-aware processing dictionary. */
    public static UrlStore add_to_compressed_dict(List<String> inputlist, Set<String> blacklist, List<String> url_filter,
            UrlStore url_store, boolean compression, boolean verbose) {
        if (url_store == null) {
            url_store = new UrlStore(compression, false, verbose);
        }

        List<String> urls = new ArrayList<>(new LinkedHashSet<>(inputlist));

        if (blacklist != null && !blacklist.isEmpty()) {
            urls.removeIf(u -> blacklist.contains(URL_BLACKLIST_REGEX.matcher(u).replaceAll("")));
        }

        if (url_filter != null && !url_filter.isEmpty()) {
            urls.removeIf(u -> url_filter.stream().noneMatch(u::contains));
        }

        url_store.add_urls(urls);
        return url_store;
    }

    public static UrlStore add_to_compressed_dict(List<String> inputlist) {
        return add_to_compressed_dict(inputlist, null, null, null, false, false);
    }

    /** Determine threading strategy and draw URLs respecting domain-based back-off rules. */
    public static Pair<List<String>, UrlStore> load_download_buffer(UrlStore url_store, double sleep_time) {
        List<String> bufferlist;
        while (true) {
            bufferlist = url_store.get_download_urls(sleep_time, 100_000);
            if (!bufferlist.isEmpty() || url_store.done) {
                break;
            }
            sleepSeconds(sleep_time);
        }
        return Pair.of(bufferlist, url_store);
    }

    public static Pair<List<String>, UrlStore> load_download_buffer(UrlStore url_store) {
        return load_download_buffer(url_store, 5.0);
    }

    /**
     * Use a thread pool to perform a series of downloads, yielding (url, result)
     * pairs in completion order, one chunk of at most {@code chunksize} URLs at a time.
     */
    public static <T> Iterator<Pair<String, T>> _buffered_downloads(List<String> bufferlist, int download_threads,
            Function<String, T> worker, int chunksize) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, download_threads));
        Iterator<List<String>> chunks = make_chunks(bufferlist, chunksize);
        return new Iterator<>() {
            private CompletionService<T> completion;
            private Map<Future<T>, String> futureToUrl = new LinkedHashMap<>();
            private int pending;

            private void fill() {
                while (pending == 0 && chunks.hasNext()) {
                    completion = new ExecutorCompletionService<>(executor);
                    futureToUrl = new LinkedHashMap<>();
                    for (String url : chunks.next()) {
                        futureToUrl.put(completion.submit(() -> worker.apply(url)), url);
                    }
                    pending = futureToUrl.size();
                }
                if (pending == 0) {
                    executor.shutdown();
                }
            }

            @Override
            public boolean hasNext() {
                fill();
                return pending > 0;
            }

            @Override
            public Pair<String, T> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                try {
                    Future<T> future = completion.take();
                    pending--;
                    return Pair.of(futureToUrl.get(future), future.get());
                } catch (InterruptedException err) {
                    Thread.currentThread().interrupt();
                    executor.shutdownNow();
                    throw new IllegalStateException(err);
                } catch (ExecutionException err) {
                    throw new IllegalStateException(err.getCause());
                }
            }
        };
    }

    /** Download queue consumer, single- or multi-threaded. */
    public static Iterator<Pair<String, String>> buffered_downloads(List<String> bufferlist, int download_threads,
            Extractor options) {
        return _buffered_downloads(bufferlist, download_threads, url -> fetch_url(url, false, DEFAULT_CONFIG, options), 10000);
    }

    public static Iterator<Pair<String, String>> buffered_downloads(List<String> bufferlist, int download_threads) {
        return buffered_downloads(bufferlist, download_threads, null);
    }

    /** Download queue consumer, returns full Response objects. */
    public static Iterator<Pair<String, Response>> buffered_response_downloads(List<String> bufferlist,
            int download_threads, Extractor options) {
        ConfigParser config = options != null ? options.config : DEFAULT_CONFIG;
        return _buffered_downloads(bufferlist, download_threads, url -> fetch_response(url, false, false, config), 10000);
    }

    public static Iterator<Pair<String, Response>> buffered_response_downloads(List<String> bufferlist,
            int download_threads) {
        return buffered_response_downloads(bufferlist, download_threads, null);
    }

    /** Parse accumulated header bytes, keeping only the last response of a redirect chain. */
    public static Map<String, String> _parse_curl_headers(byte[] raw) {
        Map<String, String> headers = new LinkedHashMap<>();
        String text = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1);
        for (String line : Py.splitlines(text)) {
            // a new status line marks the next response in a redirect chain
            if (line.startsWith("HTTP/")) {
                headers = new LinkedHashMap<>();
                continue;
            }
            String[] parts = Py.partition(line, ":");
            if (!parts[1].isEmpty()) {
                headers.put(Py.strip(parts[0]), Py.strip(parts[2]));
            }
        }
        return headers;
    }
}
