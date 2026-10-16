// Java port of trafilatura/core.py
package io.github.danielonet.trafilatura;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.danielonet.trafilatura.compat.ConfigParser;
import io.github.danielonet.trafilatura.compat.Json;
import io.github.danielonet.trafilatura.compat.Pair;
import io.github.danielonet.trafilatura.compat.Py;
import io.github.danielonet.trafilatura.compat.Triple;
import io.github.danielonet.trafilatura.lxml.Element;
import io.github.danielonet.trafilatura.lxml.Etree;
import io.github.danielonet.trafilatura.lxml.XPath;
import io.github.danielonet.trafilatura.Settings.Document;
import io.github.danielonet.trafilatura.Settings.Extractor;

import static io.github.danielonet.trafilatura.Baseline.baseline;
import static io.github.danielonet.trafilatura.Baseline.html2txt;
import static io.github.danielonet.trafilatura.Deduplication.content_fingerprint;
import static io.github.danielonet.trafilatura.Deduplication.duplicate_test;
import static io.github.danielonet.trafilatura.External.compare_extraction;
import static io.github.danielonet.trafilatura.External.justext_rescue;
import static io.github.danielonet.trafilatura.Htmlprocessing.build_html_output;
import static io.github.danielonet.trafilatura.Htmlprocessing.convert_tags;
import static io.github.danielonet.trafilatura.Htmlprocessing.prune_unwanted_nodes;
import static io.github.danielonet.trafilatura.Htmlprocessing.tree_cleaning;
import static io.github.danielonet.trafilatura.MainExtractor._elem_text;
import static io.github.danielonet.trafilatura.MainExtractor.extract_comments;
import static io.github.danielonet.trafilatura.MainExtractor.extract_content;
import static io.github.danielonet.trafilatura.Metadata.extract_metadata;
import static io.github.danielonet.trafilatura.Settings.DEFAULT_CONFIG;
import static io.github.danielonet.trafilatura.Settings.use_config;
import static io.github.danielonet.trafilatura.Utils.LANGID_FLAG;
import static io.github.danielonet.trafilatura.Utils.check_html_lang;
import static io.github.danielonet.trafilatura.Utils.language_filter;
import static io.github.danielonet.trafilatura.Utils.load_html;
import static io.github.danielonet.trafilatura.Utils.normalize_unicode;
import static io.github.danielonet.trafilatura.Xml.build_json_output;
import static io.github.danielonet.trafilatura.Xml.control_xml_output;
import static io.github.danielonet.trafilatura.Xml.delete_element;
import static io.github.danielonet.trafilatura.Xml.keeps_empty;
import static io.github.danielonet.trafilatura.Xml.xmltocsv;
import static io.github.danielonet.trafilatura.Xml.xmltotxt;
import static io.github.danielonet.trafilatura.Xpaths.RAW_TREE_PRUNE_XPATH;
import static io.github.danielonet.trafilatura.Xpaths.REMOVE_COMMENTS_AND_LISTS_XPATH;
import static io.github.danielonet.trafilatura.Xpaths.REMOVE_COMMENTS_XPATH;

/** Extraction configuration and processing functions. */
public final class Core {
    private Core() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Core.class);

    // recall escalation (see trafilatura_sequence, stage 4): retry a short balanced extraction
    // in recall mode. Calibrated as a set against the benchmark suite (own-bench/WMB/WCXB/AEB) —
    // don't tune one value in isolation, and re-run the full suite after any change.
    public static final int ESCALATION_MAX_LENGTH = 3000; // only consider extractions below this size
    public static final double ESCALATION_PAGE_SHARE = 0.2; // ... covering less than this share of the page text
    public static final double ESCALATION_ACCEPT_RATIO = 1.5; // accept the retry if it is this much longer
    // justext tends to over-include (whole pricing tables, unrelated sections) rather than stop at
    // page boundaries the way the rule-based retry does, so it needs a stricter bar than the
    // retry's own 1.5x: swept 1.5-10x, 2.0x is the best balance across the full suite.
    public static final double ESCALATION_JUSTEXT_RATIO = 2.0;

    public static final Set<String> TXT_FORMATS = Set.of("markdown", "txt");
    public static final List<String> _YAML_FIELDS = List.of(
            "title", "author", "url", "hostname", "description", "sitename", "date", "categories", "tags",
            "fingerprint", "id", "license");

    // Metadata is emitted as a YAML-style Markdown header; values such as a title
    // containing ": " (or a leading indicator, or a reserved word) otherwise produce
    // invalid YAML or get reinterpreted as a non-string. See GH #814.
    public static final Set<String> _YAML_RESERVED = Set.of("true", "false", "yes", "no", "on", "off", "y", "n", "null", "none", "~");

    /**
     * Keyword arguments of extract(), extract_with_metadata() and bare_extraction(),
     * with the Python default values. {@code output_format} defaults to "txt" for
     * extract() and to "python" for bare_extraction() when left unset.
     */
    public static class Kwargs {
        String url = null;
        String record_id = null;
        boolean fast = false;
        boolean no_fallback = false;
        boolean favor_precision = false;
        boolean favor_recall = false;
        boolean include_comments = true;
        String output_format = null;
        boolean tei_validation = false;
        String target_language = null;
        boolean include_tables = true;
        boolean include_images = false;
        Boolean include_formatting = null;
        boolean include_links = false;
        Object deduplicate = false;
        Map<String, Object> date_extraction_params = null;
        boolean with_metadata = false;
        boolean only_with_metadata = false;
        Integer max_tree_size = null;
        Set<String> url_blacklist = null;
        Set<String> author_blacklist = null;
        String settingsfile = null;
        boolean as_dict = false;
        List<String> prune_xpath = null;
        ConfigParser config = DEFAULT_CONFIG;
        Extractor options = null;

        public Kwargs url(String v) { url = v; return this; }
        public Kwargs record_id(String v) { record_id = v; return this; }
        public Kwargs fast(boolean v) { fast = v; return this; }
        public Kwargs no_fallback(boolean v) { no_fallback = v; return this; }
        public Kwargs favor_precision(boolean v) { favor_precision = v; return this; }
        public Kwargs favor_recall(boolean v) { favor_recall = v; return this; }
        public Kwargs include_comments(boolean v) { include_comments = v; return this; }
        public Kwargs output_format(String v) { output_format = v; return this; }
        public Kwargs tei_validation(boolean v) { tei_validation = v; return this; }
        public Kwargs target_language(String v) { target_language = v; return this; }
        public Kwargs include_tables(boolean v) { include_tables = v; return this; }
        public Kwargs include_images(boolean v) { include_images = v; return this; }
        public Kwargs include_formatting(Boolean v) { include_formatting = v; return this; }
        public Kwargs include_links(boolean v) { include_links = v; return this; }
        public Kwargs deduplicate(Object v) { deduplicate = v; return this; }
        public Kwargs date_extraction_params(Map<String, Object> v) { date_extraction_params = v; return this; }
        public Kwargs with_metadata(boolean v) { with_metadata = v; return this; }
        public Kwargs only_with_metadata(boolean v) { only_with_metadata = v; return this; }
        public Kwargs max_tree_size(Integer v) { max_tree_size = v; return this; }
        public Kwargs url_blacklist(Set<String> v) { url_blacklist = v; return this; }
        public Kwargs author_blacklist(Set<String> v) { author_blacklist = v; return this; }
        public Kwargs settingsfile(String v) { settingsfile = v; return this; }
        public Kwargs as_dict(boolean v) { as_dict = v; return this; }
        /** {@code prune_xpath}: one expression or several. */
        public Kwargs prune_xpath(String... v) { prune_xpath = List.of(v); return this; }
        public Kwargs prune_xpath(List<String> v) { prune_xpath = v; return this; }
        public Kwargs config(ConfigParser v) { config = v; return this; }
        public Kwargs options(Extractor v) { options = v; return this; }
    }

    public static Kwargs kw() {
        return new Kwargs();
    }

    /** Render a metadata string as a plain or double-quoted YAML-safe scalar. */
    public static String _yaml_scalar(String value) {
        if (!value.isEmpty()
                && value.equals(Py.strip(value))
                && Character.isLetter(value.codePointAt(0))
                && !value.contains(": ")
                && !value.contains(" #")
                && !value.endsWith(":")
                && !_YAML_RESERVED.contains(value.toLowerCase())
                && value.chars().allMatch(ch -> ch >= ' ' && ch != 0x7f)) {
            return value;
        }
        // a JSON string is always a valid, exactly round-tripping YAML double-quoted scalar
        return Json.dumps(value, false);
    }

    /** Convert XML tree to chosen format, clean the result and output it as a string. */
    public static String determine_returnstring(Document document, Extractor options) {
        String returnstring;
        if (options.format.contains("xml")) {
            // XML (TEI) steps
            // last cleaning
            for (Element element : document.body.iter("*")) {
                if (element.size() == 0 && !Py.truthy(element.text) && !Py.truthy(element.tail) && !keeps_empty(element)) {
                    delete_element(element, false);
                }
            }
            // build output tree
            returnstring = control_xml_output(document, options);
        } else if (options.format.equals("csv")) {
            returnstring = xmltocsv(document, options.formatting);
        } else if (options.format.equals("json")) {
            returnstring = build_json_output(document, options.with_metadata);
        } else if (options.format.equals("html")) {
            returnstring = build_html_output(document, options.with_metadata);
        } else {
            // Markdown and TXT
            String header = "";
            if (options.with_metadata) {
                // categories/tags are lists, their repr is a valid flow sequence
                StringBuilder fields = new StringBuilder();
                for (String attr : _YAML_FIELDS) {
                    Object value = document.getattr(attr);
                    if (Py.truthy(value)) {
                        fields.append(attr).append(": ")
                                .append(value instanceof String s ? _yaml_scalar(s) : Py.str(value)).append("\n");
                    }
                }
                header = "---\n" + fields + "---\n";
            }
            returnstring = header + xmltotxt(document.body, options.formatting);
            if (document.commentsbody != null) {
                returnstring = Py.strip(returnstring + "\n" + xmltotxt(document.commentsbody, options.formatting));
            }
        }
        // normalize Unicode format (defaults to NFC)
        return normalize_unicode(returnstring);
    }

    // matches "@type": "DiscussionForumPosting" or an @type array containing it, anchored to the
    // key so it can't fire on the words appearing in ordinary prose (e.g. a description field)
    public static final Pattern _DISCUSSION_FORUM_POSTING_RE = Pattern.compile(
            "\"@type\"\\s*:\\s*\"DiscussionForumPosting\"|\"@type\"\\s*:\\s*\\[[^\\]]*\"DiscussionForumPosting\"");

    /**
     * Detect a thread-forum page where posts live in the same containers
     * REMOVE_COMMENTS_XPATH would otherwise prune -- comments are content here, unlike on
     * a blog or article. Seeded by schema.org DiscussionForumPosting alone.
     */
    public static boolean _forum_thread_page(Element tree) {
        for (Element script : tree.iterfind(".//script[@type=\"application/ld+json\"]")) {
            if (Py.truthy(script.text) && _DISCUSSION_FORUM_POSTING_RE.matcher(script.text).find()) {
                return true;
            }
        }
        return false;
    }

    /** Clean and convert a raw tree, returning (converted, pre-conversion backup). */
    public static Pair<Element, Element> _prepare_tree(Element tree, Extractor options, String url) {
        Element cleaned = tree_cleaning(tree.copy(), options);
        Element backup = cleaned.copy();
        cleaned = convert_tags(cleaned, options, url);
        return Pair.of(cleaned, backup);
    }

    /** Cascade stages 1-2: main extractor, then the external comparison unless in fast mode. */
    public static Pair<Element, String> _extract_and_compare(Element cleaned_tree, Element cleaned_tree_backup, Element tree,
            Extractor options) {
        Pair<Element, String> result = extract_content(cleaned_tree, options);
        if (!options.fast) {
            result = compare_extraction(cleaned_tree_backup, tree.copy(), result.first(), result.second(), options);
        }
        return result;
    }

    /**
     * Stage-4 retry: re-run cascade stages 1-2 in recall mode on the escalation input
     * (arrives comment-pruned, or intact on a thread-forum where posts are content).
     */
    public static Pair<Element, String> _recall_retry(Element esc_tree, Extractor r_options, String url) {
        Pair<Element, Element> prepared = _prepare_tree(esc_tree, r_options, url);
        return _extract_and_compare(prepared.first(), prepared.second(), esc_tree, r_options);
    }

    /** Body and comments elements with their text, the result of {@link #trafilatura_sequence}. */
    public record SequenceResult(Element postbody, String temp_text, Element commentsbody, String temp_comments) {
    }

    /**
     * Prepare the raw tree (cleaning, tag conversion, comment handling), then execute the
     * standard cascade of extractors used by Trafilatura, each stage only engaging if the
     * previous one under-delivered:
     * 1. main extractor (includes wild-text recovery for short documents)
     * 2. comparison with external extractors (readability/justext), skipped in fast mode
     * 3. baseline rescue on the original, uncleaned tree
     * 4. recall escalation, if the result still covers little of the page
     */
    public static SequenceResult trafilatura_sequence(Element tree, Extractor options, String url) {
        boolean is_forum = _forum_thread_page(tree);
        // raw-tree prune so the external extractors inherit it too: readability would otherwise
        // pick the longest appended article over the real one
        tree = prune_unwanted_nodes(tree, RAW_TREE_PRUNE_XPATH);
        // comments off: prune the raw tree so all stages inherit it, lists are still ol/ul here
        if (!options.comments && (options.focus.equals("precision") || !is_forum)) {
            tree = prune_unwanted_nodes(tree, REMOVE_COMMENTS_AND_LISTS_XPATH);
        }
        Pair<Element, Element> prepared = _prepare_tree(tree, options, url);
        Element cleaned_tree = prepared.first();
        Element cleaned_tree_backup = prepared.second();

        Element commentsbody = Etree.Element("body");
        String temp_comments = "";
        Element forum_posts = null;
        if (options.comments) {
            Triple<Element, String, Element> comments = extract_comments(cleaned_tree, options);
            commentsbody = comments.first();
            temp_comments = comments.second();
            cleaned_tree = comments.third();
            if (!temp_comments.isEmpty() && is_forum) {
                // thread-forum: the "comments" are the posts -> route into the body (backup predates
                // capture); keep the capture aside, salvaged below if the cascade drops the posts
                forum_posts = commentsbody;
                commentsbody = Etree.Element("body");
                temp_comments = "";
                cleaned_tree = convert_tags(cleaned_tree_backup.copy(), options, url);
            }
        }
        if (options.focus.equals("precision") && !is_forum) {
            // NOT redundant with the raw-tree prune above: this runs POST-conversion, where lists are <list>
            // and match the xpath's self::list (dl lists, disqus ids, and all lists if comments are on)
            cleaned_tree = prune_unwanted_nodes(cleaned_tree, REMOVE_COMMENTS_XPATH);
        }

        Pair<Element, String> extracted = _extract_and_compare(cleaned_tree, cleaned_tree_backup, tree, options);
        Element postbody = extracted.first();
        String temp_text = extracted.second();

        // 3. rescue: baseline on the original tree, accepted only if it adds text (#896)
        if (temp_text.length() < options.min_extracted_size && !options.focus.equals("precision")) {
            Triple<Element, String, Integer> rescued = baseline(tree); // baseline copies element inputs
            LOGGER.debug("non-clean extracted length: {} (extraction)", rescued.third());
            if (rescued.third() > temp_text.length()) {
                postbody = rescued.first();
                temp_text = rescued.second();
                forum_posts = null; // the dump saw the whole page: missing posts are boilerplate, not lost
            }
        }

        // 4. recall escalation: a short extraction covering little of the page suggests
        // under-extraction (non-article layout) — retry in recall mode, keep if clearly bigger.
        // NOTE: the page measure html2txt(tree) is coupled to BASIC_CLEAN_XPATH — see settings.py.
        if (options.focus.equals("balanced")
                && 0 < temp_text.length() && temp_text.length() < ESCALATION_MAX_LENGTH
                && temp_text.length() < ESCALATION_PAGE_SHARE * html2txt(tree).length()) {
            // a copy so a shared Extractor never leaks the "recall" focus back to the caller
            Extractor r_options = options.copy();
            r_options.focus = "recall";
            // strip comment containers from the escalation input (dup risk if captured, reader comments if not),
            // ol/ul comment lists only if captured (#881). Not on a thread-forum, where the retry rescues the posts.
            // Comments off: already pruned
            Element esc_tree = is_forum || !options.comments
                    ? tree
                    : prune_unwanted_nodes(tree.copy(),
                            !temp_comments.isEmpty() ? REMOVE_COMMENTS_AND_LISTS_XPATH : REMOVE_COMMENTS_XPATH);
            Element r_body = null;
            String r_text = "";
            try {
                Pair<Element, String> retried = _recall_retry(esc_tree, r_options, url);
                r_body = retried.first();
                r_text = retried.second();
            } catch (RuntimeException err) {
                LOGGER.warn("recall retry failed: {} {}", err.toString(), url);
            }
            // justext reaches div-buried content the rule retry misses (gated: ungated regressed
            // own-fallback). No region scoping of its own -> esc_tree is comment-pruned above
            Element j_body = null;
            String j_text = "";
            if (!options.fast) {
                try {
                    Pair<Element, String> rescued = justext_rescue(esc_tree.copy(), options);
                    j_body = rescued.first();
                    j_text = rescued.second();
                } catch (RuntimeException err) {
                    LOGGER.warn("justext candidate failed: {} {}", err.toString(), url);
                }
            }

            // floor on the retry: a result below the pipeline's own minimum is noise. An accepted
            // candidate saw the full page, so its exclusions are deliberate -> drop the forum-post salvage.
            int len_text = temp_text.length();
            int r_len = r_text.length();
            int j_len = j_text.length();
            if (j_len > r_len && j_len > ESCALATION_JUSTEXT_RATIO * len_text) {
                postbody = j_body;
                temp_text = j_text;
                forum_posts = null;
            } else if (r_len >= options.min_extracted_size && r_len > ESCALATION_ACCEPT_RATIO * len_text) {
                postbody = r_body;
                temp_text = r_text;
                forum_posts = null;
            }
        }

        if (forum_posts != null) {
            // a gate (escalation length, precision) blocked the cascade from restoring the posts:
            // append the ones missing from the body
            List<String> texts = new ArrayList<>();
            for (Element el : postbody) {
                texts.add(_elem_text(el));
            }
            String existing = Xml.joinNonEmpty("\n", texts.toArray(String[]::new));
            List<Element> salvaged = new ArrayList<>();
            for (Element el : forum_posts) {
                String t = _elem_text(el);
                if (!t.isEmpty() && !existing.contains(t)) {
                    salvaged.add(el);
                }
            }
            if (!salvaged.isEmpty()) {
                LOGGER.debug("thread-forum salvage: {} captured posts appended to the body", salvaged.size());
                postbody.extend(salvaged);
                temp_text = Py.strip(String.join(" ", postbody.itertext()));
            }
        }

        return new SequenceResult(postbody, temp_text, commentsbody, temp_comments);
    }

    public static SequenceResult trafilatura_sequence(Element tree, Extractor options) {
        return trafilatura_sequence(tree, options, null);
    }

    /** Notified of every Extractor built from keyword arguments: lets tests spy on core.Extractor. */
    public static java.util.function.Consumer<Extractor> EXTRACTOR_CREATED = options -> { };

    /** {@code raise ValueError} used to discard a document in bare_extraction(). */
    private static IllegalArgumentException discard() {
        return new IllegalArgumentException();
    }

    /**
     * Internal function for text extraction returning bare Python variables.
     *
     * @return a Document containing all the extracted information or null.
     *
     * Low-level primitive: returns a Document with an unserialized .body tree; tei_validation only
     * applies when serializing via extract(). In the default balanced mode, a short extraction
     * covering little of the page is automatically retried with recall settings.
     * The deprecated {@code as_dict} flag is not supported: call {@code Document.as_dict()}.
     */
    public static Document bare_extraction(Object filecontent, Kwargs kw) {
        // deprecations
        boolean fast = _check_deprecation(kw.fast, kw.no_fallback, kw.as_dict, kw.max_tree_size);

        // regroup extraction options
        Extractor options = kw.options;
        if (options == null) {
            options = new Extractor(Extractor.kw()
                    .config(kw.config)
                    .output_format(kw.output_format != null ? kw.output_format : "python")
                    .fast(fast)
                    .precision(kw.favor_precision)
                    .recall(kw.favor_recall)
                    .comments(kw.include_comments)
                    .formatting(kw.include_formatting)
                    .links(kw.include_links)
                    .images(kw.include_images)
                    .tables(kw.include_tables)
                    .dedup(kw.deduplicate)
                    .lang(kw.target_language)
                    .url(kw.url)
                    .with_metadata(kw.with_metadata)
                    .only_with_metadata(kw.only_with_metadata)
                    .author_blacklist(kw.author_blacklist)
                    .url_blacklist(kw.url_blacklist)
                    .date_params(kw.date_extraction_params));
            EXTRACTOR_CREATED.accept(options);
        }

        Document document;
        Element postbody;
        String temp_text;
        Element commentsbody;
        try {
            // load the HTML tree
            Element tree = load_html(filecontent, options.max_file_size);
            if (tree == null) {
                LOGGER.error("empty HTML tree: {}", kw.url);
                throw discard();
            }
            if (tree == filecontent) {
                tree = tree.copy();
            }

            // quick and dirty HTML lang check
            if (Py.truthy(options.lang) && (options.fast || !LANGID_FLAG)) {
                if (!check_html_lang(tree, options.lang)) {
                    LOGGER.error("wrong HTML meta language: {}", options.source);
                    throw discard();
                }
            }

            // extract metadata if necessary
            if (options.with_metadata) {
                document = extract_metadata(tree, options.url, options.date_params, options.fast, options.author_blacklist);

                // cut short if extracted URL in blacklist
                if (document.url != null && options.url_blacklist.contains(document.url)) {
                    LOGGER.warn("blacklisted URL: {}", document.url);
                    throw discard();
                }

                // cut short if core elements are missing
                if (options.only_with_metadata
                        && !(Py.truthy(document.date) && Py.truthy(document.title) && Py.truthy(document.url))) {
                    LOGGER.error("no metadata: {}", options.source);
                    throw discard();
                }
            } else {
                document = new Document();
            }

            // prune all xpath expressions that user specified
            // no backup as this is under full control of the user
            if (kw.prune_xpath != null) {
                List<XPath> expressions = new ArrayList<>();
                for (String x : kw.prune_xpath) {
                    expressions.add(new XPath(x));
                }
                tree = prune_unwanted_nodes(tree, expressions);
            }

            SequenceResult result = trafilatura_sequence(tree, options, Py.or(options.url, document.url));
            postbody = result.postbody();
            temp_text = result.temp_text();
            commentsbody = result.commentsbody();
            int len_text = temp_text.length();
            int len_comments = result.temp_comments().length();

            // tree size sanity check
            if (options.max_tree_size != null && options.max_tree_size != 0) {
                // strip tags
                if (postbody.size() > options.max_tree_size) {
                    LOGGER.debug("output tree too long: {}", postbody.size());
                    Etree.strip_tags(postbody, "hi");
                }
                // still too long, raise an error
                if (postbody.size() > options.max_tree_size) {
                    LOGGER.debug("output tree too long: {}, discarding {}", postbody.size(), options.source);
                    throw discard();
                }
            }
            // size checks
            if (len_text < options.min_output_size && len_comments < options.min_output_comm_size) {
                LOGGER.debug("text and comments not long enough: {} {} {}", len_text, len_comments, options.source);
                throw discard();
            }

            // check duplicates at body level
            if (options.dedup_enabled() && duplicate_test(postbody, options)) {
                LOGGER.debug("discarding duplicate document: {}", options.source);
                throw discard();
            }

            // sanity check on language
            if (Py.truthy(options.lang)) {
                Pair<Boolean, Document> filtered = language_filter(temp_text, result.temp_comments(), options.lang, document);
                document = filtered.second();
                if (filtered.first()) {
                    LOGGER.debug("wrong language: {}", options.source);
                    throw discard();
                }
            }
        } catch (IllegalArgumentException err) { // TypeError, ValueError
            LOGGER.warn("discarding data: {}", options.source);
            return null;
        }

        // special case: python variables
        if (options.format.equals("python")) {
            document.text = xmltotxt(postbody, options.formatting);
            if (options.comments) {
                document.comments = xmltotxt(commentsbody, options.formatting);
                document.commentsbody = commentsbody;
            }
            document.raw_text = document.text;
        } else {
            document.raw_text = temp_text;
            document.commentsbody = commentsbody;
        }
        document.body = postbody;

        return document;
    }

    public static Document bare_extraction(Object filecontent) {
        return bare_extraction(filecontent, new Kwargs());
    }

    /**
     * Main function exposed by the package:
     * Wrapper for text extraction and conversion to chosen output format.
     *
     * @return a string in the desired format or null.
     */
    public static String extract(Object filecontent, Kwargs kw) {
        Document document = _internal_extraction(filecontent, kw, kw.with_metadata);
        return document != null ? document.text : null;
    }

    public static String extract(Object filecontent) {
        return extract(filecontent, new Kwargs());
    }

    /**
     * Main function exposed by the package:
     * Wrapper for text extraction and conversion to chosen output format.
     * This method also returns document metadata.
     *
     * @return Document metadata with content string in the desired format or null.
     */
    public static Document extract_with_metadata(Object filecontent, Kwargs kw) {
        return _internal_extraction(filecontent, kw, true);
    }

    public static Document extract_with_metadata(Object filecontent) {
        return extract_with_metadata(filecontent, new Kwargs());
    }

    /** Check deprecated params and return the effective "fast" flag. */
    public static boolean _check_deprecation(boolean fast, boolean no_fallback, boolean as_dict, Integer max_tree_size) {
        if (no_fallback) {
            LOGGER.warn("DeprecationWarning: \"no_fallback\" will be removed, use \"fast\" instead");
        }
        if (as_dict) {
            LOGGER.warn("DeprecationWarning: \"as_dict\" will be removed, use the .as_dict() method instead");
        }
        if (max_tree_size != null && max_tree_size != 0) {
            throw new IllegalArgumentException("\"max_tree_size\" will be removed, use settings.cfg instead");
        }
        return fast || no_fallback;
    }

    /** Internal method to do the extraction. */
    public static Document _internal_extraction(Object filecontent, Kwargs kw, boolean with_metadata) {
        boolean fast = _check_deprecation(kw.fast, kw.no_fallback, false, kw.max_tree_size);

        // regroup extraction options
        Extractor options = kw.options;
        if (options == null) {
            options = new Extractor(Extractor.kw()
                    .config(Py.truthy(kw.settingsfile) ? use_config(kw.settingsfile) : kw.config)
                    .output_format(kw.output_format != null ? kw.output_format : "txt")
                    .fast(fast)
                    .precision(kw.favor_precision)
                    .recall(kw.favor_recall)
                    .comments(kw.include_comments)
                    .formatting(kw.include_formatting)
                    .links(kw.include_links)
                    .images(kw.include_images)
                    .tables(kw.include_tables)
                    .dedup(kw.deduplicate)
                    .lang(kw.target_language)
                    .url(kw.url)
                    .with_metadata(with_metadata)
                    .only_with_metadata(kw.only_with_metadata)
                    .tei_validation(kw.tei_validation)
                    .author_blacklist(kw.author_blacklist)
                    .url_blacklist(kw.url_blacklist)
                    .date_params(kw.date_extraction_params));
            EXTRACTOR_CREATED.accept(options);
        }

        // extraction
        Kwargs bare = new Kwargs().options(options).prune_xpath(kw.prune_xpath);
        Document document = bare_extraction(filecontent, bare);

        // post-processing
        if (document == null) {
            return null;
        }

        // TXT formats only output the ID and fingerprint in their metadata header
        if (!TXT_FORMATS.contains(options.format) || options.with_metadata) {
            // control output
            if (options.format.equals("python")) {
                throw new IllegalArgumentException("'python' format only usable in bare_extraction() function");
            }
            // add record ID to metadata
            document.id = kw.record_id;
            // calculate fingerprint
            if (document.raw_text != null) {
                document.fingerprint = content_fingerprint(Py.str(document.title) + " " + Py.str(document.raw_text));
            }
        }

        document.text = determine_returnstring(document, options);
        return document;
    }
}
