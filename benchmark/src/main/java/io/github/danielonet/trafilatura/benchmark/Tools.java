package io.github.danielonet.trafilatura.benchmark;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.jsoup.Jsoup;

import de.l3s.boilerpipe.extractors.ArticleExtractor;
import io.github.danielonet.trafilatura.Core;
import io.github.danielonet.trafilatura.Utils;
import net.dankito.readability4j.Readability4J;

/** The extractors under test, each a function from raw page bytes to text (null = nothing found). */
final class Tools {
    private Tools() {
    }

    record Tool(String library, String version, Function<byte[], String> extract) {
    }

    static final String TRAFILATURA_VERSION = System.getProperty("trafilatura.version", "unknown");

    // the settings of upstream tests/eval_common.py (EXTRACT_OPTS), so both languages run the same configuration
    static Core.Kwargs options() {
        return Core.kw().include_comments(false).include_tables(true).include_formatting(false);
    }

    static final Map<String, Tool> ALL = new LinkedHashMap<>();

    static {
        // trafilatura gets the bytes, as in upstream's benchmark; the others get the text decoded the same way
        ALL.put("trafilatura-java fast", new Tool("trafilatura-java", TRAFILATURA_VERSION,
                html -> Core.extract(html, options().fast(true))));
        ALL.put("trafilatura-java", new Tool("trafilatura-java", TRAFILATURA_VERSION,
                html -> Core.extract(html, options().fast(false))));
        ALL.put("trafilatura-java precision", new Tool("trafilatura-java", TRAFILATURA_VERSION,
                html -> Core.extract(html, options().fast(false).favor_precision(true))));
        ALL.put("trafilatura-java recall", new Tool("trafilatura-java", TRAFILATURA_VERSION,
                html -> Core.extract(html, options().fast(false).favor_recall(true))));
        ALL.put("boilerpipe", new Tool("com.robbypond:boilerpipe", "1.2.3",
                html -> boilerpipe(Utils.decode_file(html))));
        ALL.put("readability4j", new Tool("net.dankito.readability4j:readability4j", "1.0.8",
                html -> new Readability4J("http://localhost/", Utils.decode_file(html)).parse().getTextContent()));
        // baseline with no boilerplate removal, like upstream's bs4 row
        ALL.put("jsoup text", new Tool("org.jsoup:jsoup", "1.18.3",
                html -> Jsoup.parse(Utils.decode_file(html)).text()));
    }

    static String boilerpipe(String html) {
        try {
            return ArticleExtractor.INSTANCE.getText(html);
        } catch (de.l3s.boilerpipe.BoilerpipeProcessingException err) {
            throw new IllegalStateException(err);
        }
    }
}
