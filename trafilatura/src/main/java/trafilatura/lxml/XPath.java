package trafilatura.lxml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.jaxen.BaseXPath;
import org.jaxen.Context;
import org.jaxen.DefaultNavigator;
import org.jaxen.Function;
import org.jaxen.FunctionCallException;
import org.jaxen.JaxenException;
import org.jaxen.SimpleNamespaceContext;
import org.jaxen.XPathFunctionContext;
import org.jaxen.function.StringFunction;
import org.jaxen.util.SingleObjectIterator;

/**
 * Compiled XPath 1.0 expression evaluated against {@link Element} trees
 * ({@code lxml.etree.XPath}). The EXSLT regular expression functions are
 * available under the {@code re} prefix, as with lxml.
 */
public final class XPath {
    public static final String EXSLT_REGEXP = "http://exslt.org/regular-expressions";

    private static final Map<String, XPath> CACHE = new ConcurrentHashMap<>();
    private static final XPathFunctionContext FUNCTIONS = new XPathFunctionContext(false);

    static {
        FUNCTIONS.registerFunction(EXSLT_REGEXP, "test", new RegexTest());
    }

    private final String expression;
    private final BaseXPath compiled;

    public XPath(String expression) {
        this(expression, null);
    }

    /** The namespaces argument mirrors lxml's signature; the {@code re} prefix is always bound. */
    public XPath(String expression, Map<String, String> namespaces) {
        this.expression = expression;
        try {
            this.compiled = new BaseXPath(expression, TreeNavigator.INSTANCE);
        } catch (JaxenException err) {
            throw new IllegalArgumentException("Invalid expression: " + expression, err);
        }
        SimpleNamespaceContext ns = new SimpleNamespaceContext();
        ns.addNamespace("re", EXSLT_REGEXP);
        if (namespaces != null) {
            namespaces.forEach(ns::addNamespace);
        }
        compiled.setNamespaceContext(ns);
        compiled.setFunctionContext(FUNCTIONS);
    }

    static XPath cached(String expression) {
        return CACHE.computeIfAbsent(expression, XPath::new);
    }

    public String path() {
        return expression;
    }

    /** Selected elements, in document order. */
    public List<Element> evaluate(Element context) {
        List<Element> result = new ArrayList<>();
        for (Object node : select(context)) {
            if (node instanceof Element e) {
                result.add(e);
            }
        }
        return result;
    }

    /** String values of the selected nodes (text nodes, attributes, elements). */
    public List<String> strings(Element context) {
        List<String> result = new ArrayList<>();
        Object value = eval(context);
        if (value instanceof List<?> list) {
            for (Object node : list) {
                result.add(stringValue(node));
            }
        } else if (value != null) {
            result.add(StringFunction.evaluate(value, TreeNavigator.INSTANCE));
        }
        return result;
    }

    /** Raw result: a list of nodes (elements or strings), a String, a Double or a Boolean. */
    public Object eval(Element context) {
        try {
            Object value = compiled.evaluate(context);
            if (value instanceof List<?> list) {
                List<Object> converted = new ArrayList<>();
                for (Object node : list) {
                    converted.add(node instanceof Element ? node : stringValue(node));
                }
                return converted;
            }
            return value;
        } catch (JaxenException err) {
            throw new IllegalStateException("XPath evaluation failed: " + expression, err);
        }
    }

    private List<?> select(Element context) {
        try {
            return compiled.selectNodes(context);
        } catch (JaxenException err) {
            throw new IllegalStateException("XPath evaluation failed: " + expression, err);
        }
    }

    static String stringValue(Object node) {
        if (node instanceof TextNode t) {
            return t.value();
        }
        if (node instanceof AttributeNode a) {
            return a.value();
        }
        if (node instanceof Element e) {
            return e.text_content();
        }
        if (node instanceof DocumentNode d) {
            return d.root.text_content();
        }
        return String.valueOf(node);
    }

    // ------------------------------------------------------------------ node model

    /** Synthetic document node above the topmost element. */
    record DocumentNode(Element root) {
    }

    /** Text of an element ({@code tail == false}) or its tail ({@code tail == true}). */
    record TextNode(Element owner, boolean tail) {
        String value() {
            return tail ? owner.tail : owner.text;
        }
    }

    record AttributeNode(Element owner, String name, String value) {
    }

    /** One document node per root element: Jaxen compares parents by identity. */
    static DocumentNode docOf(Element root) {
        if (root.docNode == null) {
            root.docNode = new DocumentNode(root);
        }
        return root.docNode;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }

    /** Jaxen navigator exposing the text/tail model as XPath text nodes. */
    static final class TreeNavigator extends DefaultNavigator {
        static final TreeNavigator INSTANCE = new TreeNavigator();

        @Override
        public String getElementNamespaceUri(Object element) {
            return "";
        }

        @Override
        public String getElementName(Object element) {
            return ((Element) element).tag;
        }

        @Override
        public String getElementQName(Object element) {
            return ((Element) element).tag;
        }

        @Override
        public String getAttributeNamespaceUri(Object attr) {
            return "";
        }

        @Override
        public String getAttributeName(Object attr) {
            return ((AttributeNode) attr).name();
        }

        @Override
        public String getAttributeQName(Object attr) {
            return ((AttributeNode) attr).name();
        }

        @Override
        public boolean isDocument(Object object) {
            return object instanceof DocumentNode;
        }

        @Override
        public boolean isElement(Object object) {
            return object instanceof Element;
        }

        @Override
        public boolean isAttribute(Object object) {
            return object instanceof AttributeNode;
        }

        @Override
        public boolean isNamespace(Object object) {
            return false;
        }

        @Override
        public boolean isComment(Object object) {
            return false;
        }

        @Override
        public boolean isText(Object object) {
            return object instanceof TextNode;
        }

        @Override
        public boolean isProcessingInstruction(Object object) {
            return false;
        }

        @Override
        public String getCommentStringValue(Object comment) {
            return "";
        }

        @Override
        public String getElementStringValue(Object element) {
            return ((Element) element).text_content();
        }

        @Override
        public String getAttributeStringValue(Object attr) {
            return ((AttributeNode) attr).value();
        }

        @Override
        public String getNamespaceStringValue(Object ns) {
            return "";
        }

        @Override
        public String getTextStringValue(Object text) {
            return ((TextNode) text).value();
        }

        @Override
        public String getNamespacePrefix(Object ns) {
            return "";
        }

        @Override
        public org.jaxen.XPath parseXPath(String xpath) throws org.jaxen.saxpath.SAXPathException {
            return new BaseXPath(xpath, this);
        }

        @Override
        public Iterator<?> getChildAxisIterator(Object node) {
            if (node instanceof DocumentNode d) {
                return new SingleObjectIterator(d.root());
            }
            if (!(node instanceof Element e)) {
                return Collections.emptyIterator();
            }
            List<Object> nodes = new ArrayList<>();
            if (hasText(e.text)) {
                nodes.add(new TextNode(e, false));
            }
            for (Element child : e.children) {
                nodes.add(child);
                if (hasText(child.tail)) {
                    nodes.add(new TextNode(child, true));
                }
            }
            return nodes.iterator();
        }

        @Override
        public Object getParentNode(Object node) {
            if (node instanceof Element e) {
                return e.parent != null ? e.parent : docOf(e);
            }
            if (node instanceof TextNode t) {
                if (!t.tail()) {
                    return t.owner();
                }
                return t.owner().parent != null ? t.owner().parent : docOf(t.owner());
            }
            if (node instanceof AttributeNode a) {
                return a.owner();
            }
            return null;
        }

        @Override
        public Iterator<?> getParentAxisIterator(Object node) {
            Object parent = getParentNode(node);
            return parent == null ? Collections.emptyIterator() : new SingleObjectIterator(parent);
        }

        @Override
        public Iterator<?> getAttributeAxisIterator(Object node) {
            if (!(node instanceof Element e)) {
                return Collections.emptyIterator();
            }
            List<Object> attrs = new ArrayList<>();
            e.attrib.forEach((k, v) -> attrs.add(new AttributeNode(e, k, v)));
            return attrs.iterator();
        }

        @Override
        public Iterator<?> getNamespaceAxisIterator(Object node) {
            return Collections.emptyIterator();
        }

        @Override
        public Object getDocumentNode(Object node) {
            Object current = node;
            while (true) {
                if (current instanceof DocumentNode) {
                    return current;
                }
                Object parent = getParentNode(current);
                if (parent == null) {
                    return current;
                }
                current = parent;
            }
        }
    }

    // ------------------------------------------------------------------ EXSLT

    /** {@code re:test(string, pattern, flags)} */
    static final class RegexTest implements Function {
        private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

        @Override
        @SuppressWarnings("rawtypes")
        public Object call(Context context, List args) throws FunctionCallException {
            if (args.size() < 2) {
                throw new FunctionCallException("re:test() requires two or three arguments");
            }
            String input = StringFunction.evaluate(args.get(0), context.getNavigator());
            String regex = StringFunction.evaluate(args.get(1), context.getNavigator());
            String flags = args.size() > 2 ? StringFunction.evaluate(args.get(2), context.getNavigator()) : "";
            int bits = flags.contains("i") ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            Pattern pattern = PATTERNS.computeIfAbsent(bits + ":" + regex, k -> Pattern.compile(regex, bits));
            return pattern.matcher(input).find();
        }
    }
}
