// Java port of trafilatura/main_extractor.py
package io.github.danielonet.trafilatura;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.danielonet.trafilatura.compat.Pair;
import io.github.danielonet.trafilatura.compat.Py;
import io.github.danielonet.trafilatura.compat.Triple;
import io.github.danielonet.trafilatura.courlan.Courlan;
import io.github.danielonet.trafilatura.lxml.Element;
import io.github.danielonet.trafilatura.lxml.Etree;
import io.github.danielonet.trafilatura.lxml.XPath;
import io.github.danielonet.trafilatura.Settings.Extractor;

import static io.github.danielonet.trafilatura.Htmlprocessing.delete_by_link_density;
import static io.github.danielonet.trafilatura.Htmlprocessing.handle_textnode;
import static io.github.danielonet.trafilatura.Htmlprocessing.link_density_test_tables;
import static io.github.danielonet.trafilatura.Htmlprocessing.process_node;
import static io.github.danielonet.trafilatura.Htmlprocessing.prune_unwanted_nodes;
import static io.github.danielonet.trafilatura.Settings.INLINE_CARRIED;
import static io.github.danielonet.trafilatura.Settings.INLINE_CONSUMING;
import static io.github.danielonet.trafilatura.Settings.INLINE_FORMATTABLE;
import static io.github.danielonet.trafilatura.Settings.MIN_DUPLICATE_LENGTH;
import static io.github.danielonet.trafilatura.Settings.TAG_CATALOG;
import static io.github.danielonet.trafilatura.Utils.FORMATTING_PROTECTED;
import static io.github.danielonet.trafilatura.Utils.SPACING_PROTECTED;
import static io.github.danielonet.trafilatura.Utils.image_src;
import static io.github.danielonet.trafilatura.Utils.text_chars_test;
import static io.github.danielonet.trafilatura.Utils.trim;
import static io.github.danielonet.trafilatura.Xml.delete_element;
import static io.github.danielonet.trafilatura.Xml.separates_inline;
import static io.github.danielonet.trafilatura.Xpaths.BODY_XPATH;
import static io.github.danielonet.trafilatura.Xpaths.COMMENTS_DISCARD_XPATH;
import static io.github.danielonet.trafilatura.Xpaths.COMMENTS_XPATH;
import static io.github.danielonet.trafilatura.Xpaths.DISCARD_IMAGE_ELEMENTS;
import static io.github.danielonet.trafilatura.Xpaths.OVERALL_DISCARD_XPATH;
import static io.github.danielonet.trafilatura.Xpaths.PRECISION_DISCARD_XPATH;
import static io.github.danielonet.trafilatura.Xpaths.TEASER_DISCARD_XPATH;

/** Functions related to the main Trafilatura extractor. */
public final class MainExtractor {
    private MainExtractor() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(MainExtractor.class);

    /** Module-level copy of Settings.DEDUPE_SCAN_CAP (tests patch it, like the Python module attribute). */
    public static int DEDUPE_SCAN_CAP = Settings.DEDUPE_SCAN_CAP;

    public static final Set<String> TABLE_ELEMS = Set.of("td", "th");
    // meaningful internal attributes to carry onto a rewired sub-element (drop stray class/style/width/etc.)
    public static final Set<String> KEEP_ATTRS = Set.of("rend", "role", "target", "src", "alt", "title");
    public static final Set<String> CODES_QUOTES = Set.of("code", "quote");
    // blocks rebuilt as new elements by handle_textelem(), which leaves the source tail behind
    public static final Set<String> REBUILT_BLOCKS = Settings.union(CODES_QUOTES, Set.of("list", "table"));
    public static final Set<String> NOT_AT_THE_END = Set.of("head", "ref");
    // tags allowed inside a blockquote paragraph
    public static final Set<String> _QUOTE_TAGS = Settings.union(TAG_CATALOG, Set.of("ref", "graphic"));

    /**
     * Text rendering for the recovery/adjacent dedup checks here: plain concatenation, so
     * inline-tag boundaries stay joined ("Hyper<b>link</b>ed" -> "Hyperlinked"). Both sides of
     * these checks must use it, or invented spaces defeat the comparison.
     */
    public static String _elem_text(Element element) {
        return trim(String.join("", element.itertext()));
    }

    /** A formatting element whose children must be carried verbatim: ref, or hi/del wrapping inline content. */
    public static boolean _wraps_inline(Element element) {
        if (element.size() == 0) {
            return false;
        }
        if (element.tag.equals("ref")) {
            return true;
        }
        for (Element c : element) {
            if (INLINE_CARRIED.contains(c.tag)) {
                return true;
            }
        }
        return false;
    }

    /** Format extraction event for debugging purposes. */
    public static void _log_event(String msg, Object tag, String text) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("{}: {} {}", msg, tag, Py.or(trim(Py.or(text, "")), "None"));
        }
    }

    /** Process head elements (titles). */
    public static Element handle_titles(Element element, Extractor options) {
        Element title;
        if (element.size() == 0) {
            title = process_node(element, options);
        } else {
            title = element.copy();
            // children already consumed by an earlier pass over the same tree
            Etree.strip_elements(title, "done");
            for (Element child : element.iterdescendants("*")) {
                child.tag = "done";
            }
        }
        return is_text_element(title) ? title : null;
    }

    /** Process formatting elements (b, i, etc. converted to hi) found outside of paragraphs. */
    public static Element handle_formatting(Element element, Extractor options) {
        Element formatting = process_node(element, options);
        if (formatting == null) {
            return null;
        }

        Element parent = element.getparent();
        if (parent != null && FORMATTING_PROTECTED.contains(parent.tag)) {
            return formatting;
        }
        // repair orphan elements
        Element wrapper = Etree.Element("p");
        wrapper.append(formatting);
        return wrapper;
    }

    /** Iterate through an element child and rewire its descendants. */
    public static void process_nested_elements(Element child, Element new_child_elem, Extractor options) {
        new_child_elem.text = child.text;
        for (Element subelem : child.iterdescendants("*")) {
            if (subelem.tag.equals("list")) {
                _append_block(new_child_elem, handle_lists(subelem, options), subelem);
            } else if (subelem.tag.equals("p") && subelem.size() > 0) {
                _append_block(new_child_elem, handle_paragraphs(subelem, _QUOTE_TAGS, options), subelem);
            } else if (subelem.tag.equals("graphic")) {
                _append_block(new_child_elem, handle_image(subelem, options), subelem);
            } else if (INLINE_CARRIED.contains(subelem.tag)) {
                define_newelem(subelem, new_child_elem, true);
            } else {
                if (subelem.size() > 0 && text_chars_test(subelem.tail)) {
                    // the tail follows the children, which are appended after this element
                    Element last = subelem.get(-1);
                    last.tail = Py.or(last.tail, "") + subelem.tail;
                    subelem.tail = null;
                }
                define_newelem(handle_textnode(subelem, options, false, false), new_child_elem);
            }
            subelem.tag = "done";
        }
    }

    /** Append a processed block with the source tail, or keep that tail as text after the parent's content. */
    public static void _append_block(Element parent, Element processed, Element source) {
        if (processed != null) {
            processed.tail = source.tail;
            parent.append(processed);
        } else if (Py.truthy(source.tail) && text_chars_test(source.tail)) {
            Element last = parent.size() > 0 ? parent.get(-1) : null;
            String before = Py.or(last == null ? parent.text : last.tail, "");
            String tail = source.tail;
            // a dropped block still separates the text around it, a dropped image does not
            if (!source.tag.equals("graphic") && (!before.isEmpty() || last != null)
                    && !Py.isspace(Py.slice(before, -1, null)) && !Py.isWhitespace(tail.charAt(0))) {
                tail = " " + tail;
            }
            if (last == null) {
                parent.text = before + tail;
            } else {
                last.tail = before + tail;
            }
        }
    }

    /** Copy the rend attribute from an existing element to a new one. */
    public static void update_elem_rendition(Element elem, Element new_elem) {
        String rend_attr = elem.get("rend");
        if (Py.truthy(rend_attr)) {
            new_elem.set("rend", rend_attr);
        }
    }

    /** Find if the element contains text. */
    public static boolean is_text_element(Element elem) {
        return elem != null && text_chars_test(String.join("", elem.itertext()));
    }

    /** Carry the meaningful internal attributes over to a rewired element. */
    public static void _copy_attrs(Element source, Element target) {
        for (Map.Entry<String, String> entry : source.items()) {
            if (KEEP_ATTRS.contains(entry.getKey())) {
                target.set(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Create a new sub-element, optionally carrying its inline children (INLINE_CARRIED). */
    public static void define_newelem(Element processed_elem, Element orig_elem, boolean keep_children) {
        if (processed_elem != null) {
            Element childelem = Etree.SubElement(orig_elem, processed_elem.tag);
            childelem.text = processed_elem.text;
            childelem.tail = processed_elem.tail;
            _copy_attrs(processed_elem, childelem);
            if (keep_children) {
                for (Element sub : processed_elem) {
                    if (INLINE_CARRIED.contains(sub.tag) || sub.tag.equals("lb")) {
                        define_newelem(sub, childelem, true);
                        // mark only the carried subtree done; non-carried siblings (e.g. nested <p>) stay processable
                        for (Element carried : sub.iter("*")) {
                            carried.tag = "done";
                        }
                    }
                }
            }
        }
    }

    public static void define_newelem(Element processed_elem, Element orig_elem) {
        define_newelem(processed_elem, orig_elem, false);
    }

    /** Process lists elements including their descendants. */
    public static Element handle_lists(Element element, Extractor options) {
        Element processed_element = Etree.Element(element.tag);

        if (text_chars_test(element.text)) {
            Element new_child_elem = Etree.SubElement(processed_element, "item");
            new_child_elem.text = element.text;
        }

        for (Element child : element.iterdescendants("item")) {
            // items of a nested list are processed by the recursive call and renamed "done",
            // but lxml fetches the first of them before that happens: skip it here
            if (child.tag.equals("done")) {
                continue;
            }
            Element new_child_elem = Etree.Element("item");
            if (child.size() == 0) {
                Element processed_child = process_node(child, options);
                if (processed_child != null) {
                    new_child_elem.text = Xml.joinNonEmpty(" ", processed_child.text, processed_child.tail);
                }
            } else {
                process_nested_elements(child, new_child_elem, options);
                if (text_chars_test(child.tail) && new_child_elem.size() > 0) {
                    Element last = new_child_elem.get(-1);
                    last.tail = text_chars_test(last.tail) ? last.tail + " " + child.tail : child.tail;
                }
            }
            if (is_text_element(new_child_elem) || !new_child_elem.iter("graphic").isEmpty()) {
                update_elem_rendition(child, new_child_elem);
                processed_element.append(new_child_elem);
            }
            child.tag = "done";
        }
        element.tag = "done";
        if (is_text_element(processed_element)) {
            update_elem_rendition(element, processed_element);
            return processed_element;
        }
        return null;
    }

    /** Check if it is a code element according to common structural markers. */
    public static boolean is_code_block_element(Element element) {
        // pip
        if (Py.truthy(element.get("lang")) || element.tag.equals("code")) {
            return true;
        }
        // GitHub
        Element parent = element.getparent();
        if (parent != null && parent.get("class", "").contains("highlight")) {
            return true;
        }
        // highlightjs
        Element code = element.find("code");
        return code != null && element.size() == 1 && Py.strip(Py.or(element.text, "")).isEmpty()
                && Py.strip(Py.or(code.tail, "")).isEmpty();
    }

    /** Turn element into a properly tagged code block. */
    public static Element handle_code_blocks(Element element) {
        Element processed_element = element.copy();
        for (Element child : element.iter("*")) {
            child.tag = "done";
        }
        processed_element.tag = "code";
        return processed_element;
    }

    /** Process quotes elements. */
    public static Element handle_quotes(Element element, Extractor options) {
        if (is_code_block_element(element)) {
            return handle_code_blocks(element);
        }

        Element processed_element = Etree.Element(element.tag);
        process_nested_elements(element, processed_element, options);
        if (is_text_element(processed_element)) {
            // avoid double/nested tags
            Etree.strip_tags(processed_element, "quote");
            return processed_element;
        }
        return null;
    }

    /** Handle diverse or unknown elements in the scope of relevant tags. */
    public static Element handle_other_elements(Element element, Set<String> potential_tags, Extractor options) {
        // handle w3schools code
        if (element.tag.equals("div") && element.get("class", "").contains("w3-code")) {
            return handle_code_blocks(element);
        }

        if (!element.tag.equals("div") || !potential_tags.contains("div")) {
            if (!element.tag.equals("done")) {
                _log_event("discarding element", element.tag, element.text);
            }
            return null;
        }

        Element processed_element = handle_textnode(element, options, false, true);
        if (processed_element == null || !text_chars_test(processed_element.text)) {
            return null;
        }
        processed_element.attrib.clear();
        processed_element.tag = "p";
        return processed_element;
    }

    /** Process paragraphs along with their children, trim and clean the content. */
    public static Element handle_paragraphs(Element element, Set<String> potential_tags, Extractor options) {
        // no children
        if (element.size() == 0) {
            return process_node(element, options);
        }

        // children
        Element processed_element = Etree.Element(element.tag);
        // unexpected children: keep their text in place
        Set<String> unexpected = new LinkedHashSet<>();
        for (Element c : element.iterdescendants("*")) {
            if (!potential_tags.contains(c.tag) && !c.tag.equals("done")) {
                unexpected.add(c.tag);
            }
        }
        Etree.strip_tags(element, unexpected);
        for (Element child : element.iter("*")) {
            // todo: act on spacing here?
            Element processed_child = handle_textnode(child, options, false, true);
            if (processed_child != null) {
                // todo: needing attention!
                if (processed_child.tag.equals("p")) {
                    _log_event("extra in p", "p", processed_child.text);
                    processed_element.text = Xml.joinNonEmpty(" ", processed_element.text, processed_child.text);
                    child.tag = "done";
                    continue;
                }
                // handle formatting
                Element newsub = Etree.Element(child.tag);
                if (INLINE_CONSUMING.contains(processed_child.tag)) {
                    // carry inline children verbatim (ref/hi/del wrapping formatting)
                    if (_wraps_inline(processed_child)) {
                        define_newelem(processed_child, processed_element, true);
                        child.tag = "done";
                        continue;
                    }
                    // check depth and clean
                    Set<String> itemTags = new LinkedHashSet<>();
                    for (Element item : processed_child) {
                        if (item.tag.equals("lb") && Py.truthy(item.tail)) {
                            item.tail = " " + Py.lstrip(item.tail);
                        } else if (text_chars_test(item.text)) {
                            item.text = " " + item.text;
                        }
                        itemTags.add(item.tag);
                    }
                    Etree.strip_tags(processed_child, itemTags);
                    _copy_attrs(child, newsub);
                }
                newsub.text = processed_child.text;
                newsub.tail = processed_child.tail;

                if (processed_child.tag.equals("graphic")) {
                    Element image_elem = handle_image(processed_child, options);
                    if (image_elem != null) {
                        newsub = image_elem;
                    }
                }
                processed_element.append(newsub);
            }
            child.tag = "done";
        }
        // finish
        if (processed_element.size() > 0) {
            Element last_elem = processed_element.get(-1);
            // clean trailing lb-elements
            if (last_elem.tag.equals("lb") && last_elem.tail == null) {
                delete_element(last_elem);
            }
            return processed_element;
        }
        // text_chars_test, not truthiness: layout wrappers (clearfix/spacer divs, cleared-out
        // ad slots) leave indentation-only text, which used to be emitted as blank paragraphs
        // and, sitting between two copies of a paragraph, also defeated the adjacent dedup
        if (text_chars_test(processed_element.text)) {
            return processed_element;
        }
        _log_event("discarding element:", "p", Etree.tostring(processed_element));
        return null;
    }

    /** Determine cell element type and mint new element. */
    public static Element define_cell_type(boolean is_header) {
        Element cell_element = Etree.Element("cell");
        if (is_header) {
            cell_element.set("role", "head");
        }
        return cell_element;
    }

    public static final int _MAX_SPAN = 100;

    /** Parse a cell's col/rowspan, defaulting to 1, capped at _MAX_SPAN. */
    public static int _span(Element cell, String attr) {
        // isdecimal, not isdigit: int() rejects the superscripts isdigit() admits
        String value = cell.get(attr, "1");
        if (!Py.isdigit(value)) {
            return 1;
        }
        try {
            return Math.min(Integer.parseInt(value), _MAX_SPAN);
        } catch (NumberFormatException err) {
            // larger than an int: capped anyway
            return _MAX_SPAN;
        }
    }

    /** Insert empty placeholder cells for rowspan-occupied columns at the current row position. */
    public static void _flush_rowspan_phantoms(Map<Integer, Integer> rowspan_map, Element newrow) {
        int col;
        while (rowspan_map.containsKey(col = newrow.size())) {
            newrow.append(define_cell_type(false));
            rowspan_map.put(col, rowspan_map.get(col) - 1);
            if (rowspan_map.get(col) == 0) {
                rowspan_map.remove(col);
            }
        }
    }

    /** Close a row: insert trailing rowspan placeholders, pad to width, append if non-empty. */
    public static void _finalize_row(Element newtable, Element newrow, Map<Integer, Integer> rowspan_map, int max_cols) {
        _flush_rowspan_phantoms(rowspan_map, newrow);
        while (newrow.size() < max_cols) {
            newrow.append(define_cell_type(false));
        }
        for (Element cell : newrow) {
            if (Py.truthy(cell.text) || cell.size() > 0) {
                newtable.append(newrow);
                return;
            }
        }
    }

    /** Extract a source td/th cell's content into the new <cell>, rewiring inline and block children. */
    public static void _fill_cell(Element new_child_elem, Element cell, Set<Element> nested_elems, Set<String> ptags_with_div,
            Extractor options) {
        if (cell.size() == 0) {
            Element processed_cell = process_node(cell, options);
            if (processed_cell != null) {
                new_child_elem.text = processed_cell.text;
                new_child_elem.tail = processed_cell.tail;
            }
            return;
        }
        new_child_elem.text = cell.text;
        new_child_elem.tail = cell.tail;
        cell.tag = "done"; // rename before inner walk so handle_formatting wraps orphan spans in <p>
        for (Element child : cell.iterdescendants()) {
            if (child.tag.equals("done")) {
                continue;
            }
            if (nested_elems.contains(child)) {
                // nested tables are left to the main loop, their tail moves to the cell holding them
                if (child.tag.equals("table") && !nested_elems.contains(child.firstancestor("table"))) {
                    _append_block(new_child_elem, null, child);
                    child.tail = null;
                }
                continue;
            }
            if (separates_inline(child) && !text_chars_test(child.tail)) {
                new_child_elem.append(Etree.Element("lb"));
                child.tag = "done";
                continue;
            }
            Element processed_subchild;
            if (TABLE_ELEMS.contains(child.tag)) { // stray cell from malformed HTML
                child.tag = "cell";
                processed_subchild = handle_textnode(child, options, true, true);
            } else if (INLINE_CONSUMING.contains(child.tag)) {
                processed_subchild = handle_textnode(child, options, true, true);
                // handle_textnode drops inline wrappers (ref/hi/del) with children but no direct
                // text (e.g. <ref><hi>link text</hi></ref>); carry the subtree directly instead
                if (processed_subchild == null && child.size() > 0) {
                    processed_subchild = child;
                }
            } else if (child.tag.equals("list")) {
                // lists in cells only in recall mode: keeping them otherwise is noise (measured precision loss)
                Element processed_list = handle_lists(child, options);
                _append_block(new_child_elem, options.focus.equals("recall") ? processed_list : null, child);
                continue;
            } else {
                processed_subchild = handle_textelem(child, ptags_with_div, options);
            }
            define_newelem(processed_subchild, new_child_elem, true);
            child.tag = "done";
        }
    }

    /** Process single table element. */
    public static Element handle_table(Element table_elem, Set<String> potential_tags, Extractor options) {
        Element newtable = Etree.Element("table");
        Set<String> ptags_with_div = Settings.union(potential_tags, Set.of("div"));

        // strip these structural elements
        Etree.strip_tags(table_elem, "thead", "tbody", "tfoot");

        // Collect elements inside nested <table> descendants. Used only in the inner cell
        // walk: skip without "done"-marking so the main extraction loop can call handle_table()
        // on each nested table separately.
        Set<Element> nested_elems = new HashSet<>();
        for (Element nested_table : table_elem.iterdescendants("table")) {
            nested_elems.addAll(nested_table.iter());
        }

        // direct children only, nested tables are left to the main loop.
        // Orphan cells join the current row but do not count for max_cols.
        List<List<Element>> rows = new ArrayList<>();
        rows.add(new ArrayList<>());
        List<String> captions = new ArrayList<>();
        int max_cols = 0;
        for (Element elem : table_elem) {
            if (elem.tag.equals("tr")) {
                List<Element> cells = new ArrayList<>();
                for (Element cell : elem) {
                    if (TABLE_ELEMS.contains(cell.tag)) {
                        cells.add(cell);
                    }
                }
                rows.add(cells);
                int width = 0;
                for (Element cell : cells) {
                    width += _span(cell, "colspan");
                }
                max_cols = Math.max(max_cols, width);
            } else if (TABLE_ELEMS.contains(elem.tag)) {
                rows.get(rows.size() - 1).add(elem);
                continue;
            } else if (elem.tag.equals("caption")) {
                captions.add(Py.strip(String.join(" ", elem.itertext())));
                // text consumed above, not to be emitted again, images are left to the main loop
                for (Element sub : elem.iterdescendants("*")) {
                    if (sub.tag.equals("graphic")) {
                        sub.tail = null;
                    } else {
                        sub.tag = "done";
                    }
                }
            } else if (elem.tag.equals("table")) {
                continue;
            }
            elem.tag = "done";
        }
        max_cols = Math.min(max_cols, _MAX_SPAN);

        for (String caption_text : captions) {
            if (caption_text.isEmpty()) {
                continue;
            }
            Element caption_cell = define_cell_type(true);
            caption_cell.text = caption_text;
            Element caption_row = Etree.Element("row");
            caption_row.append(caption_cell);
            _finalize_row(newtable, caption_row, new HashMap<>(), max_cols);
        }

        boolean header_row_emitted = false;
        Map<Integer, Integer> rowspan_map = new HashMap<>(); // col_idx → rows still spanned from a rowspan cell
        for (List<Element> cells : rows) {
            Element newrow = Etree.Element("row");
            boolean row_has_th = false;
            for (Element cell : cells) {
                boolean is_header = cell.tag.equals("th") && !header_row_emitted;
                row_has_th = row_has_th || is_header;
                _flush_rowspan_phantoms(rowspan_map, newrow);
                Element new_child_elem = define_cell_type(is_header);
                int colspan = _span(cell, "colspan");
                // Track rowspan: mark all spanned columns as occupied for subsequent rows
                int rowspan = _span(cell, "rowspan");
                if (rowspan > 1) {
                    for (int c = newrow.size(); c < newrow.size() + colspan; c++) {
                        rowspan_map.put(c, rowspan - 1);
                    }
                }
                _fill_cell(new_child_elem, cell, nested_elems, ptags_with_div, options);
                // add to tree (keep empty cells so column positions stay aligned)
                newrow.append(new_child_elem);
                // inline colspan: pad with empty cells so subsequent rows align
                for (int i = 0; i < colspan - 1; i++) {
                    newrow.append(define_cell_type(is_header));
                }
                cell.tag = "done";
            }
            _finalize_row(newtable, newrow, rowspan_map, max_cols);
            header_row_emitted = header_row_emitted || row_has_th;
        }
        return newtable.size() > 0 ? newtable : null;
    }

    /** Process image elements and their relevant attributes. */
    public static Element handle_image(Element element, Extractor options) {
        String link = image_src(element);
        if (link == null) {
            return null;
        }
        if (!link.startsWith("http")) {
            if (options != null && options.url != null) {
                link = Courlan.urljoin(options.url, link);
            } else {
                link = link.replaceFirst("^//", "http://");
            }
        }

        Element processed_element = Etree.Element(element.tag);
        processed_element.set("src", link);
        String alt_attr = element.get("alt");
        if (Py.truthy(alt_attr)) {
            processed_element.set("alt", alt_attr);
        }
        String title_attr = element.get("title");
        if (Py.truthy(title_attr)) {
            processed_element.set("title", title_attr);
        }
        processed_element.tail = element.tail;
        return processed_element;
    }

    public static Element handle_image(Element element) {
        return handle_image(element, null);
    }

    /** Process text element and determine how to deal with its content. */
    public static Element handle_textelem(Element element, Set<String> potential_tags, Extractor options) {
        Element new_element = null;
        // bypass: nested elements
        if (element.tag.equals("list")) {
            new_element = handle_lists(element, options);
        } else if (CODES_QUOTES.contains(element.tag)) {
            new_element = handle_quotes(element, options);
        } else if (element.tag.equals("head")) {
            new_element = handle_titles(element, options);
        } else if (element.tag.equals("p")) {
            new_element = handle_paragraphs(element, potential_tags, options);
        } else if (element.tag.equals("lb")) {
            if (text_chars_test(element.tail)) {
                Element this_element = process_node(element, options);
                if (this_element != null) {
                    new_element = Etree.Element("p");
                    new_element.text = this_element.tail;
                }
            }
        } else if (INLINE_CONSUMING.contains(element.tag)) {
            new_element = handle_formatting(element, options); // process_node(element, options)
        } else if (element.tag.equals("table") && potential_tags.contains("table")) {
            new_element = handle_table(element, potential_tags, options);
        } else if (element.tag.equals("graphic") && potential_tags.contains("graphic")) {
            new_element = handle_image(element, options);
        } else {
            // other elements (div, ??, ??)
            new_element = handle_other_elements(element, potential_tags, options);
        }
        return new_element;
    }

    /**
     * Look for all previously unconsidered wild elements, including outside of the determined
     * frame and throughout the document to recover potentially missing text parts.
     *
     * Do not widen `search_expr` (e.g. headings, more div shapes) without benchmarking the
     * full suite: extra recovered text increases the extracted length, which can suppress
     * the stronger rescues that run after this one.
     */
    public static Element recover_wild_text(Element tree, Element result_body, Extractor options,
            Set<String> potential_tags, Set<Element> consumed) {
        LOGGER.debug("Recovering wild text elements");
        // copy: the recall branch below mutates potential_tags, must not leak back to the caller
        potential_tags = new HashSet<>(potential_tags == null ? TAG_CATALOG : potential_tags);
        // blockquote/pre/q are already renamed to quote/code by convert_tags before this tree is built
        String search_expr = ".//code|.//p|.//quote|.//table|.//div[contains(@class, 'w3-code')]";
        if (options.focus.equals("recall")) {
            potential_tags.addAll(List.of("div", "lb"));
            search_expr += "|.//div|.//lb|.//list";
        }
        // prune; in fast mode (no external comparator to defer to) keep teaser-class blocks, some of
        // which are real content — this is the last-resort path after the confident extractor failed
        Element search_tree = prune_unwanted_sections(tree, potential_tags, options, options.fast);
        List<Element> subelems = search_tree.xpath(search_expr);
        // dedup against the pre-main-pass snapshot: skip what the main pass already took -- exact
        // match (not length-gated, #634; accepted cost: identical-text elements collapse) or a
        // length-gated substring (a <p> folded into its <list> container)
        List<String> elem_texts = new ArrayList<>();
        for (Element el : result_body) {
            elem_texts.add(_elem_text(el));
        }
        // newline-joined (trimmed element text has no newline) so no substring match spans two elements
        StringBuilder existing = new StringBuilder(Xml.joinNonEmpty("\n", elem_texts.toArray(String[]::new)));
        Set<String> existing_elems = new HashSet<>(elem_texts);
        // elements emitted or deduped whole: their descendants are covered
        Set<Element> handled = new HashSet<>();
        for (Element subelem : subelems) {
            if (consumed != null && consumed.contains(subelem)) {
                continue;
            }
            boolean coveredByAncestor = false;
            for (Element a : subelem.iterancestors()) {
                if (handled.contains(a)) {
                    coveredByAncestor = true;
                    break;
                }
            }
            if (coveredByAncestor) {
                continue;
            }
            Element processed = handle_textelem(subelem, potential_tags, options);
            if (processed == null) {
                continue;
            }
            if (processed == subelem) {
                handled.add(subelem);
            }
            String text = _elem_text(processed);
            // image-only blocks have no text to compare, check their sources against the live body
            Set<String> images = new HashSet<>();
            for (Element img : processed.iter("graphic")) {
                images.add(img.get("src"));
            }
            if (text.isEmpty() && !images.isEmpty()) {
                Set<String> bodyImages = new HashSet<>();
                for (Element g : result_body.iter("graphic")) {
                    bodyImages.add(g.get("src"));
                }
                if (bodyImages.containsAll(images)) {
                    continue;
                }
            }
            // past the cap, the substring scan is skipped and `existing` stops growing
            boolean under_cap = existing.length() <= DEDUPE_SCAN_CAP;
            // a copy carrying its source tail is a fragment of a text run: no length gate
            String fragment = text_chars_test(processed.tail) ? trim(text + Py.or(processed.tail, "")) : "";
            if (!text.isEmpty() && (existing_elems.contains(text)
                    || (under_cap && ((text.length() > MIN_DUPLICATE_LENGTH && existing.indexOf(text) >= 0)
                            || (!fragment.isEmpty() && existing.indexOf(fragment) >= 0))))) {
                continue;
            }
            result_body.append(processed);
            if (under_cap) {
                existing.append("\n").append(Py.or(fragment, text));
            }
            existing_elems.add(text);
        }
        return result_body;
    }

    public static Element recover_wild_text(Element tree, Element result_body, Extractor options) {
        return recover_wild_text(tree, result_body, options, null, null);
    }

    /** Rule-based deletion of targeted document sections. */
    public static Element prune_unwanted_sections(Element tree, Set<String> potential_tags, Extractor options,
            boolean keep_teasers) {
        boolean favor_precision = options.focus.equals("precision");
        // prune the rest
        tree = prune_unwanted_nodes(tree, OVERALL_DISCARD_XPATH, true);
        // decide if images are preserved
        if (!potential_tags.contains("graphic")) {
            tree = prune_unwanted_nodes(tree, DISCARD_IMAGE_ELEMENTS);
        }
        // balance precision/recall
        if (!options.focus.equals("recall")) {
            // teaser-class blocks are sometimes real content; keep them on the recovery path,
            // which only runs once the confident extractor has already come up short
            if (!keep_teasers) {
                tree = prune_unwanted_nodes(tree, TEASER_DISCARD_XPATH);
            }
            if (favor_precision) {
                tree = prune_unwanted_nodes(tree, PRECISION_DISCARD_XPATH);
            }
        }
        // remove elements by link density, several passes
        for (int i = 0; i < 2; i++) {
            tree = delete_by_link_density(tree, "div", true, favor_precision);
            tree = delete_by_link_density(tree, "list", false, favor_precision);
            tree = delete_by_link_density(tree, "p", false, favor_precision);
        }
        // tables
        if (potential_tags.contains("table") || favor_precision) {
            // collect before deleting: removing a table mid-iteration can make tree.iter() skip a table
            // that follows a deleted one containing a nested table (iterator descends into the detached subtree)
            List<Element> boilerplate_tables = new ArrayList<>();
            for (Element elem : tree.iter("table")) {
                if (link_density_test_tables(elem)) {
                    boilerplate_tables.add(elem);
                }
            }
            for (Element elem : boilerplate_tables) {
                delete_element(elem, false);
            }
        }
        if (favor_precision) {
            // delete trailing titles
            while (tree.size() > 0 && tree.get(-1).tag.equals("head")) {
                delete_element(tree.get(-1), false);
            }
            tree = delete_by_link_density(tree, "head", false, true);
            tree = delete_by_link_density(tree, "quote", false, true);
        }
        // after the link density tests, which need the refs
        if (potential_tags.contains("ref")) {
            Etree.strip_tags(tree, "span");
        } else {
            Etree.strip_tags(tree, "span", "ref");
        }
        return tree;
    }

    public static Element prune_unwanted_sections(Element tree, Set<String> potential_tags, Extractor options) {
        return prune_unwanted_sections(tree, potential_tags, options, false);
    }

    public static Triple<Element, String, Set<String>> _extract(Element tree, Extractor options) {
        // init
        Set<String> potential_tags = new HashSet<>(TAG_CATALOG);
        if (options.tables) {
            potential_tags.addAll(List.of("table", "td", "th", "tr"));
        }
        if (options.images) {
            potential_tags.add("graphic");
        }
        if (options.links) {
            potential_tags.add("ref");
        }
        Element result_body = Etree.Element("body");
        // iterate
        for (XPath expr : BODY_XPATH) {
            // select tree if the expression has been found
            List<Element> found = expr.evaluate(tree);
            Element subtree = found.isEmpty() ? null : found.get(0);
            if (subtree == null) {
                continue;
            }
            // prune the subtree
            subtree = prune_unwanted_sections(subtree, potential_tags, options);
            // skip if empty tree
            if (subtree.size() == 0) {
                continue;
            }
            // no paragraphs containing text, or not enough
            List<String> ptest = subtree.xpath_str("//p//text()");
            int factor = options.focus.equals("precision") ? 1 : 3;
            if (ptest.isEmpty() || String.join("", ptest).length() < options.min_extracted_size * factor) {
                potential_tags.add("div");
            }
            LOGGER.debug("{}", new TreeSet<>(potential_tags));

            List<Element> subelems = subtree.xpath(".//*");
            // a single text run (only lb or inline elements): process the frame itself
            // lb-only frames go there unconditionally, a failed main pass then hands them to the recovery
            Set<String> tags = new HashSet<>();
            for (Element e : subelems) {
                tags.add(e.tag);
            }
            Set<String> inlineOrLb = Settings.union(INLINE_FORMATTABLE, Set.of("lb"));
            if (tags.equals(Set.of("lb"))
                    || (subtree.tag.equals("div") && potential_tags.contains("div") && inlineOrLb.containsAll(tags))) {
                subelems = List.of(subtree);
            }
            // the frame's own text before its first child, kept if the frame is accepted
            Element lead = null;
            int start = result_body.size();
            boolean frameItself = subelems.size() == 1 && subelems.get(0) == subtree;
            if (!frameItself && text_chars_test(subtree.text)) {
                lead = Etree.Element("p");
                lead.text = subtree.text;
                lead = process_node(lead, options);
            }
            // extract content
            for (Element elem : subelems) {
                // handle_other_elements() emits a text-bearing div as-is, children and all, and
                // appending it MOVES it into result_body. Its descendants are still in the list
                // captured above, so revisiting one would emit its text twice. handle_paragraphs()
                // marks the children it consumes "done"; this covers the elements it cannot retag.
                if (elem.getroot() == result_body) {
                    continue;
                }
                // handlers may rename the element to "done", so read these first
                String tag = elem.tag;
                String tail = elem.tail;
                Element processed_elem = handle_textelem(elem, potential_tags, options);
                if (processed_elem != null) {
                    result_body.append(processed_elem);
                    // a copy leaves its source behind for a later, wider subtree
                    if (elem.getroot() != result_body) {
                        elem.tag = "done";
                    }
                }
                // text right after a rebuilt block is a paragraph of its own, like an <lb> tail,
                // unless the handler already carried it over (a code block copied as a whole)
                if (REBUILT_BLOCKS.contains(tag) && text_chars_test(tail)
                        && (processed_elem == null || processed_elem.tail == null)) {
                    Element tail_elem = Etree.Element("p");
                    tail_elem.text = tail;
                    if (process_node(tail_elem, options) != null) {
                        result_body.append(tail_elem);
                    }
                }
            }
            // remove trailing titles
            while (result_body.size() > 0 && NOT_AT_THE_END.contains(result_body.get(-1).tag)) {
                delete_element(result_body.get(-1), false);
            }
            // exit once there is real content, not just a lone image
            int nonGraphic = 0;
            for (Element e : result_body) {
                if (!e.tag.equals("graphic")) {
                    nonGraphic++;
                }
            }
            if (nonGraphic > 1) {
                LOGGER.debug("{}", trim(expr.path()));
                if (lead != null) {
                    result_body.insert(start, lead);
                }
                break;
            }
        }
        String temp_text = Py.strip(String.join(" ", result_body.itertext()));
        return Triple.of(result_body, temp_text, potential_tags);
    }

    /**
     * Find the main content of a page using a set of XPath expressions,
     * then extract relevant elements, strip them of unwanted subparts and
     * convert them.
     */
    public static Pair<Element, String> extract_content(Element cleaned_tree, Extractor options) {
        // backup
        Element backup_tree = cleaned_tree.copy();
        // source order before the main pass moves elements, the backup keeps it
        List<Element> order = cleaned_tree.iter().list();

        Triple<Element, String, Set<String>> extracted = _extract(cleaned_tree, options);
        Element result_body = extracted.first();
        String temp_text = extracted.second();
        Set<String> potential_tags = extracted.third();

        // try parsing wild <p> elements if nothing found or text too short
        // todo: test precision and recall settings here
        if (result_body.size() == 0 || temp_text.length() < options.min_extracted_size) {
            // copies of what the main pass consumed are not recovered again
            List<Element> twins = backup_tree.iter().list();
            if (twins.size() != order.size()) {
                throw new IllegalStateException("zip() argument 2 is shorter or longer than argument 1");
            }
            Set<Element> consumed = new HashSet<>();
            for (int i = 0; i < order.size(); i++) {
                if (order.get(i).tag.equals("done")) {
                    consumed.add(twins.get(i));
                }
            }
            result_body = recover_wild_text(backup_tree, result_body, options, potential_tags, consumed);
            temp_text = Py.strip(String.join(" ", result_body.itertext()));
        }
        // drop substantial elements repeating the previous one (overlapping-candidate / recovery artifact);
        // length-gated so short genuine repeats stay for the dedup (#778) and tree-size guards
        String previous = null;
        for (Element el : result_body.getchildren()) {
            String current = _elem_text(el);
            if (!current.isEmpty() && current.equals(previous) && current.length() > MIN_DUPLICATE_LENGTH) {
                delete_element(el, false);
            } else {
                previous = current;
            }
        }
        // filter output
        Etree.strip_elements(result_body, "done");
        Etree.strip_tags(result_body, "div");
        // stripping the divs above merges their indentation into the preceding <lb>'s tail, so an
        // <lb> that only separated pruned blocks (ad slots, clearfix spacers) ends up carrying a
        // run of whitespace -- rendered verbatim as blank, space-filled lines in txt/markdown.
        // Inside code/pre that whitespace is the indentation itself, so leave those alone.
        for (Element linebreak : result_body.iter("lb")) {
            if (Py.truthy(linebreak.tail) && !text_chars_test(linebreak.tail)
                    && linebreak.firstancestor(SPACING_PROTECTED) == null) {
                linebreak.tail = null;
            }
        }
        return Pair.of(result_body, temp_text);
    }

    /** Try to extract comments out of potential sections in the HTML. */
    public static Triple<Element, String, Element> extract_comments(Element tree, Extractor options) {
        Element comments_body = Etree.Element("body");
        // define iteration strategy
        Set<String> potential_tags = new HashSet<>(TAG_CATALOG); // not div: trouble with <div class="comment-author meta">
        for (XPath expr : COMMENTS_XPATH) {
            // select tree if the expression has been found
            List<Element> found = expr.evaluate(tree);
            Element subtree = found.isEmpty() ? null : found.get(0);
            if (subtree == null) {
                continue;
            }
            // prune
            subtree = prune_unwanted_nodes(subtree, COMMENTS_DISCARD_XPATH);
            // todo: unified stripping function, taking include_links into account
            Etree.strip_tags(subtree, "ref", "span");
            for (Element elem : subtree.xpath(".//*")) {
                if (potential_tags.contains(elem.tag)) {
                    Element processed = handle_textnode(elem, options, true, false);
                    if (processed != null) {
                        processed.attrib.clear();
                        comments_body.append(processed);
                    }
                }
            }
            // control
            if (comments_body.size() > 0) { // if it has children
                LOGGER.debug("{}", expr.path());
                // remove corresponding subtree
                delete_element(subtree, false);
                break;
            }
        }
        return Triple.of(comments_body, Py.strip(String.join(" ", comments_body.itertext())), tree);
    }
}
