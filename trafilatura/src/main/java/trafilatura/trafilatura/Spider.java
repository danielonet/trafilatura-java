// Java port of trafilatura/spider.py
package trafilatura.trafilatura;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import trafilatura.compat.ConfigParser;
import trafilatura.compat.Pair;
import trafilatura.compat.Py;
import trafilatura.compat.RobotFileParser;
import trafilatura.compat.Triple;
import trafilatura.courlan.Courlan;
import trafilatura.courlan.UrlStore;
import trafilatura.lxml.Element;
import trafilatura.lxml.Etree;
import trafilatura.lxml.XPath;
import trafilatura.trafilatura.Utils.Response;

import static trafilatura.trafilatura.Downloads.fetch_url;
import static trafilatura.trafilatura.Htmlprocessing.prune_unwanted_nodes;
import static trafilatura.trafilatura.Settings.DEFAULT_CONFIG;
import static trafilatura.trafilatura.Utils.LANGID_FLAG;
import static trafilatura.trafilatura.Utils.decode_file;
import static trafilatura.trafilatura.Utils.load_html;

/** Functions dedicated to website navigation and crawling/spidering. */
public final class Spider {
    private Spider() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Spider.class);

    public static UrlStore URL_STORE = new UrlStore(false, false);

    /** time.sleep(): replaceable in tests (politeness delays). */
    public static java.util.function.DoubleConsumer SLEEP = seconds -> {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
        }
    };

    /** Module-level references, replaceable in tests like the Python module attributes. */
    public static java.util.function.BiFunction<String, ConfigParser, Response> FETCH_RESPONSE =
            (url, config) -> Downloads.fetch_response(url, false, false, config);
    public static java.util.function.BiFunction<String, ConfigParser, RobotFileParser> GET_RULES = Spider::get_rules;

    static Response fetch_response(String url, ConfigParser config) {
        return FETCH_RESPONSE.apply(url, config);
    }

    public static final String ROBOTS_TXT_URL = "/robots.txt";
    public static final int MAX_SEEN_URLS = 10;
    public static final int MAX_KNOWN_URLS = 100000;

    /** Store necessary information to manage a focused crawl. */
    public static class CrawlParameters {
        public String start;
        public String base;
        public String ref;
        public String lang;
        public ConfigParser config;
        public RobotFileParser rules;
        public int i;
        public int known_num;
        public boolean is_on;
        public List<String> prune_xpath;

        public CrawlParameters(String start, String lang, RobotFileParser rules, List<String> prune_xpath,
                ConfigParser config) {
            this.start = start;
            this.base = this._get_base_url(start);
            this.ref = this._get_reference(start);
            this.lang = lang;
            this.config = config;
            this.rules = rules != null ? rules : GET_RULES.apply(this.base, config);
            this.i = 0;
            this.known_num = 0;
            this.is_on = true;
            this.prune_xpath = prune_xpath;
        }

        public CrawlParameters(String start) {
            this(start, null, null, null, DEFAULT_CONFIG);
        }

        /** Set reference domain for the crawl. */
        String _get_base_url(String start) {
            String base = Courlan.get_base_url(start);
            if (base.isEmpty()) {
                throw new IllegalArgumentException("cannot start crawl: " + start);
            }
            return base;
        }

        /** Determine the reference URL. */
        String _get_reference(String start) {
            return Py.count(start, "/") >= 3 ? start.substring(0, start.lastIndexOf('/')) : start;
        }

        /** Adjust crawl data based on URL store info. */
        public void update_metadata(UrlStore url_store) {
            this.is_on = url_store.get_known_domains().contains(this.base) && !url_store.is_exhausted_domain(this.base);
            this.known_num = url_store.find_known_urls(this.base).size();
        }

        /** Prepare the todo list, excluding invalid URLs. */
        public List<String> filter_list(List<String> todo) {
            List<String> result = new ArrayList<>();
            if (todo == null) {
                return result;
            }
            for (String u : todo) {
                if (!u.equals(this.start) && u.contains(this.ref)) {
                    result.add(u);
                }
            }
            return result;
        }

        /** Run checks: robots.txt rules, URL type and crawl breadth. */
        public boolean is_valid_link(String link) {
            return (this.rules == null || this.rules.can_fetch("*", link)) && link.contains(this.ref)
                    && !Courlan.is_not_crawlable(link);
        }
    }

    /** Check if there could be a redirection by meta-refresh tag: (html, url), both null on failure. */
    public static Pair<String, String> refresh_detection(String htmlstring, String homepage, ConfigParser config) {
        if (!htmlstring.toLowerCase(Locale.ROOT).contains("refresh")) {
            return Pair.of(htmlstring, homepage);
        }

        Element html_tree = load_html(htmlstring);
        if (html_tree == null) {
            return Pair.of(htmlstring, homepage);
        }

        // test meta-refresh redirection
        // https://stackoverflow.com/questions/2318446/how-to-follow-meta-refreshes-in-python
        String result = "";
        for (Element e : html_tree.iter("meta")) {
            if (e.get("http-equiv", "").toLowerCase(Locale.ROOT).equals("refresh")) {
                result = e.get("content", "");
                break;
            }
        }
        if (!result.contains(";")) {
            LOGGER.info("no redirect found: {}", homepage);
            return Pair.of(htmlstring, homepage);
        }

        String url2 = Py.strip(result.split(";", 2)[1]);
        if (url2.toLowerCase(Locale.ROOT).startsWith("url=")) {
            url2 = Py.strip(url2.substring(4), "'\"");
        }
        if (!url2.toLowerCase(Locale.ROOT).startsWith("http")) {
            // relative URL, adapt using the page being processed
            url2 = Courlan.urljoin(homepage, url2);
        }
        // second fetch
        String newhtmlstring = fetch_url(url2, config);
        if (newhtmlstring == null) {
            LOGGER.warn("failed redirect: {}", url2);
            return Pair.of(null, null);
        }
        LOGGER.info("successful redirect: {}", url2);
        return Pair.of(newhtmlstring, url2);
    }

    public static Pair<String, String> refresh_detection(String htmlstring, String homepage) {
        return refresh_detection(htmlstring, homepage, DEFAULT_CONFIG);
    }

    /** Check if the homepage is redirected and return appropriate values (html, homepage, base URL). */
    public static Triple<String, String, String> probe_alternative_homepage(String homepage, ConfigParser config) {
        Response response = fetch_response(homepage, config);
        if (response == null || response.data == null || response.data.length == 0) {
            return Triple.of(null, null, null);
        }

        if (!response.url.equals(homepage)) {
            LOGGER.info("followed homepage redirect: {}", response.url);
            homepage = response.url;
        }

        // decode response
        String htmlstring = decode_file(response.data);

        // is there a meta-refresh on the page?
        Pair<String, String> refreshed = refresh_detection(htmlstring, homepage, config);
        String new_homepage = refreshed.second();
        if (new_homepage == null) { // malformed or malicious content
            return Triple.of(null, null, null);
        }

        LOGGER.debug("fetching homepage OK: {}", new_homepage);
        return Triple.of(refreshed.first(), new_homepage, Courlan.get_base_url(new_homepage));
    }

    public static Triple<String, String, String> probe_alternative_homepage(String homepage) {
        return probe_alternative_homepage(homepage, DEFAULT_CONFIG);
    }

    /** Parse a robots.txt file with the urllib.robotparser port. */
    public static RobotFileParser parse_robots(String robots_url, Object data) {
        // https://github.com/python/cpython/blob/main/Lib/urllib/robotparser.py
        RobotFileParser rules = new RobotFileParser();
        rules.set_url(robots_url);
        // exceptions happening here
        try {
            if (!(data instanceof String text)) {
                // None, numbers and bytes fail in Python (no str.splitlines / str lines expected)
                throw new IllegalArgumentException("robots.txt data must be a string, not " + (data == null ? "None" : data.getClass().getSimpleName()));
            }
            rules.parse(Py.splitlines(text));
        } catch (RuntimeException exc) {
            LOGGER.error("cannot read robots.txt: {}", exc.toString());
            return null;
        }
        return rules;
    }

    /** Attempt to fetch and parse robots.txt file for a given website. */
    public static RobotFileParser get_rules(String base_url, ConfigParser config) {
        String robots_url = base_url + ROBOTS_TXT_URL;
        String data = fetch_url(robots_url, config);
        return Py.truthy(data) ? parse_robots(robots_url, data) : null;
    }

    public static RobotFileParser get_rules(String base_url) {
        return get_rules(base_url, DEFAULT_CONFIG);
    }

    /**
     * Run a baseline extraction and use a language detector to
     * check if the content matches the target language.
     * Return True if language checks are bypassed.
     */
    public static boolean is_target_language(String htmlstring, String language) {
        // py3langid is not available (LANGID_FLAG is false): checks are always bypassed
        if (Py.truthy(htmlstring) && Py.truthy(language) && LANGID_FLAG) {
            return language.equals(Utils.language_classifier(Baseline.baseline(htmlstring).second(), ""));
        }
        return true;
    }

    /** Probe if there are still navigation URLs in the queue. */
    public static boolean is_still_navigation(List<String> todo) {
        return todo.stream().anyMatch(Courlan::is_navigation_page);
    }

    /**
     * Examine the HTML code and process the retrieved internal links.
     * Extract and filter new internal links after an optional language check.
     * Store the links in todo-list while prioritizing the navigation ones.
     */
    public static void process_links(String htmlstring, CrawlParameters params, String url) {
        if (!is_target_language(htmlstring, params.lang)) {
            return;
        }

        if (Py.truthy(htmlstring) && params.prune_xpath != null && !params.prune_xpath.isEmpty()) {
            Element tree = load_html(htmlstring);
            if (tree != null) {
                List<XPath> expressions = new ArrayList<>();
                for (String x : params.prune_xpath) {
                    expressions.add(new XPath(x));
                }
                tree = prune_unwanted_nodes(tree, expressions);
                htmlstring = Etree.tostring(tree);
            }
        }

        Set<String> candidates;
        String target = Py.or(url, params.base);
        try {
            candidates = Courlan.extract_links(htmlstring, target, false, false, params.lang, false, true, true, false, null);
        } catch (IllegalArgumentException err) { // malformed relative link
            LOGGER.warn("link extraction failed: {}", target);
            return;
        }

        List<String> links = new ArrayList<>();
        List<String> links_priority = new ArrayList<>();
        for (String link : candidates) {
            if (!params.is_valid_link(link)) {
                continue;
            }
            if (Courlan.is_navigation_page(link)) {
                links_priority.add(link);
            } else {
                links.add(link);
            }
        }

        URL_STORE.add_urls(links, links_priority, false);
    }

    public static void process_links(String htmlstring, CrawlParameters params) {
        process_links(htmlstring, params, "");
    }

    /** Convert the response object and extract links. */
    public static void process_response(Response response, CrawlParameters params) {
        if (response == null || response.data == null || response.data.length == 0) {
            return;
        }
        // add final document URL to known_links
        URL_STORE.add_urls(List.of(response.url), true);

        // convert response to string and proceed to link extraction
        String url = Courlan.get_base_url(response.url).equals(params.base) ? response.url : params.base;
        process_links(decode_file(response.data), params, url);
    }

    /**
     * Initialize crawl by setting variables, copying values to the
     * URL store and retrieving the initial page if the crawl starts.
     */
    public static CrawlParameters init_crawl(String start, String lang, RobotFileParser rules, List<String> todo,
            List<String> known, List<String> prune_xpath, ConfigParser config) {
        CrawlParameters params = new CrawlParameters(start, lang, rules, prune_xpath, config);

        // todo: just known or also visited?
        URL_STORE.add_urls(known != null ? known : List.of(), true);
        URL_STORE.add_urls(params.filter_list(todo));
        URL_STORE.store_rules(params.base, params.rules);

        // visiting the start page if necessary
        if (todo == null || todo.isEmpty()) {
            URL_STORE.add_urls(List.of(params.start), false);
            params = crawl_page(params, true);
        } else {
            params.update_metadata(URL_STORE);
        }

        return params;
    }

    public static CrawlParameters init_crawl(String start) {
        return init_crawl(start, null, null, null, null, null, DEFAULT_CONFIG);
    }

    /** Examine a webpage, extract navigation links and links. */
    public static CrawlParameters crawl_page(CrawlParameters params, boolean initial) {
        String url = URL_STORE.get_url(params.base);
        if (!Py.truthy(url)) {
            params.update_metadata(URL_STORE);
            return params;
        }

        params.i += 1;

        if (initial) {
            // probe and process homepage
            Triple<String, String, String> probed = probe_alternative_homepage(url, params.config);
            String htmlstring = probed.first();
            String homepage = probed.second();
            String new_base_url = probed.third();
            if (Py.truthy(htmlstring) && Py.truthy(homepage) && Py.truthy(new_base_url)) {
                // follow the site's http -> https upgrade (same host only)
                if (params.base.startsWith("http://")
                        && new_base_url.equals("https://" + params.base.substring("http://".length()))) {
                    params.base = new_base_url;
                    params.ref = "https://" + (params.ref.startsWith("http://") ? params.ref.substring(7) : params.ref);
                }
                // register potentially new homepage
                URL_STORE.add_urls(List.of(homepage));
                // resolve links against the final URL unless the redirect left the crawled host
                process_links(htmlstring, params, Courlan.get_base_url(homepage).equals(params.base) ? homepage : url);
            }
        } else {
            Response response = fetch_response(url, params.config);
            process_response(response, params);
        }

        // optional backup of gathered pages without nav-pages ? ...
        params.update_metadata(URL_STORE);
        return params;
    }

    public static CrawlParameters crawl_page(CrawlParameters params) {
        return crawl_page(params, false);
    }

    /**
     * Basic crawler targeting pages of interest within a website.
     *
     * @param homepage URL of the page to first page to fetch, preferably the homepage of a website.
     * @param max_seen_urls maximum number of pages to visit, stop iterations at this number or at the
     *        exhaustion of pages on the website, whichever comes first.
     * @param max_known_urls stop if the total number of pages "known" exceeds this number.
     * @param todo provide a previously generated list of pages to visit / crawl frontier.
     * @param known_links provide a list of previously known pages.
     * @param lang try to target links according to language heuristics.
     * @param config use a different configuration (configparser format).
     * @param rules provide politeness rules (urllib.robotparser.RobotFileParser() format).
     * @param prune_xpath remove unwanted elements from the HTML pages using XPath.
     * @return the list of pages to visit (possibly empty) and the list of known links.
     */
    public static Pair<List<String>, List<String>> focused_crawler(String homepage, int max_seen_urls, int max_known_urls,
            List<String> todo, List<String> known_links, String lang, ConfigParser config, RobotFileParser rules,
            List<String> prune_xpath) {
        CrawlParameters params = init_crawl(homepage, lang, rules, todo, known_links, prune_xpath, config);

        double sleep_time = URL_STORE.get_crawl_delay(params.base, params.config.getfloat("DEFAULT", "SLEEP_TIME"));

        // visit pages until a limit is reached
        while (params.is_on && params.i < max_seen_urls && params.known_num < max_known_urls) {
            params = crawl_page(params);
            if (params.is_on) {
                SLEEP.accept(sleep_time);
            }
        }

        // refocus todo-list on URLs without navigation?
        List<String> newTodo = new ArrayList<>(new LinkedHashSet<>(URL_STORE.find_unvisited_urls(params.base)));
        // [u for u in todo if not is_navigation_page(u)]
        List<String> known = new ArrayList<>(new LinkedHashSet<>(URL_STORE.find_known_urls(params.base)));
        return Pair.of(newTodo, known);
    }

    public static Pair<List<String>, List<String>> focused_crawler(String homepage, int max_seen_urls, int max_known_urls) {
        return focused_crawler(homepage, max_seen_urls, max_known_urls, null, null, null, DEFAULT_CONFIG, null, null);
    }

    public static Pair<List<String>, List<String>> focused_crawler(String homepage) {
        return focused_crawler(homepage, MAX_SEEN_URLS, MAX_KNOWN_URLS);
    }
}
