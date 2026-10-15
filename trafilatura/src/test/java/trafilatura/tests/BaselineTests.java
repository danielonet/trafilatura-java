// Java port of tests/baseline_tests.py
package trafilatura.tests;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import trafilatura.compat.Json;
import trafilatura.compat.Pair;
import trafilatura.compat.Triple;
import trafilatura.lxml.Element;
import trafilatura.lxml.Etree;
import trafilatura.trafilatura.Baseline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static trafilatura.tests.T.b;
import static trafilatura.tests.T.count;
import static trafilatura.tests.T.escape;
import static trafilatura.tests.T.list;
import static trafilatura.tests.T.map;
import static trafilatura.trafilatura.Baseline._build_body;
import static trafilatura.trafilatura.Baseline.baseline;
import static trafilatura.trafilatura.Baseline.html2txt;

/** Unit tests for baseline functions of the trafilatura library. */
class BaselineTests {
    /** Wrap a JSON-LD payload (and optional body HTML) into a minimal page. */
    static String jsonld_doc(String payload, String body) {
        return "<html><head><script type=\"application/ld+json\">" + payload + "</script></head><body>" + body + "</body></html>";
    }

    static String jsonld_doc(String payload) {
        return jsonld_doc(payload, "");
    }

    static String text(Object doc) {
        return baseline(doc).second();
    }

    @Test
    void test_baseline() {
        // Empty input
        Triple<Element, String, Integer> result = baseline(new byte[0]);
        assertEquals("body", result.first().tag);
        assertEquals("", result.second());
        assertEquals(0, result.third());

        result = baseline("");
        assertEquals("body", result.first().tag);
        assertEquals("", result.second());
        assertEquals(0, result.third());

        // Invalid HTML
        assertEquals("", text(b("<invalid html>")));

        List<Pair<String, String>> tests = List.of(
                Pair.of("<html><body><article>" + "The article consists of this text.".repeat(10) + "</article></body></html>",
                        "The article consists of this text."),
                Pair.of("<html><body><article><b>The article consists of this text.</b></article></body></html>",
                        "The article consists of this text."),
                Pair.of("<html><body><quote>This is only a quote but it is better than nothing.</quote></body></html>",
                        "This is only a quote but it is better than nothing."));
        for (Pair<String, String> test : tests) {
            assertTrue(text(test.first()).contains(test.second()));
        }

        // Invalid JSON
        byte[] filecontent = b("""

                        <html>
                            <body>
                                <script type="application/ld+json">
                                    {"articleBody": "This is the article body, it has to be long enough to fool the length threshold which is set at len 100."  # invalid JSON
                                </script>
                            </body>
                        </html>
                    """);
        assertEquals("", text(filecontent));

        // JSON OK
        filecontent = b("""

                        <html>
                            <body>
                                <script type="application/ld+json">
                                    {
                                        "@type": "Article",
                                        "articleBody": "This is the article body, it has to be long enough to fool the length threshold which is set at len 100."
                                    }
                                </script>
                            </body>
                        </html>
                    """);
        assertTrue(text(filecontent).length() > 100);

        // articleBody wrapped in a single paragraph: markup is parsed, not returned literally
        filecontent = b("""

                        <html>
                            <body>
                                <script type="application/ld+json">
                                    {
                                        "@type": "Article",
                                        "articleBody": "<p>This is the article body, it has to be long enough to fool the length threshold which is set at len 100.<\\/p>"
                                    }
                                </script>
                            </body>
                        </html>
                    """);
        String res = text(filecontent);
        assertTrue(res.startsWith("This is the article body"));
        assertFalse(res.contains("<p>"));

        // Real-world examples
        String my_document = "<html><body><script type=\"application/ld+json\">{\"description\":\"In letzter Zeit kam man am Begriff \\\"Hygge\\\", was so viel wie \\\"angenehm\\\" oder \\\"gemütlich\\\" bedeutet, ja nicht vorbei. Jetzt macht ihm ein neuer Glücks-Trend ...\",\"image\":[{\"name\":\"Mit der Ikigai-Methode wirst du glücklicher\",\"url\":\"https:\\/\\/image.brigitte.de\\/10973004\\/uncropped-0-0\\/7d00b2658fd0a3b19e1b161f4657cc20\\/Xw\\/ikigai--1-.jpg\",\"width\":\"2048\",\"height\":\"1366\",\"@type\":\"ImageObject\"},{\"name\":\"Mit der Ikigai-Methode wirst du glücklicher\",\"url\":\"https:\\/\\/image.brigitte.de\\/10973004\\/16x9-1280-720\\/bf947c7c24167d7c0adae0be10942d57\\/Uf\\/ikigai--1-.jpg\",\"width\":\"1280\",\"height\":\"720\",\"@type\":\"ImageObject\"},{\"name\":\"Mit der Ikigai-Methode wirst du glücklicher\",\"url\":\"https:\\/\\/image.brigitte.de\\/10973004\\/16x9-938-528\\/bf947c7c24167d7c0adae0be10942d57\\/JK\\/ikigai--1-.jpg\",\"width\":\"938\",\"height\":\"528\",\"@type\":\"ImageObject\"},{\"name\":\"Mit der Ikigai-Methode wirst du glücklicher\",\"url\":\"https:\\/\\/image.brigitte.de\\/10973004\\/large1x1-622-622\\/f5544b7d67e1be04f7729b130e7e0485\\/KN\\/ikigai--1-.jpg\",\"width\":\"622\",\"height\":\"622\",\"@type\":\"ImageObject\"}],\"mainEntityOfPage\":{\"@id\":\"https:\\/\\/www.brigitte.de\\/liebe\\/persoenlichkeit\\/ikigai-macht-dich-sofort-gluecklicher--10972896.html\",\"@type\":\"WebPage\"},\"headline\":\"Ikigai macht dich sofort glücklicher!\",\"datePublished\":\"2019-06-19T14:29:08+0000\",\"dateModified\":\"2019-06-19T14:29:10+0000\",\"author\":{\"name\":\"BRIGITTE.de\",\"@type\":\"Organization\"},\"publisher\":{\"name\":\"BRIGITTE.de\",\"logo\":{\"url\":\"https:\\/\\/image.brigitte.de\\/11476842\\/uncropped-0-0\\/f19537e97b9189bf0f25ce924168bedb\\/kK\\/bri-logo-schema-org.png\",\"width\":\"167\",\"height\":\"60\",\"@type\":\"ImageObject\"},\"@type\":\"Organization\"},\"articleBody\":\"In letzter Zeit kam man am Begriff \\\"Hygge\\\" (\\\"gemütlich\\\" oder \\\"angenehm\\\") nicht vorbei. Jetzt macht ihm ein neuer Glücks-Trend Konkurrenz: \\\"Ikigai\\\". Bist du glücklich? Schwierige Frage, nicht wahr? Viele von uns müssen da erst mal überlegen.\",\"@type\":\"NewsArticle\"}</script></body></html>";
        res = text(my_document);
        assertTrue(res.startsWith("In letzter Zeit kam man"));
        assertTrue(res.endsWith("erst mal überlegen."));

        my_document = "<html><body><div>   Document body...   </div><script> console.log('Hello world') </script></body></html>";
        assertEquals("Document body...", text(my_document));
    }

    /** regression #849/#884: a nested scraped element (inline <code>, <p> in <blockquote>) must not be scraped twice. */
    @Test
    void test_baseline_no_nested_element_duplication() {
        String filler = "Some surrounding paragraph text that is comfortably long enough to pass the one hundred character length gate.";
        String snippet = "pip install trafilatura";
        String doc = "<html><body><p>" + filler + "</p><p>Use <code>" + snippet + "</code> for setup.</p></body></html>";
        assertEquals(1, count(text(doc), snippet));
        doc = "<html><body><blockquote><p>" + filler + "</p><p>Second paragraph inside the quote.</p></blockquote></body></html>";
        assertEquals(1, count(text(doc), filler));
    }

    /** regression: a too-short JSON-LD body must not leave a stale postbody that blocks later strategies. */
    @Test
    void test_baseline_strategy_fallthrough() {
        String para = "Real paragraph content that should be extracted by the paragraph strategy, comfortably long enough for the gate.";
        Triple<Element, String, Integer> result = baseline(jsonld_doc("{\"articleBody\": \"Too short.\"}", "<p>" + para + "</p>"));
        assertTrue(result.second().contains(para));
        assertTrue(result.third() > 100);
    }

    /** regression: articleBody must be found in list-wrapped and @graph-nested JSON-LD, not just flat dicts. */
    @Test
    void test_baseline_jsonld_shapes() {
        String body_text = "Body text from structured data, made comfortably long enough to pass the one hundred character length threshold.";
        String flat = "{\"articleBody\": \"" + body_text + "\"}";
        String graph = "{\"@context\": \"https://schema.org\", \"@graph\": [{\"@type\": \"Article\", \"articleBody\": \"" + body_text + "\"}]}";
        String wrapped = "[{\"@type\": \"Article\", \"articleBody\": \"" + body_text + "\"}]";
        String mixed = "[42, {\"@type\": \"Article\", \"articleBody\": \"" + body_text + "\"}]"; // non-dict list item is skipped
        for (String payload : List.of(flat, graph, wrapped, mixed)) {
            assertEquals(1, count(text(jsonld_doc(payload)), body_text));
        }
    }

    static String run(String body) {
        return text(jsonld_doc("{\"articleBody\": \"" + body + "\"}"));
    }

    /** regression: articleBody with raw control chars, p-less markup or HTML-escaped markup yields clean text. */
    @Test
    void test_baseline_jsonld_content() {
        // raw newline/tab inside the JSON string (invalid in strict mode but common in the wild)
        String ctrl = "First line of the body text\n\twith a raw newline and a tab inside the JSON string, comfortably long enough to pass the gate.";
        assertTrue(run(ctrl).contains("First line of the body text with a raw newline"));
        // markup without <p> must not leak literal tags
        String markup = "Opening text<br/>more after a break element<div>and inside a div</div> comfortably long enough to pass the one hundred character gate.";
        assertFalse(run(markup).contains("<"));
        assertTrue(run(markup).contains("Opening text"));
        // HTML-escaped markup must be unescaped and parsed, not emitted as entities
        String escaped = "&lt;p&gt;Escaped paragraph body text that should come out clean, comfortably long enough to pass the one hundred character gate.&lt;/p&gt;";
        String result = run(escaped);
        assertFalse(result.contains("&lt;"));
        assertTrue(result.contains("Escaped paragraph body text"));
        // a lone comparison operator in plain prose stays intact
        String prose = "Comparing a < b in plain prose should stay intact, with padding to exceed the one hundred character gate.";
        assertTrue(run(prose).contains("a < b"));
        // tag-shaped comparison expressions (a letter right after "<") must not be swallowed either
        String expr = "For x<y and z>0 the series converges absolutely, with plenty of extra padding to exceed the length gate here.";
        assertTrue(run(expr).contains("x<y and z>0"));
        // the letters after "<" are single-letter TAG names (p, q, u, s, i, b)
        String tagletters = "If p<q then u<s or i<b and c>d holds, with extra padding to comfortably exceed the one hundred character gate.";
        assertTrue(run(tagletters).contains("p<q"));
        assertTrue(run(tagletters).contains("i<b and c>d holds"));
    }

    /**
     * regression (review round 3): a schema.org Product/VideoObject description is a short teaser
     * and must not preempt a longer whole-body dump carrying the real content in bare divs.
     */
    @Test
    void test_baseline_teaser_does_not_shadow_longer_body() {
        String desc = "Short marketing product blurb standing in as a JSON-LD teaser, just past the one hundred character content gate here.";
        String divs = IntStream.range(0, 5)
                .mapToObj(i -> "<div>Real article paragraph number " + i + " with genuine sentence content that only the "
                        + "whole-body dump captures from these bare div elements on the page.</div>")
                .collect(Collectors.joining());
        String jsonld = "{\"@type\": \"Product\", \"description\": \"" + desc + "\"}";
        assertTrue(text(jsonld_doc(jsonld, divs)).contains("Real article paragraph number 3"));
    }

    /** the teaser still wins when the body dump is shorter than it (near-empty body, e.g. a JS shell). */
    @Test
    void test_baseline_teaser_used_when_body_shorter() {
        String desc = "Full product description carrying the actual page content, comfortably longer than the sparse body below and past the gate.";
        String jsonld = "{\"@type\": \"Product\", \"description\": \"" + desc + "\"}";
        assertTrue(text(jsonld_doc(jsonld, "<div>x</div>")).contains("Full product description"));
    }

    /**
     * Product/VideoObject descriptions are last-resort teasers: used when alone on the page,
     * outranked by any full-text source (a JSON articleBody or an HTML paragraph).
     */
    @ParameterizedTest
    @ValueSource(strings = {"Product", "VideoObject"})
    void test_baseline_description_teaser_tier(String teaser_type) {
        String teaser = "Description teaser standing in for page content, comfortably longer than the one hundred character gate here.";
        String fulltext = "The full body text which must take precedence over the description teaser and clears the gate comfortably.";
        String teaser_json = Json.dumps(map("@type", teaser_type, "description", teaser));
        assertTrue(text(jsonld_doc(teaser_json)).contains(teaser)); // alone -> used
        String both = Json.dumps(list(map("@type", teaser_type, "description", teaser), map("@type", "Article", "articleBody", fulltext)));
        String outranked = text(jsonld_doc(both)); // JSON full-text property wins
        assertTrue(outranked.contains(fulltext));
        assertFalse(outranked.contains(teaser));
        outranked = text(jsonld_doc(teaser_json, "<p>" + fulltext + "</p>")); // HTML paragraph wins
        assertTrue(outranked.contains(fulltext));
        assertFalse(outranked.contains(teaser));
    }

    /**
     * regression: the same JSON-LD block embedded twice (theme and SEO plugin both emitting
     * it) must not duplicate the article body; same for duplicated teaser descriptions.
     */
    @Test
    void test_baseline_jsonld_double_embed() {
        String body = "Article body text embedded twice via duplicated JSON-LD scripts, comfortably longer than the one hundred character strategy gate.";
        String desc = "Video description standing in for page content here, comfortably longer than the one hundred character gate.";
        for (Pair<String, String> pair : List.of(
                Pair.of(body, "{\"articleBody\": \"%s\"}"),
                Pair.of(desc, "{\"@type\": \"VideoObject\", \"description\": \"%s\"}"))) {
            String script = "<script type=\"application/ld+json\">" + pair.second().formatted(pair.first()) + "</script>";
            String result = text("<html><head>" + script + script + "</head><body></body></html>");
            assertEquals(1, count(result, pair.first()));
        }
    }

    static final String _CTRL = "before\u0001after, with plenty of padding words to pass the one hundred character strategy gate comfortably.";

    /**
     * regression: non-whitespace control chars survive json.loads(strict=False) and caller-built trees;
     * they must not crash at any baseline sink (JSON/paragraph _build_body, whole-body dump).
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "<html><head><script type=\"application/ld+json\">{\"articleBody\": \"before\u0001after, with plenty of padding words to pass the one hundred character strategy gate comfortably.\"}</script></head><body></body></html>",
        "<html><body><p>before\u0001after, with plenty of padding words to pass the one hundred character strategy gate comfortably.</p></body></html>",
        "<html><body><div>before\u0001after, with plenty of padding words to pass the one hundred character strategy gate comfortably.</div></body></html>",
    })
    void test_baseline_control_chars_tree_input(String doc) {
        Triple<Element, String, Integer> result = baseline(Etree.html_fromstring(doc));
        assertTrue(result.third() > 100);
        assertFalse(result.second().contains("\u0001"));
        assertTrue(result.second().contains("before"));
        assertTrue(result.second().contains("after"));
    }

    /**
     * regression: a <p> nested in a <blockquote> with surrounding text must not be emitted twice
     * by the paragraph strategy (its text is a substring of the container's, not an exact match).
     */
    @Test
    void test_baseline_nested_paragraph_not_duplicated() {
        String para = "Quoted paragraph text repeated by nesting here, made comfortably longer than the one hundred character strategy gate threshold.";
        String doc = "<html><body><blockquote>Attribution line: <p>" + para + "</p></blockquote></body></html>";
        assertEquals(1, count(text(doc), para));
    }

    /**
     * regression: the paragraph strategy's substring dedup must not drop a short, distinct
     * paragraph just because its text recurs inside earlier, unrelated prose.
     */
    @Test
    void test_baseline_paragraph_dedup_keeps_short_repeats() {
        String long_para = "The annual conference will be held in Berlin this coming September, organizers said today.";
        String short_para = "held in Berlin";
        String doc = "<html><body><p>" + long_para + "</p><p>" + short_para + "</p></body></html>";
        assertEquals(2, count(text(doc), short_para)); // once inside long_para, once standalone
    }

    /**
     * regression (review round 3): the substring dedup accumulator must not drop a distinct
     * paragraph whose text spans the join boundary of two earlier consecutive paragraphs.
     */
    @Test
    void test_baseline_dedup_keeps_cross_boundary_paragraph() {
        String p1 = "The meeting concluded with remarks about the annual budget";
        String p2 = "planning process that begins next week according to officials.";
        String p3 = "annual budget planning process that begins next week"; // tail of p1 + head of p2, >50 chars
        Element body = _build_body(List.of(p1, p2, p3), true).first();
        assertEquals(3, body.size());
        assertEquals(p3, body.get(2).text);
    }

    /**
     * regression: a nested <article> (article-in-article shell) must not be counted both
     * inside its ancestor's text and again standalone by the article strategy.
     */
    @Test
    void test_baseline_article_nested_not_duplicated() {
        String inner = "Inner article content sentence repeated to build a properly long body text here. ".repeat(3);
        String doc = "<html><body><article>Outer wrapper. <article>" + inner + "</article></article></body></html>";
        assertEquals(1, count(text(doc), inner.strip()));
    }

    /** schema.org text properties beyond articleBody: recipe steps and FAQ answers. */
    @Test
    void test_baseline_schema_properties() {
        String recipe = Json.dumps(map(
                "@type", "Recipe",
                "recipeInstructions", list(
                        map("@type", "HowToStep",
                                "text", "Mix the flour with sugar and butter until the dough is smooth and pliable in texture."),
                        map("@type", "HowToStep",
                                "text", "Bake for thirty five minutes at one hundred eighty degrees until golden brown on top."))));
        String result = text(jsonld_doc(recipe));
        assertTrue(result.contains("Mix the flour"));
        assertTrue(result.contains("Bake for thirty five minutes"));

        // recipeInstructions can also be plain strings rather than step objects
        String str_recipe = Json.dumps(map(
                "@type", "Recipe",
                "recipeInstructions", list(
                        "Combine all the dry ingredients thoroughly in a large mixing bowl before adding any liquids.",
                        "Pour the batter into the tin and bake until a skewer inserted in the centre comes out clean.")));
        result = text(jsonld_doc(str_recipe));
        assertTrue(result.contains("Combine all the dry ingredients"));
        assertTrue(result.contains("Pour the batter into the tin"));

        String faq = Json.dumps(map(
                "@type", "FAQPage",
                "mainEntity", list(map(
                        "@type", "Question",
                        "name", "How?",
                        "acceptedAnswer", map(
                                "@type", "Answer",
                                "text", "This is the accepted answer to the question, written with a comfortable amount of words to pass the one hundred character gate.")))));
        assertTrue(text(jsonld_doc(faq)).contains("This is the accepted answer"));
    }

    /** Discourse forums: posts live as JSON in the data-preloaded attribute of an empty page. */
    @Test
    void test_baseline_discourse_preload() {
        String topic = Json.dumps(map("post_stream", map("posts", list(
                map("cooked", "<p>First forum post with a good amount of substantive discussion content in it.</p>"),
                map("cooked", "<p>Second forum post continuing the thread with more useful information for everyone.</p>")))));
        // a non-"topic_" key (e.g. site settings) is skipped; only the topic_ key is parsed
        String preloaded = escape(Json.dumps(map("site_settings", "ignored", "topic_12", topic)), true);
        String doc = "<html><body><div id=\"data-preloaded\" data-preloaded=\"" + preloaded + "\"></div></body></html>";
        String result = text(doc);
        assertTrue(result.contains("First forum post"));
        assertTrue(result.contains("Second forum post"));
        assertFalse(result.contains("<p>"));
    }

    /**
     * regression: valid but non-dict JSON in data-preloaded (or a non-dict post entry) must not crash
     * baseline() -- it should just fall through to the next strategy.
     */
    @Test
    void test_baseline_discourse_preload_malformed() {
        String para = "Real paragraph content that should be extracted by the paragraph strategy, comfortably long enough for the gate.";
        for (String preloaded : List.of("[]", "\"just a string\"",
                escape(Json.dumps(map("topic_12", "[\"not\", \"a\", \"dict\"]")), true))) {
            String doc = "<html><body><div id=\"data-preloaded\" data-preloaded=\"" + preloaded + "\"></div><p>" + para + "</p></body></html>";
            assertTrue(text(doc).contains(para));
        }
    }

    /** past DEDUPE_SCAN_CAP the paragraph strategy skips the substring scan and keeps potential repeats. */
    @Test
    void test_build_body_dedupe_cap() {
        String filler = "Opening paragraph content long enough to push the accumulated text past a tiny cap.";
        String dup = "This exact paragraph repeats and is well above the duplicate length gate for sure.";
        int saved = Baseline.DEDUPE_SCAN_CAP;
        try {
            Baseline.DEDUPE_SCAN_CAP = 50;
            assertEquals(2, count(_build_body(List.of(filler, dup, dup), true).second(), dup)); // past the cap: scan skipped
            Baseline.DEDUPE_SCAN_CAP = 200_000;
            assertEquals(1, count(_build_body(List.of(filler, dup, dup), true).second(), dup)); // under the cap: dedup fires
        } finally {
            Baseline.DEDUPE_SCAN_CAP = saved;
        }
    }

    /** HowTo step/itemListElement text is content (own text and nested itemListElement both kept). */
    @Test
    void test_baseline_howto() {
        String step_text = "Measure the table and add the overhang you want on every side before cutting any fabric at all.";
        String howto = Json.dumps(map(
                "@type", "HowTo",
                "step", list(map(
                        "@type", "HowToSection",
                        "itemListElement", map(
                                "@type", "HowToDirection",
                                "text", step_text + " Then hem the edges neatly all around the cloth.")))));
        assertTrue(text(jsonld_doc(howto)).contains(step_text));

        // a step carrying BOTH its own text and an itemListElement keeps both
        String both = Json.dumps(map(
                "@type", "HowTo",
                "step", list(map(
                        "@type", "HowToStep",
                        "text", "Own step text: preheat the oven to a moderate temperature first.",
                        "itemListElement", list(map("@type", "HowToDirection",
                                "text", "Set the dial to one hundred and eighty degrees."))))));
        String result = text(jsonld_doc(both));
        assertTrue(result.contains("preheat the oven"));
        assertTrue(result.contains("one hundred and eighty"));
    }

    /** the public baseline() must not modify a caller-supplied tree (mirrors html2txt's guard). */
    @Test
    void test_baseline_element_input_not_mutated() {
        String doc = "<html><body><aside>side text</aside><p>" + "Real paragraph content on the page. ".repeat(4) + "</p></body></html>";
        Element tree = Etree.html_fromstring(doc);
        String result = text(tree);
        assertTrue(result.contains("Real paragraph content"));
        assertFalse(result.contains("side text"));
        assertNotNull(tree.find(".//aside")); // basic_cleaning ran on a copy
    }

    /** article strategy: a dominant article drops much smaller siblings (teasers), similar-sized ones all stay. */
    @Test
    void test_baseline_article_dominance() {
        String main = "Main article content sentence repeated to build a properly dominant body. ".repeat(8); // ~600 chars
        String teaser = "Related article teaser text that comfortably exceeds the length gate...."; // >100, < max/5
        String doc = "<html><body><article>" + main + "</article><article>" + teaser + teaser.substring(0, 40) + "</article></body></html>";
        String result = text(doc);
        assertTrue(result.contains("Main article content"));
        assertFalse(result.contains("Related article teaser"));

        String post = "Forum post number %d with a comparable amount of discussion text in every single post here.";
        String doc2 = "<html><body>" + IntStream.range(0, 3).mapToObj(i -> "<article>" + post.formatted(i) + "</article>")
                .collect(Collectors.joining()) + "</body></html>";
        String result2 = text(doc2);
        assertTrue(IntStream.range(0, 3).allMatch(i -> result2.contains(post.formatted(i))));
    }

    /** no whitespace at block boundaries must not fuse words (GH #896), in any strategy. */
    @Test
    void test_baseline_minified_block_boundaries() {
        String sentence = "Sentence number %d of the notice, long enough to clear the content length gate.";
        String blocks = IntStream.range(0, 3).mapToObj(i -> "<p>" + sentence.formatted(i) + "</p>").collect(Collectors.joining());
        // article strategy, then paragraph strategy (a <br> inside the paragraph)
        for (String doc : List.of(
                "<html><body><article><h1>Notice Title</h1>" + blocks + "</article></body></html>",
                "<html><body><div><p>" + sentence.formatted(0) + "<br/>" + sentence.formatted(1) + "</p>" + blocks + "</div></body></html>")) {
            String result = text(doc);
            assertFalse(result.contains("notice.Sentence"));
            assertFalse(result.contains("TitleSentence"));
            assertTrue(result.contains(sentence.formatted(1)));
        }

        // markup arriving escaped inside embedded JSON
        String body = escape(IntStream.range(0, 3).mapToObj(i -> "<p>" + sentence.formatted(i) + "</p>").collect(Collectors.joining()));
        String doc = "<html><body><script type=\"application/ld+json\">{\"articleBody\": \"" + body + "\"}</script></body></html>";
        String result = text(doc);
        assertFalse(result.contains("notice.Sentence"));
        assertTrue(result.contains(sentence.formatted(1)));
    }

    @Test
    void test_html2txt() {
        String mydoc = "<html><body>Here is the body text</body></html>";
        assertEquals("Here is the body text", html2txt(mydoc));
        assertEquals("Here is the body text", html2txt(Etree.html_fromstring(mydoc)));
        assertEquals("", html2txt(""));
        assertEquals("", html2txt("123"));
        assertEquals("", html2txt("<html></html>"));
        assertEquals("", html2txt("<html><body/></html>"));
        assertEquals("ABC", html2txt("<html><body><style>font-size: 8pt</style><p>ABC</p></body></html>"));
        // block boundaries stay separated on minified pages, inline runs stay joined
        String minified = "<html><body><p>First block.</p><p>Second block.</p><h2>Heading</h2>line<br>break</body></html>";
        assertEquals("First block. Second block. Heading line break", html2txt(minified));
        assertEquals("a b", html2txt("<html><body><div>a<p>b</p></div></body></html>"));
        assertEquals("Hyperlink", html2txt("<html><body><p>Hyper<b>link</b></p></body></html>"));
        // an element input is left untouched by the cleaning
        Element tree = Etree.html_fromstring("<html><body><aside>side</aside><p>content</p></body></html>");
        assertEquals("content", html2txt(tree));
        assertTrue(tree.text_content().contains("side"));
        // an element without a body still yields its text
        assertEquals("bare element text", html2txt(Etree.html_fromstring("<div><p>bare element text</p></div>")));
        // ... but a parsed document without a body (e.g. a feed) is not HTML text:
        // cli --probe relies on "" here to skip non-HTML URLs
        String feed = "<?xml version=\"1.0\"?><feed xmlns=\"http://www.w3.org/2005/Atom\"><title>Feed title</title>"
                + "<entry><title>Entry one</title><summary>Summary one</summary></entry>"
                + "<entry><title>Entry two</title><summary>Summary two</summary></entry></feed>";
        assertEquals("", html2txt(feed));
        // spec-invisible content is not text: template is never rendered, svg text is graphical
        String invisible = "<html><body><svg><title>Icon name</title></svg><template><p>markup</p></template><p>visible</p></body></html>";
        assertEquals("visible", html2txt(invisible));
        // a caller-supplied tree can hold control chars; html2txt must not raise.
        // the control char is dropped (as load_html does for string input), joining the two words
        Element ctrl_tree = Etree.html_fromstring("<html><body><p>before\u0001after visible text</p></body></html>");
        assertEquals("beforeafter visible text", html2txt(ctrl_tree));
    }
}
