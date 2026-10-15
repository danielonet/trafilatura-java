package trafilatura.lxml;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DefaultHandler2;

/**
 * Module-level functions of {@code lxml.etree} and {@code lxml.html}:
 * element factories, tag stripping, serialization and parsing.
 */
public final class Etree {
    private Etree() {
    }

    // ------------------------------------------------------------------ factories

    private static final java.util.regex.Pattern VALID_TAG = java.util.regex.Pattern.compile(
            "[\\p{L}_][\\p{L}\\p{N}\\p{Mn}\\p{Mc}._\\-\\u00B7]*");

    /**
     * lxml's _tagValidOrRaise: factories reject names that are not XML names or that hold a colon
     * (the HTML parser itself accepts them, e.g. {@code <http:>} from a bare URL in angle brackets).
     */
    static void _tagValidOrRaise(String tag) {
        String local = tag.startsWith("{") && tag.indexOf('}') > 0 ? tag.substring(tag.indexOf('}') + 1) : tag;
        if (!VALID_TAG.matcher(local).matches()) {
            throw new IllegalArgumentException("Invalid tag name '" + tag + "'");
        }
    }

    /** {@code Element(tag, **attrs)}: attributes given as alternating names and values. */
    public static Element Element(String tag, String... attributes) {
        _tagValidOrRaise(tag);
        Element element = new Element(tag);
        setPairs(element, attributes);
        return element;
    }

    public static Element Element(String tag, Map<String, String> attrib) {
        _tagValidOrRaise(tag);
        return new Element(tag, attrib);
    }

    /** {@code SubElement(parent, tag, **attrs)} */
    public static Element SubElement(Element parent, String tag, String... attributes) {
        Element element = Element(tag, attributes);
        parent.append(element);
        return element;
    }

    private static void setPairs(Element element, String... attributes) {
        if (attributes.length % 2 != 0) {
            throw new IllegalArgumentException("attributes must be given as name/value pairs");
        }
        for (int i = 0; i < attributes.length; i += 2) {
            if (attributes[i + 1] != null) {
                element.set(attributes[i], attributes[i + 1]);
            }
        }
    }

    // ------------------------------------------------------------------ tree surgery

    /** Removes the matching descendant tags but keeps their text and children. */
    public static void strip_tags(Element tree, String... tags) {
        if (tags.length == 0) {
            return;
        }
        Set<String> wanted = Element.tagSet(tags);
        for (Element e : tree.iterdescendants().list()) {
            if (Element.matches(e, wanted)) {
                e.drop_tag();
                // libxml2 moves the text nodes out too: the detached element keeps no text
                e.text = null;
                e.tail = null;
            }
        }
    }

    public static void strip_tags(Element tree, java.util.Collection<String> tags) {
        strip_tags(tree, tags.toArray(String[]::new));
    }

    /** Removes the matching descendants with their content (and tails by default). */
    public static void strip_elements(Element tree, boolean with_tail, String... tags) {
        if (tags.length == 0) {
            return;
        }
        Set<String> wanted = Element.tagSet(tags);
        for (Element e : tree.iterdescendants().list()) {
            if (Element.matches(e, wanted) && e.parent != null) {
                if (with_tail) {
                    e.parent.remove(e);
                } else {
                    e.drop_tree();
                }
            }
        }
    }

    public static void strip_elements(Element tree, String... tags) {
        strip_elements(tree, true, tags);
    }

    // ------------------------------------------------------------------ serialization

    private static final Set<String> HTML_VOID = Set.of(
            "area", "base", "basefont", "br", "col", "embed", "frame", "hr", "img", "input",
            "isindex", "link", "meta", "param", "source", "track", "wbr");
    private static final Set<String> HTML_RAW_TEXT = Set.of("script", "style");

    /** {@code tostring(element)}: XML, ASCII output with character references, tail included. */
    public static String tostring(Element element) {
        return tostring(element, null, "xml", false, true);
    }

    /** {@code tostring(element, encoding=...)}: "unicode" keeps non-ASCII characters as they are. */
    public static String tostring(Element element, String encoding) {
        return tostring(element, encoding, "xml", false, true);
    }

    /**
     * Full {@code tostring()} signature.
     *
     * @param encoding "unicode" for a plain string, null for ASCII with character references
     * @param method "xml", "html" or "text"
     */
    public static String tostring(Element element, String encoding, String method, boolean pretty_print, boolean with_tail) {
        StringBuilder sb = new StringBuilder();
        boolean ascii = encoding == null || encoding.equalsIgnoreCase("ascii") || encoding.equalsIgnoreCase("us-ascii");
        switch (method) {
            case "text" -> {
                sb.append(element.text_content());
                if (with_tail && element.tail != null) {
                    sb.append(element.tail);
                }
                return sb.toString();
            }
            case "html" -> serializeHtml(element, sb, ascii);
            default -> serializeXml(element, sb, ascii, pretty_print, 0);
        }
        if (pretty_print) {
            sb.append('\n');
        }
        if (with_tail && element.tail != null) {
            sb.append(escapeText(element.tail, ascii));
        }
        return sb.toString();
    }

    private static boolean hasMixedContent(Element element) {
        if (element.text != null && !element.text.isEmpty()) {
            return true;
        }
        for (Element child : element.children) {
            if (child.tail != null && !child.tail.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void serializeXml(Element e, StringBuilder sb, boolean ascii, boolean format, int level) {
        sb.append('<').append(e.tag);
        appendAttributes(e, sb, ascii, true);
        if (e.children.isEmpty() && e.text == null) {
            sb.append("/>");
            return;
        }
        sb.append('>');
        if (e.text != null) {
            sb.append(escapeText(e.text, ascii));
        }
        // libxml2 only indents children when the element contains no text node
        boolean formatChildren = format && !hasMixedContent(e);
        for (Element child : e.children) {
            if (formatChildren) {
                sb.append('\n').append("  ".repeat(level + 1));
            }
            serializeXml(child, sb, ascii, formatChildren, level + 1);
            if (child.tail != null) {
                sb.append(escapeText(child.tail, ascii));
            }
        }
        if (formatChildren && !e.children.isEmpty()) {
            sb.append('\n').append("  ".repeat(level));
        }
        sb.append("</").append(e.tag).append('>');
    }

    private static void serializeHtml(Element e, StringBuilder sb, boolean ascii) {
        sb.append('<').append(e.tag);
        appendAttributes(e, sb, ascii, false);
        sb.append('>');
        if (HTML_VOID.contains(e.tag) && e.children.isEmpty() && (e.text == null || e.text.isEmpty())) {
            return;
        }
        if (e.text != null) {
            sb.append(HTML_RAW_TEXT.contains(e.tag) ? e.text : escapeText(e.text, ascii));
        }
        for (Element child : e.children) {
            serializeHtml(child, sb, ascii);
            if (child.tail != null) {
                sb.append(escapeText(child.tail, ascii));
            }
        }
        sb.append("</").append(e.tag).append('>');
    }

    private static void appendAttributes(Element e, StringBuilder sb, boolean ascii, boolean xml) {
        for (Map.Entry<String, String> attr : e.attrib.entrySet()) {
            sb.append(' ').append(attr.getKey());
            if (attr.getValue() == null) {
                if (xml) {
                    sb.append("=\"\"");
                }
                continue;
            }
            sb.append("=\"").append(escapeAttribute(attr.getValue(), ascii, xml)).append('"');
        }
    }

    static String escapeText(String s, boolean ascii) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        s.codePoints().forEach(cp -> {
            switch (cp) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '\r' -> sb.append("&#13;");
                default -> appendCodePoint(sb, cp, ascii);
            }
        });
        return sb.toString();
    }

    static String escapeAttribute(String s, boolean ascii, boolean xml) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        s.codePoints().forEach(cp -> {
            switch (cp) {
                case '<' -> sb.append(xml ? "&lt;" : "<");
                case '>' -> sb.append(xml ? "&gt;" : ">");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\n' -> sb.append(xml ? "&#10;" : "\n");
                case '\r' -> sb.append("&#13;");
                case '\t' -> sb.append(xml ? "&#9;" : "\t");
                default -> appendCodePoint(sb, cp, ascii);
            }
        });
        return sb.toString();
    }

    private static void appendCodePoint(StringBuilder sb, int cp, boolean ascii) {
        if (ascii && cp > 127) {
            sb.append("&#").append(cp).append(';');
        } else {
            sb.appendCodePoint(cp);
        }
    }

    // ------------------------------------------------------------------ HTML parsing

    private static final Pattern LOOKS_LIKE_FULL_HTML = Pattern.compile("^\\s*<(?:html|!doctype)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> BLOCK_TAGS = Set.of(
            "address", "blockquote", "center", "dir", "div", "dl", "fieldset", "form", "h1", "h2", "h3", "h4",
            "h5", "h6", "hr", "isindex", "menu", "noframes", "noscript", "ol", "p", "pre", "table", "ul",
            "applet", "button", "del", "iframe", "ins", "map", "object", "script");

    /** Parses a whole HTML document and returns its root element ({@code lxml.html.document_fromstring}). */
    public static Element document_fromstring(String html) {
        if (html == null) {
            throw new ParserError("Document is empty");
        }
        Document doc = Jsoup.parse(html);
        Element root = convertHtml(doc.child(0));
        String lower = html.length() > 100_000 ? html.substring(0, 100_000).toLowerCase() : html.toLowerCase();
        // libxml2 only creates head/body when the input needs them
        for (Element child : root.getchildren()) {
            if (child.size() == 0 && (child.text == null || child.text.isBlank())
                    && (child.tag.equals("head") && !lower.contains("<head")
                            || child.tag.equals("body") && !lower.contains("<body"))) {
                root.remove(child);
            }
        }
        return root;
    }

    /**
     * {@code lxml.html.fromstring}: returns the document root for full documents,
     * otherwise the single element passed in or a div/span container.
     */
    // a fragment starting with a table part: libxml2 keeps it where it is, the HTML5 tree builder
    // drops it outside a table unless the fragment is parsed in its context
    private static final Pattern LEADING_TABLE_PART = Pattern.compile(
            "^\\s*<(td|th|tr|thead|tbody|tfoot|caption|colgroup|col)[\\s/>]", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> TABLE_PART_CONTEXT = Map.of(
            "td", "tr", "th", "tr", "tr", "tbody", "thead", "table", "tbody", "table", "tfoot", "table",
            "caption", "table", "colgroup", "table", "col", "colgroup");

    public static Element html_fromstring(String html) {
        boolean fullHtml = LOOKS_LIKE_FULL_HTML.matcher(html).find();
        java.util.regex.Matcher tablePart = LEADING_TABLE_PART.matcher(html);
        if (!fullHtml && tablePart.find()) {
            String context = TABLE_PART_CONTEXT.get(tablePart.group(1).toLowerCase());
            List<Node> nodes = org.jsoup.parser.Parser.parseFragment(html, new org.jsoup.nodes.Element(context), "");
            org.jsoup.nodes.Element holder = new org.jsoup.nodes.Element("body");
            for (Node node : nodes) {
                holder.appendChild(node);
            }
            Element fragmentBody = convertHtml(holder);
            new Element("html").append(fragmentBody);
            return htmlFragmentResult(fragmentBody);
        }
        Element doc = document_fromstring(html);
        if (fullHtml) {
            return doc;
        }
        Element body = doc.find("body");
        if (doc.find("head") != null || body == null) {
            return doc;
        }
        return htmlFragmentResult(body);
    }

    /** lxml.html.fromstring on a fragment: the single element, else the body renamed to div or span. */
    private static Element htmlFragmentResult(Element body) {
        if (body.size() == 1 && (body.text == null || body.text.isBlank())
                && (body.get(-1).tail == null || body.get(-1).tail.isBlank())) {
            return body.get(0);
        }
        boolean block = false;
        for (Element e : body.iterdescendants()) {
            if (BLOCK_TAGS.contains(e.tag)) {
                block = true;
                break;
            }
        }
        body.tag = block ? "div" : "span";
        return body;
    }

    /**
     * {@code lxml.html.fragment_fromstring}: a single element, or a container
     * named {@code create_parent} holding everything when it is not null.
     */
    public static Element fragment_fromstring(String html, String create_parent) {
        // lxml parses "<html><body>...</body></html>" and hands out the body children
        // without detaching them: the result keeps that throwaway body as parent
        Element doc = document_fromstring("<html><body>" + html + "</body></html>");
        Element body = doc.find("body");
        if (body == null) {
            body = new Element("body");
        }
        if (create_parent != null) {
            Element container = new Element(create_parent);
            container.text = body.text;
            container.extend(body.getchildren());
            return container;
        }
        if (body.size() == 0) {
            throw new ParserError("No elements found");
        }
        if (body.size() > 1) {
            throw new ParserError("Multiple elements found");
        }
        if (body.text != null && !body.text.isBlank()) {
            throw new ParserError("There is leading text: " + body.text);
        }
        Element result = body.get(0);
        if (result.tail != null && !result.tail.isBlank()) {
            throw new ParserError("Element followed by text: " + result.tail);
        }
        result.tail = null;
        return result;
    }

    public static Element fragment_fromstring(String html) {
        return fragment_fromstring(html, null);
    }

    private static Element convertHtml(org.jsoup.nodes.Element source) {
        Element target = new Element(source.normalName());
        for (Attribute attr : source.attributes()) {
            if (!attr.getKey().startsWith("/")) {
                target.attrib.put(attr.getKey(), attr.getValue());
            }
        }
        Element last = null;
        for (Node node : source.childNodes()) {
            String text = null;
            if (node instanceof TextNode t) {
                text = t.getWholeText();
            } else if (node instanceof DataNode d) {
                text = d.getWholeData();
            } else if (node instanceof org.jsoup.nodes.Element child) {
                last = convertHtml(child);
                last.parent = target;
                target.children.add(last);
            }
            if (text != null && !text.isEmpty()) {
                if (last == null) {
                    target.text = target.text == null ? text : target.text + text;
                } else {
                    last.tail = last.tail == null ? text : last.tail + text;
                }
            }
        }
        return target;
    }

    // ------------------------------------------------------------------ XML parsing

    /** {@code lxml.etree.fromstring(xml)} */
    public static Element fromstring(String xml) {
        return fromstring(xml, false);
    }

    /**
     * {@code lxml.etree.fromstring(xml, XMLParser(remove_blank_text=...))}.
     * Namespace prefixes are kept as part of the tag names.
     */
    public static Element fromstring(String xml, boolean remove_blank_text) {
        if (xml == null || xml.isBlank()) {
            throw new XMLSyntaxError("Document is empty");
        }
        TreeBuilder handler = new TreeBuilder();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            SAXParser parser = factory.newSAXParser();
            parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
            parser.parse(new InputSource(new StringReader(xml)), handler);
        } catch (SAXException | IOException err) {
            throw new XMLSyntaxError(err.getMessage());
        } catch (ParserConfigurationException err) {
            throw new IllegalStateException(err);
        }
        if (remove_blank_text) {
            removeBlankText(handler.root);
        }
        return handler.root;
    }

    /**
     * {@code etree.fromstring(xml, XMLParser(recover=True))}: lenient parsing of broken XML.
     * Namespace prefixes are kept as part of the tag names.
     */
    public static Element fromstring_recover(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        Document doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser());
        for (Node node : doc.childNodes()) {
            if (node instanceof org.jsoup.nodes.Element root) {
                return convertXml(root);
            }
        }
        return null;
    }

    private static Element convertXml(org.jsoup.nodes.Element source) {
        Element target = new Element(source.tagName());
        for (Attribute attr : source.attributes()) {
            target.attrib.put(attr.getKey(), attr.getValue());
        }
        Element last = null;
        for (Node node : source.childNodes()) {
            String text = null;
            if (node instanceof TextNode t) {
                text = t.getWholeText();
            } else if (node instanceof DataNode d) {
                text = d.getWholeData();
            } else if (node instanceof org.jsoup.nodes.Element child) {
                last = convertXml(child);
                last.parent = target;
                target.children.add(last);
            }
            if (text != null && !text.isEmpty()) {
                if (last == null) {
                    target.text = target.text == null ? text : target.text + text;
                } else {
                    last.tail = last.tail == null ? text : last.tail + text;
                }
            }
        }
        return target;
    }

    /** Namespace URI of an element whose tag keeps its prefix ({@code QName(elem).namespace}). */
    public static String namespace(Element element) {
        int colon = element.tag.indexOf(':');
        String prefix = colon >= 0 ? element.tag.substring(0, colon) : null;
        return element.nsmap().get(prefix);
    }

    /** Local part of a prefixed tag name ({@code QName(elem).localname}). */
    public static String localname(Element element) {
        int colon = element.tag.indexOf(':');
        return colon >= 0 ? element.tag.substring(colon + 1) : element.tag;
    }

    /** {@code etree.XML(text)} */
    public static Element XML(String xml) {
        return fromstring(xml);
    }

    /** libxml2 keepBlanks=0: drop whitespace-only text unless it is an element's only content. */
    private static void removeBlankText(Element element) {
        boolean textKept = element.text != null && !element.text.isBlank();
        if (element.text != null && element.text.isBlank() && !element.children.isEmpty()) {
            element.text = null;
        }
        for (Element child : element.children) {
            if (child.tail != null && child.tail.isBlank() && !textKept) {
                child.tail = null;
            }
            removeBlankText(child);
        }
    }

    private static final class TreeBuilder extends DefaultHandler2 {
        Element root;
        private final Deque<Element> stack = new ArrayDeque<>();
        private Element lastClosed;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            Map<String, String> attrib = new LinkedHashMap<>();
            for (int i = 0; i < attributes.getLength(); i++) {
                attrib.put(attributes.getQName(i), attributes.getValue(i));
            }
            Element element = new Element(qName, attrib);
            if (stack.isEmpty()) {
                root = element;
            } else {
                stack.peek().append(element);
            }
            stack.push(element);
            lastClosed = null;
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            lastClosed = stack.pop();
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (stack.isEmpty()) {
                return;
            }
            String s = new String(ch, start, length);
            if (lastClosed != null) {
                lastClosed.tail = lastClosed.tail == null ? s : lastClosed.tail + s;
            } else {
                Element current = stack.peek();
                current.text = current.text == null ? s : current.text + s;
            }
        }

        @Override
        public InputSource resolveEntity(String name, String publicId, String baseURI, String systemId) {
            return new InputSource(new StringReader(""));
        }
    }

    /** {@code lxml.etree.XMLSyntaxError} */
    public static class XMLSyntaxError extends RuntimeException {
        public XMLSyntaxError(String message) {
            super(message);
        }
    }

    /** {@code lxml.etree.ParserError} */
    public static class ParserError extends RuntimeException {
        public ParserError(String message) {
            super(message);
        }
    }
}
