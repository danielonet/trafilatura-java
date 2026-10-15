// Java port of trafilatura/settings.py
package trafilatura.trafilatura;

import java.io.FileNotFoundException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import trafilatura.compat.ConfigParser;
import trafilatura.compat.Py;
import trafilatura.lxml.Element;
import trafilatura.lxml.XPath;

import static trafilatura.trafilatura.Utils.line_processing;

/** Listing a series of settings that are applied module-wide. */
public final class Settings {
    private Settings() {
    }

    public static final Logger LOGGER = LoggerFactory.getLogger(Settings.class);

    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    public static final List<String> SUPPORTED_FMT_CLI = List.of("csv", "json", "html", "markdown", "txt", "xml", "xmltei");
    // for bare_extraction() only
    public static final Set<String> SUPPORTED_FORMATS = union(new HashSet<>(SUPPORTED_FMT_CLI), Set.of("python"));

    /** Default settings file, shipped as a classpath resource next to this class. */
    public static final String DEFAULT_CONFIG_RESOURCE = "/trafilatura/settings.cfg";

    public static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> result = new HashSet<>(a);
        result.addAll(b);
        return Set.copyOf(result);
    }

    /** Applied to every configuration read by use_config(): replaceable in tests. */
    public static java.util.function.UnaryOperator<ConfigParser> USE_CONFIG_HOOK = c -> c;

    /** Use configuration object or read and parse a settings file. */
    public static ConfigParser use_config(String filename, ConfigParser config) {
        if (config != null) {
            return config;
        }
        return USE_CONFIG_HOOK.apply(_read_config(filename));
    }

    private static ConfigParser _read_config(String filename) {
        ConfigParser config = new ConfigParser();
        // seed defaults first so a partial user file only overrides the keys it sets
        config.read_resource(DEFAULT_CONFIG_RESOURCE);
        if (filename != null) {
            if (!Files.isRegularFile(Path.of(filename))) {
                throw new UncheckedIOException(new FileNotFoundException("The given config file does not exist"));
            }
            config.read(filename);
        }
        return config;
    }

    public static ConfigParser use_config(String filename) {
        return use_config(filename, null);
    }

    public static ConfigParser use_config() {
        return use_config(null, null);
    }

    public static final ConfigParser DEFAULT_CONFIG = _read_config(null);

    /** Read an optional positive integer setting; None when empty or non-numeric. */
    public static Integer _get_optional_int(ConfigParser config, String option) {
        String value = config.get("DEFAULT", option, "").strip();
        return Py.isdigit(value) ? Integer.valueOf(value) : null;
    }

    // not a dataclass: __init__ contains real logic
    /** Defines a class to store all extraction options. */
    public static class Extractor {
        public ConfigParser config;
        // general
        public String format;
        public boolean fast;
        public String focus;
        public boolean comments;
        public boolean formatting;
        public boolean links;
        public boolean images;
        public boolean tables;
        /** false, true or an {@link Deduplication.LRUCache} */
        public Object dedup;
        public String lang;
        // extraction size
        public int min_extracted_size;
        public int min_output_size;
        public int min_output_comm_size;
        // deduplication
        public int min_duplcheck_size;
        public int max_repetitions;
        // rest
        public int max_file_size;
        public int min_file_size;
        public Integer max_tree_size;
        // meta
        public String source;
        public String url;
        public boolean with_metadata;
        public boolean only_with_metadata;
        public boolean tei_validation;
        public Map<String, Object> date_params;
        public Set<String> author_blacklist;
        public Set<String> url_blacklist;

        /** Keyword arguments of the Python constructor, with their default values. */
        public static class Kwargs {
            ConfigParser config = DEFAULT_CONFIG;
            String output_format = "txt";
            boolean fast = false;
            boolean precision = false;
            boolean recall = false;
            boolean comments = true;
            Boolean formatting = null;
            boolean links = false;
            boolean images = false;
            boolean tables = true;
            Object dedup = false;
            String lang = null;
            String url = null;
            String source = null;
            boolean with_metadata = false;
            boolean only_with_metadata = false;
            boolean tei_validation = false;
            Set<String> author_blacklist = null;
            Set<String> url_blacklist = null;
            Map<String, Object> date_params = null;

            public Kwargs config(ConfigParser v) { config = v; return this; }
            public Kwargs output_format(String v) { output_format = v; return this; }
            public Kwargs fast(boolean v) { fast = v; return this; }
            public Kwargs precision(boolean v) { precision = v; return this; }
            public Kwargs recall(boolean v) { recall = v; return this; }
            public Kwargs comments(boolean v) { comments = v; return this; }
            public Kwargs formatting(Boolean v) { formatting = v; return this; }
            public Kwargs links(boolean v) { links = v; return this; }
            public Kwargs images(boolean v) { images = v; return this; }
            public Kwargs tables(boolean v) { tables = v; return this; }
            public Kwargs dedup(Object v) { dedup = v; return this; }
            public Kwargs lang(String v) { lang = v; return this; }
            public Kwargs url(String v) { url = v; return this; }
            public Kwargs source(String v) { source = v; return this; }
            public Kwargs with_metadata(boolean v) { with_metadata = v; return this; }
            public Kwargs only_with_metadata(boolean v) { only_with_metadata = v; return this; }
            public Kwargs tei_validation(boolean v) { tei_validation = v; return this; }
            public Kwargs author_blacklist(Set<String> v) { author_blacklist = v; return this; }
            public Kwargs url_blacklist(Set<String> v) { url_blacklist = v; return this; }
            public Kwargs date_params(Map<String, Object> v) { date_params = v; return this; }
        }

        public static Kwargs kw() {
            return new Kwargs();
        }

        public Extractor() {
            this(new Kwargs());
        }

        public Extractor(Kwargs kw) {
            if (kw.precision && kw.recall) {
                LOGGER.warn("'precision' and 'recall' are mutually exclusive, 'recall' takes precedence");
            }
            if (!SUPPORTED_FORMATS.contains(kw.output_format)) {
                throw new IllegalArgumentException(
                        "Cannot set format, must be one of: " + String.join(", ", new TreeSet<>(SUPPORTED_FORMATS)));
            }
            this.format = kw.output_format;
            String src = Py.or(kw.url, kw.source);
            // encode("utf-8", "replace"): lone surrogates become "?"
            this.source = src == null || src.isEmpty() ? src
                    : new String(src.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            if (kw.tei_validation && !this.format.equals("xmltei")) {
                LOGGER.warn("tei_validation has no effect unless output_format is 'xmltei'");
            }
            if (Boolean.TRUE.equals(kw.formatting) && this.format.equals("json")) {
                LOGGER.warn("include_formatting has no effect on JSON output");
            }
            // single normalization point: an explicit config=None falls back to defaults
            this.config = kw.config != null ? kw.config : DEFAULT_CONFIG;
            this.min_extracted_size = this.config.getint("DEFAULT", "MIN_EXTRACTED_SIZE");
            this.min_output_size = this.config.getint("DEFAULT", "MIN_OUTPUT_SIZE");
            this.min_output_comm_size = this.config.getint("DEFAULT", "MIN_OUTPUT_COMM_SIZE");
            this.min_duplcheck_size = this.config.getint("DEFAULT", "MIN_DUPLCHECK_SIZE");
            this.max_repetitions = this.config.getint("DEFAULT", "MAX_REPETITIONS");
            this.max_file_size = this.config.getint("DEFAULT", "MAX_FILE_SIZE");
            this.min_file_size = this.config.getint("DEFAULT", "MIN_FILE_SIZE");
            this.max_tree_size = _get_optional_int(this.config, "MAX_TREE_SIZE");
            this.fast = kw.fast;
            this.focus = kw.recall ? "recall" : kw.precision ? "precision" : "balanced";
            this.comments = kw.comments;
            // markdown implies formatting by default, but an explicit formatting=False is honored
            this.formatting = kw.formatting == null ? this.format.equals("markdown") : kw.formatting;
            this.links = kw.links;
            this.images = kw.images;
            this.tables = kw.tables;
            this.dedup = kw.dedup;
            this.lang = kw.lang;
            this.url = kw.url;
            this.only_with_metadata = kw.only_with_metadata;
            this.tei_validation = kw.tei_validation;
            this.author_blacklist = kw.author_blacklist != null ? kw.author_blacklist : new HashSet<>();
            this.url_blacklist = kw.url_blacklist != null ? kw.url_blacklist : new HashSet<>();
            this.with_metadata = kw.with_metadata
                    || kw.only_with_metadata
                    || Py.truthy(kw.url_blacklist)
                    || Py.truthy(kw.author_blacklist)
                    || kw.output_format.equals("xmltei");
            this.date_params = Py.truthy(kw.date_params) ? kw.date_params
                    : set_date_params(this.config.getboolean("DEFAULT", "EXTENSIVE_DATE_SEARCH"));
        }

        /** Shallow copy ({@code copy.copy(options)}). */
        public Extractor copy() {
            Extractor clone = new Extractor(this);
            return clone;
        }

        private Extractor(Extractor other) {
            this.config = other.config;
            this.format = other.format;
            this.fast = other.fast;
            this.focus = other.focus;
            this.comments = other.comments;
            this.formatting = other.formatting;
            this.links = other.links;
            this.images = other.images;
            this.tables = other.tables;
            this.dedup = other.dedup;
            this.lang = other.lang;
            this.min_extracted_size = other.min_extracted_size;
            this.min_output_size = other.min_output_size;
            this.min_output_comm_size = other.min_output_comm_size;
            this.min_duplcheck_size = other.min_duplcheck_size;
            this.max_repetitions = other.max_repetitions;
            this.max_file_size = other.max_file_size;
            this.min_file_size = other.min_file_size;
            this.max_tree_size = other.max_tree_size;
            this.source = other.source;
            this.url = other.url;
            this.with_metadata = other.with_metadata;
            this.only_with_metadata = other.only_with_metadata;
            this.tei_validation = other.tei_validation;
            this.date_params = other.date_params;
            this.author_blacklist = other.author_blacklist;
            this.url_blacklist = other.url_blacklist;
        }

        /** Whether deduplication is active ({@code bool(options.dedup)}). */
        public boolean dedup_enabled() {
            return dedup instanceof Deduplication.LRUCache || Boolean.TRUE.equals(dedup);
        }
    }

    /** Derive extractor configuration from CLI args. */
    public static Extractor args_to_extractor(Cli.Args args, String url) {
        return new Extractor(Extractor.kw()
                .config(use_config(args.config_file))
                .output_format(args.output_format)
                .fast(args.fast)
                .formatting(args.formatting)
                .precision(args.precision)
                .recall(args.recall)
                .comments(args.comments)
                .tables(args.tables)
                .images(args.images)
                .links(args.links)
                .dedup(args.deduplicate)
                .lang(args.target_language)
                .url(url)
                .with_metadata(args.with_metadata)
                .only_with_metadata(args.only_with_metadata)
                .tei_validation(args.validate_tei));
    }

    public static Extractor args_to_extractor(Cli.Args args) {
        return args_to_extractor(args, null);
    }

    /** Provide default parameters for date extraction. */
    public static Map<String, Object> set_date_params(boolean extensive) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("original_date", true);
        params.put("extensive_search", extensive);
        params.put("max_date", ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        return params;
    }

    public static Map<String, Object> set_date_params() {
        return set_date_params(true);
    }

    // not a dataclass: idval/license_val keywords deliberately differ from the field names
    /** Defines a class to store all necessary data and metadata fields for extracted information. */
    public static class Document {
        // ordered by output precedence, not alphabetical
        public static final List<String> __slots__ = List.of(
                "title", "author", "url", "hostname", "description", "sitename", "date", "categories", "tags",
                "fingerprint", "id", "license", "body", "comments", "commentsbody", "raw_text", "text", "language",
                "image", "pagetype", "filedate");

        public String title;
        public String author;
        public String url;
        public String hostname;
        public String description;
        public String sitename;
        public String date;
        public List<String> categories;
        public List<String> tags;
        public String fingerprint;
        public String id;
        public String license;
        public Element body = new Element("body");
        public String comments;
        public Element commentsbody = new Element("body");
        public String raw_text;
        public String text;
        public String language;
        public String image;
        public String pagetype;
        public String filedate;

        public Document() {
        }

        /** Set a series of attributes using a dictionary. */
        @SuppressWarnings("unchecked")
        public static Document from_dict(Map<String, Object> data) {
            Document doc = new Document();
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                doc.setattr(entry.getKey(), entry.getValue());
            }
            return doc;
        }

        /** {@code getattr(doc, name)} for the slots. */
        public Object getattr(String name) {
            return switch (name) {
                case "title" -> title;
                case "author" -> author;
                case "url" -> url;
                case "hostname" -> hostname;
                case "description" -> description;
                case "sitename" -> sitename;
                case "date" -> date;
                case "categories" -> categories;
                case "tags" -> tags;
                case "fingerprint" -> fingerprint;
                case "id" -> id;
                case "license" -> license;
                case "body" -> body;
                case "comments" -> comments;
                case "commentsbody" -> commentsbody;
                case "raw_text" -> raw_text;
                case "text" -> text;
                case "language" -> language;
                case "image" -> image;
                case "pagetype" -> pagetype;
                case "filedate" -> filedate;
                default -> throw new IllegalArgumentException("'Document' object has no attribute '" + name + "'");
            };
        }

        /** {@code setattr(doc, name, value)} for the slots. */
        @SuppressWarnings("unchecked")
        public void setattr(String name, Object value) {
            switch (name) {
                case "title" -> title = (String) value;
                case "author" -> author = (String) value;
                case "url" -> url = (String) value;
                case "hostname" -> hostname = (String) value;
                case "description" -> description = (String) value;
                case "sitename" -> sitename = (String) value;
                case "date" -> date = (String) value;
                case "categories" -> categories = (List<String>) value;
                case "tags" -> tags = (List<String>) value;
                case "fingerprint" -> fingerprint = (String) value;
                case "id" -> id = (String) value;
                case "license" -> license = (String) value;
                case "body" -> body = (Element) value;
                case "comments" -> comments = (String) value;
                case "commentsbody" -> commentsbody = (Element) value;
                case "raw_text" -> raw_text = (String) value;
                case "text" -> text = (String) value;
                case "language" -> language = (String) value;
                case "image" -> image = (String) value;
                case "pagetype" -> pagetype = (String) value;
                case "filedate" -> filedate = (String) value;
                default -> throw new IllegalArgumentException("'Document' object has no attribute '" + name + "'");
            }
        }

        /** Limit text length and trim the attributes. */
        public void clean_and_trim() {
            for (String slot : __slots__) {
                if (getattr(slot) instanceof String value) {
                    // length
                    if (value.length() > 10000) {
                        value = value.substring(0, 9999) + "…";
                    }
                    // HTML entities, remove spaces and control characters
                    setattr(slot, line_processing(Py.unescape(value)));
                }
            }
        }

        /** Convert the document to a dictionary. */
        public Map<String, Object> as_dict() {
            // heterogeneous value types (str, list, lxml _Element, None)
            Map<String, Object> result = new LinkedHashMap<>();
            for (String slot : __slots__) {
                result.put(slot, getattr(slot));
            }
            return result;
        }
    }

    // Safety checks
    public static final int PARALLEL_CORES = Math.min(CPU_COUNT, 16); // 16 processes at most
    public static final int LRU_SIZE = 4096;

    // Files
    public static final int MAX_FILES_PER_DIRECTORY = 1000;
    public static final int FILENAME_LEN = 8;

    // Network
    public static final int MAX_LINKS = 1_000_000;
    public static final int MAX_FEEDS_CHECKED = 1_000; // candidate feeds fetched from a single web page
    public static final int MAX_SITEMAPS_SEEN = 10_000;

    // filters
    public static final Set<String> CUT_EMPTY_ELEMS = Set.of(
            "article", "b", "blockquote", "dd", "div", "dt", "em", "h1", "h2", "h3", "h4", "h5", "h6", "i", "li",
            "main", "p", "pre", "q", "section", "span", "strong");

    // order could matter, using lists to keep extraction deterministic
    public static final List<String> MANUALLY_CLEANED = List.of(
            // important
            "aside", "embed", "fencedframe", "footer", "form", "head", "iframe", "menu", "object", "script",
            // other content
            "applet", "audio", "canvas", "figure", "map", "picture", "svg", "video",
            // secondary
            "area", "blink", "button", "datalist", "dialog", "frame", "frameset", "fieldset", "link", "input",
            "label", "legend", "marquee", "math", "menuitem", "nav", "noindex", "noscript", "optgroup", "option",
            "output", "param", "progress", "rp", "rt", "rtc", "select", "source", "style", "track", "textarea",
            "time", "use");
    // 'meta', 'hr', 'img', 'data', 'details', 'summary'

    public static final List<String> MANUALLY_STRIPPED = List.of(
            "abbr", "acronym", "address", "bdi", "bdo", "big", "cite", "data", "dfn", "font", "hgroup", "img",
            "ins", "mark", "meta", "nobr", "ruby", "small", "tbody", "template", "tfoot", "thead",
            // the HTML parser does not treat it as void: it would swallow the rest of the paragraph
            "wbr");

    // baseline()/html2txt() only (not the main pipeline). NOTE: html2txt() also measures page length
    // for the recall-escalation gate (core.py) -- shrinking this set fires escalation less often, so
    // re-run the full benchmark suite before changing it.
    // prune cookie/consent banners: fluent prose justext's density metric can't classify; class/id is
    // English even on localized templates. Anchored banner/CMP compounds, NOT bare 'cookie'/'consent'
    // substrings. Vocabulary = high-signal compounds + CMP vendors, each validated against corpus banner markup.
    public static final String _COOKIE_CONSENT_RE =
            "cookie[-_]?(?:banner|bar|consent|law|notice|policy|description)|notice[-_]{0,2}cookie"
                    + "|consent[-_]?(?:banner|manager|sdk)|borlabs|cookiebot|cmplz|onetrust|moove[-_]?gdpr";
    public static final XPath BASIC_CLEAN_XPATH = new XPath(
            ".//aside|.//div[contains(@class|@id, 'footer')]|.//fencedframe|.//footer|.//script|.//style|.//svg|.//template"
                    + "|.//*[re:test(@class, '" + _COOKIE_CONSENT_RE + "', 'i') or re:test(@id, '" + _COOKIE_CONSENT_RE
                    + "', 'i')]",
            Map.of("re", "http://exslt.org/regular-expressions"));

    public static final Set<String> TAG_CATALOG = Set.of("code", "del", "head", "hi", "lb", "list", "p", "quote");

    // min length for a repeated span to count as an extraction artifact (not coincidental content).
    // Shared by main_extractor's recovery dedup and baseline's paragraph-strategy dedup
    public static final int MIN_DUPLICATE_LENGTH = 50;

    // the substring-dedup scans are O(n^2) in accumulated text; skip beyond this size (guards a
    // pathological page -- no corpus page comes close, worst ~60KB)
    public static final int DEDUPE_SCAN_CAP = 200_000;

    // inline-tag ladder (single source of truth shared by extractor and serializer):
    public static final Set<String> INLINE_CONSUMING = Set.of("hi", "ref", "del"); // element folds its children into its own text
    public static final Set<String> INLINE_FORMATTABLE = union(INLINE_CONSUMING, Set.of("code")); // + code: has text, rendered verbatim
    public static final Set<String> INLINE_CARRIED = union(INLINE_FORMATTABLE, Set.of("graphic")); // + graphic: no text, rendered as image markup

    // mapping for languages known to py3langid
    public static final Map<String, String> JUSTEXT_LANGUAGES = Map.ofEntries(
            Map.entry("af", "Afrikaans"), Map.entry("an", "Aragonese"), Map.entry("ar", "Arabic"),
            Map.entry("az", "Azerbaijani"), Map.entry("be", "Belarusian"), Map.entry("bg", "Bulgarian"),
            Map.entry("bn", "Bengali"), Map.entry("br", "Breton"), Map.entry("bs", "Bosnian"),
            Map.entry("ca", "Catalan"), Map.entry("cs", "Czech"), Map.entry("cy", "Welsh"),
            Map.entry("da", "Danish"), Map.entry("de", "German"), Map.entry("el", "Greek"),
            Map.entry("en", "English"), Map.entry("eo", "Esperanto"), Map.entry("es", "Spanish"),
            Map.entry("et", "Estonian"), Map.entry("eu", "Basque"), Map.entry("fa", "Persian"),
            Map.entry("fi", "Finnish"), Map.entry("fr", "French"), Map.entry("ga", "Irish"),
            Map.entry("gl", "Galician"), Map.entry("gu", "Gujarati"), Map.entry("he", "Hebrew"),
            Map.entry("hi", "Hindi"), Map.entry("hr", "Croatian"), Map.entry("ht", "Haitian"),
            Map.entry("hu", "Hungarian"), Map.entry("hy", "Armenian"), Map.entry("id", "Indonesian"),
            Map.entry("is", "Icelandic"), Map.entry("it", "Italian"), Map.entry("jv", "Javanese"),
            Map.entry("ka", "Georgian"), Map.entry("kk", "Kazakh"), Map.entry("kn", "Kannada"),
            Map.entry("ko", "Korean"), Map.entry("ku", "Kurdish"), Map.entry("ky", "Kyrgyz"),
            Map.entry("la", "Latin"), Map.entry("lb", "Luxembourgish"), Map.entry("lt", "Lithuanian"),
            Map.entry("lv", "Latvian"), Map.entry("mk", "Macedonian"), Map.entry("ml", "Malayalam"),
            Map.entry("mr", "Marathi"), Map.entry("ms", "Malay"), Map.entry("mt", "Maltese"),
            Map.entry("nb", "Norwegian_Bokmal"), Map.entry("ne", "Nepali"), Map.entry("nl", "Dutch"),
            Map.entry("nn", "Norwegian_Nynorsk"), Map.entry("no", "Norwegian_Bokmal"), Map.entry("oc", "Occitan"),
            Map.entry("pl", "Polish"), Map.entry("pt", "Portuguese"), Map.entry("qu", "Quechua"),
            Map.entry("ro", "Romanian"), Map.entry("ru", "Russian"), Map.entry("sk", "Slovak"),
            Map.entry("sl", "Slovenian"), Map.entry("sq", "Albanian"), Map.entry("sr", "Serbian"),
            Map.entry("sv", "Swedish"), Map.entry("sw", "Swahili"), Map.entry("ta", "Tamil"),
            Map.entry("te", "Telugu"), Map.entry("tl", "Tagalog"), Map.entry("tr", "Turkish"),
            Map.entry("uk", "Ukrainian"), Map.entry("ur", "Urdu"), Map.entry("vi", "Vietnamese"),
            Map.entry("vo", "Volapuk"), Map.entry("wa", "Walloon"));
    // no justext stoplist available: 'ja' (Japanese), 'zh' (Chinese)
}
