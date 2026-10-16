// Java port of trafilatura/__init__.py
package io.github.danielonet.trafilatura;

import java.util.List;

import io.github.danielonet.trafilatura.compat.Triple;
import io.github.danielonet.trafilatura.lxml.Element;
import io.github.danielonet.trafilatura.Settings.Document;
import io.github.danielonet.trafilatura.Utils.Response;

/**
 * Python & command-line tool to gather text on the Web:
 * web crawling/scraping, extraction of text, metadata, comments.
 *
 * Package facade: the functions listed in {@code __all__} are re-exported here.
 */
public final class TrafilaturaInit {
    private TrafilaturaInit() {
    }

    public static final String __title__ = "Trafilatura";
    public static final String __author__ = "Adrien Barbaresi and contributors";
    public static final String __license__ = "Apache-2.0";
    public static final String __copyright__ = "Copyright 2019-present, Adrien Barbaresi";
    public static final String __version__ = "2.3.1";

    public static final List<String> __all__ = List.of(
            "bare_extraction", "baseline", "extract", "extract_metadata", "extract_with_metadata", "fetch_response",
            "fetch_url", "html2txt", "load_html");

    public static Document bare_extraction(Object filecontent, Core.Kwargs kw) {
        return Core.bare_extraction(filecontent, kw);
    }

    public static Triple<Element, String, Integer> baseline(Object filecontent) {
        return Baseline.baseline(filecontent);
    }

    public static String extract(Object filecontent) {
        return Core.extract(filecontent);
    }

    public static String extract(Object filecontent, Core.Kwargs kw) {
        return Core.extract(filecontent, kw);
    }

    public static Document extract_metadata(Object filecontent) {
        return Metadata.extract_metadata(filecontent);
    }

    public static Document extract_with_metadata(Object filecontent, Core.Kwargs kw) {
        return Core.extract_with_metadata(filecontent, kw);
    }

    public static Response fetch_response(String url) {
        return Downloads.fetch_response(url);
    }

    public static String fetch_url(String url) {
        return Downloads.fetch_url(url);
    }

    public static String html2txt(Object content) {
        return Baseline.html2txt(content);
    }

    public static Element load_html(Object htmlobject) {
        return Utils.load_html(htmlobject);
    }
}
