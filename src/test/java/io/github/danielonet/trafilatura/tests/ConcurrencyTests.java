// Java-only tests, no Python counterpart: Python callers rarely share a process across
// threads, while JVM services call extract() from many request threads at once.
package io.github.danielonet.trafilatura.tests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.github.danielonet.trafilatura.Core;
import io.github.danielonet.trafilatura.Deduplication.LRUCache;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConcurrencyTests {
    static final int THREADS = 8;

    static List<String> cached_pages(int limit) throws IOException {
        try (Stream<Path> files = Files.list(Conftest.TEST_DIR.resolve("cache"))) {
            List<String> pages = new ArrayList<>();
            for (Path path : files.filter(p -> p.toString().endsWith(".html")).sorted().limit(limit).toList()) {
                pages.add(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
            return pages;
        }
    }

    static String run(String html, String format) {
        return String.valueOf(Core.extract(html, Core.kw().output_format(format).with_metadata(true)));
    }

    /** Concurrent extract() calls return the same output as sequential ones. */
    @Test
    void test_extract_concurrent_matches_sequential() throws Exception {
        List<String> pages = cached_pages(30);
        String[] formats = {"txt", "xml"};
        List<String> expected = new ArrayList<>();
        for (String html : pages) {
            for (String format : formats) {
                expected.add(run(html, format));
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int round = 0; round < 3; round++) {
                for (String html : pages) {
                    for (String format : formats) {
                        results.add(pool.submit(() -> run(html, format)));
                    }
                }
            }
            for (int i = 0; i < results.size(); i++) {
                assertEquals(expected.get(i % expected.size()), results.get(i).get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** The shared deduplication cache loses no counts under contention. */
    @Test
    void test_lru_cache_concurrent_increments() throws Exception {
        LRUCache cache = new LRUCache(16);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                results.add(pool.submit(() -> {
                    for (int j = 0; j < 1000; j++) {
                        cache.increment("key");
                    }
                }));
            }
            for (Future<?> result : results) {
                result.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(THREADS * 1000, cache.get("key"));
    }
}
