package trafilatura.htmldate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import trafilatura.lxml.Element;

/**
 * Minimal stand-in for the Python htmldate package (https://github.com/adbar/htmldate, Apache-2.0).
 * Only the most reliable sources are examined (meta tags, JSON-LD, time elements, the URL);
 * the extensive text heuristics of htmldate are not ported.
 */
public final class Htmldate {
    private Htmldate() {
    }

    public static final LocalDate MIN_DATE = LocalDate.of(1995, 1, 1);
    private static final DateTimeFormatter OUTPUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final Set<String> PUBLISHED_PROPERTIES = Set.of(
            "article:published_time", "bt:pubdate", "dc:created", "dc:date", "og:article:published_time",
            "og:published_time", "sailthru.date", "rnews:datepublished", "datepublished");
    private static final Set<String> MODIFIED_PROPERTIES = Set.of(
            "article:modified_time", "datemodified", "modified_time", "og:article:modified_time", "og:updated_time",
            "release_date", "updated_time");
    private static final Set<String> DATE_NAMES = Set.of(
            "article.created", "article_date_original", "article.published", "created", "cxenseparse:recs:publishtime",
            "date", "date_published", "dc.date", "dc.date.created", "dc.date.issued", "dcterms.date", "gentime",
            "originalpublicationdate", "pubdate", "publishdate", "published-date", "publication_date", "citation_date",
            "citation_publication_date", "parsely-pub-date", "sailthru.date", "timestamp");

    private static final Pattern ISO_DATE = Pattern.compile("([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})");
    private static final Pattern URL_DATE = Pattern.compile("/([0-9]{4})[/_-]([0-9]{1,2})[/_-]([0-9]{1,2})(?:/|[_-]|$)");
    private static final Pattern JSON_PUBLISHED = Pattern.compile("\"datePublished\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JSON_MODIFIED = Pattern.compile("\"dateModified\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * {@code htmldate.find_date(tree, **date_config)}: returns a YYYY-MM-DD string or null.
     * Recognized options: url, original_date, extensive_search (ignored), max_date, min_date.
     */
    public static String find_date(Element tree, Map<String, Object> config) {
        if (tree == null) {
            return null;
        }
        boolean original = !Boolean.FALSE.equals(config.get("original_date"));
        LocalDate maxDate = parseBound(config.get("max_date"), LocalDate.now());
        LocalDate minDate = parseBound(config.get("min_date"), MIN_DATE);

        List<String> candidates = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        // meta tags
        for (Element meta : tree.iter("meta")) {
            String content = meta.get("content");
            if (content == null) {
                content = meta.get("datetime");
            }
            if (content == null) {
                continue;
            }
            String property = meta.get("property", "").toLowerCase(Locale.ROOT);
            String name = meta.get("name", "").toLowerCase(Locale.ROOT);
            String itemprop = meta.get("itemprop", "").toLowerCase(Locale.ROOT);
            if (PUBLISHED_PROPERTIES.contains(property) || DATE_NAMES.contains(name) || itemprop.equals("datepublished")
                    || itemprop.equals("datecreated")) {
                candidates.add(content);
            } else if (MODIFIED_PROPERTIES.contains(property) || MODIFIED_PROPERTIES.contains(name)
                    || itemprop.equals("datemodified")) {
                modified.add(content);
            }
        }
        // JSON-LD
        for (Element script : tree.xpath(".//script[@type=\"application/ld+json\"]")) {
            String text = script.text;
            if (text == null) {
                continue;
            }
            Matcher m = JSON_PUBLISHED.matcher(text);
            if (m.find()) {
                candidates.add(m.group(1));
            }
            m = JSON_MODIFIED.matcher(text);
            if (m.find()) {
                modified.add(m.group(1));
            }
        }
        // time elements
        for (Element time : tree.iter("time")) {
            String datetime = time.get("datetime");
            if (datetime != null) {
                if ("pubdate".equalsIgnoreCase(time.get("pubdate", "")) || time.get("class", "").contains("published")
                        || "datepublished".equalsIgnoreCase(time.get("itemprop", ""))) {
                    candidates.add(0, datetime);
                } else {
                    candidates.add(datetime);
                }
            }
        }
        // URL
        Object url = config.get("url");
        if (url instanceof String s) {
            Matcher m = URL_DATE.matcher(s);
            if (m.find()) {
                candidates.add(m.group(1) + "-" + m.group(2) + "-" + m.group(3));
            }
        }

        List<String> ordered = new ArrayList<>(original ? candidates : modified);
        ordered.addAll(original ? modified : candidates);
        for (String candidate : ordered) {
            LocalDate date = parseDate(candidate);
            if (date != null && !date.isBefore(minDate) && !date.isAfter(maxDate)) {
                return date.format(OUTPUT_FORMAT);
            }
        }
        return null;
    }

    private static LocalDate parseBound(Object value, LocalDate fallback) {
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof String s) {
            LocalDate parsed = parseDate(s);
            if (parsed != null) {
                return parsed;
            }
        }
        return fallback;
    }

    private static LocalDate parseDate(String value) {
        Matcher m = ISO_DATE.matcher(value);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (RuntimeException err) {
            return null;
        }
    }

    /** {@code htmldate.meta.reset_caches()}: nothing is cached in this stand-in. */
    public static void reset_caches() {
        // no caches
    }
}
