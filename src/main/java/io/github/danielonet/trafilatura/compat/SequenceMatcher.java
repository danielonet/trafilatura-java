package io.github.danielonet.trafilatura.compat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port of Python's {@code difflib.SequenceMatcher} for strings (no isjunk function),
 * including the "popular element" autojunk heuristic.
 */
public class SequenceMatcher {
    private final String a;
    private final String b;
    private final Map<Character, List<Integer>> b2j = new HashMap<>();

    public SequenceMatcher(String a, String b) {
        this.a = a;
        this.b = b;
        chainB();
    }

    private void chainB() {
        for (int i = 0; i < b.length(); i++) {
            b2j.computeIfAbsent(b.charAt(i), k -> new ArrayList<>()).add(i);
        }
        int n = b.length();
        if (n >= 200) {
            int ntest = n / 100 + 1;
            Set<Character> popular = new HashSet<>();
            for (Map.Entry<Character, List<Integer>> entry : b2j.entrySet()) {
                if (entry.getValue().size() > ntest) {
                    popular.add(entry.getKey());
                }
            }
            popular.forEach(b2j::remove);
        }
    }

    private int[] findLongestMatch(int alo, int ahi, int blo, int bhi) {
        int besti = alo;
        int bestj = blo;
        int bestsize = 0;
        Map<Integer, Integer> j2len = new HashMap<>();
        for (int i = alo; i < ahi; i++) {
            Map<Integer, Integer> newj2len = new HashMap<>();
            List<Integer> js = b2j.get(a.charAt(i));
            if (js != null) {
                for (int j : js) {
                    if (j < blo) {
                        continue;
                    }
                    if (j >= bhi) {
                        break;
                    }
                    int k = j2len.getOrDefault(j - 1, 0) + 1;
                    newj2len.put(j, k);
                    if (k > bestsize) {
                        besti = i - k + 1;
                        bestj = j - k + 1;
                        bestsize = k;
                    }
                }
            }
            j2len = newj2len;
        }
        // extend the match with elements that are junk (popular) on both sides
        while (besti > alo && bestj > blo && !b2j.containsKey(b.charAt(bestj - 1))
                && a.charAt(besti - 1) == b.charAt(bestj - 1)) {
            besti--;
            bestj--;
            bestsize++;
        }
        while (besti + bestsize < ahi && bestj + bestsize < bhi && !b2j.containsKey(b.charAt(bestj + bestsize))
                && a.charAt(besti + bestsize) == b.charAt(bestj + bestsize)) {
            bestsize++;
        }
        return new int[] {besti, bestj, bestsize};
    }

    /** Number of matching characters ({@code sum of get_matching_blocks() sizes}). */
    public int matches() {
        int total = 0;
        Deque<int[]> queue = new ArrayDeque<>();
        queue.push(new int[] {0, a.length(), 0, b.length()});
        while (!queue.isEmpty()) {
            int[] q = queue.pop();
            int[] m = findLongestMatch(q[0], q[1], q[2], q[3]);
            int k = m[2];
            if (k > 0) {
                total += k;
                if (q[0] < m[0] && q[2] < m[1]) {
                    queue.push(new int[] {q[0], m[0], q[2], m[1]});
                }
                if (m[0] + k < q[1] && m[1] + k < q[3]) {
                    queue.push(new int[] {m[0] + k, q[1], m[1] + k, q[3]});
                }
            }
        }
        return total;
    }

    /** {@code 2.0 * M / T} */
    public double ratio() {
        int length = a.length() + b.length();
        return length == 0 ? 1.0 : 2.0 * matches() / length;
    }
}
