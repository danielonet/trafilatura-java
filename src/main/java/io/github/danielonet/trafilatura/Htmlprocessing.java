// Java port of trafilatura/htmlprocessing.py
package io.github.danielonet.trafilatura;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.danielonet.trafilatura.compat.Pair;
import io.github.danielonet.trafilatura.compat.Py;
import io.github.danielonet.trafilatura.lxml.Element;
import io.github.danielonet.trafilatura.lxml.Etree;
import io.github.danielonet.trafilatura.lxml.XPath;
import io.github.danielonet.trafilatura.Settings.Document;
import io.github.danielonet.trafilatura.Settings.Extractor;

import static io.github.danielonet.trafilatura.Deduplication.duplicate_test;
import static io.github.danielonet.trafilatura.Settings.CUT_EMPTY_ELEMS;
import static io.github.danielonet.trafilatura.Settings.MANUALLY_CLEANED;
import static io.github.danielonet.trafilatura.Settings.MANUALLY_STRIPPED;
import static io.github.danielonet.trafilatura.Utils.LINK_FARM_RATIO;
import static io.github.danielonet.trafilatura.Utils.image_src;
import static io.github.danielonet.trafilatura.Utils.safe_base_url;
import static io.github.danielonet.trafilatura.Utils.safe_relative_url;
import static io.github.danielonet.trafilatura.Utils.textfilter;
import static io.github.danielonet.trafilatura.Utils.trim;
import static io.github.danielonet.trafilatura.Xml.delete_element;
import static io.github.danielonet.trafilatura.Xml.meta_items;
import static io.github.danielonet.trafilatura.Xml.separates_inline;

/** Functions to process nodes in HTML code. */
public final class Htmlprocessing {
    private Htmlprocessing() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Htmlprocessing.class);

    public static final Map<String, String> REND_TAG_MAPPING = new LinkedHashMap<>();

    static {
        REND_TAG_MAPPING.put("em", "#i");
        REND_TAG_MAPPING.put("i", "#i");
        REND_TAG_MAPPING.put("b", "#b");
        REND_TAG_MAPPING.put("strong", "#b");
        REND_TAG_MAPPING.put("u", "#u");
        REND_TAG_MAPPING.put("kbd", "#t");
        REND_TAG_MAPPING.put("samp", "#t");
        REND_TAG_MAPPING.put("tt", "#t");
        REND_TAG_MAPPING.put("var", "#t");
        REND_TAG_MAPPING.put("sub", "#sub");
        REND_TAG_MAPPING.put("sup", "#sup");
    }

    // {v: k for k, v in REND_TAG_MAPPING.items()}: the last key wins
    public static final Map<String, String> HTML_TAG_MAPPING = new LinkedHashMap<>();

    static {
        REND_TAG_MAPPING.forEach((k, v) -> HTML_TAG_MAPPING.put(v, k));
    }

    public static final Set<String> PRESERVE_IMG_CLEANING = Set.of("figure", "picture", "source");

    public static final List<String> CODE_INDICATORS = List.of("{", "(\"", "('", "\n    ");

    // LaTeX source carried by MathML, in order of preference: the annotation holds the
    // original markup, alttext a rendering of it. local-name() also matches XHTML pages
    // where the subtree keeps its MathML namespace.
    public static final XPath TEX_ANNOTATION_XPATH = new XPath(".//*[local-name()=\"annotation\"][@encoding=\"application/x-tex\"]");

    /** Turn MathML into its LaTeX source so formulas survive the cleaning of <math>. */
    public static Element recover_math(Element tree) {
        for (Element element : tree.iter("math")) {
            List<Element> annotation = TEX_ANNOTATION_XPATH.evaluate(element);
            String latex = !annotation.isEmpty()
                    ? trim(Py.or(annotation.get(0).text, ""))
                    : trim(Py.or(element.get("alttext"), ""));
            if (latex.isEmpty()) {
                continue;
            }
            // delimiters the Markdown converter understands, see _convert_math()
            boolean block = "block".equals(element.get("display"));
            String opening = block ? "\\[" : "\\(";
            String closing = block ? "\\]" : "\\)";
            // the tail is kept when the element is deleted further down, the subtree is not
            element.tail = opening + latex + closing + Py.or(element.tail, "");
        }
        return tree;
    }

    /**
     * Delete <form> elements, keeping those that wrap the page's main content.
     *
     * Frameworks like ASP.NET WebForms put the whole document inside a single
     * <form id="aspnetForm">, so dropping every form leaves nothing to extract.
     * A form holding most of the remaining text is such a layout wrapper and gets
     * demoted to a plain container; genuine widgets (search, login, newsletter)
     * hold little text and are still removed.
     */
    public static void _handle_forms(Element tree) {
        List<Element> forms = tree.iter("form").list();
        if (forms.isEmpty()) {
            return;
        }
        // run after the other elements are gone, so script/head text cannot skew the ratio
        int total = tree.text_content().length();
        for (Element form : forms) {
            if (total > 0 && form.text_content().length() > total / 2.0) {
                form.tag = "div";
            } else {
                delete_element(form);
            }
        }
    }

    /** Prune the tree by discarding unwanted elements. */
    public static Element tree_cleaning(Element tree, Extractor options) {
        // salvage formulas before <math> is discarded along with its subtree
        recover_math(tree);
        // determine cleaning strategy, use lists to keep it deterministic
        List<String> cleaning_list = new ArrayList<>(MANUALLY_CLEANED);
        List<String> stripping_list = new ArrayList<>(MANUALLY_STRIPPED);
        // forms are handled separately below, once the rest of the noise is gone. MANUALLY_CLEANED is
        // public API users mutate in place, so honour a removed "form" instead of assuming it is there.
        boolean clean_forms = cleaning_list.contains("form");
        if (clean_forms) {
            cleaning_list.remove("form");
        }
        if (!options.tables) {
            cleaning_list.addAll(List.of("table", "td", "th", "tr"));
        } else {
            // figures holding a table (#301) and ARIA layout tables (role=presentation/none)
            for (Element elem : tree.iter("figure", "table")) {
                boolean layout = elem.tag.equals("figure")
                        ? elem.find(".//table") != null
                        : "presentation".equals(elem.get("role")) || "none".equals(elem.get("role"));
                if (layout) {
                    elem.tag = "div";
                }
            }
        }
        if (options.images) {
            // Many websites have <img> inside <figure> or <picture> or <source> tag
            cleaning_list.removeIf(PRESERVE_IMG_CLEANING::contains);
            stripping_list.remove("img");
        }

        // strip targeted elements
        Etree.strip_tags(tree, stripping_list);

        // recall: undo the deletions if they remove every paragraph (copy only if none can survive)
        Element tcopy = null;
        if (options.focus.equals("recall") && tree.find(".//p") != null) {
            boolean noneSurvives = cleaning_list.contains("p");
            if (!noneSurvives) {
                noneSurvives = true;
                // any(next(p.iterancestors(cleaning_list), tree) is tree for p in tree.iter("p"))
                for (Element p : tree.iter("p")) {
                    Element ancestor = p.firstancestor(cleaning_list);
                    if (ancestor == null || ancestor == tree) {
                        noneSurvives = false;
                        break;
                    }
                }
            }
            if (noneSurvives) {
                tcopy = tree.copy();
            }
        }
        for (String expression : cleaning_list) {
            for (Element element : tree.iter(expression)) {
                delete_element(element);
            }
        }
        if (tcopy != null && tree.find(".//p") == null) {
            tree = tcopy;
        }

        if (clean_forms) {
            _handle_forms(tree);
        }

        return prune_html(tree, options.focus);
    }

    /** Delete selected empty elements to save space and processing time. */
    public static Element prune_html(Element tree, String focus) {
        boolean tails = !focus.equals("precision");
        List<Element> empty = new ArrayList<>();
        for (Element e : tree.iterdescendants(CUT_EMPTY_ELEMS)) {
            if (e.text == null && e.size() == 0) {
                empty.add(e);
            }
        }
        for (Element element : empty) {
            delete_element(element, tails);
        }
        return tree;
    }

    public static Element prune_html(Element tree) {
        return prune_html(tree, "balanced");
    }

    /** Prune the HTML tree by removing unwanted sections. */
    public static Element prune_unwanted_nodes(Element tree, List<XPath> nodelist, boolean with_backup) {
        if (!with_backup) {
            for (XPath expression : nodelist) {
                for (Element subtree : expression.evaluate(tree)) {
                    delete_element(subtree); // the tail text is preserved
                }
            }
            return tree;
        }
        // guard: prune nothing if it would remove too much text, the tree keeps its elements
        Set<Element> matches = new LinkedHashSet<>();
        for (XPath expression : nodelist) {
            matches.addAll(expression.evaluate(tree));
        }
        List<Element> outer = new ArrayList<>();
        for (Element elem : matches) {
            boolean nested = false;
            for (Element a : elem.iterancestors()) {
                if (matches.contains(a)) {
                    nested = true;
                    break;
                }
            }
            if (!nested) {
                outer.add(elem);
            }
        }
        int old_len = tree.text_content().length();
        // todo: adjust for recall and precision settings
        int removed = 0;
        for (Element elem : outer) {
            removed += elem.text_content().length();
        }
        if (old_len - removed > old_len / 7.0) {
            for (Element elem : outer) {
                delete_element(elem);
            }
        }
        return tree;
    }

    public static Element prune_unwanted_nodes(Element tree, List<XPath> nodelist) {
        return prune_unwanted_nodes(tree, nodelist, false);
    }

    /** Heuristics on link text: total length, number of links, short links, the link texts. */
    public record LinkInfo(int linklen, int elemnum, int shortelems, List<String> mylist) {
    }

    /** Collect heuristics on link text. */
    public static LinkInfo collect_link_info(List<Element> links_xpath) {
        List<String> mylist = new ArrayList<>();
        for (Element elem : links_xpath) {
            String e = trim(elem.text_content());
            if (!e.isEmpty()) {
                mylist.add(e);
            }
        }
        int total = 0;
        int shortelems = 0;
        for (String s : mylist) {
            total += s.length();
            // longer strings impact recall in favor of precision
            if (s.length() < 10) {
                shortelems++;
            }
        }
        return new LinkInfo(total, mylist.size(), shortelems, mylist);
    }

    /** Tell a document listing (every link alone in its paragraph) from a farm (links running together). */
    public static boolean is_paragraph_listing(List<Element> links_xpath) {
        for (Element link : links_xpath) {
            Element parent = link.getparent();
            if (parent == null || !parent.tag.equals("p") || parent.findall(".//ref").size() > 1) {
                return false;
            }
        }
        return true;
    }

    /** Remove sections which are rich in links (probably boilerplate), flag short linked ones. */
    public static Pair<Boolean, Boolean> link_density_test(Element element, boolean favor_precision) {
        List<Element> links_xpath = element.findall(".//ref");
        if (links_xpath.isEmpty()) {
            return Pair.of(false, false);
        }
        // preserve image containers
        if (element.find(".//graphic") != null) {
            return Pair.of(false, false);
        }
        String text = trim(element.text_content());
        // shortcut
        if (links_xpath.size() == 1) {
            int len_threshold = favor_precision ? 10 : 100;
            String link_text = trim(links_xpath.get(0).text_content());
            if (link_text.length() > len_threshold && link_text.length() > text.length() * 0.9) {
                return Pair.of(true, false);
            }
        }
        int limitlen;
        if (element.tag.equals("p")) {
            limitlen = element.getnext() == null ? 60 : 30;
        } else if (element.getnext() == null) {
            limitlen = 300;
        } else {
            limitlen = 100;
        }
        int elemlen = text.length();
        if (elemlen < limitlen) {
            LinkInfo info = collect_link_info(links_xpath);
            if (info.elemnum() == 0) {
                return Pair.of(true, false);
            }
            LOGGER.debug("list link text/total: {}/{} – short elems/total: {}/{}",
                    info.linklen(), elemlen, info.shortelems(), info.elemnum());
            return Pair.of(info.linklen() > elemlen * 0.8
                    || (info.elemnum() > 1 && (double) info.shortelems() / info.elemnum() > 0.8), true);
        }
        // large near-total-link farms ("latest news" sidebars) at/above limitlen, which the size gate
        // above never tests (#584); small farms are already caught there at the plain 0.8 ratio.
        // >4: a farm is MANY links -- a handful of long sentence-links is editorial (knowtechie realworld test)
        if (links_xpath.size() > 4) {
            LinkInfo info = collect_link_info(links_xpath);
            // avg link len >= 100 => catalog/listing content (one link per card), not a farm: keep it
            if (info.linklen() > text.length() * LINK_FARM_RATIO && info.linklen() < 100 * info.elemnum()
                    && !is_paragraph_listing(links_xpath)) {
                return Pair.of(true, false);
            }
        }
        return Pair.of(false, false);
    }

    public static Pair<Boolean, Boolean> link_density_test(Element element) {
        return link_density_test(element, false);
    }

    /** Remove tables which are rich in links (probably boilerplate). */
    public static boolean link_density_test_tables(Element element) {
        List<Element> links_xpath = element.findall(".//ref");

        if (links_xpath.isEmpty()) {
            return false;
        }

        int elemlen = trim(element.text_content()).length();
        if (elemlen < 200) {
            return false;
        }

        // links with no text (e.g. icon/flag links wrapping images) yield linklen 0 -> not boilerplate
        int linklen = collect_link_info(links_xpath).linklen();
        LOGGER.debug("table link text: {} / total: {}", linklen, elemlen);
        return elemlen < 1000 ? linklen > 0.8 * elemlen : linklen > 0.5 * elemlen;
    }

    /**
     * Determine the link density of elements with respect to their length,
     * and remove the elements identified as boilerplate.
     */
    public static Element delete_by_link_density(Element subtree, String tagname, boolean backtracking,
            boolean favor_precision) {
        List<Element> deletions = new ArrayList<>();
        int len_threshold = favor_precision ? 200 : 100;
        int depth_threshold = favor_precision ? 1 : 3;

        for (Element elem : subtree.iter(tagname)) {
            Pair<Boolean, Boolean> test = link_density_test(elem, favor_precision);
            if (test.first() || (backtracking
                    && test.second()
                    && elem.size() >= depth_threshold
                    && trim(elem.text_content()).length() < len_threshold)) {
                // a paragraph that holds the content of a list item is kept: the
                // link density of the whole list is checked separately, and
                // removing it here would leave the item empty (GH #788)
                Element parent = elem.getparent();
                if (tagname.equals("p") && parent != null && List.of("item", "td", "th").contains(parent.tag)) {
                    continue;
                }
                deletions.add(elem);
            }
        }

        for (Element elem : new LinkedHashSet<>(deletions)) {
            delete_element(elem);
        }

        return subtree;
    }

    public static Element delete_by_link_density(Element subtree, String tagname) {
        return delete_by_link_density(subtree, tagname, false, false);
    }

    /** Convert, format, and probe potential text elements. */
    public static Element handle_textnode(Element elem, Extractor options, boolean comments_fix, boolean preserve_spaces) {
        if (elem.tag.equals("graphic") && image_src(elem) != null) {
            return elem;
        }
        if (elem.tag.equals("done") || (elem.size() == 0 && !Py.truthy(elem.text) && !Py.truthy(elem.tail)
                && (comments_fix || !separates_inline(elem)))) {
            return null;
        }

        // lb bypass
        if (!comments_fix && elem.tag.equals("lb")) {
            if (!preserve_spaces) {
                elem.tail = Py.or(trim(elem.tail), null);
            }
            // no textfilter/dedup for lb, unlike process_node: the two probes are not interchangeable
            return elem;
        }

        if (!Py.truthy(elem.text) && elem.size() == 0) {
            // try the tail
            elem.text = elem.tail;
            elem.tail = "";
            // handle differently for br/lb
            if (comments_fix && elem.tag.equals("lb")) {
                elem.tag = "p";
            }
        }

        // trim
        if (!preserve_spaces) {
            elem.text = Py.or(trim(elem.text), null);
            if (Py.truthy(elem.tail)) {
                elem.tail = Py.or(trim(elem.tail), null);
            }
        }

        // filter content
        if ((!Py.truthy(elem.text) && textfilter(elem)) || (options.dedup_enabled() && duplicate_test(elem, options))) {
            return null;
        }
        return elem;
    }

    public static Element handle_textnode(Element elem, Extractor options) {
        return handle_textnode(elem, options, true, false);
    }

    /** Convert, format, and probe potential text elements (light format). */
    public static Element process_node(Element elem, Extractor options) {
        if (elem.tag.equals("done") || (elem.size() == 0 && !Py.truthy(elem.text) && !Py.truthy(elem.tail))) {
            return null;
        }

        // trim
        elem.text = Py.or(trim(elem.text), null);
        elem.tail = Py.or(trim(elem.tail), null);

        // adapt content string
        if (!elem.tag.equals("lb") && !Py.truthy(elem.text) && Py.truthy(elem.tail) && elem.size() == 0) {
            elem.text = elem.tail;
            elem.tail = null;
        }

        // content checks
        if (Py.truthy(elem.text) || Py.truthy(elem.tail)) {
            if (textfilter(elem) || (options.dedup_enabled() && duplicate_test(elem, options))) {
                return null;
            }
        }

        return elem;
    }

    /** Convert <ul> and <ol> to <list> and underlying <li> elements to <item>. */
    public static void convert_lists(Element elem) {
        elem.set("rend", elem.tag);
        elem.tag = "list";
        int i = 1;
        for (Element subelem : elem.iter("dd", "dt", "li")) {
            // keep track of dd/dt items
            if (subelem.tag.equals("dd") || subelem.tag.equals("dt")) {
                subelem.set("rend", subelem.tag + "-" + i);
                // increment counter after <dd> in description list
                if (subelem.tag.equals("dd")) {
                    i++;
                }
            }
            // convert elem tag (needs to happen after the rest)
            subelem.tag = "item";
        }
    }

    /** Convert quoted elements while accounting for nested structures. */
    public static void convert_quotes(Element elem) {
        // a pre is code if it wraps a single span, carries highlight.js spans or code-like text
        List<Element> hljs = elem.tag.equals("pre") ? elem.xpath(".//span[starts-with(@class,'hljs')]") : List.of();
        for (Element subelem : hljs) {
            subelem.attrib.clear();
        }
        boolean is_code = false;
        if (elem.tag.equals("pre")) {
            is_code = (elem.size() == 1 && elem.get(0).tag.equals("span")) || !hljs.isEmpty();
            if (!is_code) {
                String text = Py.or(elem.text, "");
                is_code = CODE_INDICATORS.stream().anyMatch(text::contains);
            }
        }
        elem.tag = is_code ? "code" : "quote";
    }

    /** Add head tags and delete attributes. */
    public static void convert_headings(Element elem) {
        elem.attrib.clear();
        elem.set("rend", elem.tag);
        elem.tag = "head";
    }

    /** Convert <br> and <hr> to <lb>. */
    public static void convert_line_breaks(Element elem) {
        elem.tag = "lb";
    }

    /** Convert <del>, <s>, <strike> to <del rend="overstrike">. */
    public static void convert_deletions(Element elem) {
        elem.tag = "del";
        elem.set("rend", "overstrike");
    }

    /** Handle details and summary. */
    public static void convert_details(Element elem) {
        elem.tag = "div";
        for (Element subelem : elem.iter("summary")) {
            subelem.tag = "head";
        }
    }

    public static final Map<String, Consumer<Element>> CONVERSIONS = new LinkedHashMap<>();

    static {
        for (String tag : List.of("dl", "ol", "ul")) {
            CONVERSIONS.put(tag, Htmlprocessing::convert_lists);
        }
        for (String tag : List.of("h1", "h2", "h3", "h4", "h5", "h6")) {
            CONVERSIONS.put(tag, Htmlprocessing::convert_headings);
        }
        for (String tag : List.of("br", "hr")) {
            CONVERSIONS.put(tag, Htmlprocessing::convert_line_breaks);
        }
        for (String tag : List.of("blockquote", "pre", "q")) {
            CONVERSIONS.put(tag, Htmlprocessing::convert_quotes);
        }
        for (String tag : List.of("del", "s", "strike")) {
            CONVERSIONS.put(tag, Htmlprocessing::convert_deletions);
        }
        CONVERSIONS.put("details", Htmlprocessing::convert_details);
    }

    /** Replace link tags and href attributes, delete the rest. */
    public static void convert_link(Element elem, String base_url) {
        elem.tag = "ref";
        String target = elem.get("href"); // defaults to None
        elem.attrib.clear();
        if (target != null && !target.isEmpty()) {
            // convert relative URLs
            if (base_url != null && !base_url.isEmpty()) {
                target = Py.or(safe_relative_url(base_url, target), target);
            }
            elem.set("target", target);
        }
    }

    /** Simplify markup and convert relevant HTML tags to an XML standard. */
    public static Element convert_tags(Element tree, Extractor options, String url) {
        // delete links for faster processing
        if (!options.links) {
            List<String> containers = options.tables ? List.of("div", "li", "p", "table") : List.of("div", "li", "p");
            // necessary for further detection, the tree root does not count as a container
            for (Element elem : tree.iter("a")) {
                Element ancestor = elem.firstancestor(containers);
                if (ancestor != null && ancestor != tree) {
                    elem.tag = "ref";
                }
            }
            // strip the rest
            Etree.strip_tags(tree, "a");
        } else {
            // get base URL for converting relative URLs
            String base_url = url != null && !url.isEmpty() ? safe_base_url(url) : url;
            for (Element elem : tree.iter("a", "ref")) {
                convert_link(elem, base_url);
            }
        }

        // Yoast FAQ blocks: question headers are bold but act as titles (#471)
        for (Element elem : tree.iter("strong")) {
            if (elem.get("class", "").contains("schema-faq-question")) {
                elem.attrib.clear();
                elem.set("rend", "h3");
                elem.tag = "head";
            }
        }

        // an empty sup/sub carries nothing to raise or lower, and process_node() would later
        // hand it the following text as its own, so the marker would wrap the wrong words.
        // The tail is kept in every focus mode, unlike the pruning of CUT_EMPTY_ELEMS.
        for (Element elem : tree.iter("sub", "sup")) {
            if (!Py.truthy(elem.text) && elem.size() == 0) {
                delete_element(elem);
            }
        }

        if (options.formatting) {
            for (Element elem : tree.iter(REND_TAG_MAPPING.keySet())) {
                elem.attrib.clear();
                elem.set("rend", REND_TAG_MAPPING.get(elem.tag));
                elem.tag = "hi";
            }
        } else {
            Etree.strip_tags(tree, new ArrayList<>(REND_TAG_MAPPING.keySet()));
        }

        // iterate over all concerned elements
        for (Element elem : tree.iter(CONVERSIONS.keySet())) {
            CONVERSIONS.get(elem.tag).accept(elem);
        }
        // images
        if (options.images) {
            for (Element elem : tree.iter("img")) {
                elem.tag = "graphic";
            }
            // move images out of links to prevent duplication/loss
            if (options.links) {
                for (Element ref : tree.iter("ref").list()) {
                    List<Element> graphics = ref.iter("graphic");
                    // iterate in reverse so addnext preserves original order
                    for (int i = graphics.size() - 1; i >= 0; i--) {
                        ref.addnext(graphics.get(i));
                    }
                    // remove ref if it only contained images (no text),
                    // its leftover children (picture, source) would
                    // otherwise trigger justext fallback and lose images
                    if (!graphics.isEmpty() && Py.strip(ref.text_content()).isEmpty()) {
                        delete_element(ref);
                    }
                }
            }
        }

        return tree;
    }

    public static Element convert_tags(Element tree, Extractor options) {
        return convert_tags(tree, options, null);
    }

    public static final Map<String, Function<Element, String>> HTML_CONVERSIONS = new LinkedHashMap<>();

    static {
        HTML_CONVERSIONS.put("list", e -> "ul");
        HTML_CONVERSIONS.put("item", e -> "li");
        HTML_CONVERSIONS.put("code", e -> "pre");
        HTML_CONVERSIONS.put("quote", e -> "blockquote");
        HTML_CONVERSIONS.put("head", e -> "h" + Integer.parseInt(e.get("rend", "h3").substring(1)));
        HTML_CONVERSIONS.put("lb", e -> "br");
        HTML_CONVERSIONS.put("graphic", e -> "img");
        HTML_CONVERSIONS.put("ref", e -> "a");
        HTML_CONVERSIONS.put("hi", e -> {
            String tag = HTML_TAG_MAPPING.get(e.get("rend", "#i"));
            if (tag == null) {
                throw new IllegalArgumentException("unknown rend value: " + e.get("rend"));
            }
            return tag;
        });
        HTML_CONVERSIONS.put("row", e -> "tr");
        HTML_CONVERSIONS.put("cell", e -> "head".equals(e.get("role")) ? "th" : "td");
    }

    /** Convert XML to simplified HTML. */
    public static Element convert_to_html(Element tree) {
        for (Element elem : tree.iter(HTML_CONVERSIONS.keySet())) {
            elem.tag = HTML_CONVERSIONS.get(elem.tag).apply(elem);
            // handle attributes
            if (elem.tag.equals("a")) {
                String target = elem.attrib.remove("target");
                elem.set("href", target != null ? target : "");
            } else if (!elem.tag.equals("img")) { // keep <img> src/alt/title; drop everything else
                elem.attrib.clear();
            }
        }
        tree.tag = "body";
        Element root = Etree.Element("html");
        root.append(tree);
        return root;
    }

    /** Convert the document to HTML and return a string. */
    public static String build_html_output(Document document, boolean with_metadata) {
        Element html_tree = convert_to_html(document.body);

        if (with_metadata) {
            Element head = Etree.Element("head");
            for (Pair<String, String> item : meta_items(document)) {
                Etree.SubElement(head, "meta", "name", item.first(), "content", item.second());
            }
            html_tree.insert(0, head);
        }

        return Etree.tostring(html_tree, "unicode", "xml", true, true).strip();
    }

    public static String build_html_output(Document document) {
        return build_html_output(document, false);
    }
}
