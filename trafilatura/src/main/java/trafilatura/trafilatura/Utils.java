// Java port of trafilatura/utils.py
package trafilatura.trafilatura;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

import org.mozilla.universalchardet.UniversalDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import trafilatura.compat.Pair;
import trafilatura.compat.Py;
import trafilatura.compat.Re;
import trafilatura.courlan.Courlan;
import trafilatura.lxml.Element;
import trafilatura.lxml.Etree;
import trafilatura.trafilatura.Settings.Document;
import trafilatura.trafilatura.Settings.Extractor;

/**
 * Module bundling functions related to HTML and text processing,
 * content filtering and language detection.
 */
public final class Utils {
    private Utils() {
    }

    // optional Python packages (brotli, zstd, py3langid, cchardet) have no Java counterpart here
    public static final boolean HAS_BROTLI = false;
    public static final boolean HAS_ZSTD = false;
    public static final boolean LANGID_FLAG = false;

    /** Store information gathered in a HTTP response object. */
    public static class Response {
        public static final List<String> __slots__ = List.of("data", "headers", "html", "status", "url");

        public byte[] data;
        public Map<String, String> headers;
        public String html;
        public int status;
        public String url;

        public Response(byte[] data, int status, String url) {
            this.data = data;
            this.headers = null;
            this.html = null;
            this.status = status;
            this.url = url;
        }

        /** {@code __bool__} */
        public boolean isTruthy() {
            return this.data != null;
        }

        @Override
        public String toString() {
            return this.html != null && !this.html.isEmpty() ? this.html : decode_file(this.data);
        }

        /** Store response headers with lowercase names. */
        public void store_headers(Map<String, String> headerdict) {
            Map<String, String> headers = new LinkedHashMap<>();
            headerdict.forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v));
            this.headers = headers;
        }

        /** Decode the bytestring in data and store a string in html. */
        public void decode_data(boolean decode, Integer max_size) {
            if (decode && this.data != null && this.data.length > 0) {
                this.html = decode_file(this.data, max_size);
            }
        }

        public void decode_data(boolean decode) {
            decode_data(decode, null);
        }

        /** Convert the response object to a dictionary. */
        public Map<String, Object> as_dict() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("data", data);
            result.put("headers", headers);
            result.put("html", html);
            result.put("status", status);
            result.put("url", url);
            return result;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Utils.class);

    public static final Set<String> UNICODE_ALIASES = Set.of("utf-8", "utf_8");

    public static final Pattern DOCTYPE_TAG = Pattern.compile("^< ?! ?DOCTYPE[^>]*/[^<>]*>", Pattern.CASE_INSENSITIVE);
    public static final Pattern FAULTY_HTML = Pattern.compile("(<html.*?)\\s*/>", Pattern.CASE_INSENSITIVE);
    public static final Pattern HTML_STRIP_TAGS = Pattern.compile("(<!--.*?-->|<[^>]*>)");
    // control characters
    public static final Pattern INVALID_XML_CHARS = Pattern.compile("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\ufffe\\uffff]");

    public static final Pattern LINES_TRIMMING = Pattern.compile("(?<![p{P}>])\\n", Pattern.MULTILINE);

    public static final Pattern URL_BLACKLIST_REGEX = Pattern.compile("^https?://|/+$");

    // Regex to check image file extensions
    public static final Pattern IMAGE_EXTENSION = Pattern.compile(
            "[^\\s]+\\.(avif|bmp|gif|hei[cf]|jpe?g|png|webp)(\\b|$)", Pattern.CASE_INSENSITIVE);

    public static final Set<String> FORMATTING_PROTECTED = Set.of("cell", "head", "hi", "item", "p", "quote", "ref", "td");
    public static final Set<String> SPACING_PROTECTED = Set.of("code", "pre");

    // https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Language
    public static final List<String> TARGET_LANG_ATTRS = List.of("http-equiv=\"content-language\"", "property=\"og:locale\"");
    public static final Pattern RE_HTML_LANG = Pattern.compile("([a-z]{2})");

    // Mostly filters for social media (text-level analog of the xpaths social/share tokens)
    public static final Pattern RE_FILTER = Pattern.compile(
            "\\W*(Drucken|E-?Mail|Facebook|Flipboard|Google|Instagram|"
                    + "Linkedin|Mail|PDF|Pinterest|Pocket|Print|QQ|Reddit|Twitter|"
                    + "WeChat|WeiBo|Whatsapp|Xing|Mehr zum Thema:?|More on this.{0,8}$)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    // link text > this fraction of total text = link farm (htmlprocessing.link_density_test)
    public static final double LINK_FARM_RATIO = 0.9;

    /** Accumulate decompressed chunks, rejecting payloads over max_size. */
    public static byte[] _capped(Iterable<byte[]> chunks, int max_size) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            out.writeBytes(chunk);
            if (out.size() > max_size) {
                throw new IllegalArgumentException("decompressed content exceeds MAX_FILE_SIZE");
            }
        }
        return out.toByteArray();
    }

    // bgzip output needs ~320 members for 20MB
    public static final int MAX_MEMBERS = 1000;

    /** Read at most max_size + 1 bytes, rejecting oversized or incomplete streams. */
    private static byte[] _bounded_read(InputStream in, int max_size) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > max_size) {
                throw new IllegalArgumentException("oversized or incomplete compressed stream");
            }
        }
        return out.toByteArray();
    }

    /** Decompress a (possibly multi-member) gzip stream, rejecting output over max_size. */
    public static byte[] _bounded_gunzip(byte[] raw, int max_size) throws IOException {
        // GZIPInputStream reads concatenated members; an incomplete member raises EOFException
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return _bounded_read(in, max_size);
        }
    }

    /** Decompress a single zlib/deflate stream, ignoring trailing bytes as zlib.decompress does. */
    public static byte[] _bounded_inflate(byte[] raw, int max_size) throws DataFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[65536];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, n);
                if (out.size() > max_size) {
                    throw new IllegalArgumentException("oversized or incomplete compressed stream");
                }
            }
            if (!inflater.finished()) {
                throw new IllegalArgumentException("oversized or incomplete compressed stream");
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private static boolean startsWith(byte[] data, int... magic) {
        if (data.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((data[i] & 0xff) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Don't trust response headers and try to decompress a binary string
     * with a cascade of installed packages, capped at max_size (the configured
     * MAX_FILE_SIZE by default) to guard against decompression bombs.
     * Use magic numbers when available.
     */
    public static byte[] handle_compressed_file(byte[] filecontent, Integer max_size) {
        if (max_size == null) {
            max_size = Settings.DEFAULT_CONFIG.getint("DEFAULT", "MAX_FILE_SIZE");
        }

        // magic-numbered formats are terminal: failure means a corrupt file, not another format
        if (startsWith(filecontent, 0x1f, 0x8b, 0x08)) {
            try {
                return _bounded_gunzip(filecontent, max_size);
            } catch (IOException | IllegalArgumentException err) {
                LOGGER.warn("invalid or oversized GZ file");
            }
        } else if (HAS_ZSTD && startsWith(filecontent, 0x28, 0xb5, 0x2f, 0xfd)) {
            LOGGER.warn("invalid or oversized ZSTD file");
        } else {
            // no magic numbers: try zlib/deflate speculatively (brotli is not available)
            try {
                return _bounded_inflate(filecontent, max_size);
            } catch (DataFormatException | IllegalArgumentException err) {
                // pass
            }
        }

        // return content unchanged if decompression failed
        return filecontent;
    }

    public static byte[] handle_compressed_file(byte[] filecontent) {
        return handle_compressed_file(filecontent, null);
    }

    /** Strict decoding: null when the bytes are not valid in the given charset. */
    public static String strict_decode(byte[] data, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
        } catch (CharacterCodingException err) {
            return null;
        }
    }

    /** Read all input or first chunk and return a list of encodings. */
    public static List<String> detect_encoding(byte[] bytesobject) {
        // unicode-test
        if (strict_decode(bytesobject, StandardCharsets.UTF_8) != null) {
            return List.of("utf-8");
        }
        List<String> guesses = new ArrayList<>();
        // try the detector on first and last part, fallback on full document
        byte[] sample = bytesobject;
        if (bytesobject.length >= 10000) {
            sample = new byte[10000];
            System.arraycopy(bytesobject, 0, sample, 0, 5000);
            System.arraycopy(bytesobject, bytesobject.length - 5000, sample, 5000, 5000);
        }
        String guess = detect(sample);
        if (guess == null && sample != bytesobject) {
            guess = detect(bytesobject);
        }
        if (guess != null) {
            guesses.add(guess.toLowerCase(Locale.ROOT));
        }
        // it cannot be utf-8 (tested above)
        guesses.removeIf(g -> UNICODE_ALIASES.contains(g));
        return guesses;
    }

    private static String detect(byte[] data) {
        UniversalDetector detector = new UniversalDetector();
        detector.handleData(data, 0, data.length);
        detector.dataEnd();
        return detector.getDetectedCharset();
    }

    /**
     * Decompress the bytestring if necessary, guess its encoding and
     * decode to a Unicode string, resorting to destructive conversion otherwise.
     */
    public static String decode_file(Object filecontent, Integer max_size) {
        if (filecontent instanceof String s) {
            return s;
        }
        byte[] data = handle_compressed_file((byte[]) filecontent, max_size);
        // fast path: valid UTF-8 (avoid decoding twice via detect_encoding)
        String decoded = strict_decode(data, StandardCharsets.UTF_8);
        if (decoded != null) {
            return decoded;
        }

        // encoding
        for (String guessed_encoding : detect_encoding(data)) {
            try {
                decoded = strict_decode(data, Charset.forName(guessed_encoding));
                if (decoded != null) {
                    return decoded;
                }
            } catch (IllegalArgumentException err) {
                // LookupError: unknown encoding
            }
            LOGGER.warn("wrong encoding detected: {}", guessed_encoding);
        }

        // destructive fallback if nothing else succeeded
        return new String(data, StandardCharsets.UTF_8);
    }

    public static String decode_file(Object filecontent) {
        return decode_file(filecontent, null);
    }

    /** Assess if the object is proper HTML (with a corresponding tag or declaration). */
    public static boolean is_dubious_html(String beginning) {
        return !beginning.contains("html");
    }

    /** Repair faulty HTML strings to make then palatable for libxml2. */
    public static String repair_faulty_html(String htmlstring, String beginning) {
        htmlstring = INVALID_XML_CHARS.matcher(htmlstring).replaceAll("");
        // libxml2/LXML issue: https://bugs.launchpad.net/lxml/+bug/1955915
        if (beginning.contains("doctype")) {
            String[] parts = Py.partition(htmlstring, "\n");
            htmlstring = DOCTYPE_TAG.matcher(parts[0]).replaceFirst("") + "\n" + parts[2];
        }
        // self-closing <html/> in the first lines
        List<String> lines = Py.splitlines(Py.slice(htmlstring, null, 4096));
        for (String line : lines.subList(0, Math.min(4, lines.size()))) {
            if (line.contains("<html") && line.endsWith("/>")) {
                htmlstring = FAULTY_HTML.matcher(htmlstring).replaceFirst("$1>");
                break;
            }
        }
        return htmlstring;
    }

    /** Second parsing attempt (lxml: pass bytes to the parser); same parser in Java. */
    public static Element fromstring_bytes(String htmlobject) {
        try {
            return Etree.html_fromstring(htmlobject);
        } catch (RuntimeException err) {
            LOGGER.error("lxml parser bytestring {}", err.toString());
        }
        return null;
    }

    /**
     * Load object given as input and validate its type
     * (accepted: lxml-style tree, trafilatura response, bytestring and string).
     *
     * Expects a full document: the dubious-HTML check below rejects a single-block
     * fragment (e.g. "<p>x</p>" alone has one child and is treated as not-quite-HTML).
     * Wrap bare fragments in an extra element (e.g. "<div>" + fragment + "</div>") first.
     */
    public static Element load_html(Object htmlobject, Integer max_size) {
        // use tree directly
        if (htmlobject instanceof Element tree) {
            return tree;
        }
        // use trafilatura responses directly
        if (htmlobject instanceof Response response) {
            htmlobject = response.data;
        }
        // do not accept any other type after this point
        if (!(htmlobject instanceof byte[] || htmlobject instanceof String)) {
            throw new IllegalArgumentException("incompatible input type: "
                    + (htmlobject == null ? "None" : htmlobject.getClass().getSimpleName()));
        }
        // start processing
        Element tree = null;
        // try to guess encoding and decode file: if None then keep original
        String html = decode_file(htmlobject, max_size);
        // sanity checks
        String beginning = Py.slice(html, null, 50).toLowerCase(Locale.ROOT);
        boolean check_flag = is_dubious_html(beginning);
        // repair first
        html = repair_faulty_html(html, beginning);
        // first pass: use Unicode string
        boolean fallback_parse = false;
        try {
            tree = Etree.html_fromstring(html);
        } catch (RuntimeException err) {
            LOGGER.error("lxml parsing failed: {}", err.toString());
        }
        // second pass: try passing bytes to the parser
        if ((tree == null || tree.size() < 1) && !fallback_parse) {
            tree = fromstring_bytes(html);
        }
        // rejection test: is it (well-formed) HTML at all?
        // log parsing errors
        if (tree != null && check_flag && tree.size() < 2) {
            LOGGER.error("parsed tree length: {}, wrong data type or not valid HTML", tree.size());
            tree = null;
        }
        return tree;
    }

    public static Element load_html(Object htmlobject) {
        return load_html(htmlobject, null);
    }

    /** Get the base URL, empty string if malformed. */
    public static String safe_base_url(String url) {
        try {
            return Courlan.get_base_url(url);
        } catch (IllegalArgumentException err) {
            return "";
        }
    }

    /** Resolve a link against the base URL, empty string if malformed. */
    public static String safe_relative_url(String baseurl, String url) {
        try {
            return Courlan.fix_relative_urls(baseurl, url);
        } catch (IllegalArgumentException err) {
            return "";
        }
    }

    /** Return a character if it belongs to certain classes. */
    public static String return_printables_and_spaces(int codePoint) {
        return Py.isprintable(codePoint) || Py.isWhitespace(codePoint) ? Character.toString(codePoint) : "";
    }

    /** Prevent non-printable and XML invalid character errors. */
    public static String remove_control_characters(String string) {
        // in case most strings are already clean
        if (Py.isprintable(string)) {
            return string;
        }
        StringBuilder sb = new StringBuilder(string.length());
        string.codePoints().forEach(cp -> sb.append(return_printables_and_spaces(cp)));
        return sb.toString();
    }

    /** Normalize the given string to the specified unicode format. */
    public static String normalize_unicode(String string, String unicodeform) {
        return Normalizer.normalize(string, Normalizer.Form.valueOf(unicodeform));
    }

    public static String normalize_unicode(String string) {
        return normalize_unicode(string, "NFC");
    }

    /**
     * Remove HTML space entities, then discard incompatible unicode
     * and invalid XML characters on line level.
     */
    public static String line_processing(String line, boolean preserve_space, boolean trailing_space) {
        // spacing HTML entities: https://www.w3.org/MarkUp/html-spec/html-spec_13.html
        // unique code spaces
        String new_line = remove_control_characters(
                line.replace("&#13;", "\r").replace("&#10;", "\n").replace("&nbsp;", " "));
        if (!preserve_space) {
            // remove newlines that are not related to punctuation or markup
            // remove non-printable chars and normalize space characters (including Unicode spaces)
            new_line = trim(LINES_TRIMMING.matcher(new_line).replaceAll(" "));
            // prune empty lines (all() is true for an empty string)
            if (new_line.codePoints().allMatch(Py::isWhitespace)) {
                new_line = null;
            } else if (trailing_space) {
                String space_before = Py.isWhitespace(line.codePointAt(0)) ? " " : "";
                String space_after = Py.isWhitespace(line.codePointBefore(line.length())) ? " " : "";
                new_line = space_before + new_line + space_after;
            }
        }
        return new_line;
    }

    public static String line_processing(String line) {
        return line_processing(line, false, false);
    }

    /** Convert text and discard incompatible and invalid characters. */
    public static String sanitize(String text, boolean preserve_space, boolean trailing_space) {
        if (text == null) {
            return null;
        }
        // consider all text as a single line
        if (trailing_space) {
            return line_processing(text, preserve_space, true);
        }
        // process line by line
        List<String> lines = new ArrayList<>();
        for (String line : Py.splitlines(text)) {
            String processed = line_processing(line, preserve_space, false);
            if (processed != null && !processed.isEmpty()) {
                lines.add(processed);
            }
        }
        return String.join("\n", lines).replace("␤", "");
    }

    public static String sanitize(String text) {
        return sanitize(text, false, false);
    }

    /** Trims spaces, removes control characters and normalizes unicode. */
    public static Element sanitize_tree(Element tree) {
        Map<String, String> nsmap = tree.nsmap();
        for (Element elem : tree.iter()) {
            Element parent = elem.getparent();
            String parent_tag = parent != null ? parent.tag : "";

            // preserve space if the element or its parent is a specific tag, or if the element has text and children
            // the last part is relevant for item elements with ref inside for example
            boolean preserve_space = SPACING_PROTECTED.contains(elem.tag) || SPACING_PROTECTED.contains(parent_tag);
            boolean trailing_space = FORMATTING_PROTECTED.contains(elem.tag)
                    || FORMATTING_PROTECTED.contains(parent_tag)
                    || preserve_space;

            // remove invalid attributes (copy: pop() during iteration)
            for (String attribute : new ArrayList<>(elem.attrib.keySet())) {
                if (attribute.contains(":")) { // colon is reserved for namespaces in XML
                    String value = elem.attrib.get(attribute);
                    if (value == null || value.isEmpty() || !nsmap.containsKey(attribute.split(":", 2)[0])) {
                        elem.attrib.remove(attribute);
                    }
                }
            }

            if (elem.text != null && !elem.text.isEmpty()) {
                elem.text = sanitize(elem.text, preserve_space, trailing_space);
            }
            if (elem.tail != null && !elem.tail.isEmpty()) {
                elem.tail = sanitize(elem.tail, preserve_space, trailing_space);
            }
        }
        return tree;
    }

    /** Remove unnecessary spaces within a text string. */
    public static String trim(String string) {
        if (string == null) {
            return "";
        }
        // remove newlines that are not related to punctuation or markup + proper trimming
        return String.join(" ", Py.split(string));
    }

    /** Normalize a JSON-like value that may be a single object, a list of them, or None. */
    @SuppressWarnings("unchecked")
    public static List<Object> as_list(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        return value instanceof List<?> list ? (List<Object>) list : new ArrayList<>(List.of(value));
    }

    /** Image source of an element: src, data-src, or the first data-src* attribute pointing to an image file. */
    public static String image_src(Element element) {
        for (String attr : List.of("data-src", "src")) {
            String src = element.get(attr, "");
            if (is_image_file(src)) {
                return src;
            }
        }
        for (Map.Entry<String, String> entry : element.items()) {
            if (entry.getKey().startsWith("data-src") && is_image_file(entry.getValue())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Check if the observed string corresponds to a valid image extension.
     * Use a length threshold and apply a regex on the content.
     */
    public static boolean is_image_file(String imagesrc) {
        if (imagesrc == null || imagesrc.length() > 8192) {
            return false;
        }
        return IMAGE_EXTENSION.matcher(imagesrc).find();
    }

    /** Chunk data into smaller pieces. */
    public static <T> Iterator<List<T>> make_chunks(Iterable<T> iterable, int n) {
        Iterator<T> iterator = iterable.iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public List<T> next() {
                if (!iterator.hasNext()) {
                    throw new NoSuchElementException();
                }
                List<T> batch = new ArrayList<>(n);
                while (batch.size() < n && iterator.hasNext()) {
                    batch.add(iterator.next());
                }
                return batch;
            }
        };
    }

    /** Check if the document length is within acceptable boundaries. */
    public static boolean is_acceptable_length(int my_len, Extractor options) {
        if (my_len < options.min_file_size) {
            LOGGER.error("too small/incorrect for URL {}", options.url);
            return false;
        }
        if (my_len > options.max_file_size) {
            LOGGER.error("too large: length {} for URL {}", my_len, options.url);
            return false;
        }
        return true;
    }

    /**
     * Check HTML meta-elements for language information and split
     * the result in case there are several languages.
     */
    public static boolean check_html_lang(Element tree, String target_language, boolean strict) {
        for (String attr : TARGET_LANG_ATTRS) {
            List<Element> elems = tree.findall(".//meta[@" + attr + "][@content]");
            if (!elems.isEmpty()) {
                for (Element elem : elems) {
                    if (Re.split(RE_HTML_LANG, elem.get("content", "").toLowerCase(Locale.ROOT)).contains(target_language)) {
                        return true;
                    }
                }
                LOGGER.debug("{} lang attr failed", attr);
                return false;
            }
        }

        // HTML lang attribute: sometimes a wrong indication
        if (strict) {
            List<Element> elems = tree.xpath("//html[@lang]");
            if (!elems.isEmpty()) {
                for (Element elem : elems) {
                    if (Re.split(RE_HTML_LANG, elem.get("lang", "").toLowerCase(Locale.ROOT)).contains(target_language)) {
                        return true;
                    }
                }
                LOGGER.debug("HTML lang failed");
                return false;
            }
        }

        LOGGER.debug("No relevant lang elements found");
        return true;
    }

    public static boolean check_html_lang(Element tree, String target_language) {
        return check_html_lang(tree, target_language, false);
    }

    /** Run external component (if installed) for language identification. */
    public static String language_classifier(String temp_text, String temp_comments) {
        // py3langid is not available in Java
        LOGGER.warn("Language detector not installed, skipping detection");
        return null;
    }

    /** Filter text based on language detection and store relevant information. */
    public static Pair<Boolean, Document> language_filter(String temp_text, String temp_comments, String target_language,
            Document docmeta) {
        // todo: run and pass info along anyway?
        if (target_language != null) {
            // more thorough: detection on actual text content
            docmeta.language = language_classifier(temp_text, temp_comments);
            // HTML lang check? sometimes contradicted by detection above
            if (docmeta.language != null && !docmeta.language.equals(target_language)) {
                LOGGER.warn("wrong language: {} {}", docmeta.language, docmeta.url);
                return Pair.of(true, docmeta);
            }
        }
        return Pair.of(false, docmeta);
    }

    /** Filter out unwanted text. */
    public static boolean textfilter(Element element) {
        String testtext = element.text == null ? element.tail : element.text;
        // to check: line len → continue if len(line) <= 5
        if (testtext == null || testtext.isEmpty() || Py.isspace(testtext)) {
            return true;
        }
        for (String line : Py.splitlines(testtext)) {
            if (RE_FILTER.matcher(line).lookingAt()) {
                return true;
            }
        }
        return false;
    }

    /** Determine if a string is only composed of spaces and/or control characters. */
    public static boolean text_chars_test(String string) {
        return string != null && !string.isEmpty() && !Py.isspace(string);
    }
}
