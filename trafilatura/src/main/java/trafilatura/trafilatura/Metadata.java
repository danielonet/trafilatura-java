// Java port of trafilatura/metadata.py
package trafilatura.trafilatura;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import trafilatura.compat.Json;
import trafilatura.compat.Pair;
import trafilatura.compat.Py;
import trafilatura.compat.Re;
import trafilatura.compat.Triple;
import trafilatura.courlan.Courlan;
import trafilatura.htmldate.Htmldate;
import trafilatura.lxml.Element;
import trafilatura.lxml.Etree;
import trafilatura.lxml.XPath;
import trafilatura.trafilatura.Settings.Document;

import static trafilatura.trafilatura.Baseline.block_text;
import static trafilatura.trafilatura.Htmlprocessing.prune_unwanted_nodes;
import static trafilatura.trafilatura.JsonMetadata.extract_json;
import static trafilatura.trafilatura.JsonMetadata.extract_json_parse_error;
import static trafilatura.trafilatura.JsonMetadata.normalize_authors;
import static trafilatura.trafilatura.JsonMetadata.normalize_json;
import static trafilatura.trafilatura.Settings.set_date_params;
import static trafilatura.trafilatura.Utils.HTML_STRIP_TAGS;
import static trafilatura.trafilatura.Utils.line_processing;
import static trafilatura.trafilatura.Utils.load_html;
import static trafilatura.trafilatura.Utils.safe_base_url;
import static trafilatura.trafilatura.Utils.trim;
import static trafilatura.trafilatura.Xpaths.AUTHOR_DISCARD_XPATHS;
import static trafilatura.trafilatura.Xpaths.AUTHOR_XPATHS;
import static trafilatura.trafilatura.Xpaths.CATEGORIES_XPATHS;
import static trafilatura.trafilatura.Xpaths.TAGS_XPATHS;
import static trafilatura.trafilatura.Xpaths.TITLE_XPATHS;

/** Module bundling all functions needed to scrape metadata from webpages. */
public final class Metadata {
    private Metadata() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Metadata.class);

    public static final Pattern META_URL = Pattern.compile("https?://(?:www\\.|w[0-9]+\\.)?([^/]+)");

    // unrolled form of ("(?:\\.|[^"\\])*")|\s: java.util.regex recurses on alternations inside loops
    // and overflows the stack on long JSON strings, Python's engine does not
    public static final Pattern JSON_MINIFY = Pattern.compile("(\"[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+\")|\\s");

    public static final Pattern HTMLTITLE_REGEX = Pattern.compile("^(.+)?\\s+[–•·—|⁄*⋆~‹«<›»>:-]\\s+(.+)$"); // part without dots?

    public static final Pattern CLEAN_META_TAGS = Pattern.compile("[\"']");

    public static final Pattern LICENSE_REGEX = Pattern.compile("/(by-nc-nd|by-nc-sa|by-nc|by-nd|by-sa|by|zero)/([1-9]\\.[0-9])");
    public static final Pattern TEXT_LICENSE_REGEX = Pattern.compile(
            "(cc|creative commons) (by-nc-nd|by-nc-sa|by-nc|by-nd|by-sa|by|zero) ?([1-9]\\.[0-9])?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Set<String> METANAME_AUTHOR = Set.of(
            "article:author", "atc-metaauthor", "author", "authors", "byl", "citation_author", "creator", "dc.creator",
            "dc.creator.aut", "dc:creator", "dcterms.creator", "dcterms.creator.aut", "dcsext.author", "parsely-author",
            "rbauthors", "sailthru.author", "shareaholic:article_author_name"); // questionable: twitter:creator
    public static final Set<String> METANAME_DESCRIPTION = Set.of(
            "dc.description", "dc:description", "dcterms.abstract", "dcterms.description", "description",
            "sailthru.description", "twitter:description");
    public static final Set<String> METANAME_PUBLISHER = Set.of(
            "article:publisher", "citation_journal_title", "copyright", "dc.publisher", "dc:publisher",
            "dcterms.publisher", "publisher", "sailthru.publisher", "rbpubname", "twitter:site"); // questionable: citation_publisher
    public static final Set<String> METANAME_TAG = Set.of(
            "citation_keywords", "dcterms.subject", "keywords", "parsely-tags", "shareaholic:keywords", "tags");
    public static final Set<String> METANAME_TITLE = Set.of(
            "citation_title", "dc.title", "dcterms.title", "fb_title", "headline", "parsely-title", "sailthru.title",
            "shareaholic:title", "rbtitle", "title", "twitter:title");
    public static final Set<String> METANAME_IMAGE = Set.of(
            "image", "og:image", "og:image:url", "og:image:secure_url", "twitter:image", "twitter:image:src");
    public static final Set<String> PROPERTY_AUTHOR = Set.of("author", "article:author");
    public static final Set<String> TWITTER_ATTRS = Set.of("twitter:site", "application-name");

    // also interesting: article:section

    public static final Set<String> EXTRA_META = Set.of("charset", "http-equiv", "property");

    public static final Map<String, String> OG_PROPERTIES = Map.of(
            "og:title", "title",
            "og:description", "description",
            "og:site_name", "sitename",
            "og:image", "image",
            "og:image:url", "image",
            "og:image:secure_url", "image",
            "og:type", "pagetype");

    public static final Set<String> OG_AUTHOR = Set.of("og:author", "og:article:author");

    public static final List<String> URL_SELECTORS = List.of(
            ".//head//link[@rel=\"canonical\"]", ".//head//base", ".//head//link[@rel=\"alternate\"][@hreflang=\"x-default\"]");

    /** Remove special characters of tags. */
    public static String normalize_tags(String tags) {
        String trimmed = trim(Py.unescape(tags));
        if (trimmed.isEmpty()) {
            return "";
        }
        tags = CLEAN_META_TAGS.matcher(trimmed).replaceAll("");
        List<String> kept = new ArrayList<>();
        for (String tag : tags.split(", ", -1)) {
            if (!tag.isEmpty()) {
                kept.add(tag);
            }
        }
        return String.join(", ", kept);
    }

    /** Check if the authors string correspond to expected values. */
    public static String check_authors(String authors, Set<String> author_blacklist) {
        Set<String> blacklist = new HashSet<>();
        for (String a : author_blacklist) {
            blacklist.add(a.toLowerCase(Locale.ROOT));
        }
        List<String> new_authors = new ArrayList<>();
        for (String author : authors.split(";", -1)) {
            if (!blacklist.contains(Py.strip(author).toLowerCase(Locale.ROOT))) {
                new_authors.add(Py.strip(author));
            }
        }
        if (!new_authors.isEmpty()) {
            return Py.strip(String.join("; ", new_authors), "; ");
        }
        return null;
    }

    /** Parse and extract metadata from JSON-LD data. */
    public static Document extract_meta_json(Element tree, Document metadata) {
        for (Element elem : tree.xpath(".//script[@type=\"application/ld+json\" or @type=\"application/settings+json\"]")) {
            if (!Py.truthy(elem.text)) {
                continue;
            }
            String element_text = normalize_json(JSON_MINIFY.matcher(elem.text).replaceAll(m -> m.group(1) != null
                    ? Matcher.quoteReplacement(m.group(1)) : ""));
            Object schema;
            try {
                // strict=False: trim() handles \n\r\t, but strict JSON rejects the full 0x00-0x1F
                // range; rarer control chars (from encoding issues) would otherwise raise here
                schema = Json.loads(element_text);
            } catch (Json.JSONDecodeError err) {
                metadata = extract_json_parse_error(element_text, metadata);
                continue;
            }
            metadata = extract_json(schema, metadata);
        }
        return metadata;
    }

    /** Search meta tags following the OpenGraph guidelines (https://ogp.me/). */
    public static Map<String, Object> extract_opengraph(Element tree) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("title", "author", "url", "description", "sitename", "image", "pagetype")) {
            result.put(key, null);
        }

        // detect OpenGraph schema
        for (Element elem : tree.xpath(".//head/meta[starts-with(@property, \"og:\")]")) {
            String property_name = elem.get("property");
            String content = elem.get("content");
            // safeguard
            if (Py.truthy(content) && !Py.isspace(content)) {
                if (OG_PROPERTIES.containsKey(property_name)) {
                    result.put(OG_PROPERTIES.get(property_name), content);
                } else if ("og:url".equals(property_name) && Courlan.is_valid_url(content)) {
                    result.put("url", content);
                } else if (OG_AUTHOR.contains(property_name)) {
                    result.put("author", normalize_authors(null, content));
                }
            }
        }
        return result;
    }

    /** Search meta tags for relevant information. */
    public static Document examine_meta(Element tree) {
        // bootstrap from potential OpenGraph tags
        Document metadata = Document.from_dict(extract_opengraph(tree));

        List<String> tags = new ArrayList<>();
        String backup_sitename = null;

        // iterate through meta tags
        for (Element elem : tree.iterfind(".//head/meta[@content]")) {
            // content
            String content_attr = Py.strip(HTML_STRIP_TAGS.matcher(elem.get("content", "")).replaceAll(""));
            if (content_attr.isEmpty()) {
                continue;
            }
            // property
            if (elem.attrib.containsKey("property")) {
                String property_attr = elem.get("property", "").toLowerCase(Locale.ROOT);
                // no opengraph a second time
                if (property_attr.startsWith("og:")) {
                    continue;
                }
                if (property_attr.equals("article:tag")) {
                    tags.add(normalize_tags(content_attr));
                } else if (PROPERTY_AUTHOR.contains(property_attr)) {
                    metadata.author = normalize_authors(metadata.author, content_attr);
                } else if (property_attr.equals("article:publisher")) {
                    metadata.sitename = Py.or(metadata.sitename, content_attr);
                } else if (METANAME_IMAGE.contains(property_attr)) {
                    metadata.image = Py.or(metadata.image, content_attr);
                }
            } else if (elem.attrib.containsKey("name")) {
                // name attribute
                String name_attr = elem.get("name", "").toLowerCase(Locale.ROOT);
                if (METANAME_AUTHOR.contains(name_attr)) {
                    // author
                    metadata.author = normalize_authors(metadata.author, content_attr);
                } else if (METANAME_TITLE.contains(name_attr)) {
                    // title
                    metadata.title = Py.or(metadata.title, content_attr);
                } else if (METANAME_DESCRIPTION.contains(name_attr)) {
                    // description
                    metadata.description = Py.or(metadata.description, content_attr);
                } else if (METANAME_PUBLISHER.contains(name_attr)) {
                    // site name
                    metadata.sitename = Py.or(metadata.sitename, content_attr);
                } else if (METANAME_IMAGE.contains(name_attr)) {
                    // image
                    metadata.image = Py.or(metadata.image, content_attr);
                } else if (TWITTER_ATTRS.contains(name_attr) || name_attr.contains("twitter:app:name")) {
                    // twitter
                    backup_sitename = content_attr;
                } else if (name_attr.equals("twitter:url") && !Py.truthy(metadata.url) && Courlan.is_valid_url(content_attr)) {
                    // url
                    metadata.url = content_attr;
                } else if (METANAME_TAG.contains(name_attr)) {
                    // keywords ('page-topic')
                    tags.add(normalize_tags(content_attr));
                }
            } else if (elem.attrib.containsKey("itemprop")) {
                String itemprop_attr = elem.get("itemprop", "").toLowerCase(Locale.ROOT);
                if (itemprop_attr.equals("author")) {
                    metadata.author = normalize_authors(metadata.author, content_attr);
                } else if (itemprop_attr.equals("description")) {
                    metadata.description = Py.or(metadata.description, content_attr);
                } else if (itemprop_attr.equals("headline")) {
                    metadata.title = Py.or(metadata.title, content_attr);
                }
            } else if (EXTRA_META.stream().noneMatch(elem.attrib::containsKey)) {
                // other types
                LOGGER.debug("unknown attribute: {}", Py.strip(Etree.tostring(elem, "unicode", "xml", false, true)));
            }
        }

        // backups
        metadata.sitename = Py.or(metadata.sitename, backup_sitename);
        // copy
        metadata.tags = tags;
        return metadata;
    }

    /** Extract meta information. */
    public static String extract_metainfo(Element tree, List<XPath> expressions, int len_limit) {
        // try all XPath expressions
        for (XPath expression : expressions) {
            // examine all results
            List<Element> results = expression.evaluate(tree);
            for (Element elem : results) {
                String content = trim(String.join(" ", elem.itertext()));
                if (!content.isEmpty() && 2 < content.length() && content.length() < len_limit) {
                    return content;
                }
            }
            if (results.size() > 1) {
                LOGGER.debug("more than one invalid result: {} {}", expression.path(), results.size());
            }
        }
        return null;
    }

    public static String extract_metainfo(Element tree, List<XPath> expressions) {
        return extract_metainfo(tree, expressions, 200);
    }

    /** Extract text segments out of main <title> element. */
    public static Triple<String, String, String> examine_title_element(Element tree) {
        String title = "";
        Element title_element = tree.find(".//head//title");
        if (title_element != null) {
            title = trim(title_element.text_content());
            Matcher match = Re.match(HTMLTITLE_REGEX, title);
            if (match != null) {
                return Triple.of(title, match.group(1), match.group(2));
            }
        }
        LOGGER.debug("no main title found");
        return Triple.of(title, null, null);
    }

    /** Extract the document title. */
    public static String extract_title(Element tree) {
        // only one h1-element: take it
        List<Element> h1_results = tree.findall(".//h1");
        if (h1_results.size() == 1) {
            String title = block_text(h1_results.get(0));
            if (!title.isEmpty()) {
                return title;
            }
        }
        // extract using x-paths
        String title = Py.or(extract_metainfo(tree, TITLE_XPATHS), "");
        if (!title.isEmpty()) {
            return title;
        }
        // extract using title tag
        Triple<String, String, String> parts = examine_title_element(tree);
        title = parts.first();
        for (String t : java.util.Arrays.asList(parts.second(), parts.third(), parts.first())) {
            if (Py.truthy(t) && !t.contains(".")) {
                return t;
            }
        }
        // take first non-empty h1-title
        for (Element h1_result : h1_results) {
            title = block_text(h1_result);
            if (!title.isEmpty()) {
                return title;
            }
        }
        // take first h2-title
        List<Element> h2_results = tree.xpath(".//h2");
        if (!h2_results.isEmpty()) {
            title = block_text(h2_results.get(0));
        } else {
            LOGGER.debug("no h2 title found");
        }
        return Py.truthy(title) ? title : null;
    }

    /** Extract the document author(s). */
    public static String extract_author(Element tree) {
        Element subtree = prune_unwanted_nodes(tree.copy(), AUTHOR_DISCARD_XPATHS);
        String author = extract_metainfo(subtree, AUTHOR_XPATHS, 120);
        if (Py.truthy(author)) {
            author = normalize_authors(null, author);
        }
        // copyright?
        return author;
    }

    /** Extract the URL from the canonical link. */
    public static String extract_url(Element tree, String default_url) {
        String url = null;
        for (String selector : URL_SELECTORS) {
            Element element = tree.find(selector);
            url = element != null ? element.attrib.get("href") : null;
            if (Py.truthy(url)) {
                break;
            }
        }

        if (Py.truthy(url) && url.startsWith("//")) {
            // protocol-relative URL: only the scheme is missing
            url = "https:" + url;
        } else if (Py.truthy(url) && url.startsWith("/")) {
            // fix relative URLs
            for (Element element : tree.iterfind(".//head//meta[@content]")) {
                String attrtype = Py.or(Py.or(element.get("name"), element.get("property")), "");
                if (attrtype.startsWith("og:") || attrtype.startsWith("twitter:")) {
                    String base_url = safe_base_url(element.attrib.get("content"));
                    if (!base_url.isEmpty()) {
                        // prepend URL
                        url = base_url + url;
                        break;
                    }
                }
            }
        }

        // do not return invalid URLs
        if (Py.truthy(url)) {
            Pair<Boolean, Courlan.SplitResult> validation = Courlan.validate_url(url);
            url = validation.first() && validation.second() != null ? Courlan.normalize_url(url) : null;
        }

        return Py.truthy(url) ? url : default_url;
    }

    public static String extract_url(Element tree) {
        return extract_url(tree, null);
    }

    /** Extract the name of a site from the main title (if it exists). */
    public static String extract_sitename(Element tree) {
        Triple<String, String, String> title = examine_title_element(tree);
        for (String part : java.util.Arrays.asList(title.second(), title.third())) {
            if (Py.truthy(part) && part.contains(".")) {
                return part;
            }
        }
        return null;
    }

    /** Find category and tag information. */
    public static List<String> extract_catstags(String metatype, Element tree) {
        List<String> results = new ArrayList<>();
        Pattern regexpr = Pattern.compile("/" + Py.rstrip(metatype, "y") + "(?:y|ies|s)?/");
        List<XPath> xpath_expression = metatype.equals("category") ? CATEGORIES_XPATHS : TAGS_XPATHS;
        // search using custom expressions
        for (XPath catexpr : xpath_expression) {
            for (Element elem : catexpr.evaluate(tree)) {
                String href = elem.attrib.get("href");
                if (href == null) {
                    throw new IllegalArgumentException("KeyError: 'href'");
                }
                if (regexpr.matcher(href).find()) {
                    results.add(block_text(elem));
                }
            }
            if (!results.isEmpty()) {
                break;
            }
        }
        // category fallback
        if (metatype.equals("category") && results.isEmpty()) {
            for (Element element : tree.xpath(
                    ".//head//meta[@property=\"article:section\" or contains(@name, \"subject\")][@content]")) {
                results.add(element.attrib.get("content"));
            }
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String x : results) {
            if (Py.truthy(x)) {
                unique.add(line_processing(x));
            }
        }
        List<String> output = new ArrayList<>();
        for (String r : unique) {
            if (Py.truthy(r)) {
                output.add(r);
            }
        }
        return output;
    }

    /**
     * Probe a link for identifiable free license cues.
     * Parse the href attribute first and then the link text.
     */
    public static String parse_license_element(Element element, boolean strict) {
        // look for Creative Commons elements
        Matcher match = Re.search(LICENSE_REGEX, element.get("href", ""));
        if (match != null) {
            return "CC " + match.group(1).toUpperCase(Locale.ROOT) + " " + match.group(2);
        }
        String text = block_text(element);
        if (!text.isEmpty()) {
            // check if it could be a CC license
            if (strict) {
                match = Re.search(TEXT_LICENSE_REGEX, text);
                return match != null ? match.group(0) : null;
            }
            return text;
        }
        return null;
    }

    public static String parse_license_element(Element element) {
        return parse_license_element(element, false);
    }

    /** Search the HTML code for license information and parse it. */
    public static String extract_license(Element tree) {
        // look for links labeled as license
        for (Element element : tree.findall(".//a[@rel][@href]")) {
            if (!Py.split(element.get("rel", "").toLowerCase(Locale.ROOT)).contains("license")) {
                continue;
            }
            String result = parse_license_element(element, false);
            if (result != null) {
                return result;
            }
        }
        // probe footer elements for CC links
        for (Element element : tree.xpath(
                ".//footer//a[@href]|.//div[contains(@class, \"footer\") or contains(@id, \"footer\")]//a[@href]")) {
            String result = parse_license_element(element, true);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * Main process for metadata extraction.
     *
     * @param filecontent HTML code as string or parsed tree.
     * @param default_url Previously known URL of the downloaded document.
     * @param date_config Provide extraction parameters to htmldate as dict().
     * @param extensive Use extensive search for date extraction.
     * @param author_blacklist Provide a blacklist of Author Names as set() to filter out authors.
     * @return a Document containing the extracted metadata information.
     */
    public static Document extract_metadata(Object filecontent, String default_url, Map<String, Object> date_config,
            boolean extensive, Set<String> author_blacklist) {
        // init
        if (author_blacklist == null) {
            author_blacklist = new HashSet<>();
        }
        date_config = Py.truthy(date_config) ? new LinkedHashMap<>(date_config) : set_date_params(extensive);

        // load contents
        Element tree = load_html(filecontent);
        if (tree == null) {
            return new Document();
        }

        // initialize dict and try to strip meta tags
        Document metadata = examine_meta(tree);

        // to check: remove it and replace with author_blacklist in test case
        if (Py.truthy(metadata.author) && !metadata.author.contains(" ")) {
            metadata.author = null;
        }

        // fix: try json-ld metadata and override
        try {
            metadata = extract_meta_json(tree, metadata);
        } catch (RuntimeException err) { // bugs in json_metadata.py
            LOGGER.warn("error in JSON metadata extraction: {}", err.toString());
        }

        // title
        if (!Py.truthy(metadata.title)) {
            metadata.title = extract_title(tree);
        }

        // check author in blacklist
        if (Py.truthy(metadata.author) && !author_blacklist.isEmpty()) {
            metadata.author = check_authors(metadata.author, author_blacklist);
        }
        // author
        if (!Py.truthy(metadata.author)) {
            metadata.author = extract_author(tree);
        }
        // recheck author in blacklist
        if (Py.truthy(metadata.author) && !author_blacklist.isEmpty()) {
            metadata.author = check_authors(metadata.author, author_blacklist);
        }

        // url
        if (!Py.truthy(metadata.url)) {
            metadata.url = extract_url(tree, default_url);
        }

        // hostname
        if (Py.truthy(metadata.url)) {
            metadata.hostname = Courlan.extract_domain(metadata.url, true);
        }

        // extract date with external module htmldate
        date_config.put("url", metadata.url);
        metadata.date = Htmldate.find_date(tree, date_config);

        // sitename
        if (!Py.truthy(metadata.sitename)) {
            metadata.sitename = extract_sitename(tree);
        }
        if (Py.truthy(metadata.sitename)) {
            // scrap Twitter ID
            metadata.sitename = Py.lstrip(metadata.sitename, "@");
            // capitalize
            if (Py.truthy(metadata.sitename) && !metadata.sitename.contains(".")
                    && !Character.isUpperCase(metadata.sitename.codePointAt(0))) {
                metadata.sitename = Py.title(metadata.sitename);
            }
        } else if (Py.truthy(metadata.url)) {
            // use URL
            Matcher mymatch = Re.match(META_URL, metadata.url);
            if (mymatch != null) {
                metadata.sitename = mymatch.group(1);
            }
        }

        // categories
        if (!Py.truthy(metadata.categories)) {
            metadata.categories = extract_catstags("category", tree);
        }

        // tags
        if (!Py.truthy(metadata.tags)) {
            metadata.tags = extract_catstags("tag", tree);
        }

        // license
        metadata.license = extract_license(tree);

        // safety checks
        metadata.filedate = (String) date_config.get("max_date");
        metadata.clean_and_trim();

        return metadata;
    }

    public static Document extract_metadata(Object filecontent) {
        return extract_metadata(filecontent, null, null, true, null);
    }

    public static Document extract_metadata(Object filecontent, String default_url) {
        return extract_metadata(filecontent, default_url, null, true, null);
    }
}
