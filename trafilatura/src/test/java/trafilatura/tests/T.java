package trafilatura.tests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import trafilatura.compat.Py;

/** Small helpers standing in for Python literals and builtins in the ported tests. */
final class T {
    private T() {
    }

    /** An ordered dict literal: {@code map("a", 1, "b", 2)}. */
    static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put((String) keyValues[i], keyValues[i + 1]);
        }
        return result;
    }

    /** A list literal that may hold None (null). */
    static List<Object> list(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    /** A tuple of strings that may hold None (null). */
    static List<String> t(String... values) {
        return Arrays.asList(values);
    }

    /** {@code html.escape(s, quote)} */
    static String escape(String s, boolean quote) {
        s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        if (quote) {
            s = s.replace("\"", "&quot;").replace("'", "&#x27;");
        }
        return s;
    }

    static String escape(String s) {
        return escape(s, true);
    }

    /** {@code s.count(sub)} */
    static int count(String s, String sub) {
        return Py.count(s, sub);
    }

    static byte[] b(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
