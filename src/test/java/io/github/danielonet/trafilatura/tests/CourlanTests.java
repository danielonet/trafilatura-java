
package io.github.danielonet.trafilatura.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.danielonet.trafilatura.courlan.Courlan;

class CourlanTests {

    @Test
    void test_clean_query_param_ordering() {
        String strict = Courlan.clean_query("badparam=1&page=2", true);
        assertTrue(strict.contains("page=2"));
        assertTrue(!strict.contains("badparam"));

        String normal = Courlan.clean_query("utm_source=twitter&title=hello", false);
        assertTrue(normal.contains("title=hello"));
        assertTrue(!normal.contains("utm_source"));
    }

    @Test
    void test_clean_query_removes_tracker_families() {
        assertEquals("page=2", Courlan.clean_query(
                "utm_source=newsletter&fbclid=abc&gclid=xyz&page=2", false));
        assertEquals("id=42", Courlan.clean_query(
                "msclkid=abc&igshid=xyz&id=42", false));
    }

    @Test
    void test_clean_query_preserves_repeated_values_and_sorts_keys() {
        assertEquals("a=1&a=2&z=last",
                Courlan.clean_query("z=last&a=1&a=2", false));
    }

    @Test
    void test_clean_query_encodes_values() {
        assertEquals("title=hello+world", Courlan.clean_query("title=hello world", false));
        assertEquals("title=a%26b", Courlan.clean_query("title=a%26b", false));
    }

    @Test
    void test_clean_query_strict_allowlist() {
        assertEquals("id=7&page=2", Courlan.clean_query(
                "utm_source=mail&badparam=1&page=2&id=7", true));
        assertEquals("lang=en", Courlan.clean_query("lang=en&unknown=x", true));
    }

    @Test
    void test_clean_query_rejects_mismatched_target_language() {
        assertThrows(IllegalArgumentException.class,
                () -> Courlan.clean_query("lang=de", false, "en"));
        assertEquals("lang=en", Courlan.clean_query("lang=en", false, "en"));
    }

    @Test
    void test_normalize_url_removes_trackers() {
        assertEquals("https://example.com/article?page=2",
                Courlan.normalize_url(
                        "https://example.com/article?utm_source=mail&page=2", false, null, true));
    }
    @Test
    void test_clean_query_tolerates_malformed_percent_encoding() {
        assertEquals("q=%25ZZ",
                Courlan.clean_query("q=%ZZ", false));
    }
}
