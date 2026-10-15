// Java port of trafilatura/sitemaps.py
package trafilatura.trafilatura;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import trafilatura.compat.ConfigParser;
import trafilatura.compat.Pair;
import trafilatura.compat.Py;
import trafilatura.compat.Re;
import trafilatura.courlan.Courlan;
import trafilatura.lxml.Element;
import trafilatura.lxml.Etree;

import static trafilatura.trafilatura.Deduplication.is_similar_domain;
import static trafilatura.trafilatura.Downloads.fetch_url;
import static trafilatura.trafilatura.Downloads.is_live_page;
import static trafilatura.trafilatura.Settings.DEFAULT_CONFIG;
import static trafilatura.trafilatura.Settings.MAX_LINKS;
import static trafilatura.trafilatura.Settings.MAX_SITEMAPS_SEEN;
import static trafilatura.trafilatura.Utils.safe_relative_url;

/** Deriving link info from sitemaps. */
public final class Sitemaps {
    private Sitemaps() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Sitemaps.class);

    public static final String SITEMAP_NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9";
    public static final Pattern XHTML_REGEX = Pattern.compile("<xhtml:link.+?>", Pattern.DOTALL);
    public static final Pattern HREFLANG_REGEX = Pattern.compile("href=[\"'](.+?)[\"']");
    public static final Pattern WHITELISTED_PLATFORMS = Pattern.compile(
            "(?:blogger|blogpost|ghost|hubspot|livejournal|medium|typepad|squarespace|tumblr|weebly|wix|wordpress)\\.");

    public static final Pattern SITEMAP_FORMAT = Pattern.compile(
            "^.{0,5}<\\?xml|<(?:[\\w.-]+:)?(?:sitemapindex|sitemap|urlset)\\b", Pattern.UNICODE_CHARACTER_CLASS);
    public static final Pattern DETECT_SITEMAP_LINK = Pattern.compile("\\.xml(\\..{2,4})?$|\\.xml[?#]");
    public static final Pattern DETECT_LINKS = Pattern.compile("https?://[^\\s<\"]+");
    public static final Pattern SCRUB_REGEX = Pattern.compile("\\?.*$|#.*$");
    public static final Pattern POTENTIAL_SITEMAP = Pattern.compile("\\.xml\\b"); // |\bsitemap\b

    public static final List<String> GUESSES = List.of(
            "sitemap.xml", "sitemap.xml.gz", "sitemap", "sitemap_index.xml", "sitemap_news.xml");

    /** Store all necessary information on sitemap download and processing. */
    public static class SitemapObject {
        public String base_url;
        public ConfigParser config;
        public String content = "";
        public String current_url = "";
        public String domain;
        public boolean external;
        public Set<String> seen = new HashSet<>();
        public List<String> sitemap_urls;
        public String target_lang;
        public List<String> urls = new ArrayList<>();

        public SitemapObject(String base_url, String domain, List<String> sitemapsurls, String target_lang,
                boolean external, ConfigParser config) {
            this.base_url = base_url;
            this.config = config;
            this.domain = domain;
            this.external = external;
            this.sitemap_urls = sitemapsurls;
            this.target_lang = target_lang;
        }

        public SitemapObject(String base_url, String domain, List<String> sitemapsurls) {
            this(base_url, domain, sitemapsurls, null, false, DEFAULT_CONFIG);
        }

        /** Fetch a sitemap over the network. */
        public void fetch() {
            LOGGER.debug("fetching sitemap: {}", this.current_url);
            this.content = Py.or(fetch_url(this.current_url, this.config), "");
            this.seen.add(this.current_url);
        }

        /**
         * Examine a link and determine if it's valid and if it leads to
         * a sitemap or a web page.
         */
        public void handle_link(String link) {
            if (link.equals(this.current_url)) { // safety check
                return;
            }
            // fix, check, clean and normalize
            link = safe_relative_url(this.base_url, link);
            // filter before cleaning: slash-stripping can hide language markers
            if (!Courlan.lang_filter(link, this.target_lang)) {
                return;
            }
            link = Py.or(Courlan.clean_url(link, this.target_lang), "");
            if (link.isEmpty()) {
                return;
            }

            String newdomain = Courlan.extract_domain(link, true);
            if (newdomain == null) {
                LOGGER.error("couldn't extract domain: {}", link);
                return;
            }

            // don't take links from another domain and make an exception for main platforms
            // also bypass: subdomains vs. domains
            if (!this.external && !WHITELISTED_PLATFORMS.matcher(newdomain).find()
                    && !is_similar_domain(this.domain, newdomain)) {
                LOGGER.warn("link discarded, diverging domain names: {} {}", this.domain, newdomain);
                return;
            }

            if (DETECT_SITEMAP_LINK.matcher(link).find()) {
                this.sitemap_urls.add(link);
            } else {
                this.urls.add(link);
            }
        }

        /** Extract links from the content using pre-defined regex, index and handler. */
        public void extract_links(Pattern regex, int index, Consumer<String> handler) {
            Matcher m = regex.matcher(this.content);
            int count = 0;
            while (count < MAX_LINKS && m.find()) {
                count++;
                handler.accept(m.group(index));
            }
            LOGGER.debug("{} sitemaps and {} links found for {}", this.sitemap_urls.size(), this.urls.size(), this.current_url);
        }

        /** Extract links corresponding to a given target language. */
        public void extract_sitemap_langlinks() {
            if (!this.content.contains("hreflang=")) {
                return;
            }

            Pattern lang_regex = Pattern.compile(
                    "hreflang=[\"'](" + Pattern.quote(Py.or(this.target_lang, "")) + ".*?|x-default)[\"']", Pattern.DOTALL);

            // Examine language code attributes.
            this.extract_links(XHTML_REGEX, 0, attrs -> {
                if (lang_regex.matcher(attrs).find()) {
                    Matcher lang_match = Re.search(HREFLANG_REGEX, attrs);
                    if (lang_match != null) {
                        this.handle_link(lang_match.group(1));
                    }
                }
            });
        }

        /** Extract locations in the sitemap namespace (or legacy unnamespaced XML). */
        public void extract_sitemap_links() {
            Element tree;
            try {
                // XMLParser(recover=True): lenient parsing
                tree = Etree.fromstring_recover(this.content);
            } catch (RuntimeException err) {
                return;
            }
            if (tree == null) {
                return;
            }
            int count = 0;
            for (Element element : tree.iter()) {
                if (count >= MAX_LINKS) {
                    break;
                }
                if (!Etree.localname(element).equals("loc")) {
                    continue;
                }
                // "loc" without namespace or "{http://www.sitemaps.org/schemas/sitemap/0.9}loc"
                String ns = Etree.namespace(element);
                if (ns != null && !ns.isEmpty() && !ns.equals(SITEMAP_NAMESPACE)) {
                    continue;
                }
                count++;
                // Entity references and nested markup are not part of a location URL.
                if (element.size() == 0 && Py.truthy(element.text) && element.text.startsWith("http")) {
                    this.handle_link(element.text);
                }
            }
        }

        /** Download a sitemap and extract the links it contains. */
        public void process() {
            boolean plausible = is_plausible_sitemap(this.current_url, this.content);
            // safeguard
            if (!plausible) {
                return;
            }
            // try to extract links from TXT file
            if (Re.match(SITEMAP_FORMAT, this.content) == null) {
                this.extract_links(DETECT_LINKS, 0, this::handle_link);
                return;
            }
            // process XML sitemap
            if (this.target_lang != null) {
                // only skip the generic extraction if hreflang links were found here
                int found_before = this.sitemap_urls.size() + this.urls.size();
                this.extract_sitemap_langlinks();
                if (this.sitemap_urls.size() + this.urls.size() > found_before) {
                    return;
                }
            }
            this.extract_sitemap_links();
        }
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
     * Look for sitemaps for the given URL and gather links.
     *
     * @param url Webpage or sitemap URL as string. Triggers URL-based filter if the webpage isn't a homepage.
     * @param target_lang Define a language to filter URLs based on heuristics (two-letter string, ISO 639-1 format).
     * @param external Similar hosts only or external URLs (boolean, defaults to False).
     * @param sleep_time Wait between requests on the same website.
     * @param max_sitemaps Maximum number of sitemaps to process.
     * @param config Pass configuration values for download control.
     * @return the extracted links as a list (sorted list of unique links).
     */
    public static List<String> sitemap_search(String url, String target_lang, boolean external, double sleep_time,
            int max_sitemaps, ConfigParser config) {
        Pair<String, String> hostinfo = Courlan.get_hostinfo(url);
        String domainname = hostinfo.first();
        String baseurl = hostinfo.second();
        if (domainname == null) {
            LOGGER.warn("invalid URL: {}", url);
            return new ArrayList<>();
        }

        if (!is_live_page(baseurl, config)) {
            LOGGER.warn("base URL unreachable, dropping sitemap: {}", url);
            return new ArrayList<>();
        }

        String urlfilter = null;

        List<String> sitemapurls;
        if (url.endsWith(".gz") || url.endsWith("sitemap") || url.endsWith(".xml")) {
            sitemapurls = new ArrayList<>(List.of(url));
        } else {
            sitemapurls = new ArrayList<>();
            // set url filter to target subpages
            if (url.length() > baseurl.length() + 2) {
                urlfilter = url;
            }
        }

        SitemapObject sitemap = new SitemapObject(baseurl, domainname, sitemapurls, target_lang, external, config);

        // try sitemaps in robots.txt file, additional URLs just in case
        if (sitemap.sitemap_urls.isEmpty()) {
            List<String> robots = find_robots_sitemaps(baseurl, config);
            if (robots.isEmpty()) {
                // reversed: the queue is consumed from the end, so the most likely guess is tried first
                robots = new ArrayList<>();
                for (String guess : GUESSES.reversed()) {
                    robots.add(baseurl + "/" + guess);
                }
            }
            sitemap.sitemap_urls = robots;
        }

        // iterate through nested sitemaps and results
        while (!sitemap.sitemap_urls.isEmpty() && sitemap.seen.size() < max_sitemaps) {
            sitemap.current_url = sitemap.sitemap_urls.remove(sitemap.sitemap_urls.size() - 1);
            sitemap.fetch();
            sitemap.process();
            // sanity check: keep track of visited sitemaps and exclude them
            List<String> remaining = new ArrayList<>();
            for (String s : sitemap.sitemap_urls) {
                if (!sitemap.seen.contains(s)) {
                    remaining.add(s);
                }
            }
            sitemap.sitemap_urls = remaining;

            if (!sitemap.sitemap_urls.isEmpty() && sitemap.seen.size() < max_sitemaps) {
                sleep(sleep_time);
            }
        }

        if (urlfilter != null) {
            sitemap.urls = Courlan.filter_urls(sitemap.urls, urlfilter);
        }

        LOGGER.debug("{} sitemap links found for {}", sitemap.urls.size(), domainname);
        return sitemap.urls;
    }

    public static List<String> sitemap_search(String url) {
        return sitemap_search(url, null, false, 2.0, MAX_SITEMAPS_SEEN, DEFAULT_CONFIG);
    }

    public static List<String> sitemap_search(String url, String target_lang) {
        return sitemap_search(url, target_lang, false, 2.0, MAX_SITEMAPS_SEEN, DEFAULT_CONFIG);
    }

    /** Check if the sitemap corresponds to an expected format, i.e. TXT or XML. */
    public static boolean is_plausible_sitemap(String url, String contents) {
        if (contents == null) {
            return false;
        }

        // strip query and fragments
        url = SCRUB_REGEX.matcher(url).replaceAll("");

        // check content
        if ((POTENTIAL_SITEMAP.matcher(url).find() && Re.match(SITEMAP_FORMAT, contents) == null)
                || Py.slice(contents, null, 150).toLowerCase().contains("<html")) {
            LOGGER.warn("not a valid XML sitemap: {}", url);
            return false;
        }

        return true;
    }

    /** Guess the location of the robots.txt file and try to extract sitemap URLs from it. */
    public static List<String> find_robots_sitemaps(String baseurl, ConfigParser config) {
        String robotstxt = fetch_url(baseurl + "/robots.txt", config);
        return extract_robots_sitemaps(robotstxt, baseurl);
    }

    public static List<String> find_robots_sitemaps(String baseurl) {
        return find_robots_sitemaps(baseurl, DEFAULT_CONFIG);
    }

    /** Read a robots.txt file and find sitemap links. */
    public static List<String> extract_robots_sitemaps(String robotstxt, String baseurl) {
        // sanity check on length (cause: redirections)
        if (robotstxt == null || robotstxt.length() > 10000) {
            return new ArrayList<>();
        }

        List<String> candidates = new ArrayList<>();
        // source: https://github.com/python/cpython/blob/3.12/Lib/urllib/robotparser.py
        for (String line : Py.splitlines(robotstxt)) {
            // remove optional comment and strip line
            int i = line.indexOf('#');
            if (i >= 0) {
                line = line.substring(0, i);
            }
            line = Py.strip(line);
            if (line.isEmpty()) {
                continue;
            }
            String[] line_parts = line.split(":", 2);
            if (line_parts.length == 2) {
                line_parts[0] = Py.strip(line_parts[0]).toLowerCase();
                if (line_parts[0].equals("sitemap")) {
                    // urllib.parse.unquote(line[1].strip())
                    candidates.add(Py.strip(line_parts[1]));
                }
            }
        }

        List<String> sitemapurls = new ArrayList<>();
        for (String u : new LinkedHashSet<>(candidates)) {
            if (!u.isEmpty()) {
                String url = safe_relative_url(baseurl, u);
                if (!url.isEmpty()) {
                    sitemapurls.add(url);
                }
            }
        }

        LOGGER.debug("{} sitemaps found in robots.txt", sitemapurls.size());
        return sitemapurls;
    }
}
