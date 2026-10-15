// Java port of trafilatura/external.py
package trafilatura.trafilatura;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import trafilatura.compat.Pair;
import trafilatura.compat.Py;
import trafilatura.justext.Justext;
import trafilatura.justext.Justext.Paragraph;
import trafilatura.justext.Justext.ParagraphMaker;
import trafilatura.lxml.Element;
import trafilatura.lxml.Etree;
import trafilatura.trafilatura.Settings.Extractor;

import static trafilatura.trafilatura.Baseline.basic_cleaning;
import static trafilatura.trafilatura.Htmlprocessing.convert_tags;
import static trafilatura.trafilatura.Htmlprocessing.prune_unwanted_nodes;
import static trafilatura.trafilatura.Htmlprocessing.tree_cleaning;
import static trafilatura.trafilatura.MainExtractor.handle_image;
import static trafilatura.trafilatura.Settings.JUSTEXT_LANGUAGES;
import static trafilatura.trafilatura.Utils.fromstring_bytes;
import static trafilatura.trafilatura.Utils.trim;
import static trafilatura.trafilatura.Xml.TEI_VALID_TAGS;
import static trafilatura.trafilatura.Xml.delete_element;
import static trafilatura.trafilatura.Xpaths.OVERALL_DISCARD_XPATH;

/** Functions grounding on third-party software. */
public final class External {
    private External() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(External.class);

    /** justext_rescue as looked up by compare_extraction(): replaceable in tests, like monkeypatching the module attribute. */
    public static java.util.function.BiFunction<Element, Extractor, Pair<Element, String>> JUSTEXT_RESCUE =
            External::justext_rescue;

    public static Set<String> JT_STOPLIST = null;

    public static final String SANITIZED_XPATH = ".//aside|.//audio|.//button|.//fencedframe|.//fieldset|.//figure|.//footer|.//iframe|.//input|.//label|.//link|.//nav|.//noindex|.//noscript|.//object|.//option|.//select|.//source|.//svg|.//time";

    // adopt justext only when the text it replaces is at most this much longer (swept 2-4: every
    // (3, 4]-band page was justext wrongly replacing a longer, closer-to-target extraction)
    public static final int JUSTEXT_OVERRIDE_RATIO = 3;

    /** Safety net: try with the generic algorithm readability. */
    public static Element try_readability(Element htmlinput) {
        try {
            ReadabilityLxml.Document doc = new ReadabilityLxml.Document(htmlinput, 25);
            // force conversion to utf-8 (see #319)
            Element summary = fromstring_bytes(doc.summary());
            return summary != null ? summary : new Element("");
        } catch (RuntimeException err) {
            LOGGER.warn("readability_lxml failed: {}", err.toString());
            return new Element("");
        }
    }

    /** Decide whether the readability output should replace the own extraction. */
    public static boolean _prefer_readability(Element body, Element algo_body, String algo_text, int len_text, int len_algo,
            Extractor options) {
        // readability empty, or same length as the own extraction (assumed same content)
        if (len_algo == 0 || len_algo == len_text) {
            return false;
        }
        // own extraction much longer
        if (len_text > 2 * len_algo) {
            return false;
        }
        return
        // own text empty
        len_text == 0
                // readability much longer, unless it grabbed raw JSON (#632)
                || (len_algo > 2 * len_text && !algo_text.startsWith("{"))
                // own extraction structurally deficient: no paragraph text or table-dominated
                || (len_algo > options.min_extracted_size * 2
                        && (body.xpath_str(".//p//text()").isEmpty()
                                || body.findall(".//table").size() > body.findall(".//p").size()))
                // recall mode: readability output substantially longer
                || (options.focus.equals("recall") && len_algo > 1.5 * len_text && !algo_text.startsWith("{"))
                // recall mode: readability recovers a headed article (#354)
                || (options.focus.equals("recall")
                        && body.xpath(".//head").isEmpty()
                        && !algo_body.iter("h2", "h3", "h4").isEmpty()
                        && len_algo > len_text);
    }

    /**
     * Decide whether to choose own or external extraction based on a series of heuristics.
     * {@code raw_tree} (uncleaned) feeds readability; {@code cleaned_tree} (tree_cleaning'd, unconverted)
     * feeds justext.
     */
    public static Pair<Element, String> compare_extraction(Element cleaned_tree, Element raw_tree, Element body,
            String text, Extractor options) {
        int len_text = text.length();
        // bypass for recall
        if (options.focus.equals("recall") && len_text > options.min_extracted_size * 10) {
            return Pair.of(body, text);
        }

        // prior cleaning
        if (options.focus.equals("precision")) {
            raw_tree = prune_unwanted_nodes(raw_tree, OVERALL_DISCARD_XPATH);
        }

        // try with readability
        Element temppost_algo = try_readability(raw_tree);
        // unicode fix necessary on certain systems (#331)
        String algo_text = trim(Etree.tostring(temppost_algo, "unicode", "text", false, true));
        int len_algo = algo_text.length();
        LOGGER.debug("extracted length: {} (algorithm) {} (extraction)", len_algo, len_text);

        boolean use_readability = _prefer_readability(body, temppost_algo, algo_text, len_text, len_algo, options);
        if (use_readability) {
            body = temppost_algo;
            text = algo_text;
            len_text = len_algo;
        }
        LOGGER.debug("using {} extraction: {}", use_readability ? "generic" : "custom", options.source);

        // override faulty extraction: try with justext
        boolean unclean = !body.xpath(SANITIZED_XPATH).isEmpty();
        if (unclean || len_text < options.min_extracted_size) {
            LOGGER.debug("unclean or short document triggering justext examination: {}", options.source);
            Pair<Element, String> rescued = JUSTEXT_RESCUE.apply(cleaned_tree, options);
            Element body2 = rescued.first();
            String text2 = rescued.second();
            int len_text2 = text2.length();
            // unclean: allow shorter (boilerplate dropped), guard image-heavy pages;
            // merely short: must add text, len_text2 > len_text is implied (#896)
            boolean accept;
            if (unclean) {
                accept = len_text <= JUSTEXT_OVERRIDE_RATIO * len_text2
                        && (len_text2 > len_text || !options.images || body.find(".//graphic") == null);
            } else {
                accept = len_text < len_text2;
            }
            if (!text2.isEmpty() && accept) {
                LOGGER.debug("using justext, length: {}", len_text2);
                body = body2;
                text = text2;
                use_readability = false;
            }
        }

        // post-processing: remove unwanted sections
        if (use_readability) {
            Pair<Element, String> sanitized = sanitize_tree(body, options);
            body = sanitized.first();
            text = sanitized.second();
        }

        return Pair.of(body, text);
    }

    /** Retrieve and return the content of all JusText stoplists. */
    public static Set<String> jt_stoplist_init() {
        Set<String> words = new HashSet<>();
        for (String language : Justext.get_stoplists()) {
            words.addAll(Justext.get_stoplist(language));
        }
        JT_STOPLIST = words;
        return JT_STOPLIST;
    }

    /** Second safety net: try with the generic algorithm justext. */
    public static Element try_justext(Element tree, String url, String target_language) {
        // init
        Element result_body = Etree.Element("body");
        // determine language
        Set<String> justext_stoplist;
        if (target_language != null && JUSTEXT_LANGUAGES.containsKey(target_language)) {
            justext_stoplist = Justext.get_stoplist(JUSTEXT_LANGUAGES.get(target_language));
        } else {
            justext_stoplist = JT_STOPLIST != null && !JT_STOPLIST.isEmpty() ? JT_STOPLIST : jt_stoplist_init();
        }
        // extract
        List<Paragraph> paragraphs;
        try {
            paragraphs = ParagraphMaker.make_paragraphs(tree);
            Justext.classify_paragraphs(paragraphs, justext_stoplist, 50, 150, 0.1, 0.2, 0.25, true);
            Justext.revise_paragraph_classification(paragraphs, 150);
        } catch (RuntimeException err) {
            LOGGER.error("justext {} {}", err.toString(), url);
            return result_body;
        }
        for (Paragraph paragraph : paragraphs) {
            if (!paragraph.is_boilerplate) {
                Etree.SubElement(result_body, "p").text = paragraph.text;
            }
        }
        return result_body;
    }

    /** Try to use justext algorithm as a second fallback. */
    public static Pair<Element, String> justext_rescue(Element tree, Extractor options) {
        Element temppost_algo = try_justext(basic_cleaning(tree), options.url, options.lang);
        return Pair.of(temppost_algo, trim(String.join(" ", temppost_algo.itertext())));
    }

    /** Convert and sanitize the output from the generic algorithm (post-processing). */
    public static Pair<Element, String> sanitize_tree(Element tree, Extractor options) {
        // 1. clean
        Element cleaned_tree = tree_cleaning(tree, options);
        if (options.links) {
            Etree.strip_tags(cleaned_tree, "span");
        } else {
            Etree.strip_tags(cleaned_tree, "span", "a");
        }
        // 2. convert (pass url so relative links are absolutized on the fallback path)
        cleaned_tree = convert_tags(cleaned_tree, options, options.url);
        for (Element elem : cleaned_tree.iter("graphic").list()) {
            Element image = handle_image(elem, options);
            if (image == null) {
                delete_element(elem);
            } else {
                elem.attrib.clear();
                elem.attrib.putAll(image.attrib);
            }
        }
        // Mark first <th>-containing row per parent group as head (mirrors handle_table logic).
        // Groups by direct parent (the enclosing table once tbody/thead/tfoot are stripped upstream);
        // nested tables form their own group.
        Set<Element> seen_group_elems = new HashSet<>();
        boolean seenNoParent = false;
        for (Element tr : cleaned_tree.iter("tr")) {
            Element parent = tr.getparent();
            boolean seen = parent == null ? seenNoParent : seen_group_elems.contains(parent);
            if (!seen && tr.find("th") != null) {
                if (parent == null) {
                    seenNoParent = true;
                } else {
                    seen_group_elems.add(parent);
                }
                for (Element c : tr.iterchildren("th")) {
                    c.set("role", "head");
                }
            }
        }
        for (Element elem : cleaned_tree.iter("td", "th", "tr")) {
            elem.tag = elem.tag.equals("tr") ? "row" : "cell";
        }
        // 3. sanitize
        Set<String> invalid = new HashSet<>();
        for (Element elem : cleaned_tree.iter("*")) {
            invalid.add(elem.tag);
        }
        invalid.removeAll(TEI_VALID_TAGS);
        Etree.strip_tags(cleaned_tree, invalid);
        // 4. return
        return Pair.of(cleaned_tree, trim(String.join(" ", cleaned_tree.itertext())));
    }
}
