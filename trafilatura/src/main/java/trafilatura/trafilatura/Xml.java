// Java port of trafilatura/xml.py
package trafilatura.trafilatura;

import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import trafilatura.compat.Json;
import trafilatura.compat.Pair;
import trafilatura.compat.Py;
import trafilatura.compat.Re;
import trafilatura.lxml.Element;
import trafilatura.lxml.Etree;
import trafilatura.trafilatura.Settings.Document;
import trafilatura.trafilatura.Settings.Extractor;

import static trafilatura.trafilatura.Settings.INLINE_CARRIED;
import static trafilatura.trafilatura.Settings.INLINE_CONSUMING;
import static trafilatura.trafilatura.Settings.INLINE_FORMATTABLE;
import static trafilatura.trafilatura.Utils.sanitize;
import static trafilatura.trafilatura.Utils.sanitize_tree;
import static trafilatura.trafilatura.Utils.text_chars_test;

/** All functions related to XML generation, processing and validation. */
public final class Xml {
    private Xml() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Xml.class);
    public static final String PKG_VERSION = TrafilaturaInit.__version__;

    // validation
    public static final String TEI_SCHEMA = "/trafilatura/data/tei_corpus.dtd";
    public static final Set<String> TEI_VALID_TAGS = Set.of(
            "ab", "body", "cell", "code", "del", "div", "graphic", "head", "hi", "item", "lb", "list", "p", "quote",
            "ref", "row", "table");
    public static final Set<String> TEI_VALID_ATTRS = Set.of("rend", "rendition", "role", "target", "type");
    public static final Set<String> TEI_REMOVE_TAIL = Set.of("ab", "p");
    public static final Set<String> TEI_DIV_SIBLINGS = Set.of("p", "list", "table", "quote", "ab");

    public static final Set<String> NEWLINE_ELEMS = Set.of("graphic", "head", "lb", "list", "p", "quote", "row", "table");
    public static final Set<String> SPECIAL_FORMATTING = Set.of("code", "del", "head", "hi", "ref", "item", "cell");
    public static final Set<String> WITH_ATTRIBUTES = Set.of("cell", "row", "del", "graphic", "head", "hi", "item", "list", "ref");
    public static final Set<String> NESTING_WHITELIST = Set.of("cell", "figure", "item", "note", "quote");

    public static final List<String> META_ATTRIBUTES = List.of(
            "sitename", "title", "author", "date", "url", "hostname", "description", "categories", "tags", "license",
            "id", "fingerprint", "language");

    public static final Map<String, String> HI_FORMATTING = Map.of("#b", "**", "#i", "*", "#t", "`");
    // no markdown syntax for these: inline HTML, as in the html output format.
    // Pandoc-style "~x~" is not usable: GitHub renders it as strikethrough.
    public static final Map<String, String> HI_HTML_TAGS = Map.of("#sup", "sup", "#sub", "sub", "#u", "u");
    // adjacent sup/sub stay apart: they are often distinct footnote references
    public static final Set<String> MERGEABLE_RENDS = Set.of("#b", "#i", "#t", "#u");
    public static final Set<String> HEADING_LEVELS = Set.of("1", "2", "3", "4", "5", "6");
    // preceding characters that already separate content, so no extra space/newline is needed
    public static final Set<String> SEPARATORS = Set.of(" ", "\n", "|", "");

    // CommonMark inline-syntax characters that are ambiguous wherever they appear in prose text:
    // emphasis/strikethrough (*, _, ~), code spans (`), link/image brackets ([, ]) and
    // autolinks/raw HTML (<). Unlike '#', '-', '+', '>' and '.'/')' after a digit run, these are
    // risky mid-line too, not just at the start of a block (see _escape_block_start for those).
    public static final List<String> _INLINE_SPECIAL_CHARS = List.of("*", "_", "`", "~", "[", "]", "<");
    // each entry matches that character preceded by any run of backslashes already in the source text
    public static final Map<String, Pattern> _INLINE_SPECIAL_RUN_RES = new LinkedHashMap<>();

    static {
        for (String ch : _INLINE_SPECIAL_CHARS) {
            _INLINE_SPECIAL_RUN_RES.put(ch, Pattern.compile("\\\\*" + Pattern.quote(ch)));
        }
    }

    // a leading heading/blockquote/list marker, only meaningful at the very start of a block line
    public static final Pattern _HEADING_START_RE = Pattern.compile("^( {0,3})(#{1,6})(?=[ \\t]|$)");
    public static final Pattern _QUOTE_START_RE = Pattern.compile("^( {0,3})(>)");
    public static final Pattern _BULLET_START_RE = Pattern.compile("^( {0,3})([-+])(?=[ \\t]|$)");
    public static final Pattern _ORDERED_START_RE = Pattern.compile("^( {0,3})(\\d{1,9})([.)])(?=[ \\t]|$)");
    public static final List<Pattern> _BLOCK_START_RES = List.of(_HEADING_START_RE, _QUOTE_START_RE, _BULLET_START_RE, _ORDERED_START_RE);

    // an unescaped '[' or ']', used as a safety net for text that never went through the tree-wide pass;
    // code spans are matched first and kept as-is, since backslash escapes are literal inside them
    public static final Pattern _UNESCAPED_BRACKET_RE = Pattern.compile(
            "((?<![\\\\`])(`+)(?!`).+?(?<!`)\\2(?!`))|(?<!\\\\)([\\[\\]])", Pattern.DOTALL);

    // block \[...\] and inline \(...\) math; only matched pairs are converted
    public static final Pattern _MATH_BLOCK_RE = Pattern.compile("(?<!\\S)\\\\\\[(.+?)\\\\\\]", Pattern.DOTALL);
    public static final Pattern _MATH_INLINE_RE = Pattern.compile("\\\\\\((.+?)\\\\\\)");
    // the very same spans, as a splitter: math is LaTeX, not CommonMark, so the inline-special
    // escaping has to step over exactly the regions _convert_math rewrites, and nothing else
    public static final Pattern _MATH_SPAN_RE = Pattern.compile("((?s:(?<!\\S)\\\\\\[.+?\\\\\\])|\\\\\\(.+?\\\\\\))");

    // https://github.com/lxml/lxml/blob/master/src/lxml/html/__init__.py
    /**
     * Removes this element from the tree, including its children and
     * text. The tail text is joined to the previous element or parent.
     */
    public static void delete_element(Element element, boolean keep_tail) {
        Element parent = element.getparent();
        if (parent == null) {
            return;
        }

        if (keep_tail && Py.truthy(element.tail)) {
            Element previous = element.getprevious();
            if (previous == null) {
                parent.text = Py.or(parent.text, "") + element.tail;
            } else {
                previous.tail = Py.or(previous.tail, "") + element.tail;
            }
        }

        parent.remove(element);
    }

    public static void delete_element(Element element) {
        delete_element(element, true);
    }

    /** Merge element with its parent and convert formatting to markdown. */
    public static void merge_with_parent(Element element, boolean include_formatting) {
        Element parent = element.getparent();
        if (parent == null) {
            return;
        }

        String full_text = replace_element_text(element, include_formatting);
        if (element.tail != null) {
            full_text += element.tail;
        }

        Element previous = element.getprevious();
        if (previous != null) {
            // There is a previous node, append text to its tail
            previous.tail = Py.truthy(previous.tail) ? previous.tail + " " + full_text : full_text;
        } else if (parent.text != null) {
            parent.text = parent.text + " " + full_text;
        } else {
            parent.text = full_text;
        }
        parent.remove(element);
    }

    public static void merge_with_parent(Element element) {
        merge_with_parent(element, false);
    }

    /** Whether the element is a line break before inline content. */
    public static boolean separates_inline(Element element) {
        Element nxt = element.getnext();
        return element.tag.equals("lb") && nxt != null && INLINE_CARRIED.contains(nxt.tag);
    }

    /** Remove text elements without text. */
    public static Element remove_empty_elements(Element tree) {
        // bottom-up: document order never revisits a parent that its own children just emptied
        for (Element element : tree.iter("*").reversed()) { // 'head', 'hi', 'item', 'p'
            if (element.size() == 0
                    && !text_chars_test(element.text)
                    && !text_chars_test(element.tail)
                    && !keeps_empty(element)) {
                delete_element(element, false);
            }
        }
        return tree;
    }

    /** Whether an empty element must stay. */
    public static boolean keeps_empty(Element element) {
        Element parent = element.getparent();
        return parent == null
                || element.tag.equals("graphic")
                || parent.tag.equals("code")
                || separates_inline(element)
                || is_padding_cell(element);
    }

    /** Whether the element is an empty cell keeping the following values of its row in their columns (#633). */
    public static boolean is_padding_cell(Element element) {
        Element parent = element.getparent();
        return element.tag.equals("cell") && parent != null && text_chars_test(String.join("", parent.itertext()));
    }

    /** Prevent nested tags among a fixed list of tags. */
    public static Element strip_double_tags(Element tree) {
        for (Element elem : tree.xpath(".//head | .//code | .//p").reversed()) {
            for (Element subelem : elem.iterdescendants("code", "head", "p")) {
                if (subelem.tag.equals(elem.tag) && subelem.getparent() != null
                        && !NESTING_WHITELIST.contains(subelem.getparent().tag)) {
                    merge_with_parent(subelem);
                }
            }
        }
        return tree;
    }

    /** Build JSON output based on extracted information. */
    @SuppressWarnings("unchecked")
    public static String build_json_output(Document docmeta, boolean with_metadata) {
        Map<String, Object> outputdict;
        Element commentsbody;
        if (with_metadata) {
            outputdict = docmeta.as_dict();
            Object source = outputdict.remove("url");
            Object sourceHostname = outputdict.remove("sitename");
            Object excerpt = outputdict.remove("description");
            List<String> categories = (List<String>) outputdict.remove("categories");
            List<String> tags = (List<String>) outputdict.remove("tags");
            Element body = (Element) outputdict.remove("body");
            outputdict.put("source", source);
            outputdict.put("source-hostname", sourceHostname);
            outputdict.put("excerpt", excerpt);
            outputdict.put("categories", String.join(";", categories != null ? categories : List.of()));
            outputdict.put("tags", String.join(";", tags != null ? tags : List.of()));
            outputdict.put("text", xmltotxt(body, false));
            commentsbody = (Element) outputdict.remove("commentsbody");
        } else {
            outputdict = new LinkedHashMap<>();
            outputdict.put("text", xmltotxt(docmeta.body, false));
            commentsbody = docmeta.commentsbody;
        }

        outputdict.put("comments", xmltotxt(commentsbody, false));

        return Json.dumps(outputdict, false);
    }

    public static String build_json_output(Document docmeta) {
        return build_json_output(docmeta, true);
    }

    /** Remove unnecessary attributes. */
    public static Element clean_attributes(Element tree) {
        for (Element elem : tree.iter("*")) {
            if (!WITH_ATTRIBUTES.contains(elem.tag)) {
                elem.attrib.clear();
            }
        }
        return tree;
    }

    /** Build XML output tree based on extracted information. */
    public static Element build_xml_output(Document docmeta) {
        Element output = Etree.Element("doc");
        add_xml_meta(output, docmeta);
        docmeta.body.tag = "main";

        // clean XML tree
        output.append(clean_attributes(docmeta.body));
        docmeta.commentsbody.tag = "comments";
        output.append(clean_attributes(docmeta.commentsbody));

        return output;
    }

    /** Make sure the XML output is conform and valid if required. */
    public static String control_xml_output(Document document, Extractor options) {
        strip_double_tags(document.body);
        remove_empty_elements(document.body);

        Element output_tree = options.format.equals("xml") ? build_xml_output(document) : build_tei_output(document);

        output_tree = sanitize_tree(output_tree);
        // necessary for cleaning (XMLParser(remove_blank_text=True))
        output_tree = Etree.fromstring(Etree.tostring(output_tree, "unicode"), true);

        // validate
        if (options.format.equals("xmltei") && options.tei_validation) {
            LOGGER.debug("TEI validation result: {} {}", validate_tei(output_tree), options.source);
        }

        return Etree.tostring(output_tree, "unicode", "xml", true, true).strip();
    }

    /** Non-empty metadata fields as strings, lists joined with semicolons. */
    @SuppressWarnings("unchecked")
    public static List<Pair<String, String>> meta_items(Document docmeta) {
        List<Pair<String, String>> items = new ArrayList<>();
        for (String attribute : META_ATTRIBUTES) {
            Object value = docmeta.getattr(attribute);
            if (Py.truthy(value)) {
                items.add(Pair.of(attribute, value instanceof String s ? s : String.join(";", (List<String>) value)));
            }
        }
        return items;
    }

    /** Add extracted metadata to the XML output tree. */
    public static void add_xml_meta(Element output, Document docmeta) {
        for (Pair<String, String> item : meta_items(docmeta)) {
            output.set(item.first(), item.second());
        }
    }

    /** Build TEI-XML output tree based on extracted information. */
    public static Element build_tei_output(Document docmeta) {
        return check_tei(write_teitree(docmeta), docmeta.url);
    }

    /** Check if the resulting XML file is conform and scrub remaining tags. */
    public static Element check_tei(Element xmldoc, String url) {
        // convert head tags
        for (Element elem : xmldoc.iter("head")) {
            elem.tag = "ab";
            elem.set("type", "header");
            Element parent = elem.getparent();
            if (parent == null) {
                continue;
            }
            if (elem.size() > 0) {
                Element new_elem = _tei_handle_complex_head(elem);
                parent.replace(elem, new_elem);
                elem = new_elem;
            }
            if (parent.tag.equals("p")) {
                _move_element_one_level_up(elem);
            }
        }
        // convert <lb/> when child of <div> to <p>
        for (Element elem : xmldoc.findall(".//text/body//div/lb")) {
            if (elem.tail != null && !Py.strip(elem.tail).isEmpty()) {
                elem.tag = "p";
                elem.text = elem.tail;
                elem.tail = null;
            }
        }
        // look for elements that are not valid
        for (Element elem : xmldoc.findall(".//text/body//*")) {
            // check elements
            if (!TEI_VALID_TAGS.contains(elem.tag)) {
                LOGGER.warn("not a TEI element, removing: {} {}", elem.tag, url);
                merge_with_parent(elem);
                continue;
            }
            if (TEI_REMOVE_TAIL.contains(elem.tag)) {
                _handle_unwanted_tails(elem);
            } else if (elem.tag.equals("div")) {
                _handle_text_content_of_div_nodes(elem);
                _wrap_unwanted_siblings_of_div(elem);
            }
            // check attributes
            for (String attribute : new ArrayList<>(elem.attrib.keySet())) {
                if (!TEI_VALID_ATTRS.contains(attribute)) {
                    LOGGER.warn("not a valid TEI attribute, removing: {} in {} {}", attribute, elem.tag, url);
                    elem.attrib.remove(attribute);
                }
            }
        }
        return xmldoc;
    }

    /** Check if an XML document is conform to the guidelines of the Text Encoding Initiative. */
    public static boolean validate_tei(Element xmldoc) {
        // https://tei-c.org/release/xml/tei/custom/schema/dtd/tei_corpus.dtd
        String document = "<!DOCTYPE TEI SYSTEM \"tei_corpus.dtd\">\n" + Etree.tostring(xmldoc, "unicode", "xml", false, false);
        List<String> errors = new ArrayList<>();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(true);
            SAXParser parser = factory.newSAXParser();
            parser.parse(new InputSource(new StringReader(document)), new DefaultHandler() {
                @Override
                public InputSource resolveEntity(String publicId, String systemId) {
                    InputStream dtd = Xml.class.getResourceAsStream(TEI_SCHEMA);
                    return new InputSource(dtd);
                }

                @Override
                public void error(SAXParseException e) {
                    errors.add(e.getMessage());
                }
            });
        } catch (Exception err) {
            errors.add(err.getMessage());
        }
        boolean result = errors.isEmpty();
        if (!result) {
            LOGGER.warn("not a valid TEI document: {}", errors.get(errors.size() - 1));
        }
        return result;
    }

    /** Return the shortest backtick string of at least min_len that does not appear as a run in text. */
    public static String _code_fence(String text, int min_len) {
        int fence_len = min_len;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '`') {
                run++;
                if (run >= fence_len) {
                    fence_len = run + 1;
                }
            } else {
                run = 0;
            }
        }
        return "`".repeat(fence_len);
    }

    public static String _code_fence(String text) {
        return _code_fence(text, 1);
    }

    /** Inline code span: fence wider than any internal backtick run, padding an edge that abuts a backtick. */
    public static String _code_span(String text) {
        String fence = _code_fence(text);
        if (text.startsWith("`") || text.endsWith("`")) {
            text = " " + text + " "; // CommonMark: a space stops the edge backtick from merging with the fence
        }
        return fence + text + fence;
    }

    /** Wrap text in a markdown marker, leaving any flanking whitespace outside it (valid CommonMark). */
    public static String _md_wrap(String text, String opening, String closing) {
        String stripped = Py.strip(text);
        if (stripped.isEmpty()) {
            return text;
        }
        closing = closing == null ? opening : closing;
        return replaceFirstLiteral(text, stripped, opening + stripped + closing);
    }

    public static String _md_wrap(String text, String opening) {
        return _md_wrap(text, opening, null);
    }

    /** {@code s.replace(old, new, 1)} */
    public static String replaceFirstLiteral(String s, String target, String replacement) {
        int idx = s.indexOf(target);
        return idx < 0 ? s : s.substring(0, idx) + replacement + s.substring(idx + target.length());
    }

    /** Wrap text in an inline code span, leaving any flanking whitespace outside it. */
    public static String _md_code(String text) {
        String stripped = Py.strip(text);
        return !stripped.isEmpty() ? replaceFirstLiteral(text, stripped, _code_span(stripped)) : text;
    }

    /** Convert LaTeX math delimiters to CommonMark $ notation. */
    public static String _convert_math(String text) {
        text = Re.sub(_MATH_BLOCK_RE, m -> "\n$$\n" + Py.strip(m.group(1)) + "\n$$\n", text);
        return Re.sub(_MATH_INLINE_RE, m -> "$" + m.group(1) + "$", text);
    }

    /** Splice out redundant emphasis levels in linear hi chains (nested <font> junk). */
    public static void _collapse_emphasis(Element element, Set<String> active) {
        if (element.tag.equals("hi")) {
            String here = HI_FORMATTING.get(Py.or(element.get("rend"), ""));
            if (here != null) {
                Set<String> extended = new java.util.HashSet<>(active);
                extended.add(here);
                active = extended;
            }
            while (Py.strip(Py.or(element.text, "")).isEmpty()
                    && element.size() == 1
                    && element.get(0).tag.equals("hi")
                    && Py.strip(Py.or(element.get(0).tail, "")).isEmpty()
                    && HI_FORMATTING.get(Py.or(element.get(0).get("rend"), "")) != null
                    && active.contains(HI_FORMATTING.get(Py.or(element.get(0).get("rend"), "")))) {
                Element child = element.get(0);
                element.text = Py.or(element.text, "") + Py.or(child.text, "");
                element.extend(child.getchildren());
                element.remove(child);
            }
        }
        for (Element child : element) {
            _collapse_emphasis(child, active);
        }
    }

    public static void _collapse_emphasis(Element element) {
        _collapse_emphasis(element, Set.of());
    }

    /** Strip leading/trailing whitespace from p, quote and head text nodes (not meaningful in block markdown). */
    public static void _strip_block_whitespace(Element element) {
        for (Element p : element.iter("p", "quote", "head")) {
            if (Py.truthy(p.text)) {
                p.text = p.size() > 0 ? Py.lstrip(p.text) : Py.strip(p.text);
            }
            if (p.size() > 0 && Py.truthy(p.get(-1).tail)) {
                p.get(-1).tail = Py.rstrip(p.get(-1).tail);
            }
        }
    }

    /** Merge adjacent <hi> siblings with identical rend into one to avoid **X** **Y** patterns. */
    public static void _merge_adjacent_hi(Element element) {
        for (Element child : element) {
            _merge_adjacent_hi(child);
        }
        // reverse pass: removing nxt never disturbs the pairs still to be visited
        List<Element> children = element.getchildren();
        for (int i = children.size() - 2; i >= 0; i--) {
            Element curr = children.get(i);
            Element nxt = children.get(i + 1);
            if (curr.tag.equals("hi")
                    && nxt.tag.equals("hi")
                    && java.util.Objects.equals(curr.get("rend"), nxt.get("rend"))
                    && curr.get("rend") != null && MERGEABLE_RENDS.contains(curr.get("rend"))
                    && curr.size() == 0
                    && nxt.size() == 0
                    && Py.strip(Py.or(curr.tail, "")).isEmpty()) {
                curr.text = Py.or(curr.text, "") + Py.or(curr.tail, "") + Py.or(nxt.text, "");
                curr.tail = nxt.tail;
                element.remove(nxt);
            }
        }
    }

    /**
     * Escape literal CommonMark inline-syntax characters so they render as themselves rather than
     * emphasis/strikethrough, a code span, link/image brackets or an autolink/raw HTML tag.
     *
     * Any backslashes already sitting in front of a target character are doubled (so they still
     * render as literal backslashes) before the new escaping backslash is added, which keeps the
     * escape correct even if the source text happened to already contain an escaped occurrence.
     */
    public static String _escape_inline_specials(String text) {
        if (_INLINE_SPECIAL_CHARS.stream().noneMatch(text::contains)) {
            return text;
        }
        for (String ch : _INLINE_SPECIAL_CHARS) {
            if (text.contains(ch)) {
                text = Re.sub(_INLINE_SPECIAL_RUN_RES.get(ch), Xml::_escape_run, text);
            }
        }
        return text;
    }

    private static String _escape_run(MatchResult match) {
        String run = match.group();
        return "\\".repeat(2 * Py.count(run, "\\") + 1) + run.substring(run.length() - 1);
    }

    /**
     * Escape the prose and rewrite the formulas in one pass over the text.
     *
     * Inside a formula '_', '*', '[', ']', '<', '`' and '~' are LaTeX notation, so a backslash put
     * there is read by the math renderer rather than by CommonMark and breaks the formula. Split on
     * the math delimiters once instead, and give each kind of region the treatment it needs.
     */
    public static String _escape_and_convert_math(String text) {
        List<String> parts = Re.split(_MATH_SPAN_RE, text);
        // re.split on one capturing group alternates prose, math, prose, ...
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            sb.append(i % 2 == 0 ? _escape_inline_specials(parts.get(i)) : _convert_math(parts.get(i)));
        }
        return sb.toString();
    }

    /** Escape inline-syntax characters and rewrite LaTeX math in text/tails in place, leaving code subtrees untouched. */
    public static void _escape_and_convert_math_tree(Element element) {
        // code content is verbatim: skip the whole subtree
        if (element.tag.equals("code")
                || (element.tag.equals("hi") && "`".equals(HI_FORMATTING.get(Py.or(element.get("rend"), ""))))) {
            return;
        }
        if (Py.truthy(element.text)) {
            element.text = _escape_and_convert_math(element.text);
        }
        for (Element child : element) {
            _escape_and_convert_math_tree(child);
            if (Py.truthy(child.tail)) { // a code element's tail is prose, so it is still escaped and converted
                child.tail = _escape_and_convert_math(child.tail);
            }
        }
    }

    /** Escape a leading heading/blockquote/list marker so a fresh paragraph/item line isn't misread as block syntax. */
    public static String _escape_block_start(String text) {
        for (Pattern regex : _BLOCK_START_RES) {
            Matcher match = Re.match(regex, text);
            if (match != null) {
                // match.lastindex: every _BLOCK_START_RES pattern has a marker group
                int lastindex = 0;
                for (int g = match.groupCount(); g >= 1; g--) {
                    if (match.start(g) >= 0) {
                        lastindex = g;
                        break;
                    }
                }
                if (lastindex > 0) {
                    int pos = match.start(lastindex);
                    return text.substring(0, pos) + "\\" + text.substring(pos);
                }
            }
        }
        return text;
    }

    /** Last character emitted so far, or '' if nothing yet. */
    public static String _last_char(List<String> returnlist) {
        if (returnlist.isEmpty()) {
            return "";
        }
        String last = returnlist.get(returnlist.size() - 1);
        return last.isEmpty() ? "" : last.substring(last.length() - 1);
    }

    /** Markdown marker of a list item: 'N. ' or '- ' with nesting indent. */
    public static String _list_marker(Element item, boolean include_formatting) {
        String indent = "  ".repeat(Math.max(0, item.iterancestors("list").size() - 1));
        Element parent = item.getparent();
        // numbering is markdown-only: in plain text it injects digit tokens, so fall back to '-'
        if (include_formatting && parent != null && "ol".equals(parent.get("rend"))) {
            return indent + (item.itersiblings(true, "item").size() + 1) + ". ";
        }
        return indent + "- ";
    }

    public static String _list_marker(Element item) {
        return _list_marker(item, true);
    }

    /**
     * Markdown link/image with escaped text and a CommonMark-safe target.
     *
     * Text coming from the tree-wide escaping pass already has its brackets escaped; only
     * brackets not already escaped are touched here, so this is safe to call on both
     * pre-escaped and raw text.
     */
    public static String _md_link(String text, String url, boolean image) {
        String esc = Re.sub(_UNESCAPED_BRACKET_RE, m -> m.group(1) != null ? m.group(1) : "\\" + m.group(3), text);
        String prefix = image ? "!" : "";
        if (url == null) {
            return prefix + "[" + esc + "]";
        }
        String safe;
        if (url.chars().anyMatch(c -> " <>()".indexOf(c) >= 0)) {
            // angle-bracket destination: backslash-escape \, < and > so a literal
            // bracket in the URL does not terminate the destination early
            String inner = url.replace("\\", "\\\\").replace("<", "\\<").replace(">", "\\>");
            safe = "<" + inner + ">";
        } else {
            safe = url;
        }
        return prefix + "[" + esc + "](" + safe + ")";
    }

    public static String _md_link(String text, String url) {
        return _md_link(text, url, false);
    }

    /** Whether replace_element_text emits this element's children inline (vs. process_element recursion). */
    public static boolean _consumes_inline_children(Element element) {
        return INLINE_CONSUMING.contains(element.tag) && element.size() > 0;
    }

    /** Markdown #-marker for a head element, defaulting to level 2. */
    public static String _heading_prefix(Element element) {
        String level = Py.or(element.get("rend"), "");
        int number = HEADING_LEVELS.contains(Py.slice(level, 1, 2)) ? Integer.parseInt(level.substring(1, 2)) : 2;
        return "#".repeat(number);
    }

    /** Markdown image for a graphic element: ![alt](src). */
    public static String _image_markup(Element element, boolean include_formatting) {
        String alt = Py.strip(element.get("title", "") + " " + element.get("alt", ""));
        // alt/title come from attributes, so the text/tail tree pass never reaches them
        if (include_formatting) {
            alt = _escape_inline_specials(alt);
        }
        return _md_link(alt, element.get("src", ""), true);
    }

    /** Collect text from an element's direct text and inline children (carried INLINE_CARRIED tags). */
    public static String _collect_inline_text(Element element, boolean include_formatting) {
        StringBuilder parts = new StringBuilder();
        if (Py.truthy(element.text)) {
            parts.append(element.text);
        }
        for (Element child : element) {
            if (child.tag.equals("graphic")) {
                parts.append(_image_markup(child, include_formatting));
            } else if (child.tag.equals("lb")) {
                parts.append("\n");
            } else if (INLINE_FORMATTABLE.contains(child.tag)) {
                parts.append(replace_element_text(child, include_formatting));
            } else if (Py.truthy(child.text)) { // fallback: e.g. structural tag nested inside inline (malformed input)
                parts.append(child.text);
            }
            if (Py.truthy(child.tail)) {
                parts.append(child.tail);
            }
        }
        return parts.toString();
    }

    /** Whether the element carries the trailing content of its enclosing item/cell. */
    public static boolean _closes_container(Element element, String tag) {
        if (element.tag.equals(tag)) {
            return element.size() == 0;
        }
        Element nxt = element.getnext();
        // a following item closes the current one, any other sibling continues it
        return nxt == null || (tag.equals("item") && nxt.tag.equals("item"));
    }

    /** Whether nothing in the enclosing item renders before the element. */
    public static boolean _opens_item(Element element) {
        while (!element.tag.equals("item")) {
            Element parent = element.getparent();
            if (parent == null || element.getprevious() != null || text_chars_test(parent.text)) {
                return false;
            }
            element = parent;
        }
        return true;
    }

    /** Whether the item renders content of its own before any sublist (whose items carry their markers). */
    public static boolean _item_needs_marker(Element item) {
        if (text_chars_test(item.text)) {
            return true;
        }
        for (Element child : item) {
            if (child.tag.equals("list")) {
                return false;
            }
            // iter() includes the child itself, itertext() excludes its tail
            if (text_chars_test(String.join("", child.itertext()) + Py.or(child.tail, ""))
                    || !child.iter("graphic").isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Escape characters that would break a GFM table row: pipes split columns, newlines split rows.
     * Serializer only: replace_element_text also runs tree-level (merge_with_parent).
     */
    public static String _escape_cell(String text, boolean in_cell) {
        return in_cell ? text.replace("|", "\\|").replace("\n", " ") : text;
    }

    /** Determine element text based on just the text of the element. One must deal with the tail separately. */
    public static String replace_element_text(Element element, boolean include_formatting, boolean in_cell) {
        String elem_text;
        if (_consumes_inline_children(element)) {
            elem_text = _collect_inline_text(element, include_formatting);
        } else {
            elem_text = Py.or(element.text, "");
        }
        // a fresh paragraph/list-item/quote line must not start with a marker that reads as block
        // syntax; table cells are inline-only in GFM, so this never applies there
        if (include_formatting && !elem_text.isEmpty() && !in_cell && List.of("p", "quote", "item").contains(element.tag)) {
            elem_text = _escape_block_start(elem_text);
        }
        // handle formatting: convert to markdown
        if (include_formatting && !elem_text.isEmpty()) {
            if (List.of("article", "list", "table").contains(element.tag)) {
                elem_text = Py.strip(elem_text);
            } else if (element.tag.equals("head") && !in_cell) {
                elem_text = _heading_prefix(element) + " " + elem_text;
            } else if (element.tag.equals("del")) {
                // the tree-wide pass already escaped every '~' in elem_text, so no literal "~~" survives
                elem_text = _md_wrap(elem_text, "~~");
            } else if (element.tag.equals("hi")) {
                String rend = Py.or(element.get("rend"), "");
                String marker = HI_FORMATTING.get(rend);
                if ("`".equals(marker)) {
                    elem_text = _md_code(elem_text); // inline code, flanking whitespace stays outside
                } else if (marker != null) {
                    elem_text = _md_wrap(elem_text, marker);
                } else if (HI_HTML_TAGS.containsKey(rend)) {
                    String tag = HI_HTML_TAGS.get(rend);
                    elem_text = _md_wrap(elem_text, "<" + tag + ">", "</" + tag + ">");
                }
            } else if (element.tag.equals("code")) {
                List<Element> lbs = element.xpath(".//lb");
                if (elem_text.contains("\n") || !lbs.isEmpty()) { // Handle <br> inside <code>
                    // Convert <br> to \n within code blocks
                    for (Element lb : lbs) {
                        elem_text = elem_text + "\n" + Py.or(lb.tail, "");
                        lb.getparent().remove(lb);
                    }
                    String fence = _code_fence(elem_text, 3);
                    elem_text = fence + "\n" + elem_text + "\n" + fence + "\n";
                } else {
                    elem_text = _md_code(elem_text);
                }
            }
        }
        // handle links
        if (element.tag.equals("ref")) {
            String stripped = Py.strip(elem_text);
            if (!stripped.isEmpty()) {
                String target = element.get("target");
                if (target == null || target.isEmpty()) {
                    LOGGER.warn("missing link attribute: {} {}'", elem_text, element.attrib);
                }
                String link_text = _md_link(stripped, target == null || target.isEmpty() ? null : target);
                elem_text = replaceFirstLiteral(elem_text, stripped, link_text);
            } else {
                LOGGER.warn("empty link: {} {}", elem_text, element.attrib);
            }
        }
        // cells
        if (element.tag.equals("cell")) {
            elem_text = Py.strip(elem_text);
            // separate the cell's text from its children
            if (!elem_text.isEmpty() && element.size() > 0) {
                elem_text = elem_text + " ";
            }
        }

        return elem_text;
    }

    public static String replace_element_text(Element element, boolean include_formatting) {
        return replace_element_text(element, include_formatting, false);
    }

    /** Append text, dropping a leading space after a separator. */
    public static void _append_spaced(List<String> returnlist, String text) {
        if (text.startsWith(" ") && SEPARATORS.contains(_last_char(returnlist))) {
            text = text.substring(1);
        }
        if (!text.isEmpty()) {
            returnlist.add(text);
        }
    }

    /** Append the element's tail. */
    public static void _append_tail(Element element, List<String> returnlist, boolean in_cell, boolean in_item,
            boolean include_formatting) {
        String tail = element.tail;
        if (tail == null || tail.isEmpty()) {
            return;
        }
        Element parent = element.getparent();
        if (include_formatting && element.tag.equals("list") && in_item && !in_cell && parent != null
                && !Py.strip(tail).isEmpty()) {
            // blank line and parent item indent, else CommonMark continues the last sub-item
            returnlist.add("\u2424\n" + " ".repeat(_list_marker(parent).length()) + Py.strip(tail));
            return;
        }
        if (in_cell || in_item || element.tag.equals("list")) {
            String core = Py.strip(tail);
            // keep the source separators, block elements lost theirs during extraction
            if (!core.isEmpty() && (Py.isWhitespace(tail.charAt(0)) || !INLINE_FORMATTABLE.contains(element.tag))) {
                core = " " + core;
            }
            Element nxt = element.getnext();
            if (Py.isWhitespace(tail.charAt(tail.length() - 1)) && nxt != null && INLINE_CARRIED.contains(nxt.tag)) {
                core += " ";
            }
            _append_spaced(returnlist, _escape_cell(core, in_cell));
            return;
        }
        if (NEWLINE_ELEMS.contains(element.tag)) {
            // block elements already end on their own line, so source HTML indentation in the tail is noise
            tail = Py.lstrip(tail);
        }
        returnlist.add(tail);
    }

    /** Recursively convert an element and its children to a flattened string representation. */
    public static void process_element(Element element, List<String> returnlist, boolean include_formatting, boolean in_cell,
            boolean in_item) {
        // in_cell/in_item are inherited down the recursion instead of re-walking ancestors at every node
        in_cell = in_cell || element.tag.equals("cell");
        in_item = in_item || element.tag.equals("item");
        if (element.tag.equals("cell") && element.getprevious() == null) {
            returnlist.add("| ");
        }

        // a block element starts on its own line, not mashed onto preceding loose text (#661)
        if (NEWLINE_ELEMS.contains(element.tag) && !in_cell && !in_item && !SEPARATORS.contains(_last_char(returnlist))) {
            returnlist.add("\n");
        } else if (List.of("head", "p", "quote", "table").contains(element.tag)
                && !Py.isspace(_last_char(returnlist))
                && !SEPARATORS.contains(_last_char(returnlist))
                && (Py.truthy(element.text) || element.size() == 0 || !element.get(0).tag.equals("lb"))) {
            // flattened into an item or cell, it still needs a separator
            returnlist.add(" ");
        }

        boolean _consumes_children = _consumes_inline_children(element);
        boolean _renders_inline = Py.truthy(element.text) || _consumes_children;

        if (element.tag.equals("item") && !in_cell && _item_needs_marker(element)) {
            returnlist.add(_list_marker(element, include_formatting));
        }

        // whitespace-only item text would split the marker from the content
        if (_renders_inline && (!element.tag.equals("item") || _consumes_children || text_chars_test(element.text))) {
            returnlist.add(_escape_cell(replace_element_text(element, include_formatting, in_cell), in_cell));
        } else if (include_formatting && element.tag.equals("head") && !in_cell && element.size() > 0) {
            // heading starting with an inline child still needs its # prefix (children render below)
            returnlist.add(_heading_prefix(element) + " ");
        }

        // a sublist starts on its own line, not mashed onto the parent item
        if (element.tag.equals("list") && in_item && !List.of("\n", "").contains(_last_char(returnlist))) {
            returnlist.add("\n");
        }

        if (!_consumes_children) {
            for (Element child : element) {
                process_element(child, returnlist, include_formatting, in_cell, in_item);
            }
        }

        if (!_renders_inline) {
            if (element.tag.equals("graphic")) {
                returnlist.add(_escape_cell(_image_markup(element, include_formatting), in_cell));
                if (Py.truthy(element.tail)) {
                    returnlist.add(_escape_cell(" " + Py.strip(element.tail), in_cell));
                }
            } else if (NEWLINE_ELEMS.contains(element.tag)) {
                // newlines for textless elements
                if (element.tag.equals("row")) {
                    // add line after table head
                    List<Element> cells = element.findall("cell");
                    // rows are materialized to full width upstream; draw the head separator below
                    if (cells.stream().anyMatch(cell -> "head".equals(cell.get("role")))) {
                        returnlist.add("\n|" + "---|".repeat(cells.size()) + "\n");
                    }
                } else if (!in_cell && !(element.tag.equals("lb") && in_item && _opens_item(element))) {
                    // block elements inside a cell must not inject a row-breaking newline,
                    // a leading line break in an item must not split the marker from the text
                    returnlist.add("\n");
                }
            } else if (!List.of("cell", "item").contains(element.tag)) {
                // cells still need to append vertical bars
                if (in_cell) {
                    _append_tail(element, returnlist, in_cell, in_item, false);
                }
                return;
            }
        }

        boolean last_in_item = in_item && _closes_container(element, "item");
        boolean last_in_cell = in_cell && _closes_container(element, "cell");
        if (NEWLINE_ELEMS.contains(element.tag) && !in_cell && !in_item) {
            returnlist.add(include_formatting && !element.tag.equals("row") ? "\n\u2424\n" : "\n");
        } else if (element.tag.equals("cell")) {
            returnlist.add(" | ");
        } else if (List.of("head", "item").contains(element.tag) && in_cell && !last_in_cell) {
            // separate flattened block elements inside a cell (e.g. list items) instead of mashing them
            _append_spaced(returnlist, " ");
        } else if (!SPECIAL_FORMATTING.contains(element.tag) && !last_in_item && !last_in_cell) {
            _append_spaced(returnlist, " ");
        }

        if (!element.tag.equals("graphic")) { // graphic tail already handled above
            _append_tail(element, returnlist, in_cell, in_item, include_formatting);
        }

        // deal with list items alone
        if (last_in_item && !in_cell) {
            returnlist.add("\n");
        }
    }

    public static void process_element(Element element, List<String> returnlist, boolean include_formatting) {
        process_element(element, returnlist, include_formatting, false, false);
    }

    /** Convert to plain text format and optionally preserve formatting as markdown. */
    public static String xmltotxt(Element xmloutput, boolean include_formatting) {
        if (xmloutput == null) {
            return "";
        }

        List<String> returnlist = new ArrayList<>();

        if (include_formatting) {
            // math rewrite, special-char escaping, emphasis collapse, lb removal mutate the tree; protect caller's copy
            xmloutput = xmloutput.copy();
            _escape_and_convert_math_tree(xmloutput);
            _collapse_emphasis(xmloutput);
            _merge_adjacent_hi(xmloutput);
            _strip_block_whitespace(xmloutput);
        }
        process_element(xmloutput, returnlist, include_formatting);

        String sanitized = sanitize(String.join("", returnlist), true, false);
        return Py.unescape(Py.or(sanitized, ""));
    }

    /** {@code csv.writer(delimiter=delim, quoting=csv.QUOTE_MINIMAL).writerow(row)} */
    public static String csvRow(List<String> row, String delim) {
        List<String> fields = new ArrayList<>();
        for (String field : row) {
            if (field.contains(delim) || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
                field = "\"" + field.replace("\"", "\"\"") + "\"";
            }
            fields.add(field);
        }
        return String.join(delim, fields) + "\r\n";
    }

    /** Convert the internal XML document representation to a CSV string. */
    public static String xmltocsv(Document document, boolean include_formatting, String delim, String nullValue) {
        // preprocessing
        String posttext = Py.or(xmltotxt(document.body, include_formatting), nullValue);
        String commentstext = Py.or(xmltotxt(document.commentsbody, include_formatting), nullValue);

        // organize fields
        List<String> row = new ArrayList<>();
        for (String d : java.util.Arrays.asList(
                document.url,
                document.id,
                document.fingerprint,
                document.hostname,
                document.title,
                document.image,
                document.date,
                posttext,
                commentstext,
                document.license,
                document.pagetype)) {
            row.add(Py.or(d, nullValue));
        }
        return csvRow(row, delim);
    }

    public static String xmltocsv(Document document, boolean include_formatting) {
        return xmltocsv(document, include_formatting, "\t", "null");
    }

    /** Bundle the extracted post and comments into a TEI tree. */
    public static Element write_teitree(Document docmeta) {
        Element teidoc = Etree.Element("TEI", "xmlns", "http://www.tei-c.org/ns/1.0");
        write_fullheader(teidoc, docmeta);
        Element textelem = Etree.SubElement(teidoc, "text");
        Element textbody = Etree.SubElement(textelem, "body");
        for (Pair<Element, String> pair : List.of(Pair.of(docmeta.body, "entry"), Pair.of(docmeta.commentsbody, "comments"))) {
            Element body = pair.first();
            clean_attributes(body).tag = "div";
            body.set("type", pair.second());
            textbody.append(body);
        }
        return teidoc;
    }

    /** Construct a publisher string to include in TEI header. */
    public static String _define_publisher_string(Document docmeta) {
        String publisher;
        if (Py.truthy(docmeta.hostname) && Py.truthy(docmeta.sitename)) {
            publisher = Py.strip(docmeta.sitename) + " (" + docmeta.hostname + ")";
        } else {
            publisher = Py.or(Py.or(docmeta.hostname, docmeta.sitename), "N/A");
            if (publisher.equals("N/A")) {
                LOGGER.warn("no publisher for URL {}", docmeta.url);
            }
        }
        return publisher;
    }

    /** {@code ", ".join(filter(None, values))} */
    public static String joinNonEmpty(String separator, String... values) {
        List<String> kept = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                kept.add(v);
            }
        }
        return String.join(separator, kept);
    }

    /** Write TEI header based on gathered metadata. */
    public static Element write_fullheader(Element teidoc, Document docmeta) {
        // todo: add language info
        Element header = Etree.SubElement(teidoc, "teiHeader");
        Element filedesc = Etree.SubElement(header, "fileDesc");
        Element bib_titlestmt = Etree.SubElement(filedesc, "titleStmt");
        Etree.SubElement(bib_titlestmt, "title", "type", "main").text = docmeta.title;
        if (Py.truthy(docmeta.author)) {
            Etree.SubElement(bib_titlestmt, "author").text = docmeta.author;
        }

        Element publicationstmt_a = Etree.SubElement(filedesc, "publicationStmt");
        String publisher_string = _define_publisher_string(docmeta);
        // license, if applicable
        if (Py.truthy(docmeta.license)) {
            Etree.SubElement(publicationstmt_a, "publisher").text = publisher_string;
            Element availability = Etree.SubElement(publicationstmt_a, "availability");
            Etree.SubElement(availability, "p").text = docmeta.license;
        } else {
            // insert an empty paragraph for conformity
            Etree.SubElement(publicationstmt_a, "p");
        }

        Element notesstmt = Etree.SubElement(filedesc, "notesStmt");
        if (Py.truthy(docmeta.id)) {
            Etree.SubElement(notesstmt, "note", "type", "id").text = docmeta.id;
        }
        Etree.SubElement(notesstmt, "note", "type", "fingerprint").text = docmeta.fingerprint;

        Element sourcedesc = Etree.SubElement(filedesc, "sourceDesc");
        Element source_bibl = Etree.SubElement(sourcedesc, "bibl");

        String sigle = joinNonEmpty(", ", docmeta.sitename, docmeta.date);
        if (sigle.isEmpty()) {
            LOGGER.warn("no sigle for URL {}", docmeta.url);
        }
        source_bibl.text = joinNonEmpty(", ", docmeta.title, sigle);
        Etree.SubElement(sourcedesc, "bibl", "type", "sigle").text = sigle;

        Element biblfull = Etree.SubElement(sourcedesc, "biblFull");
        bib_titlestmt = Etree.SubElement(biblfull, "titleStmt");
        Etree.SubElement(bib_titlestmt, "title", "type", "main").text = docmeta.title;
        if (Py.truthy(docmeta.author)) {
            Etree.SubElement(bib_titlestmt, "author").text = docmeta.author;
        }

        Element publicationstmt = Etree.SubElement(biblfull, "publicationStmt");
        Etree.SubElement(publicationstmt, "publisher").text = publisher_string;
        if (Py.truthy(docmeta.url)) {
            Etree.SubElement(publicationstmt, "ptr", "type", "URL", "target", docmeta.url);
        }
        Etree.SubElement(publicationstmt, "date").text = docmeta.date;

        Element profiledesc = Etree.SubElement(header, "profileDesc");
        Element abstract_ = Etree.SubElement(profiledesc, "abstract");
        Etree.SubElement(abstract_, "p").text = docmeta.description;

        if (Py.truthy(docmeta.categories) || Py.truthy(docmeta.tags)) {
            Element textclass = Etree.SubElement(profiledesc, "textClass");
            Element keywords = Etree.SubElement(textclass, "keywords");
            if (Py.truthy(docmeta.categories)) {
                Etree.SubElement(keywords, "term", "type", "categories").text = String.join(",", docmeta.categories);
            }
            if (Py.truthy(docmeta.tags)) {
                Etree.SubElement(keywords, "term", "type", "tags").text = String.join(",", docmeta.tags);
            }
        }

        Element creation = Etree.SubElement(profiledesc, "creation");
        Etree.SubElement(creation, "date", "type", "download").text = docmeta.filedate;

        Element encodingdesc = Etree.SubElement(header, "encodingDesc");
        Element appinfo = Etree.SubElement(encodingdesc, "appInfo");
        Element application = Etree.SubElement(appinfo, "application", "version", PKG_VERSION, "ident", "Trafilatura");
        Etree.SubElement(application, "label").text = "Trafilatura";
        Etree.SubElement(application, "ptr", "target", "https://github.com/adbar/trafilatura");

        return header;
    }

    /** Wrap loose text in <div> within <p> elements for TEI conformity. */
    public static void _handle_text_content_of_div_nodes(Element element) {
        if (Py.truthy(element.text) && !Py.strip(element.text).isEmpty()) {
            if (element.size() > 0 && element.get(0).tag.equals("p")) {
                element.get(0).text = Py.strip(element.text + " " + Py.or(element.get(0).text, ""));
            } else {
                Element new_child = Etree.Element("p");
                new_child.text = element.text;
                element.insert(0, new_child);
            }
            element.text = null;
        }

        if (Py.truthy(element.tail) && !Py.strip(element.tail).isEmpty()) {
            if (element.size() > 0 && element.get(-1).tag.equals("p")) {
                element.get(-1).text = Py.strip(Py.or(element.get(-1).text, "") + " " + element.tail);
            } else {
                Element new_child = Etree.Element("p");
                new_child.text = element.tail;
                element.append(new_child);
            }
            element.tail = null;
        }
    }

    /** Handle tail on p and ab elements. */
    public static void _handle_unwanted_tails(Element element) {
        element.tail = Py.truthy(element.tail) ? Py.strip(element.tail) : null;
        if (!Py.truthy(element.tail)) {
            return;
        }

        if (element.tag.equals("p")) {
            element.text = joinNonEmpty(" ", element.text, element.tail);
        } else {
            Element new_sibling = Etree.Element("p");
            new_sibling.text = element.tail;
            element.addnext(new_sibling);
        }
        element.tail = null;
    }

    /** Convert certain child elements to <ab> and <lb>. */
    public static Element _tei_handle_complex_head(Element element) {
        Element new_element = Etree.Element("ab", element.attrib);
        new_element.text = Py.truthy(element.text) ? Py.strip(element.text) : null;
        for (Element child : element.iterchildren()) {
            if (child.tag.equals("p")) {
                if (new_element.size() > 0 || Py.truthy(new_element.text)) {
                    // add <lb> if <ab> has no children or last tail contains text
                    if (new_element.size() == 0 || Py.truthy(new_element.get(-1).tail)) {
                        Etree.SubElement(new_element, "lb");
                    }
                    new_element.get(-1).tail = child.text;
                } else {
                    new_element.text = child.text;
                }
            } else {
                new_element.append(child);
            }
        }
        String tail = Py.truthy(element.tail) ? Py.strip(element.tail) : null;
        if (Py.truthy(tail)) {
            new_element.tail = tail;
        }
        return new_element;
    }

    /** Wrap each run of block siblings following a div element in a new div element. */
    public static void _wrap_unwanted_siblings_of_div(Element div_element) {
        // other elements (e.g. <lb/>) end a run, so the order of elements is kept
        List<List<Element>> runs = new ArrayList<>();
        runs.add(new ArrayList<>());
        for (Element sibling : div_element.itersiblings()) {
            if (sibling.tag.equals("div")) {
                break;
            }
            List<Element> last = runs.get(runs.size() - 1);
            if (TEI_DIV_SIBLINGS.contains(sibling.tag)) {
                last.add(sibling);
            } else if (!last.isEmpty()) {
                runs.add(new ArrayList<>());
            }
        }
        for (List<Element> run : runs) {
            if (run.isEmpty()) {
                continue;
            }
            Element wrapper = Etree.Element("div");
            run.get(0).addprevious(wrapper);
            wrapper.extend(run);
        }
    }

    /**
     * Fix TEI compatibility issues by moving certain p-elems up in the XML tree.
     * There is always a n+2 nesting for p-elements with the minimal structure ./TEI/text/body/p
     */
    public static void _move_element_one_level_up(Element element) {
        Element parent = element.getparent();
        Element grand_parent = parent != null ? parent.getparent() : null;
        if (parent == null || grand_parent == null) {
            return;
        }

        Element new_elem = Etree.Element("p");
        new_elem.extend(element.itersiblings());

        parent.addnext(element);

        String tail = Py.truthy(element.tail) ? Py.strip(element.tail) : null;
        if (Py.truthy(tail)) {
            new_elem.text = tail;
            element.tail = null;
        }

        tail = Py.truthy(parent.tail) ? Py.strip(parent.tail) : null;
        if (Py.truthy(tail)) {
            new_elem.tail = tail;
            parent.tail = null;
        }

        if (new_elem.size() > 0 || Py.truthy(new_elem.text) || Py.truthy(new_elem.tail)) {
            element.addnext(new_elem);
        }

        if (parent.size() == 0 && !Py.truthy(parent.text)) {
            grand_parent.remove(parent);
        }
    }
}
