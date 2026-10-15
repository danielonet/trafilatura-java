package trafilatura.lxml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jaxen.Context;
import org.jaxen.ContextSupport;
import org.jaxen.JaxenException;
import org.jaxen.Navigator;
import org.jaxen.XPathSyntaxException;
import org.jaxen.expr.DefaultXPathFactory;
import org.jaxen.expr.Expr;
import org.jaxen.expr.LocationPath;
import org.jaxen.expr.Step;
import org.jaxen.expr.UnionExpr;
import org.jaxen.saxpath.Axis;

import trafilatura.lxml.XPath.AttributeNode;
import trafilatura.lxml.XPath.DocumentNode;
import trafilatura.lxml.XPath.TextNode;

/**
 * Document order for XPath results.
 *
 * Jaxen sorts node-sets with a comparator that walks the following-sibling axis and scans to the end
 * of the sibling list whenever the second node comes first. TimSort compares every element with the
 * one before it, so sorting a node-set that is already in order cost a full scan per element: quadratic
 * on long sibling runs (a page of 10,000 paragraphs took over 20 seconds). Location paths and unions are
 * replaced here by equivalents sorting with positions taken from the tree, as lxml does.
 */
final class DocumentOrder {
    private DocumentOrder() {
    }

    /** Position of a node as a path of slots from the root: shorter prefixes are ancestors. */
    private static int[] path(Object node) {
        List<Integer> slots = new ArrayList<>();
        Element anchor;
        if (node instanceof Element e) {
            anchor = e;
        } else if (node instanceof TextNode t && !t.tail()) {
            // leading text: after the attributes, before the children
            anchor = t.owner();
            slots.add(1);
        } else if (node instanceof TextNode t) {
            // tail: between its owner and the owner's next sibling
            Element owner = t.owner();
            if (owner.parent == null) {
                anchor = owner;
                slots.add(Integer.MAX_VALUE);
            } else {
                anchor = owner.parent;
                slots.add(2 * owner.parent.index(owner) + 3);
            }
        } else if (node instanceof AttributeNode a) {
            anchor = a.owner();
            slots.add(attributeIndex(a));
            slots.add(0);
        } else if (node instanceof DocumentNode) {
            return new int[0];
        } else {
            throw new IllegalArgumentException("unknown node type: " + node);
        }
        for (Element e = anchor; e.parent != null; e = e.parent) {
            slots.add(2 * e.parent.index(e) + 2);
        }
        // the document node sorts first, then the root element
        slots.add(0);
        int[] result = new int[slots.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = slots.get(result.length - 1 - i);
        }
        return result;
    }

    private static int attributeIndex(AttributeNode a) {
        int i = 0;
        for (String key : a.owner().attrib.keySet()) {
            if (key.equals(a.name())) {
                return -a.owner().attrib.size() + i; // negative: before the leading text and the children
            }
            i++;
        }
        return -1;
    }

    private static Element root(Object node) {
        Element e = node instanceof Element el ? el
                : node instanceof TextNode t ? t.owner()
                : node instanceof AttributeNode a ? a.owner()
                : node instanceof DocumentNode d ? d.root() : null;
        while (e != null && e.parent != null) {
            e = e.parent;
        }
        return e;
    }

    static final Comparator<Object> COMPARATOR = (a, b) -> {
        if (a == b || a.equals(b)) {
            return 0;
        }
        Element ra = root(a);
        Element rb = root(b);
        if (ra != rb) {
            // separate trees: any stable order
            return Integer.compare(System.identityHashCode(ra), System.identityHashCode(rb));
        }
        int[] pa = path(a);
        int[] pb = path(b);
        int n = Math.min(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            if (pa[i] != pb[i]) {
                return Integer.compare(pa[i], pb[i]);
            }
        }
        return Integer.compare(pa.length, pb.length);
    };

    /** Sorts a node-set in document order; lists of other values (unions over non-nodes) are left as is. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void sort(List nodes) {
        if (nodes.size() < 2) {
            return;
        }
        for (Object node : nodes) {
            if (!(node instanceof Element || node instanceof TextNode || node instanceof AttributeNode
                    || node instanceof DocumentNode)) {
                return;
            }
        }
        nodes.sort(COMPARATOR);
    }

    private static boolean isReverseAxis(Step step) {
        int axis = step.getAxis();
        return axis == Axis.ANCESTOR || axis == Axis.ANCESTOR_OR_SELF || axis == Axis.PRECEDING
                || axis == Axis.PRECEDING_SIBLING;
    }

    /** {@code DefaultLocationPath} with the document-order sort above. */
    static final class FastLocationPath implements LocationPath {
        private static final long serialVersionUID = 1L;
        private final List<Step> steps = new ArrayList<>();
        private final boolean absolute;

        FastLocationPath(boolean absolute) {
            this.absolute = absolute;
        }

        @Override
        public void addStep(Step step) {
            steps.add(step);
        }

        @Override
        public List<Step> getSteps() {
            return steps;
        }

        @Override
        public boolean isAbsolute() {
            return absolute;
        }

        @Override
        public String getText() {
            StringBuilder sb = new StringBuilder(absolute ? "/" : "");
            for (int i = 0; i < steps.size(); i++) {
                if (i > 0) {
                    sb.append('/');
                }
                sb.append(steps.get(i).getText());
            }
            return sb.toString();
        }

        @Override
        public Expr simplify() {
            for (Step step : steps) {
                step.simplify();
            }
            return this;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Object evaluate(Context context) throws JaxenException {
            List nodeSet = context.getNodeSet();
            ContextSupport support = context.getContextSupport();
            if (absolute) {
                if (nodeSet.isEmpty()) {
                    return Collections.emptyList();
                }
                Navigator nav = support.getNavigator();
                Object docNode = nav.getDocumentNode(nodeSet.get(0));
                if (docNode == null) {
                    return Collections.emptyList();
                }
                nodeSet = Collections.singletonList(docNode);
            }
            List contextNodeSet = new ArrayList(nodeSet);
            Context stepContext = new Context(support);
            for (Step step : steps) {
                stepContext.setNodeSet(contextNodeSet);
                contextNodeSet = step.evaluate(stepContext);
                if (isReverseAxis(step)) {
                    Collections.reverse(contextNodeSet);
                }
            }
            if (steps.size() > 1 || nodeSet.size() > 1) {
                sort(contextNodeSet);
            }
            return contextNodeSet;
        }

        @Override
        public String toString() {
            return getText();
        }
    }

    /** {@code DefaultUnionExpr} with the document-order sort above. */
    static final class FastUnionExpr implements UnionExpr {
        private static final long serialVersionUID = 1L;
        private Expr lhs;
        private Expr rhs;

        FastUnionExpr(Expr lhs, Expr rhs) {
            this.lhs = lhs;
            this.rhs = rhs;
        }

        @Override
        public Expr getLHS() {
            return lhs;
        }

        @Override
        public Expr getRHS() {
            return rhs;
        }

        @Override
        public String getOperator() {
            return "|";
        }

        @Override
        public String getText() {
            return "(" + lhs.getText() + " | " + rhs.getText() + ")";
        }

        @Override
        public Expr simplify() {
            lhs = lhs.simplify();
            rhs = rhs.simplify();
            return this;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Object evaluate(Context context) throws JaxenException {
            try {
                List lhsResults = (List) lhs.evaluate(context);
                List rhsResults = (List) rhs.evaluate(context);
                List results = new ArrayList(lhsResults);
                Set unique = new HashSet(lhsResults);
                for (Object each : rhsResults) {
                    if (unique.add(each)) {
                        results.add(each);
                    }
                }
                sort(results);
                return results;
            } catch (ClassCastException err) {
                throw new XPathSyntaxException(getText(), context.getPosition(), "Unions are only allowed over node-sets");
            }
        }

        @Override
        public String toString() {
            return getText();
        }
    }

    /** Jaxen's expression factory, with the location paths and unions above. */
    static final class Factory extends DefaultXPathFactory {
        @Override
        public LocationPath createRelativeLocationPath() {
            return new FastLocationPath(false);
        }

        @Override
        public LocationPath createAbsoluteLocationPath() {
            return new FastLocationPath(true);
        }

        @Override
        public UnionExpr createUnionExpr(Expr lhs, Expr rhs) {
            return new FastUnionExpr(lhs, rhs);
        }
    }
}
