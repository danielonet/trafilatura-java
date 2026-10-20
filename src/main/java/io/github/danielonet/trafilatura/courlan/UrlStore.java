// Java port of courlan/urlstore.py
package io.github.danielonet.trafilatura.courlan;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.danielonet.trafilatura.compat.Pair;
import io.github.danielonet.trafilatura.compat.RobotFileParser;

/**
 * Port of {@code courlan.urlstore.UrlStore} without compression or pickling:
 * stores domain-classified URLs and their visited state.
 */
public class UrlStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(UrlStore.class);

    public enum State { OPEN, ALL_VISITED, BUSTED }

    public static final class DomainEntry {
        int count;
        RobotFileParser rules;
        State state;
        LocalDateTime timestamp;
        int total;
        Deque<UrlPathTuple> tuples = new ArrayDeque<>();

        DomainEntry(State state) {
            this.state = state;
        }
    }

    public static final class UrlPathTuple {
        final String urlpath;
        boolean visited;

        UrlPathTuple(String urlpath, boolean visited) {
            this.urlpath = urlpath;
            this.visited = visited;
        }

        String path() {
            return urlpath;
        }
    }

    public final boolean compressed;
    public boolean done;
    public final String language;
    public final boolean strict;
    public final boolean trailing_slash;
    private Map<String, DomainEntry> urldict = new LinkedHashMap<>();

    public UrlStore(boolean compressed, String language, boolean strict, boolean trailing_slash, boolean verbose) {
        this.compressed = compressed;
        this.language = language;
        this.strict = strict;
        this.trailing_slash = trailing_slash;
    }

    public UrlStore(boolean compressed, boolean strict, boolean verbose) {
        this(compressed, null, strict, true, verbose);
    }

    public UrlStore(boolean compressed, boolean strict) {
        this(compressed, null, strict, true, false);
    }

    public UrlStore() {
        this(false, null, false, true, false);
    }

    private DomainEntry entry(String domain) {
        return urldict.computeIfAbsent(domain, k -> new DomainEntry(State.OPEN));
    }

    private Map<String, Deque<UrlPathTuple>> _buffer_urls(List<String> data, boolean visited) {
        Map<String, Deque<UrlPathTuple>> inputdict = new LinkedHashMap<>();
        for (String url : new LinkedHashSet<>(data)) {
            try {
                Pair<Boolean, Courlan.SplitResult> validation = Courlan.validate_url(url);
                if (!validation.first() || validation.second() == null) {
                    LOGGER.debug("Invalid URL: {}", url);
                    throw new IllegalArgumentException();
                }
                if (language != null && !Courlan.lang_filter(url, language, strict, trailing_slash)) {
                    LOGGER.debug("Wrong language: {}", url);
                    throw new IllegalArgumentException();
                }
                String normalized = Courlan.normalize_url(url, strict, language, trailing_slash);
                Pair<String, String> hostAndPath = Courlan.get_host_and_path(normalized);
                inputdict.computeIfAbsent(hostAndPath.first(), k -> new ArrayDeque<>())
                        .add(new UrlPathTuple(hostAndPath.second(), visited));
            } catch (IllegalArgumentException err) {
                LOGGER.warn("Discarding URL: {}", url);
            }
        }
        return inputdict;
    }

    private Deque<UrlPathTuple> _load_urls(String domain) {
        DomainEntry entry = urldict.get(domain);
        return entry != null ? entry.tuples : new ArrayDeque<>();
    }

    private void _set_done() {
        if (!done && urldict.values().stream().allMatch(v -> v.state != State.OPEN)) {
            synchronized (this) {
                done = true;
            }
        }
    }

    private synchronized void _store_urls(String domain, Deque<UrlPathTuple> to_right, LocalDateTime timestamp,
            Deque<UrlPathTuple> to_left) {
        // http/https switch
        if (domain.startsWith("http://")) {
            String candidate = "https" + domain.substring(4);
            if (urldict.containsKey(candidate)) {
                domain = candidate;
            }
        } else if (domain.startsWith("https://")) {
            String candidate = "http" + domain.substring(5);
            if (urldict.containsKey(candidate)) {
                urldict.put(domain, urldict.remove(candidate));
            }
        }

        Deque<UrlPathTuple> urls;
        Set<String> known = new HashSet<>();
        if (urldict.containsKey(domain)) {
            if (urldict.get(domain).state == State.BUSTED) {
                return;
            }
            urls = _load_urls(domain);
            for (UrlPathTuple u : urls) {
                known.add(u.path());
            }
        } else {
            urls = new ArrayDeque<>();
        }

        if (to_right != null) {
            for (UrlPathTuple t : to_right) {
                if (!Courlan.is_known_link(t.path(), known)) {
                    urls.addLast(t);
                }
            }
        }
        if (to_left != null) {
            for (UrlPathTuple t : to_left) {
                if (!Courlan.is_known_link(t.path(), known)) {
                    urls.addFirst(t);
                }
            }
        }

        DomainEntry entry = entry(domain);
        entry.tuples = urls;
        entry.total = urls.size();
        if (timestamp != null) {
            entry.timestamp = timestamp;
        }
        if (urls.stream().allMatch(u -> u.visited)) {
            entry.state = State.ALL_VISITED;
        } else {
            entry.state = State.OPEN;
            done = false;
        }
    }

    private List<String> _search_urls(List<String> urls, int switchValue) {
        String lastDomain = null;
        Map<String, Boolean> knownPaths = new LinkedHashMap<>();
        Set<String> remaining = new LinkedHashSet<>(urls);
        for (String url : new TreeSet<>(remaining)) {
            Pair<String, String> hostAndPath = Courlan.get_host_and_path(url);
            if (!hostAndPath.first().equals(lastDomain)) {
                lastDomain = hostAndPath.first();
                knownPaths = new LinkedHashMap<>();
                for (UrlPathTuple u : _load_urls(lastDomain)) {
                    knownPaths.put(u.path(), u.visited);
                }
            }
            if (knownPaths.containsKey(hostAndPath.second())
                    && (switchValue == 1 || (switchValue == 2 && knownPaths.get(hostAndPath.second())))) {
                remaining.remove(url);
            }
        }
        return new ArrayList<>(remaining);
    }

    // ADDITIONS AND DELETIONS

    /** Add a list of URLs, optionally some to the left, optionally as already visited. */
    public void add_urls(List<String> urls, List<String> appendleft, boolean visited) {
        if (urls != null && !urls.isEmpty()) {
            _buffer_urls(urls, visited).forEach((host, tuples) -> _store_urls(host, tuples, null, null));
        }
        if (appendleft != null && !appendleft.isEmpty()) {
            _buffer_urls(appendleft, visited).forEach((host, tuples) -> _store_urls(host, null, null, tuples));
        }
    }

    public void add_urls(List<String> urls) {
        add_urls(urls, null, false);
    }

    public void add_urls(List<String> urls, boolean visited) {
        add_urls(urls, null, visited);
    }

    /** Declare domains void and prune the store. */
    public synchronized void discard(List<String> domains) {
        for (String d : domains) {
            urldict.put(d, new DomainEntry(State.BUSTED));
        }
        _set_done();
    }

    /** Re-initialize the URL store. */
    public synchronized void reset() {
        urldict = new LinkedHashMap<>();
        Courlan.clear_caches();
    }

    // DOMAINS / HOSTNAMES

    public List<String> get_known_domains() {
        return new ArrayList<>(urldict.keySet());
    }

    public List<String> get_unvisited_domains() {
        List<String> result = new ArrayList<>();
        urldict.forEach((d, v) -> {
            if (v.state == State.OPEN) {
                result.add(d);
            }
        });
        return result;
    }

    public boolean is_exhausted_domain(String domain) {
        DomainEntry entry = urldict.get(domain);
        return entry != null && entry.state != State.OPEN;
    }

    public int unvisited_websites_number() {
        return get_unvisited_domains().size();
    }

    // URL-BASED QUERIES

    public List<String> find_known_urls(String domain) {
        List<String> result = new ArrayList<>();
        for (UrlPathTuple u : _load_urls(domain)) {
            result.add(domain + u.path());
        }
        return result;
    }

    public List<String> find_unvisited_urls(String domain) {
        List<String> result = new ArrayList<>();
        if (!is_exhausted_domain(domain)) {
            for (UrlPathTuple u : _load_urls(domain)) {
                if (!u.visited) {
                    result.add(domain + u.path());
                }
            }
        }
        return result;
    }

    public List<String> filter_unknown_urls(List<String> urls) {
        return _search_urls(urls, 1);
    }

    public List<String> filter_unvisited_urls(List<String> urls) {
        return _search_urls(urls, 2);
    }

    public boolean has_been_visited(String url) {
        return filter_unvisited_urls(List.of(url)).isEmpty();
    }

    public boolean is_known(String url) {
        Pair<String, String> hostAndPath = Courlan.get_host_and_path(url);
        for (UrlPathTuple u : _load_urls(hostAndPath.first())) {
            if (u.path().equals(hostAndPath.second())) {
                return true;
            }
        }
        return false;
    }

    // DOWNLOADS

    /** Retrieve a single URL and consider it to be visited (with corresponding timestamp). */
    public String get_url(String domain, boolean as_visited) {
        if (!is_exhausted_domain(domain)) {
            Deque<UrlPathTuple> urlTuples = _load_urls(domain);
            for (UrlPathTuple url : urlTuples) {
                if (!url.visited) {
                    if (as_visited) {
                        url.visited = true;
                        synchronized (this) {
                            entry(domain).count += 1;
                        }
                        _store_urls(domain, null, LocalDateTime.now(), null);
                    }
                    return domain + url.path();
                }
            }
        }
        synchronized (this) {
            entry(domain).state = State.ALL_VISITED;
        }
        _set_done();
        return null;
    }

    public String get_url(String domain) {
        return get_url(domain, true);
    }

    /** Get a list of immediately downloadable URLs according to the given time limit per domain. */
    public List<String> get_download_urls(double time_limit, int max_urls) {
        List<String> urls = new ArrayList<>();
        for (Map.Entry<String, DomainEntry> e : new ArrayList<>(urldict.entrySet())) {
            DomainEntry entry = e.getValue();
            if (entry.state != State.OPEN) {
                continue;
            }
            if (entry.timestamp == null
                    || Duration.between(entry.timestamp, LocalDateTime.now()).toMillis() / 1000.0 > time_limit) {
                String url = get_url(e.getKey());
                if (url != null) {
                    urls.add(url);
                    if (urls.size() >= max_urls) {
                        break;
                    }
                }
            }
        }
        _set_done();
        return urls;
    }

    public List<String> get_download_urls(double time_limit) {
        return get_download_urls(time_limit, 10000);
    }

    // CRAWLING

    public synchronized void store_rules(String website, RobotFileParser rules) {
        entry(website).rules = rules;
    }

    public RobotFileParser get_rules(String website) {
        DomainEntry entry = urldict.get(website);
        return entry != null ? entry.rules : null;
    }

    /** Return the delay as extracted from robots.txt, or a given default. */
    public double get_crawl_delay(String website, double defaultValue) {
        RobotFileParser rules = get_rules(website);
        Double delay = rules != null ? rules.crawl_delay("*") : null;
        return delay != null && delay != 0 ? delay : defaultValue;
    }

    public double get_crawl_delay(String website) {
        return get_crawl_delay(website, 5);
    }

    // GENERAL INFO

    public List<Integer> get_all_counts() {
        List<Integer> counts = new ArrayList<>();
        urldict.values().forEach(v -> counts.add(v.count));
        return counts;
    }

    public int total_url_number() {
        return urldict.values().stream().mapToInt(v -> v.total).sum();
    }

    public boolean download_threshold_reached(double threshold) {
        return urldict.values().stream().anyMatch(v -> v.count >= threshold);
    }

    public List<String> dump_urls() {
        List<String> urls = new ArrayList<>();
        for (String domain : urldict.keySet()) {
            urls.addAll(find_known_urls(domain));
        }
        return urls;
    }

    public void print_unvisited_urls() {
        for (String domain : urldict.keySet()) {
            System.out.println(String.join("\n", find_unvisited_urls(domain)));
        }
        System.out.flush();
    }

    public void print_urls() {
        for (String domain : urldict.keySet()) {
            List<String> lines = new ArrayList<>();
            for (UrlPathTuple u : _load_urls(domain)) {
                lines.add(domain + u.path() + "\t" + (u.visited ? "True" : "False"));
            }
            System.out.println(String.join("\n", lines));
        }
        System.out.flush();
    }
}
