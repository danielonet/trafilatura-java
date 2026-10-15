package trafilatura.compat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.jsoup.parser.Parser;

/**
 * Python built-in semantics that have no direct Java equivalent
 * (truthiness, str methods using Python's definition of whitespace, slicing).
 */
public final class Py {
    private Py() {
    }

    /** Python truthiness: None, False, 0, empty strings and empty containers are false. */
    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof CharSequence s) {
            return !s.isEmpty();
        }
        if (value instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof byte[] b) {
            return b.length > 0;
        }
        return true;
    }

    /** {@code a or b} for strings. */
    public static String or(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    /** Python's {@code str.isspace()} definition of whitespace. */
    public static boolean isWhitespace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85;
    }

    /** {@code s.isspace()}: non-empty and only whitespace. */
    public static boolean isspace(String s) {
        return s != null && !s.isEmpty() && s.codePoints().allMatch(Py::isWhitespace);
    }

    /** {@code s.strip()} with Python whitespace (Java's strip() keeps U+00A0). */
    public static String strip(String s) {
        return rstrip(lstrip(s));
    }

    public static String lstrip(String s) {
        int i = 0;
        while (i < s.length() && isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    public static String rstrip(String s) {
        int j = s.length();
        while (j > 0 && isWhitespace(s.charAt(j - 1))) {
            j--;
        }
        return s.substring(0, j);
    }

    /** {@code s.strip(chars)} */
    public static String strip(String s, String chars) {
        return rstrip(lstrip(s, chars), chars);
    }

    public static String lstrip(String s, String chars) {
        int i = 0;
        while (i < s.length() && chars.indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return s.substring(i);
    }

    public static String rstrip(String s, String chars) {
        int j = s.length();
        while (j > 0 && chars.indexOf(s.charAt(j - 1)) >= 0) {
            j--;
        }
        return s.substring(0, j);
    }

    /** {@code s.split()}: split on runs of whitespace, no empty strings. */
    public static List<String> split(String s) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            while (i < n && isWhitespace(s.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < n && !isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i > start) {
                parts.add(s.substring(start, i));
            }
        }
        return parts;
    }

    /** {@code s.splitlines()} using Python's line boundaries. */
    public static List<String> splitlines(String s) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            boolean boundary = c == '\n' || c == '\r' || c == '\u000b' || c == '\u000c' || c == '\u001c'
                    || c == '\u001d' || c == '\u001e' || c == '\u0085' || c == ' ' || c == ' ';
            if (boundary) {
                lines.add(s.substring(start, i));
                if (c == '\r' && i + 1 < n && s.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
            i++;
        }
        if (start < n) {
            lines.add(s.substring(start));
        }
        return lines;
    }

    /** {@code s.isdigit()}: non-empty and only digits. */
    public static boolean isdigit(String s) {
        return s != null && !s.isEmpty() && s.codePoints().allMatch(Character::isDigit);
    }

    /** {@code s.isalpha()} */
    public static boolean isalpha(String s) {
        return s != null && !s.isEmpty() && s.codePoints().allMatch(Character::isLetter);
    }

    /** Python's {@code str.isprintable()} for a single code point. */
    public static boolean isprintable(int cp) {
        if (cp == ' ') {
            return true;
        }
        int type = Character.getType(cp);
        return switch (type) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
                    Character.UNASSIGNED, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                    Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }

    public static boolean isprintable(String s) {
        return s.codePoints().allMatch(Py::isprintable);
    }

    /** {@code s[start:end]} with Python's negative indexes and clamping; null means open. */
    public static String slice(String s, Integer start, Integer end) {
        int n = s.length();
        int a = start == null ? 0 : start < 0 ? Math.max(0, n + start) : Math.min(start, n);
        int b = end == null ? n : end < 0 ? Math.max(0, n + end) : Math.min(end, n);
        return a >= b ? "" : s.substring(a, b);
    }

    /** {@code html.unescape()} */
    public static String unescape(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s;
        }
        return Parser.unescapeEntities(s, false);
    }

    /** {@code s.title()} */
    public static String title(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean previousCased = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                sb.append(previousCased ? Character.toLowerCase(c) : Character.toTitleCase(c));
                previousCased = true;
            } else {
                sb.append(c);
                previousCased = false;
            }
        }
        return sb.toString();
    }

    /** {@code s.count(sub)} */
    public static int count(String s, String sub) {
        if (sub.isEmpty()) {
            return s.length() + 1;
        }
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /** {@code s.partition(sep)} */
    public static String[] partition(String s, String sep) {
        int idx = s.indexOf(sep);
        if (idx < 0) {
            return new String[] {s, "", ""};
        }
        return new String[] {s.substring(0, idx), sep, s.substring(idx + sep.length())};
    }

    /** {@code str(value)}: None prints as "None", booleans as "True"/"False". */
    public static String str(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (value instanceof String s) {
            return s;
        }
        return repr(value);
    }

    /** {@code repr(value)} for strings, numbers, booleans, None, lists and dicts. */
    public static String repr(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (value instanceof String s) {
            char quote = s.contains("'") && !s.contains("\"") ? '"' : '\'';
            StringBuilder sb = new StringBuilder().append(quote);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == quote || c == '\\') {
                    sb.append('\\').append(c);
                } else if (c == '\n') {
                    sb.append("\\n");
                } else if (c == '\r') {
                    sb.append("\\r");
                } else if (c == '\t') {
                    sb.append("\\t");
                } else if (c < 0x20 || c == 0x7f) {
                    sb.append(String.format("\\x%02x", (int) c));
                } else {
                    sb.append(c);
                }
            }
            return sb.append(quote).toString();
        }
        if (value instanceof Collection<?> c) {
            List<String> items = new ArrayList<>();
            for (Object item : c) {
                items.add(repr(item));
            }
            return "[" + String.join(", ", items) + "]";
        }
        if (value instanceof Map<?, ?> m) {
            List<String> items = new ArrayList<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                items.add(repr(e.getKey()) + ": " + repr(e.getValue()));
            }
            return "{" + String.join(", ", items) + "}";
        }
        return String.valueOf(value);
    }
}
