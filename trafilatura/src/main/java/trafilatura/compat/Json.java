package trafilatura.compat;

import java.util.Collection;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * {@code json.loads} / {@code json.dumps} with Python's types and output format:
 * objects become LinkedHashMap, arrays ArrayList, and dumps() uses ", " and ": ".
 */
public final class Json {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            // json.loads rejects anything after the first value ("Extra data")
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private Json() {
    }

    /** {@code json.JSONDecodeError} (a ValueError in Python). */
    public static class JSONDecodeError extends IllegalArgumentException {
        public JSONDecodeError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static Object loads(String text) {
        try {
            return MAPPER.readValue(text, Object.class);
        } catch (JsonProcessingException err) {
            throw new JSONDecodeError(err.getOriginalMessage(), err);
        }
    }

    public static String dumps(Object value) {
        return dumps(value, true, null);
    }

    public static String dumps(Object value, boolean ensure_ascii) {
        return dumps(value, ensure_ascii, null);
    }

    /** {@code json.dumps(value, ensure_ascii=..., indent=...)} */
    public static String dumps(Object value, boolean ensure_ascii, Integer indent) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, ensure_ascii, indent, 0);
        return sb.toString();
    }

    private static void newline(StringBuilder sb, Integer indent, int level) {
        if (indent != null) {
            sb.append('\n').append(" ".repeat(indent * level));
        }
    }

    private static void write(StringBuilder sb, Object value, boolean ascii, Integer indent, int level) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d)) {
                sb.append("NaN");
            } else if (Double.isInfinite(d)) {
                sb.append(d > 0 ? "Infinity" : "-Infinity");
            } else {
                sb.append(pythonFloat(d));
            }
        } else if (value instanceof Number n) {
            sb.append(n);
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(indent != null ? "," : ", ");
                }
                first = false;
                newline(sb, indent, level + 1);
                writeString(sb, String.valueOf(entry.getKey()), ascii);
                sb.append(": ");
                write(sb, entry.getValue(), ascii, indent, level + 1);
            }
            newline(sb, indent, level);
            sb.append('}');
        } else if (value instanceof Collection<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(indent != null ? "," : ", ");
                }
                first = false;
                newline(sb, indent, level + 1);
                write(sb, item, ascii, indent, level + 1);
            }
            newline(sb, indent, level);
            sb.append(']');
        } else {
            writeString(sb, value.toString(), ascii);
        }
    }

    private static String pythonFloat(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e16) {
            return String.valueOf((long) d) + ".0";
        }
        return Double.toString(d);
    }

    private static void writeString(StringBuilder sb, String s, boolean ascii) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || (ascii && c > 0x7f)) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
