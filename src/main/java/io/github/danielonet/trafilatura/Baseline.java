// Java port of trafilatura/baseline.py
package io.github.danielonet.trafilatura;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.github.danielonet.trafilatura.compat.Json;
import io.github.danielonet.trafilatura.compat.Pair;
import io.github.danielonet.trafilatura.compat.Py;
import io.github.danielonet.trafilatura.compat.Triple;
import io.github.danielonet.trafilatura.lxml.Element;
import io.github.danielonet.trafilatura.lxml.Etree;

import static io.github.danielonet.trafilatura.Settings.BASIC_CLEAN_XPATH;
import static io.github.danielonet.trafilatura.Settings.MIN_DUPLICATE_LENGTH;
import static io.github.danielonet.trafilatura.Utils.as_list;
import static io.github.danielonet.trafilatura.Utils.load_html;
import static io.github.danielonet.trafilatura.Utils.remove_control_characters;
import static io.github.danielonet.trafilatura.Utils.trim;
import static io.github.danielonet.trafilatura.Xml.delete_element;

/** Module regrouping baseline and basic extraction functions. */
public final class Baseline {
    private Baseline() {
    }

    // detection (not removal, unlike HTML_STRIP_TAGS): must not fire on comparison-operator prose
    // ("i<b and c>d", b a tag name), only real tags -- closing, bare (<p>), self-closing (<br/>), or
    // attribute-bearing (contains '='). The old `[^>]*` swallowed prose up to the next '>'.
    public static final String _HTML_TAG_NAMES =
            "a|abbr|address|article|aside|b|blockquote|body|br|caption|cite|code|dd|del|div|dl|dt|"
                    + "em|figcaption|figure|footer|h[1-6]|head|header|hr|html|i|img|ins|kbd|li|main|mark|nav|"
                    + "ol|p|pre|q|quote|s|section|small|span|strong|sub|summary|sup|table|tbody|td|tfoot|th|"
                    + "thead|time|title|tr|u|ul";
    public static final Pattern _HTML_MARKUP = Pattern.compile(
            "</(" + _HTML_TAG_NAMES + ")>|<(" + _HTML_TAG_NAMES + ")(\\s[^<>]*=[^<>]*)?/?>", Pattern.CASE_INSENSITIVE);

    /** Module-level copy of Settings.DEDUPE_SCAN_CAP (tests patch it, like the Python module attribute). */
    public static int DEDUPE_SCAN_CAP = Settings.DEDUPE_SCAN_CAP;

    /** Remove a few section types from the document. */
    public static Element basic_cleaning(Element tree) {
        for (Element elem : BASIC_CLEAN_XPATH.evaluate(tree)) {
            delete_element(elem);
        }
        return tree;
    }

    // schema.org text properties usable as page content
    public static final List<String> _JSON_TEXT_KEYS = List.of("articleBody", "reviewBody");
    // types whose description carries the page content (teaser tier: summaries, last resort)
    public static final List<String> _DESCRIPTION_TYPES = List.of("Product", "VideoObject");
    // cheap script pre-filter, derived from what _walk_json consumes: bare strings match
    // property names, quoted ones match @type values. "step" is deliberately not a hook
    // (too generic a substring); a schema.org HowTo carrying it is caught via its @type
    public static final List<String> _JSON_HOOKS = List.of(
            "articleBody", "reviewBody", "recipeInstructions", "acceptedAnswer",
            "\"Product\"", "\"VideoObject\"", "\"HowTo\"");
    public static final Pattern _JSON_HOOKS_RE = Pattern.compile(
            _JSON_HOOKS.stream().map(Pattern::quote).collect(Collectors.joining("|")));
    // a strategy must accumulate more than this much text to be accepted (and a single
    // <article> must carry more than this to count as content)
    public static final int _MIN_CONTENT_LENGTH = 100;

    /**
     * Collect schema.org text content from parsed JSON-LD (list-wrapped and @graph-nested
     * nodes included). Teasers (Product/VideoObject descriptions) are short summaries,
     * only usable when no full-text property exists.
     */
    @SuppressWarnings("unchecked")
    public static void _walk_json(Object node, List<String> bodies, List<String> teasers) {
        for (Object entry : as_list(node)) {
            if (!(entry instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) entry;
            for (String key : _JSON_TEXT_KEYS) {
                if (item.get(key) instanceof String s && !s.isEmpty()) {
                    bodies.add(s);
                }
            }
            // recipe/how-to instructions: a string, strings, or step objects carrying "text",
            // possibly one itemListElement level down (HowToDirection)
            for (String key : List.of("recipeInstructions", "step")) {
                for (Object step : as_list(item.get(key))) {
                    if (step instanceof String s) {
                        bodies.add(s);
                    } else if (step instanceof Map<?, ?> stepMap) {
                        List<Object> subs = new ArrayList<>();
                        subs.add(stepMap);
                        subs.addAll(as_list(stepMap.get("itemListElement")));
                        for (Object sub : subs) {
                            if (sub instanceof Map<?, ?> subMap && subMap.get("text") instanceof String text) {
                                bodies.add(text);
                            }
                        }
                    }
                }
            }
            // FAQ answers
            if (item.get("acceptedAnswer") instanceof Map<?, ?> answer && answer.get("text") instanceof String text) {
                bodies.add(text);
            }
            String type = jsonStr(item.getOrDefault("@type", ""));
            if (_DESCRIPTION_TYPES.stream().anyMatch(type::contains) && item.get("description") instanceof String desc) {
                teasers.add(desc);
            }
            for (String container : List.of("@graph", "mainEntity")) {
                _walk_json(item.get(container), bodies, teasers);
            }
        }
    }

    /** {@code str(value)} for JSON values, close enough for substring tests. */
    public static String jsonStr(Object value) {
        return value == null ? "None" : String.valueOf(value);
    }

    /** Extract post HTML from the JSON Discourse forums preload into an attribute (page body is empty). */
    @SuppressWarnings("unchecked")
    public static List<String> _discourse_texts(Element tree) {
        Element node = tree.find(".//div[@id=\"data-preloaded\"]");
        if (node == null) {
            return List.of();
        }
        Object preloaded;
        try {
            preloaded = Json.loads(Py.or(node.get("data-preloaded"), ""));
        } catch (RuntimeException err) {
            return List.of();
        }
        if (!(preloaded instanceof Map<?, ?> map)) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!String.valueOf(entry.getKey()).startsWith("topic_")) {
                continue;
            }
            List<Object> posts;
            try {
                Map<String, Object> topic = (Map<String, Object>) Json.loads((String) entry.getValue());
                posts = (List<Object>) ((Map<String, Object>) topic.get("post_stream")).get("posts");
                if (posts == null) {
                    continue;
                }
            } catch (RuntimeException err) {
                continue;
            }
            for (Object post : posts) {
                if (post instanceof Map<?, ?> postMap && postMap.get("cooked") instanceof String cooked) {
                    texts.add(cooked);
                }
            }
        }
        return texts;
    }

    /** Derive clean text from an embedded-JSON value which may carry (escaped) HTML markup. */
    public static String _render_text(String raw) {
        // some sites HTML-escape the content ("&lt;p&gt;…"); unescape so markup is parsed, not leaked.
        // remove control chars after unescape (&#1; -> one char): strict=False JSON lets them through
        raw = remove_control_characters(Py.unescape(raw));
        if (_HTML_MARKUP.matcher(raw).find()) {
            try {
                return block_text(Etree.fragment_fromstring(raw, "div"));
            } catch (RuntimeException err) {
                // pass
            }
        }
        return trim(raw);
    }

    /** Wrap one paragraph per text in a fresh body element, optionally dropping repeated content. */
    public static Pair<Element, String> _build_body(Iterable<String> texts, boolean dedupe) {
        Element postbody = Etree.Element("body");
        StringBuilder temp_text = new StringBuilder();
        for (String text : texts) {
            // strip control chars lxml rejects in .text (element inputs skip load_html's cleaning)
            text = remove_control_characters(text);
            // keep short paragraphs (<= MIN_DUPLICATE_LENGTH) even if they recur -- only long substring
            // repeats (e.g. a <p> nested in its <blockquote>) are artifacts. Scan capped at
            // DEDUPE_SCAN_CAP; newline-joined (trimmed text has none) so no match spans two paragraphs
            if (!text.isEmpty() && (!dedupe || text.length() <= MIN_DUPLICATE_LENGTH
                    || temp_text.length() > DEDUPE_SCAN_CAP || temp_text.indexOf(text) < 0)) {
                Etree.SubElement(postbody, "p").text = text;
                if (temp_text.length() > 0) {
                    temp_text.append("\n");
                }
                temp_text.append(text);
            }
        }
        return Pair.of(postbody, temp_text.toString());
    }

    /** Build a body from the texts and accept it if it carries enough content. */
    public static Triple<Element, String, Integer> _attempt(Iterable<String> texts, boolean dedupe) {
        Pair<Element, String> built = _build_body(texts, dedupe);
        String temp_text = built.second();
        return temp_text.length() > _MIN_CONTENT_LENGTH ? Triple.of(built.first(), temp_text, temp_text.length()) : null;
    }

    /** Gather raw text content embedded as JSON: (full-text bodies, teaser descriptions). */
    public static Pair<List<String>, List<String>> _collect_json_content(Element tree) {
        List<String> bodies = new ArrayList<>();
        List<String> teasers = new ArrayList<>();
        for (Element elem : tree.iterfind(".//script[@type=\"application/ld+json\"]")) {
            if (Py.truthy(elem.text) && _JSON_HOOKS_RE.matcher(elem.text).find()) {
                try {
                    // strict=False: real pages carry raw newlines/tabs inside JSON strings
                    _walk_json(Json.loads(elem.text), bodies, teasers);
                } catch (RuntimeException err) { // JSONDecodeError
                    continue;
                }
            }
        }
        // Discourse forums render posts client-side but embed them as JSON in an attribute
        bodies.addAll(_discourse_texts(tree));
        return Pair.of(bodies, teasers);
    }

    private static List<String> renderAll(List<String> raws) {
        List<String> rendered = new ArrayList<>();
        for (String raw : raws) {
            rendered.add(_render_text(raw));
        }
        return rendered;
    }

    /**
     * Use baseline extraction function targeting content in embedded JSON or text elements.
     *
     * Tries a series of sources and takes the first that yields enough text:
     * JSON content embedded in scripts or attributes (schema.org properties,
     * Discourse forum posts), article tags, text paragraphs, schema.org teaser
     * descriptions, and finally the raw text of the whole page body.
     *
     * @param filecontent HTML code as binary string or string (or element;
     *        elements are copied, the input is left untouched).
     * @return a <body> element containing the extracted paragraphs,
     *         the main text as string, and its length as integer.
     */
    public static Triple<Element, String, Integer> baseline(Object filecontent) {
        Element tree = load_html(filecontent);
        if (tree == null) {
            return Triple.of(Etree.Element("body"), "", 0);
        }
        if (filecontent instanceof Element) {
            tree = tree.copy(); // basic_cleaning below mutates the tree
        }

        // scrape from embedded JSON: full-text properties first, teaser descriptions kept for
        // later. dedupe: pages often embed the same JSON-LD block twice (theme + SEO plugin)
        Pair<List<String>, List<String>> json = _collect_json_content(tree);
        Triple<Element, String, Integer> result = _attempt(renderAll(json.first()), true);
        if (result != null) {
            return result;
        }

        tree = basic_cleaning(tree);

        // article tags: a dominant one relegates much smaller siblings to noise (related teasers),
        // similar-sized ones are all content (forum posts). Nested articles excluded (counted in ancestor)
        List<String> article_texts = new ArrayList<>();
        for (Element elem : tree.xpath(".//article[not(ancestor::article)]")) {
            String text = block_text(elem);
            if (text.length() > _MIN_CONTENT_LENGTH) {
                article_texts.add(text);
            }
        }
        if (!article_texts.isEmpty()) {
            // never None: the longest article passes both its own length gate and the cutoff
            double cutoff = article_texts.stream().mapToInt(String::length).max().getAsInt() / 5.0;
            result = _attempt(article_texts.stream().filter(text -> text.length() >= cutoff).toList(), false);
            if (result != null) {
                return result;
            }
        }

        // scrape from text paragraphs, dropping repeats: a nested element (e.g. <p> in
        // <blockquote>) duplicates part of its container's text, collected first in document order
        // skip any element whose ancestor is already in the scraped set (handles <p><code>, <blockquote><p>, etc.)
        Set<String> scraped_tags = Set.of("blockquote", "code", "p", "pre", "q", "quote");
        List<String> paragraphs = new ArrayList<>();
        for (Element element : tree.iter("blockquote", "code", "p", "pre", "q", "quote")) {
            if (element.firstancestor(scraped_tags) == null) {
                paragraphs.add(block_text(element));
            }
        }
        result = _attempt(paragraphs, true);
        if (result != null) {
            return result;
        }

        // teaser tier: Product/VideoObject descriptions, once every full-text source came up empty.
        // A short summary must not shadow a longer body dump -- it wins only if the dump is shorter
        Triple<Element, String, Integer> teaser = _attempt(renderAll(json.second()), true);

        // default strategy: clean the tree and take everything
        Element postbody = Etree.Element("body");
        Element body_elem = tree.find(".//body");
        if (body_elem != null) {
            Element p_elem = Etree.SubElement(postbody, "p");
            List<String> lines = new ArrayList<>();
            for (String e : body_elem.itertext()) {
                String text = trim(e);
                if (!text.isEmpty()) {
                    lines.add(text);
                }
            }
            // strip control chars lxml rejects in .text (element inputs skip load_html's cleaning)
            p_elem.text = remove_control_characters(String.join("\n", lines));
            if (teaser == null || p_elem.text.length() >= teaser.third()) {
                return Triple.of(postbody, p_elem.text, p_elem.text.length());
            }
        }

        return teaser != null ? teaser : Triple.of(postbody, "", 0);
    }

    // block-level elements: their boundaries separate text runs (minified pages carry no whitespace there)
    public static final String[] _BLOCK_ELEMS = {
        "address", "article", "aside", "blockquote", "br", "dd", "div", "dl", "dt", "figcaption", "figure",
        "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "li", "main", "nav", "ol", "p",
        "pre", "section", "summary", "table", "td", "th", "tr", "ul",
    };

    /**
     * Run basic html2txt on a document.
     *
     * @param content HTML document as string or element (elements are copied, the input is left untouched).
     * @param clean remove potentially undesirable elements.
     * @return the extracted text in the form of a string or an empty string.
     */
    public static String html2txt(Object content, boolean clean) {
        Element tree = load_html(content);
        if (tree == null) {
            return "";
        }
        if (content instanceof Element) {
            tree = tree.copy(); // the steps below modify the tree
        }
        Element body = tree.find(".//body");
        if (body == null) {
            // a caller-supplied element without <body> is itself the content; a parsed
            // document without one (e.g. a feed) is not HTML text
            if (!(content instanceof Element)) {
                return "";
            }
            body = tree;
        }
        return _spaced_text(clean ? basic_cleaning(body) : body);
    }

    public static String html2txt(Object content) {
        return html2txt(content, true);
    }

    /** text_content() alternative that preserves word boundaries at block tags (#896). */
    public static String block_text(Element element) {
        return _spaced_text(element.copy());
    }

    /** Collect the text, spacing block boundaries in place so adjacent runs don't stick (minified pages). */
    public static String _spaced_text(Element element) {
        // remove_control_characters guards the .text write against chars lxml rejects
        for (Element elem : element.iter(_BLOCK_ELEMS)) {
            elem.text = Py.truthy(elem.text) ? " " + remove_control_characters(elem.text) : " ";
            elem.tail = Py.truthy(elem.tail) ? " " + remove_control_characters(elem.tail) : " ";
        }
        return String.join(" ", Py.split(element.text_content()));
    }
}
