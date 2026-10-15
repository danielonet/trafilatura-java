package trafilatura.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Python {@code re} module functions whose semantics differ from java.util.regex. */
public final class Re {
    private Re() {
    }

    /** {@code pattern.match(s)}: anchored at the start, null when there is no match. */
    public static Matcher match(Pattern pattern, String s) {
        Matcher m = pattern.matcher(s);
        return m.lookingAt() ? m : null;
    }

    /** {@code pattern.search(s)}: null when there is no match. */
    public static Matcher search(Pattern pattern, String s) {
        Matcher m = pattern.matcher(s);
        return m.find() ? m : null;
    }

    /** {@code pattern.fullmatch(s)} */
    public static Matcher fullmatch(Pattern pattern, String s) {
        Matcher m = pattern.matcher(s);
        return m.matches() ? m : null;
    }

    /** {@code pattern.split(s)}: captured groups are part of the result, as in Python. */
    public static List<String> split(Pattern pattern, String s) {
        return split(pattern, s, 0);
    }

    public static List<String> split(Pattern pattern, String s, int maxsplit) {
        List<String> parts = new ArrayList<>();
        Matcher m = pattern.matcher(s);
        int last = 0;
        int splits = 0;
        while (m.find() && (maxsplit <= 0 || splits < maxsplit)) {
            if (m.end() == 0 && m.start() == 0 && s.isEmpty()) {
                break;
            }
            parts.add(s.substring(last, m.start()));
            for (int g = 1; g <= m.groupCount(); g++) {
                parts.add(m.group(g));
            }
            last = m.end();
            splits++;
        }
        parts.add(s.substring(last));
        return parts;
    }

    /** {@code pattern.findall(s)}: whole matches, or group 1 when the pattern has exactly one group. */
    public static List<String> findall(Pattern pattern, String s) {
        List<String> result = new ArrayList<>();
        Matcher m = pattern.matcher(s);
        while (m.find()) {
            result.add(m.groupCount() == 1 ? m.group(1) : m.group());
        }
        return result;
    }

    /** {@code pattern.finditer(s)} */
    public static List<MatchResult> finditer(Pattern pattern, String s) {
        List<MatchResult> result = new ArrayList<>();
        Matcher m = pattern.matcher(s);
        while (m.find()) {
            result.add(m.toMatchResult());
        }
        return result;
    }

    /** {@code pattern.sub(repl, s, count)} with a Java replacement string ({@code $1} for {@code \1}). */
    public static String sub(Pattern pattern, String replacement, String s, int count) {
        if (count <= 0) {
            return pattern.matcher(s).replaceAll(replacement);
        }
        Matcher m = pattern.matcher(s);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        while (n < count && m.find()) {
            m.appendReplacement(sb, replacement);
            n++;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** {@code pattern.sub(function, s)} */
    public static String sub(Pattern pattern, Function<MatchResult, String> replacement, String s) {
        return pattern.matcher(s).replaceAll(m -> Matcher.quoteReplacement(replacement.apply(m)));
    }

    /** {@code re.escape(s)} */
    public static String escape(String s) {
        return Pattern.quote(s);
    }
}
