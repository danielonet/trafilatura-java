// Java port of tests/deduplication_tests.py
package trafilatura.tests;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import trafilatura.compat.Pair;
import trafilatura.lxml.Element;
import trafilatura.lxml.Etree;
import trafilatura.trafilatura.Core;
import trafilatura.trafilatura.Deduplication;
import trafilatura.trafilatura.Deduplication.LRUCache;
import trafilatura.trafilatura.Deduplication.Simhash;
import trafilatura.trafilatura.Settings.Extractor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static trafilatura.trafilatura.CliUtils.generate_hash_filename;
import static trafilatura.trafilatura.Deduplication.content_fingerprint;
import static trafilatura.trafilatura.Deduplication.duplicate_test;
import static trafilatura.trafilatura.Deduplication.generate_bow_hash;
import static trafilatura.trafilatura.Deduplication.sample_tokens;
import static trafilatura.trafilatura.Htmlprocessing.process_node;
import static trafilatura.trafilatura.Meta.reset_caches;

/** Unit tests for the trafilatura's text hashing and cache. */
class DeduplicationTests {
    static final Extractor DEFAULT_OPTIONS = new Extractor();

    /** 2**64 - 1 as an unsigned 64-bit value. */
    static final long MAX_HASH = Long.parseUnsignedLong("18446744073709551615");

    private LRUCache original;

    /** Restore the module-global LRU_TEST after tests that reassign it. */
    @BeforeEach
    void saveLruTest() {
        original = Deduplication.LRU_TEST;
    }

    @AfterEach
    void restoreLruTest() {
        Deduplication.LRU_TEST = original;
        reset_caches();
    }

    static String extract_dedup(Object doc, Object deduplicate) {
        return Core.extract(doc, Core.kw().deduplicate(deduplicate));
    }

    /** Hashing functions and content fingerprints. */
    @Test
    void test_hashes() {
        String content = "abcde ijk l, ".repeat(10);
        assertEquals("528497a1d07b66d6", content_fingerprint(content));
        assertEquals("42LNugG3Sc95646i", generate_hash_filename(content));
        assertEquals("5efdce9f2b554683", content_fingerprint("Hello world! This is a test string with some numbers 123."));
        assertEquals("ff377edee6edfb78", content_fingerprint("这是一个测试。我们在测试中文。"));
        assertEquals("24979dc6c8a26a5", content_fingerprint("Hello世界。This is混合文本!"));
    }

    /** Test similarity calculation based on Simhash class. */
    @Test
    void test_simhash() {
        // https://en.wiktionary.org/wiki/put_lipstick_on_a_pig
        int factor = 1;
        List<Simhash> hashes = new ArrayList<>();
        hashes.add(new Simhash("This is like putting lipstick on a pig.".repeat(factor)));
        hashes.add(new Simhash("This is just like putting lipstick on a pig.".repeat(factor)));
        hashes.add(new Simhash("Putting lipstick on a pig is what this is about.".repeat(factor)));
        hashes.add(new Simhash("The words are completely different but let's see.".repeat(factor)));

        List<Double> sims = hashes.stream().map(h -> hashes.get(0).similarity(h)).toList();
        assertEquals(1.0, sims.get(0));
        assertEquals(sims.stream().mapToDouble(Double::doubleValue).min().getAsDouble(), sims.get(sims.size() - 1));

        // sanity checks
        assertEquals(hashes.get(0).hash, new Simhash("", 64, hashes.get(0).to_hex()).hash);
        assertEquals(hashes.get(0).hash, Long.parseUnsignedLong(Long.toHexString(hashes.get(0).hash), 16));

        // re-hashed
        assertEquals(MAX_HASH, new Simhash("", 64, "aghj").hash);
        assertEquals(MAX_HASH, new Simhash("", 64, "18446744073709551615").hash);
        assertNotEquals(new BigInteger("18446744073709551616"),
                new BigInteger(Long.toUnsignedString(new Simhash("", 64, "18446744073709551616").hash)));
        assertEquals(123L, new Simhash("", 64, 123).hash);
        assertEquals(MAX_HASH, new Simhash("", 64, new BigInteger("18446744073709551615")).hash);
        assertNotEquals(BigInteger.TWO.pow(64),
                new BigInteger(Long.toUnsignedString(new Simhash("", 64, BigInteger.TWO.pow(64)).hash)));
        assertNotEquals(BigInteger.valueOf(-1), new BigInteger(Long.toUnsignedString(new Simhash("", 64, -1).hash)));
        assertEquals(new Simhash().hash, new Simhash("", 64, null).hash);

        // similarity
        assertEquals(1.0, new Simhash("abcde").similarity(new Simhash("abcde")));
        assertNotEquals(1.0, new Simhash("abcde").similarity(new Simhash("abcde", 2, null)));
        assertTrue(new Simhash("abcde").similarity(new Simhash("fghij")) < 0.6);
        assertEquals(1.0, new Simhash("abcde ".repeat(100)).similarity(new Simhash("abcde")));
    }

    /** Body wrapping a paragraph: both share the same text for duplicate_test. */
    static Pair<Element, Element> _make_pair(String text) {
        Element body = Etree.Element("body");
        Element element = Etree.html_fromstring("<p>" + text + "</p>");
        body.append(element);
        return Pair.of(body, element);
    }

    static List<Boolean> dedupSeq(Element... seq) {
        List<Boolean> results = new ArrayList<>();
        for (Element e : seq) {
            results.add(duplicate_test(e, DEFAULT_OPTIONS));
        }
        return results;
    }

    /** Duplicate detection through the global LRU_TEST cache. */
    @Test
    void test_lrucache() {
        LRUCache lru_test = new LRUCache(2);
        Deduplication.LRU_TEST = lru_test;

        Pair<Element, Element> my = _make_pair("AAAA BBBB ".repeat(13));
        assertEquals(List.of(false, false, false, true), dedupSeq(my.second(), my.second(), my.first(), my.second()));
        Pair<Element, Element> other = _make_pair("CCCC DDDD ".repeat(11));
        assertEquals(List.of(false, false, false, true), dedupSeq(other.first(), other.second(), other.first(), other.second()));
        Pair<Element, Element> yetAnother = _make_pair("EEEE FFFF ".repeat(13));
        assertEquals(List.of(false, false, false), dedupSeq(yetAnother.first(), yetAnother.first(), yetAnother.first()));
        // 2 elements in cache, original element has been cleared?
        assertTrue(duplicate_test(other.second(), DEFAULT_OPTIONS));
        assertTrue(duplicate_test(yetAnother.second(), DEFAULT_OPTIONS));
        assertFalse(duplicate_test(my.second(), DEFAULT_OPTIONS));
        // clear the cache
        lru_test.clear();
        assertFalse(duplicate_test(other.second(), DEFAULT_OPTIONS));
    }

    /** Test paragraph-level deduplication. */
    @Test
    void test_dedup() {
        String my_p = "<p>abc</p>";
        Element doc = Etree.html_fromstring("<html><body>" + my_p.repeat(50) + "</body></html>");
        Deduplication.LRU_TEST = new LRUCache(2);
        assertNotNull(extract_dedup(doc, true));
        assertNotNull(extract_dedup(doc, true));
        assertNotNull(extract_dedup(doc, true));
        assertNull(extract_dedup(doc, true));

        // paragraph level
        Deduplication.LRU_TEST = new LRUCache(2);
        Element p = Etree.fromstring("<p>" + "abc".repeat(50) + "</p>");
        Extractor options = new Extractor(Extractor.kw().dedup(true));
        assertNotNull(process_node(p, options));
        assertNotNull(process_node(p, options));
        assertNotNull(process_node(p, options));
        assertNull(process_node(p, options));
    }

    /** Repeated identical extractions accumulate in LRU_TEST; reset_caches() clears it (#778). */
    @Test
    void test_dedup_reset_caches() {
        reset_caches();
        try {
            Element doc = Etree.html_fromstring("<html><body>" + "<p>abc</p>".repeat(50) + "</body></html>");
            List<String> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                results.add(extract_dedup(doc, true));
            }
            assertNotNull(results.get(0));
            assertNull(results.get(results.size() - 1));
            reset_caches();
            assertNotNull(extract_dedup(doc, true));
        } finally {
            reset_caches();
        }
    }

    /** Direct coverage of the LRUCache public API. */
    @Test
    void test_lrucache_api() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new LRUCache(0));
        assertThrows(IllegalArgumentException.class, () -> new LRUCache(-1));

        LRUCache original = new LRUCache(8);
        original.increment("a");
        // pickle round trip -> Java serialization
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            out.writeObject(original);
        }
        LRUCache cache;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
            cache = (LRUCache) in.readObject();
        }
        assertEquals(8, cache.maxsize);
        assertEquals(1, cache.get("a")); // contents survive
        assertEquals(1, cache.increment("a")); // the cache stays usable
        assertNotSame(original.cache, original.copy().cache); // a copy does not share the dict

        LRUCache lru = new LRUCache(2);
        assertTrue(lru.isTruthy()); // an empty cache as dedup option must keep dedup enabled
        assertEquals(-1, lru.get("a"));
        lru.put("a", 1);
        assertEquals(1, lru.get("a"));
        lru.put("b", 2);
        // touching "a" makes "b" the eviction candidate
        assertEquals(1, lru.get("a"));
        lru.put("c", 3);
        assertEquals(-1, lru.get("b"));
        assertEquals(1, lru.get("a"));
        assertEquals(3, lru.get("c"));
        // put on an existing key updates value and recency
        lru.put("a", 10);
        lru.put("d", 4);
        assertEquals(-1, lru.get("c"));
        assertEquals(10, lru.get("a"));
        lru.clear();
        assertTrue(lru.cache.isEmpty());

        // increment matches the get + put composition and returns the previous count
        lru = new LRUCache(2);
        assertEquals(-1, lru.increment("x"));
        assertEquals(1, lru.increment("x"));
        assertEquals(2, lru.get("x"));
        lru.increment("y");
        lru.increment("z");
        assertEquals(-1, lru.get("x")); // evicted as oldest
    }

    /** Pin deliberate validate behaviors so refactoring cannot silently change them. */
    @Test
    void test_simhash_validate_pins() {
        Simhash reference = new Simhash("abcde");
        // negative integers are rejected and trigger re-hashing
        assertEquals(reference.hash, new Simhash("abcde", 64, -1_000_000_000_000_000_000L).hash);
        // booleans are rejected despite being int subclasses
        assertEquals(reference.hash, new Simhash("abcde", 64, true).hash);
        // existing_hash="0" is deliberately re-hashed (kept for backward compatibility)
        assertEquals(MAX_HASH, new Simhash("", 64, "0").hash);
        // non-decimal Unicode digits fall through to re-hashing instead of crashing int()
        assertEquals(reference.hash, new Simhash("abcde", 64, "¹".repeat(18)).hash);
        // hex strings outside the 64-bit hash range are rejected and re-hashed
        assertEquals(reference.hash, new Simhash("abcde", 64, "-ff").hash);
        assertEquals(reference.hash, new Simhash("abcde", 64, "f".repeat(17)).hash);
        // small hex values as produced by to_hex() still round-trip
        assertEquals(255L, new Simhash("", 64, "ff").hash);
    }

    /** min_duplcheck_size is a strict bound and the 4th repetition gets flagged. */
    @Test
    void test_duplicate_test_boundaries() {
        Extractor options = new Extractor();
        options.dedup = new LRUCache(1024); // scoped cache keeps the global one clean
        Element at_limit = Etree.html_fromstring("<p>" + "x".repeat(options.min_duplcheck_size) + "</p>");
        List<Boolean> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            results.add(duplicate_test(at_limit, options));
        }
        assertEquals(List.of(false, false, false, false, false, false), results);
        Element above_limit = Etree.html_fromstring("<p>" + "x".repeat(options.min_duplcheck_size + 1) + "</p>");
        // max_repetitions=2: flagged once the previous count exceeds it
        results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            results.add(duplicate_test(above_limit, options));
        }
        assertEquals(List.of(false, false, false, true, true, true), results);
    }

    /** Token sampling: regular, fallback and threshold-relaxation inputs. */
    @Test
    void test_sample_tokens() {
        List<String> tokens = sample_tokens("Hello world! This is a test string with some numbers 123.");
        assertTrue(tokens.contains("Hello"));
        assertTrue(tokens.contains("world"));
        assertTrue(tokens.contains("123"));
        // Chinese punctuation: fallback path
        assertEquals(List.of("这是一个测试", "我们在测试中文"), sample_tokens("这是一个测试。我们在测试中文。"));
        // mixed text: primary path yields one token, no fallback
        assertEquals(List.of("is混合文本"), sample_tokens("Hello世界。This is混合文本!"));
        assertEquals(List.of(), sample_tokens(""));
        assertEquals(List.of(), sample_tokens("!!! ... ???"));
        assertEquals(List.of(), sample_tokens("。、！"));
        // many short tokens force the length threshold down
        assertEquals(java.util.Collections.nCopies(40, "ab"), sample_tokens("ab ".repeat(40)));
        // enough long tokens keep the strict threshold: short ones are dropped
        tokens = sample_tokens(IntStream.range(0, 40).mapToObj(i -> "token" + i).collect(Collectors.joining(" ")) + " ab cd");
        assertFalse(tokens.contains("ab"));
        assertEquals(40, tokens.size());
    }

    /** Digest size parameter and degenerate inputs. */
    @Test
    void test_generate_bow_hash() {
        assertEquals(12, generate_bow_hash("Hello world this is a test", 12).length);
        assertEquals(24, generate_bow_hash("", 24).length);
        assertEquals(java.util.HexFormat.of().formatHex(generate_bow_hash("")),
                java.util.HexFormat.of().formatHex(generate_bow_hash("   ")));
    }

    /** A cache instance passed as dedup option scopes deduplication (#778). */
    @Test
    void test_dedup_cache_option() {
        Element doc = Etree.html_fromstring("<html><body>" + "<p>abc</p>".repeat(50) + "</body></html>");

        // fresh cache per call: deterministic, repeated extractions all succeed
        List<String> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            results.add(extract_dedup(doc, new LRUCache(1024)));
        }
        assertFalse(results.contains(null));
        assertEquals(1, new HashSet<>(results).size());

        // shared cache across calls: accumulation triggers deduplication
        LRUCache shared = new LRUCache(1024);
        results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            results.add(extract_dedup(doc, shared));
        }
        assertNotNull(results.get(0));
        assertNull(results.get(results.size() - 1));

        // the global cache is untouched by scoped runs
        assertNotNull(extract_dedup(doc, true));
    }
}
