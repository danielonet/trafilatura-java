// Java port of trafilatura/meta.py
package io.github.danielonet.trafilatura;

import io.github.danielonet.trafilatura.courlan.Courlan;
import io.github.danielonet.trafilatura.htmldate.Htmldate;

/** Meta-functions to be applied module-wide. */
public final class Meta {
    private Meta() {
    }

    /**
     * Reset all known LRU caches used to speed up processing.
     * This may release some memory.
     */
    public static void reset_caches() {
        // justext: no caches
        // handles htmldate and charset_normalizer
        Htmldate.reset_caches();
        // courlan
        Courlan.clear_caches();
        // own: only the deduplication cache exists in the Java port (no @lru_cache memoization)
        Deduplication.LRU_TEST.clear();
        // garbage collection
        System.gc();
    }
}
