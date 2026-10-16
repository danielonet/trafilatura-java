// Java port of trafilatura/deduplication.py
package io.github.danielonet.trafilatura;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.bouncycastle.crypto.digests.Blake2bDigest;

import io.github.danielonet.trafilatura.compat.Py;
import io.github.danielonet.trafilatura.compat.SequenceMatcher;
import io.github.danielonet.trafilatura.lxml.Element;
import io.github.danielonet.trafilatura.Settings.Extractor;

import static io.github.danielonet.trafilatura.Settings.LRU_SIZE;
import static io.github.danielonet.trafilatura.Utils.trim;

/** Code parts dedicated to duplicate removal and text similarity. */
public final class Deduplication {
    private Deduplication() {
    }

    public static final Pattern STRIP_EXTENSION = Pattern.compile("\\.[^/?#]{2,63}$");
    // all Unicode punctuation (category P*), the translation table of the Python version
    private static final Pattern PUNCTUATION = Pattern.compile("\\p{P}");
    // string.punctuation
    private static final String ASCII_PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";

    /** Return the similarity ratio between two short strings, here domain names. */
    public static boolean is_similar_domain(String reference, String new_string, double threshold) {
        reference = STRIP_EXTENSION.matcher(reference).replaceAll("");
        new_string = STRIP_EXTENSION.matcher(new_string).replaceAll("");
        return new SequenceMatcher(reference, new_string).ratio() >= threshold;
    }

    public static boolean is_similar_domain(String reference, String new_string) {
        return is_similar_domain(reference, new_string, 0.5);
    }

    private static boolean isalnum(String s) {
        return !s.isEmpty() && s.codePoints().allMatch(Character::isLetterOrDigit);
    }

    /**
     * Split input into list of tokens and adjust length threshold to make sure
     * there is enough data.
     */
    public static List<String> sample_tokens(String inputstring, int length) {
        List<String> tokens = new ArrayList<>();
        for (String token : Py.split(inputstring)) {
            String t = Py.strip(token, ASCII_PUNCTUATION);
            if (isalnum(t)) {
                tokens.add(t);
            }
        }
        if (tokens.isEmpty()) {
            // non-latin punctuation, e.g. mandarin 。
            for (String t : Py.split(PUNCTUATION.matcher(inputstring).replaceAll(" "))) {
                if (isalnum(t)) {
                    tokens.add(t);
                }
            }
        }

        // tokens are non-empty, so a threshold of 0 would keep them all
        for (int i = 4; i > 0; i--) {
            List<String> sample = new ArrayList<>();
            for (String t : tokens) {
                if (t.length() > i) {
                    sample.add(t);
                }
            }
            if (sample.size() >= length / 2.0) {
                return sample;
            }
        }
        return tokens;
    }

    public static List<String> sample_tokens(String inputstring) {
        return sample_tokens(inputstring, 64);
    }

    /** {@code hashlib.blake2b(data, digest_size=n).digest()} */
    public static byte[] blake2b(byte[] data, int digestSize) {
        Blake2bDigest digest = new Blake2bDigest(digestSize * 8);
        digest.update(data, 0, data.length);
        byte[] out = new byte[digestSize];
        digest.doFinal(out, 0);
        return out;
    }

    /** Create a bag of words and generate a hash for a given string. */
    public static byte[] generate_bow_hash(String inputstring, int length) {
        String teststring = String.join(" ", sample_tokens(inputstring));
        return blake2b(teststring.getBytes(StandardCharsets.UTF_8), length);
    }

    public static byte[] generate_bow_hash(String inputstring) {
        return generate_bow_hash(inputstring, 24);
    }

    /** Token's contribution to a Simhash vector. */
    public static int[] _vector_to_add(String token, int length) {
        long token_hash = new BigInteger(1, blake2b(token.getBytes(StandardCharsets.UTF_8), 8)).longValue();
        int[] result = new int[length];
        for (int i = 0; i < length; i++) {
            result[i] = (token_hash & (1L << i)) != 0 ? 1 : -1;
        }
        return result;
    }

    /** Implement a basic Charikar hashing approach of string similarity. */
    public static class Simhash {
        public final int length;
        /** Unsigned 64-bit hash value (Python int). */
        public final long hash;

        /** Store length and existing or new hash. */
        public Simhash(String inputstring, int length, Object existing_hash) {
            this.length = length;
            Long valid = this.validate(existing_hash);
            // Python `validate(...) or create_hash(...)`: a zero hash counts as missing
            this.hash = valid != null && valid != 0 ? valid : this.create_hash(inputstring);
        }

        public Simhash(String inputstring) {
            this(inputstring, 64, null);
        }

        public Simhash() {
            this("", 64, null);
        }

        /**
         * Calculates a Charikar simhash. References used:
         * https://github.com/vilda/shash/
         * https://github.com/sean-public/python-hashes/blob/master/hashes/simhash.py
         */
        public long create_hash(String inputstring) {
            int[] vector = new int[this.length];
            for (String token : sample_tokens(inputstring, this.length)) {
                int[] add = _vector_to_add(token, this.length);
                for (int i = 0; i < this.length; i++) {
                    vector[i] += add[i];
                }
            }
            long result = 0;
            for (int i = 0; i < this.length; i++) {
                if (vector[i] >= 0) {
                    result |= 1L << i;
                }
            }
            return result;
        }

        /** Convert the numerical hash to a hexadecimal string. */
        public String to_hex() {
            return Long.toUnsignedString(this.hash, 16);
        }

        private boolean inRange(long value) {
            return this.length >= 64 || Long.compareUnsigned(value, 1L << this.length) < 0;
        }

        /** Validate the input hash and return it, or None otherwise. */
        public Long validate(Object inputhash) {
            Long value = null;
            if (inputhash instanceof String s) {
                // historical decimal representation, now hex via to_hex()
                try {
                    if (Py.isdigit(s) && s.length() >= 18 && s.length() <= 22) {
                        value = Long.parseUnsignedLong(s);
                    } else {
                        value = Long.parseUnsignedLong(Py.strip(s).toLowerCase().replaceFirst("^0x", ""), 16);
                    }
                } catch (NumberFormatException err) {
                    return null;
                }
            } else if (inputhash instanceof Long l) {
                if (l < 0) {
                    return null;
                }
                value = l;
            } else if (inputhash instanceof Integer i) {
                if (i < 0) {
                    return null;
                }
                value = i.longValue();
            } else if (inputhash instanceof BigInteger big) {
                // Python ints are unbounded: only [0, 2**64) fits the hash
                if (big.signum() < 0 || big.bitLength() > 64) {
                    return null;
                }
                value = big.longValue();
            }
            if (value != null && inRange(value)) {
                return value;
            }
            return null;
        }

        /** Similarity to another simhash based on the Hamming distance, from 0.0 to 1.0. */
        public double similarity(Simhash other_hash) {
            return (double) (this.length - Long.bitCount(this.hash ^ other_hash.hash)) / this.length;
        }
    }

    /** Calculate a simhash hex value for meaningful bits of the content. */
    public static String content_fingerprint(String content) {
        return new Simhash(content).to_hex();
    }

    /** Least Recently Used (LRU) cache backed by an access-ordered LinkedHashMap. */
    public static class LRUCache implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        /** Access-ordered map evicting its eldest entry beyond maxsize. */
        public static final class LruMap extends LinkedHashMap<String, Integer> {
            private static final long serialVersionUID = 1L;
            private final int maxsize;

            LruMap(int maxsize) {
                super(16, 0.75f, true);
                this.maxsize = maxsize;
            }

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                return size() > maxsize;
            }
        }

        public final int maxsize;
        public final LruMap cache;

        public LRUCache(int maxsize) {
            if (maxsize < 1) {
                throw new IllegalArgumentException("maxsize must be at least 1");
            }
            this.maxsize = maxsize;
            this.cache = new LruMap(maxsize);
        }

        public LRUCache() {
            this(128);
        }

        /** {@code copy.copy(cache)}: the contents are copied, not shared. */
        public synchronized LRUCache copy() {
            LRUCache clone = new LRUCache(maxsize);
            clone.cache.putAll(cache);
            return clone;
        }

        /** Always true, even when empty: instances passed as dedup option enable deduplication. */
        public boolean isTruthy() {
            return true;
        }

        /** Retrieve a value from the cache, or -1 if the key is absent. */
        public synchronized int get(String key) {
            Integer value = cache.get(key);
            return value != null ? value : -1;
        }

        /** Store a given key in the cache, evicting the oldest entry if full. */
        public synchronized void put(String key, int value) {
            cache.put(key, value);
        }

        /** Increment the stored count, return the previous count or -1. */
        public synchronized int increment(String key) {
            Integer removed = cache.remove(key);
            int previous = removed != null ? removed : -1;
            put(key, Math.max(previous, 0) + 1);
            return previous;
        }

        /** Delete all cache content. */
        public synchronized void clear() {
            cache.clear();
        }

        public synchronized int size() {
            return cache.size();
        }
    }

    public static LRUCache LRU_TEST = new LRUCache(LRU_SIZE);

    /** Check for duplicate text with LRU cache. */
    public static boolean duplicate_test(Element element, Extractor options) {
        String teststring = trim(String.join(" ", element.itertext()));
        LRUCache lru = options.dedup instanceof LRUCache cache ? cache : LRU_TEST;
        int previous = lru.increment(teststring);
        return teststring.length() > options.min_duplcheck_size && previous > options.max_repetitions;
    }
}
