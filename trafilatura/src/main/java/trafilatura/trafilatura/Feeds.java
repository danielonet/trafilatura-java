// Java port of trafilatura/feeds.py
package trafilatura.trafilatura;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import trafilatura.compat.ConfigParser;
import trafilatura.compat.Json;
import trafilatura.compat.Pair;
import trafilatura.compat.Py;
import trafilatura.compat.Re;
import trafilatura.courlan.Courlan;
import trafilatura.lxml.Element;

import static trafilatura.trafilatura.Deduplication.is_similar_domain;
import static trafilatura.trafilatura.Downloads.fetch_url;
import static trafilatura.trafilatura.Settings.DEFAULT_CONFIG;
import static trafilatura.trafilatura.Settings.MAX_FEEDS_CHECKED;
import static trafilatura.trafilatura.Settings.MAX_LINKS;
import static trafilatura.trafilatura.Utils.load_html;
import static trafilatura.trafilatura.Utils.safe_relative_url;

/** Examining feeds and extracting links for further processing. */
public final class Feeds {
    private Feeds() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Feeds.class);

    // https://www.iana.org/assignments/media-types/media-types.xhtml
    // standard + potential types
    public static final Set<String> FEED_TYPES = Set.of(
            "application/atom", // not IANA-compatible
            "application/atom+xml",
            "application/feed+json", // not IANA-compatible
            "application/json",
            "application/rdf", // not IANA-compatible
            "application/rdf+xml",
            "application/rss", // not IANA-compatible
            "application/rss+xml",
            "application/x.atom+xml", // not IANA-compatible
            "application/x-atom+xml", // not IANA-compatible
            "application/xml",
            "text/atom", // not IANA-compatible
            "text/atom+xml",
            "text/plain",
            "text/rdf", // not IANA-compatible
            "text/rdf+xml",
            "text/rss", // not IANA-compatible
            "text/rss+xml",
            "text/xml");

    public static final Pattern FEED_OPENING = Pattern.compile("<(feed|rss|\\?xml)");

    public static final Pattern LINK_ATTRS = Pattern.compile("<link\\s+(?:[^>\"']|\"[^\"]*\"|'[^']*')*[\"']?/?>");
    public static final Pattern LINK_ATTRIBUTES = Pattern.compile("\\s([\\w:-]+)\\s*=\\s*([\"'])(.*?)\\2",
            Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS);
    public static final Pattern LINK_ELEMENTS = Pattern.compile("<link>(?:\\s*)(?:<!\\[CDATA\\[)?(.+?)(?:\\]\\]>)?(?:\\s*)</link>",
            Pattern.DOTALL);

    public static final Pattern BLACKLIST = Pattern.compile("\\bcomments\\b"); // no comment feed

    public static final Pattern LINK_VALIDATION_RE = Pattern.compile(
            "\\.(?:atom|rdf|rss|xml)$|"
                    + "\\b(?:atom|rss)\\b|"
                    + "\\?type=100$|" // Typo3
                    + "feeds/posts/default/?$|" // Blogger
                    + "\\?feed=(?:atom|rdf|rss|rss2)|"
                    + "feed$"); // Generic

    /** Store necessary information to proceed a feed. */
    public static class FeedParameters {
        public String base;
        public String domain;
        public boolean ext;
        public String lang;
        public String ref;

        public FeedParameters(String baseurl, String domain, String reference, boolean external, String target_lang) {
            this.base = baseurl;
            this.domain = domain;
            this.ext = external;
            this.lang = target_lang;
            this.ref = reference;
        }

        public FeedParameters(String baseurl, String domain, String reference) {
            this(baseurl, domain, reference, false, null);
        }
    }

    /** Check if the string could be a feed. */
    public static boolean is_potential_feed(String feed_string) {
        if (Re.match(FEED_OPENING, feed_string) != null) {
            return true;
        }
        String beginning = Py.slice(feed_string, null, 100);
        return beginning.contains("<rss") || beginning.contains("<feed");
    }

    /** Examine links to determine if they are valid and lead to a web page. */
    public static List<String> handle_link_list(List<String> linklist, FeedParameters params) {
        List<String> output_links = new ArrayList<>();

        for (String item : new TreeSet<>(linklist)) {
            String link = safe_relative_url(params.base, item);
            Pair<String, String> checked = Courlan.check_url(link, params.lang);

            if (checked != null) {
                if (!params.ext && !link.contains("feed") && !is_similar_domain(params.domain, checked.second())) {
                    LOGGER.warn("Rejected, diverging domain names: {} {}", params.domain, checked.second());
                } else {
                    output_links.add(checked.first());
                }
            } else if (item.contains("feedburner") || item.contains("feedproxy")) {
                // Feedburner/Google feeds
                output_links.add(item);
            }
        }

        return output_links;
    }

    /** Try different feed types and return the corresponding links. */
    public static List<String> find_links(String feed_string, FeedParameters params) {
        if (!is_potential_feed(feed_string)) {
            // JSON
            if (feed_string.startsWith("{")) {
                Object parsed;
                try {
                    // fallback: https://www.jsonfeed.org/version/1.1/
                    parsed = Json.loads(feed_string);
                } catch (Json.JSONDecodeError | StackOverflowError err) {
                    LOGGER.debug("JSON decoding error: {}", params.domain);
                    return new ArrayList<>();
                }
                if (!(parsed instanceof Map<?, ?> feed)) {
                    throw new IllegalArgumentException("'" + parsed.getClass().getSimpleName() + "' object has no attribute 'get'");
                }
                Object items = feed.containsKey("items") ? feed.get("items") : List.of();
                if (!(items instanceof List<?> itemList)) {
                    return new ArrayList<>();
                }
                List<String> candidates = new ArrayList<>();
                for (Object item : itemList) {
                    if (!(item instanceof Map<?, ?> itemMap)) {
                        continue;
                    }
                    for (String key : List.of("url", "id")) {
                        if (itemMap.get(key) instanceof String candidate && !candidate.isEmpty()) {
                            candidates.add(candidate);
                            break;
                        }
                    }
                }
                return candidates;
            } else {
                LOGGER.debug("Possibly invalid feed: {}", params.domain);
            }
            return new ArrayList<>();
        }

        // Atom
        if (LINK_ATTRS.matcher(feed_string).find()) {
            List<String> links = new ArrayList<>();
            Matcher match = LINK_ATTRS.matcher(feed_string);
            int count = 0;
            while (count < MAX_LINKS && match.find()) {
                count++;
                Map<String, String> attributes = new LinkedHashMap<>();
                Matcher attr = LINK_ATTRIBUTES.matcher(match.group());
                while (attr.find()) {
                    attributes.put(attr.group(1), attr.group(3));
                }
                if (Py.truthy(attributes.get("href")) && !"self".equals(attributes.get("rel"))
                        && !attributes.getOrDefault("type", "").contains("atom+xml")) {
                    links.add(attributes.get("href"));
                }
            }
            return links;
        }

        // RSS
        if (feed_string.contains("<link>")) {
            List<String> links = new ArrayList<>();
            Matcher m = LINK_ELEMENTS.matcher(feed_string);
            int count = 0;
            while (count < MAX_LINKS && m.find()) {
                count++;
                links.add(Py.strip(m.group(1)));
            }
            return links;
        }

        return new ArrayList<>();
    }

    /** Extract and refine links from Atom, RSS and JSON feeds. */
    public static List<String> extract_links(String feed_string, FeedParameters params) {
        if (feed_string == null || feed_string.isEmpty()) {
            LOGGER.debug("Empty feed: {}", params.domain);
            return new ArrayList<>();
        }

        List<String> feed_links = find_links(Py.strip(feed_string), params);

        List<String> output_links = new ArrayList<>();
        for (String link : handle_link_list(feed_links, params)) {
            if (!link.equals(params.ref) && Py.count(link, "/") > 2) {
                output_links.add(link);
            }
        }

        if (!feed_links.isEmpty()) {
            LOGGER.debug("Links found: {} of which {} valid", feed_links.size(), output_links.size());
        } else {
            LOGGER.debug("Invalid feed for {}", params.domain);
        }

        return output_links;
    }

    /**
     * Parse the HTML and try to extract feed URLs from the home page.
     * Adapted from http://www.aaronsw.com/2002/feedfinder/
     */
    public static List<String> determine_feed(String htmlstring, FeedParameters params) {
        Element tree = load_html(htmlstring);
        if (tree == null) {
            LOGGER.debug("Invalid HTML/Feed page: {}", params.base);
            return new ArrayList<>();
        }

        // most common case + websites like geo.de
        List<String> feed_urls = new ArrayList<>();
        for (Element link : tree.xpath("//link[@rel=\"alternate\"][@href]")) {
            // normalize the type attribute (e.g. "application/rss+xml; charset=UTF-8")
            String type = Py.strip(link.get("type", "").split(";", -1)[0]).toLowerCase(Locale.ROOT);
            if (FEED_TYPES.contains(type) || LINK_VALIDATION_RE.matcher(link.get("href", "")).find()) {
                feed_urls.add(link.get("href", ""));
            }
        }

        // backup
        if (feed_urls.isEmpty()) {
            for (Element link : tree.xpath("//a[@href]")) {
                if (LINK_VALIDATION_RE.matcher(link.get("href", "")).find()) {
                    feed_urls.add(link.get("href", ""));
                }
            }
        }

        // refine
        List<String> output_urls = new ArrayList<>();
        for (String candidate : new LinkedHashSet<>(feed_urls)) {
            String link = safe_relative_url(params.base, candidate);
            link = Courlan.clean_url(link);
            if (Py.truthy(link) && !link.equals(params.ref) && Courlan.is_valid_url(link) && !BLACKLIST.matcher(link).find()) {
                output_urls.add(link);
            }
        }

        // log result
        LOGGER.debug("Feed URLs found: {} of which {} valid", feed_urls.size(), output_urls.size());
        return output_urls;
    }

    /** Alternative way to gather feed links: Google News. */
    public static List<String> probe_gnews(FeedParameters params, String urlfilter, ConfigParser config) {
        if (Py.truthy(params.lang)) {
            String downloaded = fetch_url(
                    "https://news.google.com/rss/search?q=site:" + params.domain + "&hl=" + params.lang
                            + "&scoring=n&num=100",
                    config);
            if (Py.truthy(downloaded)) {
                List<String> feed_links = extract_links(downloaded, params);
                feed_links = Courlan.filter_urls(feed_links, urlfilter);
                LOGGER.debug("{} Google news links found for {}", feed_links.size(), params.domain);
                return feed_links;
            }
        }
        return new ArrayList<>();
    }

    public static List<String> probe_gnews(FeedParameters params, String urlfilter) {
        return probe_gnews(params, urlfilter, DEFAULT_CONFIG);
    }

    /** time.sleep(): replaceable in tests (politeness delays). */
    public static java.util.function.DoubleConsumer SLEEP = seconds -> {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
        }
    };

    private static void sleep(double seconds) {
        SLEEP.accept(seconds);
    }


    /**
     * Try to find feed URLs.
     *
     * @param url Webpage or feed URL as string. Triggers URL-based filter if the webpage isn't a homepage.
     * @param target_lang Define a language to filter URLs based on heuristics (two-letter string, ISO 639-1 format).
     * @param external Similar hosts only or external URLs (boolean, defaults to False).
     * @param sleep_time Wait between requests on the same website.
     * @param config Pass configuration values for download control.
     * @return the extracted links as a list (sorted list of unique links).
     */
    public static List<String> find_feed_urls(String url, String target_lang, boolean external, double sleep_time,
            ConfigParser config) {
        Pair<String, String> hostinfo = Courlan.get_hostinfo(url);
        String domain = hostinfo.first();
        String baseurl = hostinfo.second();
        if (domain == null) {
            LOGGER.warn("Invalid URL: {}", url);
            return new ArrayList<>();
        }

        FeedParameters params = new FeedParameters(baseurl, domain, url, external, target_lang);
        String urlfilter = null;
        String downloaded = fetch_url(url, config);

        if (downloaded != null) {
            // assume it's a feed
            List<String> feed_links = extract_links(downloaded, params);
            if (feed_links.isEmpty()) {
                // assume it's a web page
                List<String> feeds = determine_feed(downloaded, params);
                for (int i = 0; i < Math.min(feeds.size(), MAX_FEEDS_CHECKED); i++) {
                    if (i > 0) {
                        sleep(sleep_time);
                    }
                    String feed_string = fetch_url(feeds.get(i), config);
                    if (Py.truthy(feed_string)) {
                        feed_links.addAll(extract_links(feed_string, params));
                    }
                }
                // filter triggered, prepare it
                if (url.length() > baseurl.length() + 2) {
                    urlfilter = url;
                }
            }
            // return links found
            if (!feed_links.isEmpty()) {
                feed_links = Courlan.filter_urls(feed_links, urlfilter);
                LOGGER.debug("{} feed links found for {}", feed_links.size(), domain);
                return feed_links;
            }
            LOGGER.debug("No usable feed links found: {}", url);
        } else {
            LOGGER.error("Could not download web page: {}", url);
            if (!Py.strip(url, "/").equals(baseurl)) {
                sleep(sleep_time);
                return try_homepage(baseurl, target_lang, external, sleep_time, config);
            }
        }

        return probe_gnews(params, urlfilter, config);
    }

    public static List<String> find_feed_urls(String url) {
        return find_feed_urls(url, null, false, 2.0, DEFAULT_CONFIG);
    }

    public static List<String> find_feed_urls(String url, String target_lang) {
        return find_feed_urls(url, target_lang, false, 2.0, DEFAULT_CONFIG);
    }

    /**
     * Shift into reverse and try the homepage instead of the particular feed
     * page that was given as input.
     */
    public static List<String> try_homepage(String baseurl, String target_lang, boolean external, double sleep_time,
            ConfigParser config) {
        LOGGER.debug("Probing homepage for feeds instead: {}", baseurl);
        return find_feed_urls(baseurl, target_lang, external, sleep_time, config);
    }
}
