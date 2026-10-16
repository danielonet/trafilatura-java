package io.github.danielonet.trafilatura.lxml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal Java counterpart of an lxml element ({@code lxml.etree._Element} and
 * {@code lxml.html.HtmlElement}), keeping lxml's text/tail model:
 * {@code text} is the text before the first child, {@code tail} the text after
 * the closing tag (it belongs to the element and moves with it).
 */
public class Element implements Iterable<Element> {
    public String tag;
    public String text;
    public String tail;
    public final LinkedHashMap<String, String> attrib = new LinkedHashMap<>();

    Element parent;
    XPath.DocumentNode docNode;
    final List<Element> children = new ArrayList<>();
    /** Last known position in the parent's children, validated before use. */
    private int indexHint = -1;

    public Element(String tag) {
        this.tag = tag;
    }

    public Element(String tag, Map<String, String> attrib) {
        this.tag = tag;
        if (attrib != null) {
            this.attrib.putAll(attrib);
        }
    }

    // ------------------------------------------------------------------ attributes

    public String get(String key) {
        return attrib.get(key);
    }

    public String get(String key, String defaultValue) {
        String value = attrib.get(key);
        return value != null ? value : defaultValue;
    }

    public void set(String key, String value) {
        attrib.put(key, value);
    }

    public Set<String> keys() {
        return attrib.keySet();
    }

    public Set<Map.Entry<String, String>> items() {
        return attrib.entrySet();
    }

    // ------------------------------------------------------------------ children

    /** {@code len(elem)} */
    public int size() {
        return children.size();
    }

    /** {@code elem[i]}, negative indexes count from the end like Python. */
    public Element get(int index) {
        return children.get(index < 0 ? children.size() + index : index);
    }

    /** {@code list(elem)}: a snapshot of the children. */
    public List<Element> getchildren() {
        return new ArrayList<>(children);
    }

    /**
     * Live iteration over the children, like lxml: the next sibling is looked up when the
     * current child is returned, so the current child may be moved or removed meanwhile.
     */
    @Override
    public Iterator<Element> iterator() {
        Element first = children.isEmpty() ? null : children.get(0);
        return new Iterator<>() {
            private Element pending = first;

            @Override
            public boolean hasNext() {
                return pending != null;
            }

            @Override
            public Element next() {
                if (pending == null) {
                    throw new java.util.NoSuchElementException();
                }
                Element current = pending;
                pending = current.getnext();
                return current;
            }
        };
    }

    public List<Element> iterchildren(String... tags) {
        Set<String> wanted = tagSet(tags);
        List<Element> result = new ArrayList<>();
        for (Element child : children) {
            if (matches(child, wanted)) {
                result.add(child);
            }
        }
        return result;
    }

    public int index(Element child) {
        // position hint: sibling walks (getnext, iter) would otherwise scan the parent's children
        // on every step, quadratic for long sibling runs; lxml follows a linked list instead
        int hint = child.indexHint;
        int size = children.size();
        if (hint >= 0 && hint < size && children.get(hint) == child) {
            return hint;
        }
        // unknown or stale hint: renumber all children at once, later lookups are O(1) again
        int found = -1;
        for (int i = 0; i < size; i++) {
            Element c = children.get(i);
            c.indexHint = i;
            if (c == child) {
                found = i;
            }
        }
        if (found >= 0) {
            return found;
        }
        throw new IllegalArgumentException("element is not a child");
    }

    private void detach() {
        if (parent != null) {
            parent.children.remove(parent.index(this));
            parent = null;
        }
    }

    public void append(Element child) {
        checkNotAncestor(child);
        child.detach();
        child.parent = this;
        children.add(child);
        child.indexHint = children.size() - 1;
    }

    public void extend(Iterable<Element> elements) {
        List<Element> copy = new ArrayList<>();
        elements.forEach(copy::add);
        for (Element child : copy) {
            append(child);
        }
    }

    public void insert(int index, Element child) {
        checkNotAncestor(child);
        if (child.parent == this) {
            int current = index(child);
            if (index > current) {
                index--;
            }
        }
        child.detach();
        if (index < 0) {
            index = Math.max(0, children.size() + index);
        }
        index = Math.min(index, children.size());
        child.parent = this;
        children.add(index, child);
    }

    /** Removes a child; like lxml its tail goes with it. */
    public void remove(Element child) {
        if (child.parent != this) {
            throw new IllegalArgumentException("Element is not a child of this node.");
        }
        child.detach();
    }

    /** Replaces a child by another element at the same position. */
    public void replace(Element oldChild, Element newChild) {
        int pos = index(oldChild);
        if (newChild == oldChild) {
            return;
        }
        newChild.detach();
        pos = index(oldChild);
        oldChild.parent = null;
        newChild.parent = this;
        children.set(pos, newChild);
    }

    /** Removes children, attributes, text and tail ({@code clear(keep_tail=False)}). */
    public void clear() {
        clear(false);
    }

    public void clear(boolean keepTail) {
        for (Element child : children) {
            child.parent = null;
        }
        children.clear();
        attrib.clear();
        text = null;
        if (!keepTail) {
            tail = null;
        }
    }

    private void checkNotAncestor(Element child) {
        for (Element e = this; e != null; e = e.parent) {
            if (e == child) {
                throw new IllegalArgumentException("cannot append parent to itself");
            }
        }
    }

    // ------------------------------------------------------------------ navigation

    public Element getparent() {
        return parent;
    }

    public Element getnext() {
        if (parent == null) {
            return null;
        }
        int i = parent.index(this);
        return i + 1 < parent.children.size() ? parent.children.get(i + 1) : null;
    }

    public Element getprevious() {
        if (parent == null) {
            return null;
        }
        int i = parent.index(this);
        return i > 0 ? parent.children.get(i - 1) : null;
    }

    /** Adds an element right after this one (as a sibling); its tail moves along. */
    public void addnext(Element element) {
        if (parent == null) {
            throw new IllegalArgumentException("cannot add a sibling to the root element");
        }
        if (element == this) {
            return;
        }
        Element p = parent;
        element.detach();
        p.checkNotAncestor(element);
        element.parent = p;
        p.children.add(p.index(this) + 1, element);
    }

    /** Adds an element right before this one (as a sibling). */
    public void addprevious(Element element) {
        if (parent == null) {
            throw new IllegalArgumentException("cannot add a sibling to the root element");
        }
        if (element == this) {
            return;
        }
        Element p = parent;
        element.detach();
        p.checkNotAncestor(element);
        element.parent = p;
        p.children.add(p.index(this), element);
    }

    /** Topmost ancestor (lxml: {@code getroottree().getroot()}). */
    public Element getroot() {
        Element e = this;
        while (e.parent != null) {
            e = e.parent;
        }
        return e;
    }

    // ------------------------------------------------------------------ iteration

    static Set<String> tagSet(String... tags) {
        if (tags == null || tags.length == 0) {
            return null;
        }
        Set<String> set = new HashSet<>(Arrays.asList(tags));
        return set.contains("*") ? null : set;
    }

    static boolean matches(Element e, Set<String> wanted) {
        return wanted == null || wanted.contains(e.tag);
    }

    /**
     * Result of {@code iter()} / {@code iterdescendants()}. Iterating it is live like lxml's
     * ElementDepthFirstIterator (the next node is computed when the current one is returned,
     * bounded by the top node, so detaching the current node can end the iteration early);
     * indexed access and size() work on a snapshot taken at first use, like {@code list(...)}.
     */
    public static final class Iter extends java.util.AbstractList<Element> {
        private final Element top;
        private final Set<String> wanted;
        private final boolean inclusive;
        private List<Element> snapshot;

        Iter(Element top, Set<String> wanted, boolean inclusive) {
            this.top = top;
            this.wanted = wanted;
            this.inclusive = inclusive;
        }

        /** Next element in document order after {@code node}, without leaving {@code top}. */
        private Element advance(Element node) {
            if (!node.children.isEmpty()) {
                return node.children.get(0);
            }
            Element c = node;
            while (c != null && c != top) {
                Element next = c.getnext();
                if (next != null) {
                    return next;
                }
                c = c.parent;
            }
            return null;
        }

        private Element nextMatch(Element node) {
            Element c = advance(node);
            while (c != null && !matches(c, wanted)) {
                c = advance(c);
            }
            return c;
        }

        @Override
        public Iterator<Element> iterator() {
            Element start = inclusive && matches(top, wanted) ? top : nextMatch(top);
            return new Iterator<>() {
                private Element pending = start;

                @Override
                public boolean hasNext() {
                    return pending != null;
                }

                @Override
                public Element next() {
                    if (pending == null) {
                        throw new java.util.NoSuchElementException();
                    }
                    Element current = pending;
                    pending = nextMatch(current);
                    return current;
                }
            };
        }

        /** {@code list(elem.iter(...))}: a snapshot of the current matches. */
        public List<Element> list() {
            List<Element> result = new ArrayList<>();
            iterator().forEachRemaining(result::add);
            return result;
        }

        private List<Element> snapshot() {
            if (snapshot == null) {
                snapshot = list();
            }
            return snapshot;
        }

        @Override
        public Element get(int index) {
            return snapshot().get(index);
        }

        @Override
        public int size() {
            return snapshot().size();
        }
    }

    /** Depth-first iteration including this element, filtered by tag ({@code iter(*tags)}). */
    public Iter iter(String... tags) {
        return new Iter(this, tagSet(tags), true);
    }

    /** {@code iter(tags)} with a collection of tag names. */
    public Iter iter(java.util.Collection<String> tags) {
        return iter(tags.toArray(String[]::new));
    }

    public Iter iterdescendants(java.util.Collection<String> tags) {
        return iterdescendants(tags.toArray(String[]::new));
    }

    public List<Element> iterancestors(java.util.Collection<String> tags) {
        return iterancestors(tags.toArray(String[]::new));
    }

    /** First ancestor with one of the tags, or null ({@code next(elem.iterancestors(tags), None)}). */
    public Element firstancestor(String... tags) {
        Set<String> wanted = tagSet(tags);
        for (Element e = parent; e != null; e = e.parent) {
            if (matches(e, wanted)) {
                return e;
            }
        }
        return null;
    }

    public Element firstancestor(java.util.Collection<String> tags) {
        return firstancestor(tags.toArray(String[]::new));
    }

    /** Like {@link #iter(String...)} but without the element itself. */
    public Iter iterdescendants(String... tags) {
        return new Iter(this, tagSet(tags), false);
    }

    public List<Element> iterancestors(String... tags) {
        Set<String> wanted = tagSet(tags);
        List<Element> result = new ArrayList<>();
        for (Element e = parent; e != null; e = e.parent) {
            if (matches(e, wanted)) {
                result.add(e);
            }
        }
        return result;
    }

    /** Following siblings ({@code itersiblings(*tags)}). */
    public List<Element> itersiblings(String... tags) {
        return itersiblings(false, tags);
    }

    /** Siblings after this element, or before it in reverse order when {@code preceding} is set. */
    public List<Element> itersiblings(boolean preceding, String... tags) {
        Set<String> wanted = tagSet(tags);
        List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        int i = parent.index(this);
        if (preceding) {
            for (int j = i - 1; j >= 0; j--) {
                if (matches(parent.children.get(j), wanted)) {
                    result.add(parent.children.get(j));
                }
            }
        } else {
            for (int j = i + 1; j < parent.children.size(); j++) {
                if (matches(parent.children.get(j), wanted)) {
                    result.add(parent.children.get(j));
                }
            }
        }
        return result;
    }

    /** Text content of the element and its descendants, tails included, own tail excluded. */
    public List<String> itertext(String... tags) {
        Set<String> wanted = tagSet(tags);
        List<String> result = new ArrayList<>();
        collectText(this, wanted, result, true);
        return result;
    }

    private static void collectText(Element e, Set<String> wanted, List<String> out, boolean top) {
        boolean ok = matches(e, wanted);
        if (ok && e.text != null && !e.text.isEmpty()) {
            out.add(e.text);
        }
        for (Element child : e.children) {
            collectText(child, wanted, out, false);
        }
        if (!top && ok && e.tail != null && !e.tail.isEmpty()) {
            out.add(e.tail);
        }
    }

    /** {@code HtmlElement.text_content()} */
    public String text_content() {
        return String.join("", itertext());
    }

    // ------------------------------------------------------------------ lxml.html helpers

    /** Removes the tag but keeps its text and children ({@code HtmlElement.drop_tag()}). */
    public void drop_tag() {
        Element p = parent;
        if (p == null) {
            // lxml.html: assert parent is not None
            throw new IllegalStateException("drop_tag() on an element without parent");
        }
        Element previous = getprevious();
        if (text != null) {
            if (previous == null) {
                p.text = (p.text == null ? "" : p.text) + text;
            } else {
                previous.tail = (previous.tail == null ? "" : previous.tail) + text;
            }
        }
        if (tail != null) {
            if (!children.isEmpty()) {
                Element last = children.get(children.size() - 1);
                last.tail = (last.tail == null ? "" : last.tail) + tail;
            } else if (previous == null) {
                p.text = (p.text == null ? "" : p.text) + tail;
            } else {
                previous.tail = (previous.tail == null ? "" : previous.tail) + tail;
            }
        }
        int index = p.index(this);
        List<Element> moved = new ArrayList<>(children);
        for (Element child : moved) {
            child.parent = p;
        }
        children.clear();
        p.children.remove(index);
        p.children.addAll(index, moved);
        parent = null;
    }

    /** Removes the element and its children, keeping the tail ({@code HtmlElement.drop_tree()}). */
    public void drop_tree() {
        Element p = parent;
        if (p == null) {
            // lxml.html: assert parent is not None
            throw new IllegalStateException("drop_tree() on an element without parent");
        }
        if (tail != null) {
            Element previous = getprevious();
            if (previous == null) {
                p.text = (p.text == null ? "" : p.text) + tail;
            } else {
                previous.tail = (previous.tail == null ? "" : previous.tail) + tail;
            }
        }
        p.remove(this);
    }

    /** Namespace prefixes declared on this element and its ancestors ({@code nsmap}). */
    public Map<String, String> nsmap() {
        Map<String, String> result = new LinkedHashMap<>();
        List<Element> chain = new ArrayList<>();
        for (Element e = this; e != null; e = e.parent) {
            chain.add(0, e);
        }
        for (Element e : chain) {
            for (Map.Entry<String, String> entry : e.attrib.entrySet()) {
                if (entry.getKey().startsWith("xmlns:")) {
                    result.put(entry.getKey().substring(6), entry.getValue());
                } else if (entry.getKey().equals("xmlns")) {
                    result.put(null, entry.getValue());
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ copy

    /** Deep copy including the tail ({@code copy.deepcopy(elem)}). */
    public Element copy() {
        Element clone = new Element(tag, attrib);
        clone.text = text;
        clone.tail = tail;
        for (Element child : children) {
            Element c = child.copy();
            c.parent = clone;
            clone.children.add(c);
        }
        return clone;
    }

    // ------------------------------------------------------------------ XPath / ElementPath

    /** XPath query returning the selected elements (text and attribute results are skipped). */
    public List<Element> xpath(String expression) {
        return XPath.cached(expression).evaluate(this);
    }

    /** XPath query returning strings, for expressions ending in {@code text()} or {@code @attr}. */
    public List<String> xpath_str(String expression) {
        return XPath.cached(expression).strings(this);
    }

    /** XPath query returning the raw result (List, String, Double or Boolean). */
    public Object xpath_eval(String expression) {
        return XPath.cached(expression).eval(this);
    }

    private static final java.util.regex.Pattern CLARK_NS = java.util.regex.Pattern.compile("\\{[^}]*\\}");

    /**
     * ElementPath queries are evaluated as XPath (the subset used here is compatible);
     * the namespace qualifiers of the Clark notation ({@code {*}tag}, {@code {uri}tag})
     * are dropped since this tree does not resolve namespaces.
     */
    private static String elementPath(String path) {
        return path.indexOf('{') >= 0 ? CLARK_NS.matcher(path).replaceAll("") : path;
    }

    public List<Element> findall(String path) {
        return xpath(elementPath(path));
    }

    public List<Element> iterfind(String path) {
        return xpath(elementPath(path));
    }

    public Element find(String path) {
        List<Element> result = xpath(elementPath(path));
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public String toString() {
        return "<Element " + tag + ">";
    }
}
