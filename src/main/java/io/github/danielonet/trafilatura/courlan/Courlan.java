// Java port of courlan (simplified), https://github.com/adbar/courlan
package io.github.danielonet.trafilatura.courlan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.danielonet.trafilatura.compat.Pair;
import io.github.danielonet.trafilatura.compat.Py;

/**
 * Minimal stand-in for the Python courlan package (https://github.com/adbar/courlan, Apache-2.0):
 * the functions trafilatura imports, with simplified URL cleaning (no tracker removal,
 * no punycode decoding, no language heuristics, no public suffix list).
 */
public final class Courlan {
    private static final Logger LOGGER = LoggerFactory.getLogger(Courlan.class);

    private Courlan() {
    }

    public static final Set<String> PROTOCOLS = Set.of("http", "https");

    // ------------------------------------------------------------------ urllib.parse

    /** {@code urllib.parse.SplitResult} */
    public record SplitResult(String scheme, String netloc, String path, String query, String fragment) {
        public String hostname() {
            String host = netloc;
            int at = host.lastIndexOf('@');
            if (at >= 0) {
                host = host.substring(at + 1);
            }
            if (host.startsWith("[")) {
                int end = host.indexOf(']');
                return end > 0 ? host.substring(1, end).toLowerCase(Locale.ROOT) : host;
            }
            int colon = host.indexOf(':');
            return (colon >= 0 ? host.substring(0, colon) : host).toLowerCase(Locale.ROOT);
        }

        /** Port number or null; throws IllegalArgumentException for a non-numeric port. */
        public Integer port() {
            String host = netloc.substring(netloc.lastIndexOf('@') + 1);
            int bracket = host.lastIndexOf(']');
            int colon = host.lastIndexOf(':');
            if (colon < 0 || colon < bracket) {
                return null;
            }
            String port = host.substring(colon + 1);
            if (port.isEmpty()) {
                return null;
            }
            if (!Py.isdigit(port)) {
                throw new IllegalArgumentException("Port could not be cast to integer value");
            }
            return Integer.valueOf(port);
        }
    }

    private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*):");

    /** {@code urllib.parse.urlsplit} */
    public static SplitResult urlsplit(String url) {
        String rest = url.strip();
        String scheme = "";
        Matcher m = SCHEME.matcher(rest);
        if (m.find()) {
            scheme = m.group(1).toLowerCase(Locale.ROOT);
            rest = rest.substring(m.end());
        }
        String netloc = "";
        if (rest.startsWith("//")) {
            int end = rest.length();
            for (char c : new char[] {'/', '?', '#'}) {
                int idx = rest.indexOf(c, 2);
                if (idx >= 0 && idx < end) {
                    end = idx;
                }
            }
            netloc = rest.substring(2, end);
            rest = rest.substring(end);
            if (netloc.contains("[") != netloc.contains("]")) {
                throw new IllegalArgumentException("Invalid IPv6 URL");
            }
        }
        String fragment = "";
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            fragment = rest.substring(hash + 1);
            rest = rest.substring(0, hash);
        }
        String query = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        return new SplitResult(scheme, netloc, rest, query, fragment);
    }

    /** {@code urllib.parse.urlunsplit} */
    public static String urlunsplit(SplitResult parts) {
        StringBuilder sb = new StringBuilder();
        String path = parts.path();
        if (!parts.netloc().isEmpty() || (!parts.scheme().isEmpty() && path.startsWith("//"))) {
            if (!path.isEmpty() && !path.startsWith("/")) {
                path = "/" + path;
            }
            sb.append("//").append(parts.netloc());
        }
        sb.append(path);
        String url = sb.toString();
        if (!parts.scheme().isEmpty()) {
            url = parts.scheme() + ":" + url;
        }
        if (!parts.query().isEmpty()) {
            url = url + "?" + parts.query();
        }
        if (!parts.fragment().isEmpty()) {
            url = url + "#" + parts.fragment();
        }
        return url;
    }

    /** {@code urllib.parse.urljoin} (RFC 3986 reference resolution). */
    public static String urljoin(String base, String url) {
        if (base == null || base.isEmpty()) {
            return url;
        }
        if (url == null || url.isEmpty()) {
            return base;
        }
        SplitResult b = urlsplit(base);
        SplitResult r = urlsplit(url);
        if (!r.scheme().isEmpty() && !r.scheme().equals(b.scheme())) {
            return url;
        }
        String scheme = b.scheme();
        if (!r.netloc().isEmpty()) {
            return urlunsplit(new SplitResult(scheme, r.netloc(), removeDotSegments(r.path()), r.query(), r.fragment()));
        }
        String path;
        String query;
        if (r.path().isEmpty()) {
            path = b.path();
            query = !r.query().isEmpty() ? r.query() : b.query();
        } else {
            if (r.path().startsWith("/")) {
                path = removeDotSegments(r.path());
            } else {
                String basePath = b.path();
                String merged;
                if (!b.netloc().isEmpty() && basePath.isEmpty()) {
                    merged = "/" + r.path();
                } else {
                    int slash = basePath.lastIndexOf('/');
                    merged = (slash >= 0 ? basePath.substring(0, slash + 1) : "") + r.path();
                }
                path = removeDotSegments(merged);
            }
            query = r.query();
        }
        return urlunsplit(new SplitResult(scheme, b.netloc(), path, query, r.fragment()));
    }

    private static String removeDotSegments(String path) {
        if (!path.contains(".")) {
            return path;
        }
        String[] segments = path.split("/", -1);
        List<String> output = new ArrayList<>();
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            boolean last = i == segments.length - 1;
            if (seg.equals(".")) {
                if (last) {
                    output.add("");
                }
            } else if (seg.equals("..")) {
                if (output.size() > 1 || (output.size() == 1 && !output.get(0).isEmpty())) {
                    output.remove(output.size() - 1);
                }
                if (last) {
                    output.add("");
                }
            } else {
                output.add(seg);
            }
        }
        String result = String.join("/", output);
        if (path.startsWith("/") && !result.startsWith("/")) {
            result = "/" + result;
        }
        return result;
    }

    // ------------------------------------------------------------------ urlutils

    private static final Pattern DOMAIN_REGEX = Pattern.compile(
            "(?:(?:f|ht)tp)s?://"
                    + "(?:[^/?#]{0,63}\\.)?"
                    + "([^/?#.]{4,63}\\.[^/?#]{2,63}|"
                    + "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|"
                    + "[0-9a-f:]{16,})"
                    + "(?:/|$)");
    private static final Pattern STRIP_PORT_REGEX = Pattern.compile("(?<=\\D):\\d+");
    private static final Pattern CLEAN_FLD_REGEX = Pattern.compile("^www[0-9]*\\.");
    private static final Pattern FEED_WHITELIST_REGEX = Pattern.compile("(?:feed(?:burner|proxy))", Pattern.CASE_INSENSITIVE);

    /** Domain and full domain (simplified: the last two host labels stand in for the registered domain). */
    public static Pair<String, String> get_tldinfo(String url, boolean fast) {
        if (url == null || url.isEmpty()) {
            return Pair.of(null, null);
        }
        if (fast) {
            Matcher m = DOMAIN_REGEX.matcher(url);
            if (m.lookingAt()) {
                String[] parts = m.group(1).split("@");
                String fullDomain = STRIP_PORT_REGEX.matcher(parts[parts.length - 1]).replaceAll("");
                String cleanMatch = fullDomain.split("\\.")[0];
                if (!cleanMatch.isEmpty()) {
                    return Pair.of(cleanMatch, fullDomain);
                }
            }
        }
        SplitResult parsed;
        try {
            parsed = urlsplit(url);
        } catch (IllegalArgumentException err) {
            return Pair.of(null, null);
        }
        String host = parsed.hostname();
        if (parsed.scheme().isEmpty() || host.isEmpty() || !host.contains(".")) {
            return Pair.of(null, null);
        }
        String[] labels = host.split("\\.");
        String fld = labels.length >= 2 ? labels[labels.length - 2] + "." + labels[labels.length - 1] : host;
        String domain = labels.length >= 2 ? labels[labels.length - 2] : host;
        return Pair.of(domain, CLEAN_FLD_REGEX.matcher(fld).replaceFirst(""));
    }

    public static String extract_domain(String url, Set<String> blacklist, boolean fast) {
        if (blacklist == null) {
            blacklist = Set.of();
        }
        Pair<String, String> info = get_tldinfo(url, fast);
        String fullDomain = info.second();
        return fullDomain != null && !fullDomain.isEmpty() && !blacklist.contains(info.first())
                && !blacklist.contains(fullDomain) ? fullDomain : null;
    }

    public static String extract_domain(String url) {
        return extract_domain(url, null, false);
    }

    public static String extract_domain(String url, boolean fast) {
        return extract_domain(url, null, fast);
    }

    private static SplitResult _parse(String url) {
        return urlsplit(Py.unescape(url));
    }

    /** Strip URL of some of its parts to get base URL. */
    public static String get_base_url(String url) {
        SplitResult parsed = _parse(url);
        String scheme = parsed.scheme().isEmpty() ? "" : parsed.scheme() + "://";
        return scheme + parsed.netloc();
    }

    /** Decompose URL in two parts: protocol + host/domain and path. */
    public static Pair<String, String> get_host_and_path(String url) {
        SplitResult parsed = _parse(url);
        String hostname = get_base_url(url);
        String pathval = urlunsplit(new SplitResult("", "", parsed.path(), parsed.query(), parsed.fragment()));
        if (pathval.isEmpty()) {
            pathval = "/";
        }
        if (hostname.isEmpty()) {
            throw new IllegalArgumentException("incomplete URL: " + url);
        }
        return Pair.of(hostname, pathval);
    }

    /** Domain name (may be null) and host info (protocol + host). */
    public static Pair<String, String> get_hostinfo(String url) {
        return Pair.of(extract_domain(url, true), get_base_url(url));
    }

    /** Prepend protocol and host information to relative links. */
    public static String fix_relative_urls(String baseurl, String url) {
        if (url.startsWith("{")) {
            return url;
        }
        String baseNetloc = urlsplit(baseurl).netloc();
        SplitResult split = urlsplit(url);
        if (!split.netloc().equals(baseNetloc) && !split.netloc().isEmpty()) {
            if (!split.scheme().isEmpty()) {
                return url;
            }
            return urlunsplit(new SplitResult("http", split.netloc(), split.path(), split.query(), split.fragment()));
        }
        return urljoin(baseurl, url);
    }

    /** Return a list of links corresponding to the given substring pattern. */
    public static List<String> filter_urls(List<String> link_list, String urlfilter) {
        if (urlfilter == null) {
            return new ArrayList<>(new TreeSet<>(link_list));
        }
        List<String> filtered = new ArrayList<>();
        for (String link : link_list) {
            if (link.contains(urlfilter)) {
                filtered.add(link);
            }
        }
        if (filtered.isEmpty()) {
            for (String link : link_list) {
                if (FEED_WHITELIST_REGEX.matcher(link).find()) {
                    filtered.add(link);
                }
            }
        }
        return new ArrayList<>(new TreeSet<>(filtered));
    }

    /** Determine if a link leads to another host. */
    public static boolean is_external(String url, String reference, boolean ignore_suffix) {
        Pair<String, String> ref = get_tldinfo(reference, true);
        Pair<String, String> dom = get_tldinfo(url, true);
        if (ignore_suffix) {
            return !java.util.Objects.equals(dom.first(), ref.first());
        }
        return !java.util.Objects.equals(dom.second(), ref.second());
    }

    /** Compare the link and its possible variants to the existing URL base. */
    public static boolean is_known_link(String link, Set<String> known_links) {
        if (link == null || link.isEmpty()) {
            return false;
        }
        if (known_links.contains(link)) {
            return true;
        }
        String slashTest = link.endsWith("/") ? Py.rstrip(link, "/") : link + "/";
        if (known_links.contains(slashTest)) {
            return true;
        }
        if (link.startsWith("http")) {
            String protocolTest = link.startsWith("https") ? "http" + link.substring(5) : "https" + link.substring(4);
            slashTest = protocolTest.endsWith("/") ? Py.rstrip(protocolTest, "/") : protocolTest + "/";
            return known_links.contains(protocolTest) || known_links.contains(slashTest);
        }
        return false;
    }

    // ------------------------------------------------------------------ filters

    private static final Pattern NAVIGATION_FILTER = Pattern.compile(
            "[/_-](archives|auth?or|[ck]at|category|kategorie|paged?|schlagwort|seite|tags?|topics?|user)/|\\?p=[0-9]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NOTCRAWLABLE = Pattern.compile(
            "/([ck]onta[ck]t|datenschutzerkl.{1,2}rung|login|impressum|imprint)(\\.[a-z]{3,4})?/?$|/login\\?|"
                    + "/(javascript:|mailto:|tel\\.?:|whatsapp:)",
            Pattern.CASE_INSENSITIVE);

    /** Parse and validate the input; the second element is null when the URL is invalid. */
    public static Pair<Boolean, SplitResult> validate_url(String url) {
        if (url == null) {
            return Pair.of(false, null);
        }
        SplitResult parsed;
        try {
            parsed = urlsplit(url);
        } catch (IllegalArgumentException err) {
            return Pair.of(false, null);
        }
        if (parsed.scheme().isEmpty() || !PROTOCOLS.contains(parsed.scheme())) {
            return Pair.of(false, null);
        }
        if (parsed.netloc().length() < 5 || (parsed.netloc().startsWith("www.") && parsed.netloc().length() < 8)) {
            return Pair.of(false, null);
        }
        return Pair.of(true, parsed);
    }

    public static boolean is_valid_url(String url) {
        return validate_url(url).first();
    }

    public static boolean is_navigation_page(String url) {
        return NAVIGATION_FILTER.matcher(url).find();
    }

    public static boolean is_not_crawlable(String url) {
        return NOTCRAWLABLE.matcher(url).find();
    }

    private static final Pattern PATH_LANG_FILTER = Pattern.compile(
            "(?:https?://[^/]+/)([a-z]{2})([_-][a-z]{2,3})?(?:/|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALL_PATH_LANGS = Pattern.compile("(?:/)([a-z]{2})([_-][a-z]{2})?(?:/)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALL_PATH_LANGS_NO_TRAILING = Pattern.compile(
            "(?:/)([a-z]{2})([_-][a-z]{2})?(?:/|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOST_LANG_FILTER = Pattern.compile(
            "https?://([a-z]{2})\\.(?:[^.]{4,})\\.(?:[^.]+)(?:\\.[^.]+)?/", Pattern.CASE_INSENSITIVE);
    private static final Set<String> ISO_LANGUAGES = Set.of(java.util.Locale.getISOLanguages());

    /** Assess the plausibility of the chosen URL segment (babel's Locale.parse replaced by the ISO 639 list). */
    static int langcodes_score(String language, String segment, int score) {
        String code = segment.toLowerCase(Locale.ROOT);
        if (!ISO_LANGUAGES.contains(code)) {
            // UnknownLocaleError: no change
            return score;
        }
        return code.equals(language) ? score + 1 : score - 1;
    }

    private static int count(Pattern pattern, String s) {
        Matcher m = pattern.matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** Heuristics targeting internationalization and linguistic elements based on a score. */
    public static boolean lang_filter(String url, String language, boolean strict, boolean trailing_slash) {
        // sanity check
        if (language == null) {
            return true;
        }
        int score = 0;
        // first test: internationalization in URL path
        Matcher match = PATH_LANG_FILTER.matcher(url);
        if (match.lookingAt()) {
            // look for other occurrences
            int occurrences = count(trailing_slash ? ALL_PATH_LANGS : ALL_PATH_LANGS_NO_TRAILING, url);
            if (occurrences == 1) {
                score = langcodes_score(language, match.group(1), score);
            }
            // two occurrences: courlan passes the findall() tuples to babel, which rejects them (no change);
            // more than two candidates: no test
        }
        // second test: prepended language cues
        if (strict) {
            Matcher host = HOST_LANG_FILTER.matcher(url);
            if (host.lookingAt()) {
                score += host.group(1).toLowerCase(Locale.ROOT).equals(language) ? 1 : -1;
            }
        }
        // determine test result
        return score >= 0;
    }

    public static boolean lang_filter(String url, String language) {
        return lang_filter(url, language, false, true);
    }

    // ------------------------------------------------------------------ cleaning

    /** Strip unnecessary parts (simplified version of courlan.clean.scrub_url). */
    public static String scrub_url(String url) {
        url = Py.strip(String.join("", Py.split(url)));
        if (url.startsWith("<![CDATA[")) {
            url = url.replace("<![CDATA[", "").replace("]]>", "");
        }
        url = url.replace("&amp;", "&");
        if (Py.count(url, "/") == 3 || Py.count(url, "://") > 1) {
            url = Py.rstrip(url, "/");
        }
        return url;
    }

    /** Lowercase scheme and host, strip default ports, drop fragments in strict mode. */
    public static String normalize_url(String url, boolean strict, String language, boolean trailing_slash) {
        SplitResult parsed = _parse(url);
        String scheme = parsed.scheme().toLowerCase(Locale.ROOT);
        String netloc = parsed.netloc().toLowerCase(Locale.ROOT);
        Integer port;
        try {
            port = parsed.port();
        } catch (IllegalArgumentException err) {
            port = null;
        }
        if ((scheme.equals("http") && Integer.valueOf(80).equals(port))
                || (scheme.equals("https") && Integer.valueOf(443).equals(port))) {
            netloc = netloc.replaceFirst(":\\d+$", "");
        }
        String newpath = parsed.path();
        String newquery = parsed.query();
        if (!newquery.isEmpty() && newpath.isEmpty()) {
            newpath = "/";
        } else if (!trailing_slash && newquery.isEmpty() && newpath.length() > 1 && newpath.endsWith("/")) {
            newpath = Py.rstrip(newpath, "/");
        }
        String newfragment = strict ? "" : parsed.fragment();
        return urlunsplit(new SplitResult(scheme, netloc, newpath, newquery, newfragment));
    }

    public static String normalize_url(String url) {
        return normalize_url(url, false, null, true);
    }

    /** Chained scrubbing and normalization. */
    public static String clean_url(String url, String language) {
        try {
            return normalize_url(scrub_url(url), false, language, true);
        } catch (RuntimeException err) {
            return null;
        }
    }

    public static String clean_url(String url) {
        return clean_url(url, null);
    }

    /** Check links for appropriateness and sanity: canonical URL and domain, or null. */
    public static Pair<String, String> check_url(String url, boolean strict, boolean with_redirects, String language,
            boolean with_nav, boolean trailing_slash) {
        try {
            if (url == null || url.length() < 10 || !url.startsWith("http")) {
                LOGGER.debug("rejected, basic filter: {}", url);
                return null;
            }
            url = scrub_url(url);
            Pair<Boolean, SplitResult> validation = validate_url(url);
            if (!validation.first()) {
                LOGGER.debug("rejected, validation test: {}", url);
                return null;
            }
            if (language != null && !lang_filter(url, language, strict, trailing_slash)) {
                return null;
            }
            url = normalize_url(url, strict, language, trailing_slash);
            String domain = extract_domain(url, true);
            if (domain == null) {
                LOGGER.debug("rejected, domain name: {}", url);
                return null;
            }
            return Pair.of(url, domain);
        } catch (RuntimeException err) {
            LOGGER.debug("discarded URL: {}", url);
            return null;
        }
    }

    public static Pair<String, String> check_url(String url) {
        return check_url(url, false, false, null, false, true);
    }

    public static Pair<String, String> check_url(String url, String language) {
        return check_url(url, false, false, language, false, true);
    }

    // ------------------------------------------------------------------ links

    private static final Pattern FIND_LINKS_REGEX = Pattern.compile("<a\\s+[^<>]+?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREFLANG_REGEX = Pattern.compile("hreflang=[\"']?([a-z-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_REGEX = Pattern.compile("href=[\"']?([^ ]+?)([\"' >])", Pattern.CASE_INSENSITIVE);

    /** Filter links in a HTML document using a series of heuristics. */
    public static Set<String> extract_links(String pagecontent, String url, boolean external_bool, boolean no_filter,
            String language, boolean strict, boolean trailing_slash, boolean with_nav, boolean redirects,
            String reference) {
        String baseUrl = get_base_url(url != null ? url : "");
        url = url != null && !url.isEmpty() ? url : baseUrl;
        Set<String> candidates = new LinkedHashSet<>();
        Set<String> validlinks = new LinkedHashSet<>();
        if (pagecontent == null || pagecontent.isEmpty()) {
            return validlinks;
        }
        reference = reference != null && !reference.isEmpty() ? reference : baseUrl;

        Matcher m = FIND_LINKS_REGEX.matcher(pagecontent);
        while (m.find()) {
            String link = m.group();
            if (link.contains("rel") && link.contains("nofollow")) {
                continue;
            }
            if (!no_filter && language != null && link.contains("hreflang")) {
                Matcher langmatch = HREFLANG_REGEX.matcher(link);
                if (langmatch.find() && (langmatch.group(1).startsWith(language) || langmatch.group(1).equals("x-default"))) {
                    Matcher linkmatch = LINK_REGEX.matcher(link);
                    if (linkmatch.find()) {
                        candidates.add(linkmatch.group(1));
                    }
                }
            } else {
                Matcher linkmatch = LINK_REGEX.matcher(link);
                if (linkmatch.find()) {
                    candidates.add(linkmatch.group(1));
                }
            }
        }

        for (String link : candidates) {
            if (!link.startsWith("http")) {
                link = fix_relative_urls(url, link);
            }
            if (!no_filter) {
                Pair<String, String> checked = check_url(link, strict, redirects, language, with_nav, trailing_slash);
                if (checked == null) {
                    continue;
                }
                link = checked.first();
                if (external_bool != is_external(link, reference, true)) {
                    continue;
                }
            }
            if (is_known_link(link, validlinks)) {
                continue;
            }
            validlinks.add(link);
        }
        LOGGER.info("{} links found – {} valid links", candidates.size(), validlinks.size());
        return validlinks;
    }

    public static Set<String> extract_links(String pagecontent, String url, boolean external_bool) {
        return extract_links(pagecontent, url, external_bool, false, null, true, true, false, false, null);
    }

    /** {@code courlan.meta.clear_caches()}: nothing is cached in this port. */
    public static void clear_caches() {
        // no caches
    }
}
