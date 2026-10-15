// Java port of tests/repeated_output_tests.py
package trafilatura.tests;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import trafilatura.compat.Py;
import trafilatura.trafilatura.Core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static trafilatura.trafilatura.Core.extract;

/** Synthetic pages where every word is unique, so any word repeated in the output is a duplicate. */
class RepeatedOutputTests {
    // each @ becomes a unique word
    static final Map<String, String> INLINE = new LinkedHashMap<>();

    static {
        INLINE.put("a", "<a href=\"/x\">@ @</a>");
        INLINE.put("b", "<b>@ @</b>");
        INLINE.put("br", "<br/>");
        INLINE.put("code", "<code>@</code>");
        INLINE.put("em", "<em>@ @</em>");
        INLINE.put("img", "<img src=\"/x.png\" alt=\"@ @\"/>");
        INLINE.put("span", "<span>@ @</span>");
        INLINE.put("sup", "<sup>@</sup>");
        INLINE.put("a_code", "<a href=\"/x\"><code>@</code></a>");
        INLINE.put("b_code", "<b><code>@</code></b>");
    }

    // {} is replaced by a run of text with inline elements
    static final Map<String, String> BLOCKS = new LinkedHashMap<>();

    static {
        BLOCKS.put("p", "<p>{}</p>");
        BLOCKS.put("div", "<div>{}</div>");
        BLOCKS.put("span_p", "<span data-as=\"p\">{}</span>");
        BLOCKS.put("span_p_holds_p", "<span data-as=\"p\">{}<p>{}</p></span>@ @ @");
        BLOCKS.put("span_p_then_p", "<span data-as=\"p\">{}</span><p>{}</p>@ @ @");
        BLOCKS.put("li", "<ul><li>{}</li><li>{}</li></ul>@ @ @");
        BLOCKS.put("nested_li", "<ul><li>{}<ul><li>{}</li><li>@ @ @</li></ul></li></ul>");
        BLOCKS.put("p_in_li", "<ul><li><p>{}</p></li><li>@ @</li></ul>@ @ @");
        BLOCKS.put("table_in_li", "<ul><li>@ @<table><tr><td>{}</td></tr></table></li></ul>@ @ @");
        BLOCKS.put("blockquote_in_li", "<ul><li><blockquote><p>{}</p></blockquote>@ @ @</li><li>@ @</li></ul>@ @ @");
        BLOCKS.put("ol", "<ol><li>{}</li><li>@ @ @</li></ol>@ @ @");
        BLOCKS.put("table", "<table><tr><th>@</th><th>@</th></tr><tr><td>{}</td><td>@ @</td></tr></table>@ @ @");
        BLOCKS.put("table_in_td", "<table><tr><td><table><tr><td>{}</td><td>@ @</td></tr></table>@ @ @</td><td>@ @</td></tr></table>@ @ @");
        BLOCKS.put("caption", "<table><caption><p>{}</p></caption><tr><td>@ @</td><td>@ @</td></tr></table>@ @ @");
        BLOCKS.put("blockquote", "<blockquote><p>{}</p></blockquote>@ @ @");
        BLOCKS.put("pre", "<p>{}</p><pre>@ @ @</pre>@ @ @");
        BLOCKS.put("figure", "<figure><img src=\"/f.png\" alt=\"@ @\"/><figcaption>{}</figcaption></figure>@ @ @");
        BLOCKS.put("figure_then_p", "<figure><img src=\"/f.png\" alt=\"@ @\"/><figcaption>{}</figcaption></figure>@ @ @<p>{}</p>");
        BLOCKS.put("hidden", "<p>{}</p><div style='display:none'>" + "@ ".repeat(60) + "</div>");
        BLOCKS.put("h2", "<h2>@ @ {}</h2>@ @ @");
    }

    static final Map<String, String> WRAPPERS = new LinkedHashMap<>();

    static {
        WRAPPERS.put("bare", "{}");
        WRAPPERS.put("nested", "<div class=\"page\"><div class=\"col\"><main><div class=\"content\">{}</div></main></div></div>");
        WRAPPERS.put("article", "<main id='content'><article>{}</article><p>@ @ @</p></main>");
    }

    static final Map<String, Supplier<Core.Kwargs>> CONFIGS = new LinkedHashMap<>();

    static {
        CONFIGS.put("markdown", () -> Core.kw().output_format("markdown").include_formatting(true).include_links(true)
                .include_images(true));
        CONFIGS.put("recall", () -> Core.kw().favor_recall(true));
        CONFIGS.put("precision", () -> Core.kw().favor_precision(true));
    }

    static final String FILLER = ("<p>" + "@ ".repeat(25) + "</p>").repeat(3);

    record Key(String block, String inline, boolean shortPage, String wrapper, String config) {
    }

    // precision drops these short pages entirely, which would make the test pass vacuously
    static final Set<Key> EMPTY = Set.of(
            new Key("span_p", "a", true, "nested", "precision"),
            new Key("li", "a", true, "nested", "precision"),
            new Key("nested_li", "a", true, "bare", "precision"),
            new Key("nested_li", "a", true, "nested", "precision"));

    static final String MOVED = "moved block re-emitted by recall recovery";
    static final String NESTED_CODE = "code nested in an inline re-emitted by recovery";
    static final Map<Key, String> KNOWN_DUPLICATES = new HashMap<>();

    static {
        for (String block : List.of("span_p_holds_p", "span_p_then_p")) {
            for (String inline : INLINE.keySet()) {
                if (!inline.equals("a") && !inline.equals("a_code")) {
                    KNOWN_DUPLICATES.put(new Key(block, inline, true, "nested", "recall"), MOVED);
                }
            }
        }
        for (String inline : List.of("b", "em", "img", "span", "sup")) {
            KNOWN_DUPLICATES.put(new Key("figure_then_p", inline, true, "nested", "recall"), MOVED);
        }
        String[][] nested = {
            {"div", "b_code", "nested"},
            {"div", "b_code", "article"},
            {"span_p", "a_code", "article"},
            {"span_p", "b_code", "nested"},
            {"span_p", "b_code", "article"},
            {"span_p_holds_p", "a_code", "article"},
            {"span_p_holds_p", "b_code", "article"},
            {"span_p_then_p", "a_code", "article"},
            {"span_p_then_p", "b_code", "article"},
        };
        for (String[] entry : nested) {
            KNOWN_DUPLICATES.put(new Key(entry[0], entry[1], true, entry[2], "markdown"), NESTED_CODE);
        }
        KNOWN_DUPLICATES.put(new Key("blockquote", "a_code", true, "article", "precision"),
                "dropped blockquote, its tail emitted twice");
    }

    /** Only raised for repeated words, so a known duplicate cannot fail for another reason. */
    static class RepeatedWordsError extends AssertionError {
        RepeatedWordsError(Object detail) {
            super(detail);
        }
    }

    static final Pattern WORD = Pattern.compile("w\\d+x");

    static List<String> _assert_unique(String result) {
        List<String> words = new ArrayList<>();
        Matcher m = WORD.matcher(result);
        while (m.find()) {
            words.add(m.group());
        }
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (String w : words) {
            counter.merge(w, 1, Integer::sum);
        }
        List<String> repeated = counter.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
        if (!repeated.isEmpty()) {
            throw new RepeatedWordsError(repeated.subList(0, Math.min(5, repeated.size())));
        }
        return words;
    }

    static String _number(String body) {
        AtomicInteger ids = new AtomicInteger();
        return Pattern.compile("@").matcher("<html><body>" + body + "</body></html>")
                .replaceAll(m -> "w" + ids.getAndIncrement() + "x");
    }

    static String _page(String block, String inline, String wrapper, boolean shortPage) {
        String run = "@ @ @ @ " + INLINE.get(inline) + " @ @ @ " + INLINE.get(inline) + " @ @ @ @.";
        String body = BLOCKS.get(block).replace("{}", run);
        if (!shortPage) {
            body = FILLER + body + FILLER;
        }
        return _number(WRAPPERS.get(wrapper).replace("{}", body));
    }

    /** pytest.mark.xfail(strict=True, raises=RepeatedWordsError) */
    static void xfailStrict(String reason, Executable test) {
        if (reason == null) {
            try {
                test.execute();
            } catch (Throwable err) {
                if (err instanceof RuntimeException r) {
                    throw r;
                }
                if (err instanceof Error e) {
                    throw e;
                }
                throw new IllegalStateException(err);
            }
        } else {
            assertThrows(RepeatedWordsError.class, test, "XPASS(strict): " + reason);
        }
    }

    static Stream<Arguments> productCases() {
        List<Arguments> cases = new ArrayList<>();
        for (String block : BLOCKS.keySet()) {
            for (String inline : INLINE.keySet()) {
                for (boolean shortPage : List.of(false, true)) {
                    for (String wrapper : WRAPPERS.keySet()) {
                        for (String config : CONFIGS.keySet()) {
                            cases.add(Arguments.of(block, inline, shortPage, wrapper, config));
                        }
                    }
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("productCases")
    void test_no_repeated_words(String block, String inline, boolean shortPage, String wrapper, String config) {
        Key key = new Key(block, inline, shortPage, wrapper, config);
        xfailStrict(KNOWN_DUPLICATES.get(key), () -> {
            String result = extract(_page(block, inline, wrapper, shortPage), CONFIGS.get(config).get());
            if (EMPTY.contains(key)) {
                assertNull(result);
            } else {
                assertTrue(Py.truthy(result));
                _assert_unique(result);
            }
        });
    }

    static Stream<Arguments> overPrunedCases() {
        List<Arguments> cases = new ArrayList<>();
        for (String config : List.of("markdown", "precision")) {
            for (String wrapper : List.of("nested", "article")) {
                for (String block : List.of("p_in_li", "blockquote_in_li", "table_in_li")) {
                    cases.add(Arguments.of(block, wrapper, config));
                }
            }
        }
        return cases.stream();
    }

    /** A sidebar holding most of the text trips the over-pruning guard, recovery still skips consumed elements. */
    @ParameterizedTest
    @MethodSource("overPrunedCases")
    void test_over_pruned_page_not_repeated(String block, String wrapper, String config) {
        AtomicInteger tokens = new AtomicInteger();
        StringBuilder sidebar = new StringBuilder("<div class='sidebar'>");
        for (int i = 0; i < 12; i++) {
            List<String> words = new ArrayList<>();
            for (int j = 0; j < 40; j++) {
                words.add("s" + tokens.getAndIncrement() + "x");
            }
            sidebar.append("<p>").append(String.join(" ", words)).append("</p>");
        }
        sidebar.append("</div>");
        String page = _page(block, "br", wrapper, true).replace("</body>", sidebar + "</body>");
        String result = extract(page, CONFIGS.get(config).get());
        assertTrue(Py.truthy(result));
        assertTrue(Py.truthy(_assert_unique(result)));
    }

    static Stream<Arguments> inlineFrameCases() {
        List<Arguments> cases = new ArrayList<>();
        for (String frame : List.of("article", "main")) {
            for (String inline : List.of("a", "b", "code", "em")) {
                cases.add(Arguments.of(frame, inline));
            }
        }
        return cases.stream();
    }

    /** A non-div frame holding a single formatted text run must not lose it to a sidebar. */
    @ParameterizedTest
    @MethodSource("inlineFrameCases")
    void test_inline_only_frame_kept(String frame, String inline) {
        String run = (INLINE.get(inline) + " @ @ @ " + INLINE.get(inline) + " @ @ @ @. ").repeat(3);
        String result = extract(_number("<" + frame + ">" + run + "</" + frame + "><div>" + FILLER + "</div>"),
                CONFIGS.get("markdown").get());
        assertTrue(Py.truthy(result));
        assertTrue(result.contains("w0x"));
    }

    static Stream<Arguments> frameLeadCases() {
        List<Arguments> cases = new ArrayList<>();
        for (String config : CONFIGS.keySet()) {
            for (String child : List.of("<br/>", "<p>@ @ @ @ @ @ @ @ @ @.</p>")) {
                cases.add(Arguments.of(config, child));
            }
        }
        return cases.stream();
    }

    /** The frame's own text before its first child is part of the content (Blogger post bodies). */
    @ParameterizedTest
    @MethodSource("frameLeadCases")
    void test_frame_lead_text_kept(String config, String child) {
        String sentence = "@ @ @ @ @ @ @ @ @ @. ".repeat(3);
        String body = "<div class=\"post-body entry-content\">" + sentence + child + FILLER + "</div>";
        String result = extract(_number(body), CONFIGS.get(config).get());
        assertTrue(Py.truthy(result));
        assertEquals("w0x", _assert_unique(result).get(0));
    }

    static Stream<Arguments> commentListCases() {
        List<Arguments> cases = new ArrayList<>();
        List<String> configs = List.of("default", "fast", "favor_recall", "favor_precision");
        for (String config : configs) {
            for (String[] tagCls : new String[][] {{"ol", "commentlist"}, {"ul", "comment-list"}}) {
                cases.add(Arguments.of(tagCls[0], tagCls[1], config));
            }
        }
        return cases.stream();
    }

    static Core.Kwargs commentConfig(String config) {
        return switch (config) {
            case "fast" -> Core.kw().fast(true);
            case "favor_recall" -> Core.kw().favor_recall(true);
            case "favor_precision" -> Core.kw().favor_precision(true);
            default -> Core.kw();
        };
    }

    /** Replies of a captured comment list appear once, even when the article is too short. */
    @ParameterizedTest
    @MethodSource("commentListCases")
    void test_short_article_comment_list_not_repeated(String tag, String cls, String config) {
        String reason = !config.equals("favor_precision") ? "the replies also land in the body" : null;
        String replies = ("<li><p>" + "@ ".repeat(25) + "</p></li>").repeat(8);
        String body = "<article><p>@ @ @ @ @ @ @ @ @ @.</p></article><" + tag + " class='" + cls + "'>" + replies + "</" + tag + ">";
        xfailStrict(reason, () -> {
            String result = extract(_number(body), commentConfig(config));
            assertTrue(Py.truthy(result));
            assertEquals(Py.count(body, "@"), _assert_unique(result).size());
        });
    }
}
