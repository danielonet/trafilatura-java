// Java port of the jusText API (stub), BSD-2-Clause, see licenses/LICENSE-jusText.txt
package io.github.danielonet.trafilatura.justext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.danielonet.trafilatura.lxml.Element;

/**
 * Minimal stand-in for the Python jusText package (https://github.com/miso-belica/jusText, BSD-2-Clause).
 * The API used by trafilatura is present, but no paragraphs are produced: the justext
 * fallback never replaces an extraction in this port.
 */
public final class Justext {
    private Justext() {
    }

    /** {@code justext.paragraph.Paragraph} (the fields trafilatura reads). */
    public static class Paragraph {
        public String text;
        public boolean is_boilerplate = true;
        public String class_type = "bad";

        public Paragraph(String text) {
            this.text = text;
        }
    }

    /** {@code justext.core.ParagraphMaker} */
    public static final class ParagraphMaker {
        private ParagraphMaker() {
        }

        /** Not ported: no paragraphs are produced. */
        public static List<Paragraph> make_paragraphs(Element root) {
            return new ArrayList<>();
        }
    }

    /** {@code justext.core.classify_paragraphs}: not ported, paragraphs stay boilerplate. */
    public static void classify_paragraphs(List<Paragraph> paragraphs, Set<String> stoplist, int length_low,
            int length_high, double stopwords_low, double stopwords_high, double max_link_density, boolean no_headings) {
        // stub
    }

    /** {@code justext.core.revise_paragraph_classification}: not ported. */
    public static void revise_paragraph_classification(List<Paragraph> paragraphs, int max_heading_distance) {
        // stub
    }

    /** {@code justext.utils.get_stoplists()}: no stoplists are bundled. */
    public static Set<String> get_stoplists() {
        return Set.of();
    }

    /** {@code justext.utils.get_stoplist(language)}: no stoplists are bundled. */
    public static Set<String> get_stoplist(String language) {
        return Set.of();
    }

    /** {@code justext.core.define_stoplist.cache_clear()} */
    public static void clear_caches() {
        // no caches
    }
}
