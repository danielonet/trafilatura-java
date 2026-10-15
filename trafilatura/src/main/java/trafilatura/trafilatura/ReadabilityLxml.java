// Java port of trafilatura/readability_lxml.py
package trafilatura.trafilatura;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import trafilatura.compat.Py;
import trafilatura.lxml.Element;
import trafilatura.lxml.Etree;

import static trafilatura.trafilatura.Utils.load_html;
import static trafilatura.trafilatura.Utils.trim;

/**
 * Minimalistic fork of readability-lxml code.
 *
 * This is a python port of a ruby port of arc90's readability project
 * http://lab.arc90.com/experiments/readability/
 *
 * Given a html document, it pulls out the main body text and cleans it up.
 *
 * Ruby port by starrhorne and iterationlabs, Python port by gfxmonk.
 * For list of contributors see https://github.com/timbertson/python-readability
 * and https://github.com/buriy/python-readability
 *
 * License of forked code: Apache-2.0.
 */
public final class ReadabilityLxml {
    private ReadabilityLxml() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadabilityLxml.class);

    public static final Pattern DOT_SPACE = Pattern.compile("\\.( |$)");

    public static final Map<String, Integer> TAG_SCORES = new HashMap<>();

    static {
        for (String tag : List.of("div", "article")) {
            TAG_SCORES.put(tag, 5);
        }
        for (String tag : List.of("pre", "td", "blockquote")) {
            TAG_SCORES.put(tag, 3);
        }
        for (String tag : List.of("address", "ol", "ul", "dl", "dd", "dt", "li", "form", "aside")) {
            TAG_SCORES.put(tag, -3);
        }
        for (String tag : List.of("h1", "h2", "h3", "h4", "h5", "h6", "th", "header", "footer", "nav")) {
            TAG_SCORES.put(tag, -5);
        }
    }

    public static final String[] TEXT_CLEAN_ELEMS = {"p", "img", "li", "a", "embed", "input"};

    // matched against lowercased attributes
    public static final Pattern UNLIKELY_CANDIDATES_RE = Pattern.compile(
            "combx|comment|community|disqus|extra|foot|header|menu|remark|rss|shoutbox|sidebar|sponsor|ad-break|agegate|pagination|pager|popup|tweet|twitter");
    public static final Pattern OK_MAYBE_ITS_A_CANDIDATE_RE = Pattern.compile("and|article|body|column|content|main|shadow");
    public static final Pattern POSITIVE_RE = Pattern.compile(
            "article|body|content|entry|hentry|main|page|pagination|post|text|blog|story", Pattern.CASE_INSENSITIVE);
    public static final Pattern NEGATIVE_RE = Pattern.compile(
            "button|combx|comment|com-|contact|figure|foot|footer|footnote|form|input|masthead|media|meta|outbrain|promo|related|scroll|shoutbox|sidebar|sponsor|shopping|tags|tool|widget",
            Pattern.CASE_INSENSITIVE);
    public static final Pattern VIDEO_RE = Pattern.compile("https?:\\/\\/(?:www\\.)?(?:youtube|vimeo)\\.com", Pattern.CASE_INSENSITIVE);

    // block tag prefixes that keep a div from becoming a <p>, anchors are inline
    public static final List<String> DIV_TO_P_PREFIXES = List.of(
            "address", "article", "aside", "audio", "blockquote", "dl", "div", "img", "ol", "p", "table", "ul");

    public static final Set<String> FRAME_TAGS = Set.of("body", "html");
    public static final Set<String> LIST_TAGS = Set.of("ol", "ul");

    /** Return the length of the element with all its contents. */
    public static int text_length(Element elem) {
        return trim(elem.text_content()).length();
    }

    /** Defines a class to score candidate elements. */
    public static final class Candidate {
        public double score;
        public final Element elem;

        public Candidate(double score, Element elem) {
            this.score = score;
            this.elem = elem;
        }
    }

    /** Class to build a etree document out of html. */
    public static class Document {
        public final Element doc;
        public final int min_text_length;

        /**
         * Generate the document.
         *
         * @param doc parsed HTML tree.
         * @param min_text_length Set to a higher value for more precise detection of longer texts.
         *
         * The Document class is not re-enterable.
         * It is designed to create a new Document() for each HTML file to process it.
         */
        public Document(Element doc, int min_text_length) {
            this.doc = doc;
            this.min_text_length = min_text_length;
        }

        public Document(Element doc) {
            this(doc, 25);
        }

        /**
         * Given a HTML file, extracts the text of the article.
         *
         * Warning: It mutates internal DOM representation of the HTML document,
         * so it is better to call other API methods before this one.
         */
        public String summary() {
            for (Element elem : this.doc.iter("script", "style", "fencedframe").list()) {
                elem.drop_tree();
            }

            // no retry: it would rerun on the same mutated tree
            this.remove_unlikely_candidates();
            this.transform_misused_divs_into_paragraphs();
            Map<Element, Candidate> candidates = this.score_paragraphs();
            Candidate best_candidate = this.select_best_candidate(candidates);
            Element article;
            if (best_candidate != null) {
                article = this.get_article(candidates, best_candidate);
            } else {
                LOGGER.debug("No candidate found, returning raw html");
                Element body = this.doc.find("body");
                article = body != null ? body : this.doc;
            }
            return this.sanitize(article, candidates);
        }

        public Element get_article(Map<Element, Candidate> candidates, Candidate best_candidate) {
            // Now that we have the top candidate, look through its siblings for
            // content that might also be related.
            // Things like preambles, content split by ads that we removed, etc.
            double sibling_score_threshold = Math.max(10, best_candidate.score * 0.2);
            // create a new html document with a div (still attached to the parsed fragment's body)
            Element output = Etree.fragment_fromstring("<div/>");
            Element parent = best_candidate.elem.getparent();
            List<Element> siblings = parent != null ? parent.getchildren() : List.of(best_candidate.elem);
            for (Element sibling : siblings) {
                boolean append = false;
                // conditions
                if (sibling == best_candidate.elem
                        || (candidates.containsKey(sibling) && candidates.get(sibling).score >= sibling_score_threshold)) {
                    append = true;
                } else if (sibling.tag.equals("p")) {
                    double link_density = this.get_link_density(sibling);
                    String node_content = Py.or(sibling.text, "");
                    int node_length = node_content.length();

                    if ((node_length > 80 && link_density < 0.25)
                            || (node_length <= 80 && link_density == 0 && DOT_SPACE.matcher(node_content).find())) {
                        append = true;
                    }
                }
                // append to the output div
                if (append) {
                    output.append(sibling);
                }
            }
            return output;
        }

        /** Highest-scoring candidate, the first one on ties ({@code max(..., key=score)}). */
        public Candidate select_best_candidate(Map<Element, Candidate> candidates) {
            Candidate best = null;
            for (Candidate candidate : candidates.values()) {
                if (best == null || candidate.score > best.score) {
                    best = candidate;
                }
            }
            return best;
        }

        public double get_link_density(Element elem) {
            int total_length = text_length(elem);
            if (total_length == 0) {
                total_length = 1;
            }
            int link_length = 0;
            for (Element link : elem.findall(".//a")) {
                link_length += text_length(link);
            }
            return (double) link_length / total_length;
        }

        public Map<Element, Candidate> score_paragraphs() {
            Map<Element, Candidate> candidates = new LinkedHashMap<>();

            for (Element elem : this.doc.iter("p", "pre", "td")) {
                Element parent_node = elem.getparent();
                if (parent_node == null) {
                    continue;
                }
                Element grand_parent_node = parent_node.getparent();

                String elem_text = trim(elem.text_content());
                int elem_text_len = elem_text.length();

                // discard too short paragraphs
                if (elem_text_len < this.min_text_length) {
                    continue;
                }

                for (Element node : java.util.Arrays.asList(parent_node, grand_parent_node)) {
                    if (node != null && !candidates.containsKey(node)) {
                        candidates.put(node, this.score_node(node));
                    }
                }

                double score = 1 + elem_text.split(",", -1).length + Math.min(elem_text_len / 100.0, 3);

                candidates.get(parent_node).score += score;
                if (grand_parent_node != null) {
                    candidates.get(grand_parent_node).score += score / 2;
                }
            }

            // Scale the final candidates score based on link density. Good content
            // should have a relatively small link density (5% or less) and be
            // mostly unaffected by this operation.
            for (Map.Entry<Element, Candidate> entry : candidates.entrySet()) {
                entry.getValue().score *= 1 - this.get_link_density(entry.getKey());
            }

            return candidates;
        }

        public double class_weight(Element elem) {
            double weight = 0;
            for (String attribute : java.util.Arrays.asList(elem.get("class"), elem.get("id"))) {
                if (!Py.truthy(attribute)) {
                    continue;
                }
                if (NEGATIVE_RE.matcher(attribute).find()) {
                    weight -= 25;
                }
                if (POSITIVE_RE.matcher(attribute).find()) {
                    weight += 25;
                }
            }
            return weight;
        }

        public Candidate score_node(Element elem) {
            return new Candidate(this.class_weight(elem) + TAG_SCORES.getOrDefault(elem.tag.toLowerCase(), 0), elem);
        }

        public void remove_unlikely_candidates() {
            Map<String, Boolean> verdicts = new HashMap<>();
            for (Element elem : this.doc.findall(".//*")) {
                String attrs = Xml.joinNonEmpty(" ", elem.get("class"), elem.get("id"));
                if (attrs.length() < 2 || FRAME_TAGS.contains(elem.tag)) {
                    continue;
                }
                Boolean verdict = verdicts.get(attrs);
                if (verdict == null) {
                    String low = attrs.toLowerCase();
                    verdict = UNLIKELY_CANDIDATES_RE.matcher(low).find() && !OK_MAYBE_ITS_A_CANDIDATE_RE.matcher(low).find();
                    verdicts.put(attrs, verdict);
                }
                if (verdict) {
                    elem.drop_tree();
                }
            }
        }

        public void transform_misused_divs_into_paragraphs() {
            for (Element elem : this.doc.findall(".//div")) {
                // a div without block descendants becomes a <p>, a link wrapper only if it has loose text
                boolean hasBlock = false;
                for (Element e : elem.iterdescendants("*")) {
                    if (DIV_TO_P_PREFIXES.stream().anyMatch(e.tag::startsWith)) {
                        hasBlock = true;
                        break;
                    }
                }
                if (!hasBlock && (elem.find(".//a") == null || !elem.xpath_str("text()[normalize-space()]").isEmpty())) {
                    elem.tag = "p";
                }
            }

            for (Element elem : this.doc.findall(".//div")) {
                if (Py.truthy(elem.text) && !Py.strip(elem.text).isEmpty()) {
                    Element p_elem = Etree.fragment_fromstring("<p/>");
                    p_elem.text = elem.text;
                    elem.text = null;
                    elem.insert(0, p_elem);
                }

                List<Element> children = elem.getchildren();
                for (int pos = children.size() - 1; pos >= 0; pos--) {
                    Element child = children.get(pos);
                    if (Py.truthy(child.tail) && !Py.strip(child.tail).isEmpty()) {
                        Element p_elem = Etree.fragment_fromstring("<p/>");
                        p_elem.text = child.tail;
                        child.tail = null;
                        elem.insert(pos + 1, p_elem);
                    }
                    if (child.tag.equals("br")) {
                        child.drop_tree();
                    }
                }
            }
        }

        private static int count(Map<String, Integer> counts, String key) {
            return counts.getOrDefault(key, 0);
        }

        public String sanitize(Element node, Map<Element, Candidate> candidates) {
            for (Element header : node.iter("h1", "h2", "h3", "h4", "h5", "h6").list()) {
                if (this.class_weight(header) < 0 || this.get_link_density(header) > 0.33) {
                    header.drop_tree();
                }
            }

            for (Element elem : node.iter("form", "textarea").list()) {
                elem.drop_tree();
            }

            for (Element elem : node.iter("iframe").list()) {
                if (elem.attrib.containsKey("src") && VIDEO_RE.matcher(elem.attrib.get("src")).find()) {
                    elem.text = "VIDEO"; // ADD content to iframe text node to force <iframe></iframe> proper output
                } else {
                    elem.drop_tree();
                }
            }

            // Conditionally clean <table>s, <ul>s, and <div>s
            for (Element elem : node.iter("table", "ul", "div", "aside", "header", "footer", "section").list().reversed()) {
                double weight = this.class_weight(elem);
                double score = candidates.containsKey(elem) ? candidates.get(elem).score : 0;
                if (weight + score < 0) {
                    LOGGER.debug("Removed {} with score {} and weight {}", elem.tag, String.format("%6.3f", score), weight);
                    elem.drop_tree();
                } else if (Py.count(elem.text_content(), ",") < 10) {
                    boolean to_remove = true;
                    Map<String, Integer> counts = new HashMap<>();
                    for (Element e : elem.iterdescendants(TEXT_CLEAN_ELEMS)) {
                        counts.merge(e.tag, 1, Integer::sum);
                    }
                    counts.put("li", count(counts, "li") - 100);
                    counts.put("input", count(counts, "input") - elem.findall(".//input[@type=\"hidden\"]").size());

                    // Count the text length excluding any surrounding whitespace
                    int content_length = text_length(elem);
                    double link_density = this.get_link_density(elem);
                    String reason = null;
                    if (count(counts, "p") != 0 && count(counts, "img") > 1 + count(counts, "p") * 1.3) {
                        reason = "too many images (" + count(counts, "img") + ")";
                    } else if (count(counts, "li") > count(counts, "p") && !LIST_TAGS.contains(elem.tag)) {
                        reason = "more <li>s than <p>s";
                    } else if (count(counts, "input") > (count(counts, "p") / 3.0)) {
                        reason = "less than 3x <p>s than <input>s";
                    } else if (content_length < this.min_text_length && count(counts, "img") == 0) {
                        reason = "too short content length " + content_length + " without a single image";
                    } else if (content_length < this.min_text_length && count(counts, "img") > 2) {
                        reason = "too short content length " + content_length + " and too many images";
                    } else if (link_density > (weight >= 25 ? 0.5 : 0.2)) {
                        reason = "too many links " + String.format("%.3f", link_density) + " for its weight " + weight;
                    } else if ((count(counts, "embed") == 1 && content_length < 75) || count(counts, "embed") > 1) {
                        reason = "<embed>s with too short content length, or too many <embed>s";
                    } else if (content_length == 0) {
                        reason = "no content";
                        // kept between long neighbours: the nearest non-empty sibling on each side
                        int nearest = 0;
                        for (List<Element> sibs : List.of(elem.itersiblings(), elem.itersiblings(true))) {
                            for (Element sib : sibs) {
                                int n = text_length(sib);
                                if (n != 0) {
                                    nearest += n;
                                    break;
                                }
                            }
                        }
                        to_remove = nearest <= 1000;
                    } else {
                        to_remove = false;
                    }

                    if (to_remove) {
                        elem.drop_tree();
                        LOGGER.debug("Removed {} {} with weight {} cause it has {}.",
                                String.format("%6.3f", score), elem.tag, weight, Py.or(reason, ""));
                    }
                }
            }

            return Etree.tostring(node, "unicode", "xml", false, true);
        }
    }

    // Port of isProbablyReaderable from mozilla/readability.js to Python.
    // https://github.com/mozilla/readability
    // License of forked code: Apache-2.0.

    public static final Pattern UNLIKELY_CANDIDATES = Pattern.compile(
            "-ad-|ai2html|banner|breadcrumbs|combx|comment|community|cover-wrap|disqus|extra|footer|gdpr|header|legends|menu|related|remark|replies|rss|shoutbox|sidebar|skyscraper|social|sponsor|supplemental|ad-break|agegate|pagination|pager|popup|yom-remote");

    public static final Pattern DISPLAY_NONE = Pattern.compile("display:\\s*none", Pattern.CASE_INSENSITIVE);

    /** Checks if the node is visible by considering style, attributes, and class. */
    public static boolean is_node_visible(Element node) {
        if (node.attrib.containsKey("style") && DISPLAY_NONE.matcher(node.get("style", "")).find()) {
            return false;
        }
        if (node.attrib.containsKey("hidden")) {
            return false;
        }
        return !("true".equals(node.get("aria-hidden")) && !node.get("class", "").contains("fallback-image"));
    }

    /** Decides whether or not the document is reader-able without parsing the whole thing. */
    @SuppressWarnings("unchecked")
    public static boolean is_probably_readerable(Object html, Map<String, Object> options) {
        if (options == null) {
            options = Map.of();
        }
        Element doc = load_html(html);
        if (doc == null) {
            return false;
        }

        int min_content_length = ((Number) options.getOrDefault("min_content_length", 140)).intValue();
        double min_score = ((Number) options.getOrDefault("min_score", 20)).doubleValue();
        Predicate<Element> visibility_checker = (Predicate<Element>) options.getOrDefault(
                "visibility_checker", (Predicate<Element>) ReadabilityLxml::is_node_visible);

        Set<Element> nodes = new HashSet<>(doc.xpath(".//p | .//pre | .//article"));
        for (Element node : doc.xpath(".//div/br")) {
            nodes.add(node.getparent());
        }

        double score = 0.0;
        for (Element node : nodes) {
            if (!visibility_checker.test(node)) {
                continue;
            }

            String class_and_id = (node.get("class", "") + " " + node.get("id", "")).toLowerCase();
            if (UNLIKELY_CANDIDATES.matcher(class_and_id).find() && !OK_MAYBE_ITS_A_CANDIDATE_RE.matcher(class_and_id).find()) {
                continue;
            }

            if (!node.xpath("./parent::li/p").isEmpty()) {
                continue;
            }

            int text_content_length = Py.strip(node.text_content()).length();
            if (text_content_length < min_content_length) {
                continue;
            }

            score += Math.sqrt(text_content_length - min_content_length);
            if (score > min_score) {
                return true;
            }
        }

        return false;
    }

    public static boolean is_probably_readerable(Object html) {
        return is_probably_readerable(html, null);
    }
}
