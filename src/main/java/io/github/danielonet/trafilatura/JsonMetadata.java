// Java port of trafilatura/json_metadata.py
package io.github.danielonet.trafilatura;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.danielonet.trafilatura.compat.Json;
import io.github.danielonet.trafilatura.compat.Py;
import io.github.danielonet.trafilatura.compat.Re;
import io.github.danielonet.trafilatura.Settings.Document;

import static io.github.danielonet.trafilatura.Utils.HTML_STRIP_TAGS;
import static io.github.danielonet.trafilatura.Utils.as_list;
import static io.github.danielonet.trafilatura.Utils.trim;

/**
 * Functions needed to scrape metadata from JSON-LD format.
 * For reference, here is the list of all JSON-LD types: https://schema.org/docs/full.html
 */
public final class JsonMetadata {
    private JsonMetadata() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonMetadata.class);

    // Python's re is Unicode-aware for \w, \W, \b, \d and IGNORECASE on str patterns
    private static final int U = Pattern.UNICODE_CHARACTER_CLASS;
    private static final int I = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    public static final Set<String> JSON_ARTICLE_SCHEMA = Set.of(
            "article", "backgroundnewsarticle", "blogposting", "medicalscholarlyarticle", "newsarticle",
            "opinionnewsarticle", "reportagenewsarticle", "scholarlyarticle", "socialmediaposting", "liveblogposting");
    public static final Set<String> JSON_OGTYPE_SCHEMA = Set.of(
            "aboutpage", "checkoutpage", "collectionpage", "contactpage", "faqpage", "itempage", "medicalwebpage",
            "profilepage", "qapage", "realestatelisting", "searchresultspage", "webpage", "website", "article",
            "advertisercontentarticle", "newsarticle", "analysisnewsarticle", "askpublicnewsarticle",
            "backgroundnewsarticle", "opinionnewsarticle", "reportagenewsarticle", "reviewnewsarticle", "report",
            "satiricalarticle", "scholarlyarticle", "medicalscholarlyarticle", "socialmediaposting", "blogposting",
            "liveblogposting", "discussionforumposting", "techarticle", "blog", "jobposting");
    public static final Set<String> JSON_PUBLISHER_SCHEMA = Set.of("newsmediaorganization", "organization", "webpage", "website");
    public static final Set<String> JSON_SCHEMA_TYPES = Settings.union(
            Settings.union(Settings.union(JSON_ARTICLE_SCHEMA, JSON_OGTYPE_SCHEMA), JSON_PUBLISHER_SCHEMA), Set.of("person"));
    public static final Pattern JSON_AUTHOR_1 = Pattern.compile(
            "\"author\":[^}\\[]+?\"name?\\\\?\": ?\\\\?\"([^\"\\\\]+)|\"author\"[^}\\[]+?\"names?\".+?\"([^\"]+)", Pattern.DOTALL);
    public static final Pattern JSON_AUTHOR_2 = Pattern.compile("\"[Pp]erson\"[^}]+?\"names?\".+?\"([^\"]+)", Pattern.DOTALL);
    public static final Pattern JSON_AUTHOR_REMOVE = Re.compile(
            ",?(?:\"\\w+\":?[:|,\\[])?\\{?\"@type\":\"(?:[Ii]mageObject|[Oo]rganization|[Ww]eb[Pp]age)\",[^}\\[]+}[\\]|}]?", U);
    public static final Pattern JSON_PUBLISHER = Pattern.compile("\"publisher\":[^}]+?\"name?\\\\?\": ?\\\\?\"([^\"\\\\]+)", Pattern.DOTALL);
    public static final Pattern JSON_TYPE = Pattern.compile("\"@type\"\\s*:\\s*\"([^\"]*)\"", Pattern.DOTALL | U);
    public static final Pattern JSON_CATEGORY = Pattern.compile("\"articleSection\": ?\"([^\"\\\\]+)", Pattern.DOTALL);
    public static final Pattern JSON_SCHEMA_ORG = Pattern.compile("^https?://schema\\.org", I);
    public static final Pattern JSON_UNICODE_REPLACE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    public static final List<String> AUTHOR_ATTRS = List.of("givenName", "additionalName", "familyName");

    public static final Pattern JSON_NAME = Pattern.compile("\"@type\":\"[Aa]rticle\", ?\"name\": ?\"([^\"\\\\]+)", Pattern.DOTALL);
    public static final Pattern JSON_HEADLINE = Pattern.compile("\"headline\": ?\"([^\"\\\\]+)", Pattern.DOTALL);
    public static final List<Map.Entry<String, Pattern>> JSON_SEQ = List.of(
            Map.entry("\"name\"", JSON_NAME), Map.entry("\"headline\"", JSON_HEADLINE));

    public static final Pattern AUTHOR_PREFIX = Pattern.compile(
            "^([a-zäöüß]+(ed|t))? ?(written by|words by|words|by|von|from) ", I);
    public static final Pattern AUTHOR_REMOVE_NUMBERS = Pattern.compile("\\d.+?$", U);
    public static final Pattern AUTHOR_TWITTER = Re.compile("@[\\w]+", U);
    public static final Pattern AUTHOR_REPLACE_JOIN = Pattern.compile("[._+]");
    public static final Pattern AUTHOR_REMOVE_NICKNAME = Pattern.compile("[\"‘({\\[’'][^\"]+?[‘’\"')\\]}]");
    public static final Pattern AUTHOR_REMOVE_SPECIAL = Re.compile("[^\\w]+$|[:()?*$#!%/<>{}~¿]", U);
    public static final Pattern AUTHOR_REMOVE_PREPOSITION = Pattern.compile(
            "\\b\\s+(am|on|for|at|in|to|from|of|via|with|—|-|–)\\s+(.*)", I | U);
    public static final Pattern AUTHOR_EMAIL = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b", U);
    public static final Pattern AUTHOR_SPLIT = Re.compile("/|;|,|\\||&|(?:^|\\W)[ua]nd(?:$|\\W)", I | U);
    public static final Pattern AUTHOR_EMOJI_REMOVE = Pattern.compile(
            "["
                    + "\\x{2700}-\\x{27be}" // Dingbats
                    + "\\x{1f600}-\\x{1f64f}" // Emoticons
                    + "\\x{2600}-\\x{26ff}" // Miscellaneous Symbols
                    + "\\x{1f300}-\\x{1f5ff}" // Miscellaneous Symbols And Pictographs
                    + "\\x{1f900}-\\x{1f9ff}" // Supplemental Symbols and Pictographs
                    + "\\x{1fa70}-\\x{1faff}" // Symbols and Pictographs Extended-A
                    + "\\x{1f680}-\\x{1f6ff}" // Transport and Map Symbols
                    + "]+");

    /** Determine if the candidate should be used as sitename. */
    public static boolean is_plausible_sitename(Document metadata, Object candidate, String content_type) {
        if (candidate instanceof String c && !c.isEmpty()) {
            if (!Py.truthy(metadata.sitename)
                    || (metadata.sitename.length() < c.length() && !"webpage".equals(content_type))) {
                return true;
            }
            return Py.truthy(metadata.sitename) && metadata.sitename.startsWith("http") && !c.startsWith("http");
        }
        return false;
    }

    public static boolean is_plausible_sitename(Document metadata, Object candidate) {
        return is_plausible_sitename(metadata, candidate, null);
    }

    /** {@code dict.get(key)} on a JSON object; a non-object raises like Python's AttributeError. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asDict(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("'" + pyType(value) + "' object has no attribute 'get'");
    }

    private static String pyType(Object value) {
        if (value instanceof String) {
            return "str";
        }
        if (value instanceof List<?>) {
            return "list";
        }
        return value == null ? "NoneType" : value.getClass().getSimpleName();
    }

    /** Find and extract selected metadata from JSON parts. */
    @SuppressWarnings("unchecked")
    public static Document process_parent(List<Object> parent, Document metadata) {
        for (Object entry : parent) {
            if (!Py.truthy(entry)) {
                continue;
            }
            Map<String, Object> content = asDict(entry);
            // publisher may be a bare string, not a dict
            Object publisher = content.get("publisher");
            if (publisher instanceof Map<?, ?> pub && is_plausible_sitename(metadata, pub.get("name"))) {
                metadata.sitename = (String) pub.get("name");
            }

            if (!content.containsKey("@type") || !Py.truthy(content.get("@type"))) {
                continue;
            }

            // Prefer the first recognized type, preserving order among recognized types.
            List<Object> content_types = as_list(content.get("@type"));
            Object chosen = content_types.get(0);
            for (Object schema_type : content_types) {
                if (schema_type instanceof String s && JSON_SCHEMA_TYPES.contains(s.toLowerCase(Locale.ROOT))) {
                    chosen = s;
                    break;
                }
            }
            if (!(chosen instanceof String)) {
                throw new IllegalArgumentException("'" + pyType(chosen) + "' object has no attribute 'lower'");
            }
            String content_type = ((String) chosen).toLowerCase(Locale.ROOT);

            // The "pagetype" should only be returned if the page is some kind of an article, category, website...
            if (JSON_OGTYPE_SCHEMA.contains(content_type) && !Py.truthy(metadata.pagetype)) {
                metadata.pagetype = normalize_json(content_type);
            }

            if (JSON_PUBLISHER_SCHEMA.contains(content_type)) {
                Object candidate = content.get("name");
                if (!Py.truthy(candidate)) {
                    candidate = content.get("legalName");
                }
                if (!Py.truthy(candidate)) {
                    candidate = content.get("alternateName");
                }
                if (is_plausible_sitename(metadata, candidate, content_type)) {
                    metadata.sitename = (String) candidate;
                }
            } else if (content_type.equals("person")) {
                if (content.get("name") instanceof String name && !name.startsWith("http")) {
                    metadata.author = normalize_authors(metadata.author, name);
                }
            } else if (JSON_ARTICLE_SCHEMA.contains(content_type)) {
                // author and person
                if (content.containsKey("author")) {
                    Object list_authors = content.get("author");
                    if (list_authors instanceof String authorString) {
                        // try to convert to json object
                        try {
                            list_authors = Json.loads(authorString);
                        } catch (Json.JSONDecodeError err) {
                            // it is a normal string
                            metadata.author = normalize_authors(metadata.author, authorString);
                        }
                    }

                    for (Object authorEntry : as_list(list_authors)) {
                        Map<String, Object> author;
                        if (authorEntry instanceof String s) {
                            author = new java.util.LinkedHashMap<>();
                            author.put("name", s);
                        } else {
                            author = asDict(authorEntry);
                        }
                        if (!author.containsKey("@type") || as_list(author.get("@type")).contains("Person")) {
                            Object author_name = null;
                            // error thrown: author['name'] can be a list (?)
                            if (author.containsKey("name")) {
                                author_name = author.get("name");
                                if (author_name instanceof List<?> names) {
                                    author_name = Py.strip(String.join("; ", (List<String>) names), "; ");
                                } else if (author_name instanceof Map<?, ?> nameMap && nameMap.containsKey("name")) {
                                    author_name = nameMap.get("name");
                                }
                            } else if (author.containsKey("givenName") && author.containsKey("familyName")) {
                                List<String> parts = new ArrayList<>();
                                for (String x : AUTHOR_ATTRS) {
                                    if (author.containsKey(x)) {
                                        parts.add((String) author.get(x));
                                    }
                                }
                                author_name = String.join(" ", parts);
                            }
                            // additional check to prevent bugs
                            if (author_name instanceof String name) {
                                metadata.author = normalize_authors(metadata.author, name);
                            }
                        }
                    }
                }

                // category
                if (!Py.truthy(metadata.categories) && content.containsKey("articleSection")) {
                    Object section = content.get("articleSection");
                    if (section instanceof String s) {
                        metadata.categories = new ArrayList<>(List.of(s));
                    } else {
                        List<String> categories = new ArrayList<>();
                        for (Object c : (List<Object>) section) {
                            if (Py.truthy(c)) {
                                categories.add((String) c);
                            }
                        }
                        metadata.categories = categories;
                    }
                }

                // try to extract title
                if (!Py.truthy(metadata.title)) {
                    if (content.containsKey("name") && content_type.equals("article")) {
                        metadata.title = (String) content.get("name");
                    } else if (content.containsKey("headline")) {
                        metadata.title = (String) content.get("headline");
                    }
                }
            }
        }
        return metadata;
    }

    /**
     * Parse and extract metadata from JSON-LD data.
     *
     * Note: baseline.py's `_walk_json` also walks JSON-LD, for page content rather than
     * metadata, and is intentionally not shared with this function.
     */
    public static Document extract_json(Object schema, Document metadata) {
        // collect content from every valid block, then process once (no short-circuit on a flat object)
        List<Object> parents = new ArrayList<>();
        for (Object entry : as_list(schema)) {
            Map<String, Object> parent = asDict(entry);
            Object context = parent.get("@context");

            if (context instanceof String c && !c.isEmpty() && Re.match(JSON_SCHEMA_ORG, c) != null) {
                if (parent.containsKey("@graph")) {
                    parents.addAll(as_list(parent.get("@graph")));
                } else if (parent.get("@type") instanceof String type
                        && type.toLowerCase(Locale.ROOT).contains("liveblogposting")
                        && parent.containsKey("liveBlogUpdate")) {
                    parents.addAll(as_list(parent.get("liveBlogUpdate")));
                } else {
                    parents.add(parent);
                }
            }
        }

        return PROCESS_PARENT.apply(parents, metadata);
    }

    /** process_parent() as called by extract_json(): replaceable in tests. */
    public static java.util.function.BiFunction<List<Object>, Document, Document> PROCESS_PARENT = JsonMetadata::process_parent;

    /** Crudely extract author names from JSON-LD data. */
    public static String extract_json_author(String elemtext, Pattern regular_expression) {
        String authors = null;
        Matcher mymatch = Re.search(regular_expression, elemtext);
        while (mymatch != null) {
            // first matching group (JSON_AUTHOR_1 has two)
            String name = null;
            for (int g = 1; g <= mymatch.groupCount(); g++) {
                if (Py.truthy(mymatch.group(g))) {
                    name = mymatch.group(g);
                    break;
                }
            }
            if (name == null || !name.contains(" ")) {
                break;
            }
            authors = normalize_authors(authors, name);
            elemtext = Re.sub(regular_expression, "", elemtext, 1);
            mymatch = Re.search(regular_expression, elemtext);
        }
        return Py.truthy(authors) ? authors : null;
    }

    /** Crudely extract metadata from JSON-LD data. */
    public static Document extract_json_parse_error(String elem, Document metadata) {
        // author info
        String element_text_author = JSON_AUTHOR_REMOVE.matcher(elem).replaceAll("");
        String author = extract_json_author(element_text_author, JSON_AUTHOR_1);
        if (!Py.truthy(author)) {
            author = extract_json_author(element_text_author, JSON_AUTHOR_2);
        }
        if (Py.truthy(author)) {
            metadata.author = author;
        }

        // try to extract page type as an alternative to og:type
        if (elem.contains("@type")) {
            Matcher mymatch = Re.search(JSON_TYPE, elem);
            if (mymatch != null) {
                String candidate = normalize_json(mymatch.group(1).toLowerCase(Locale.ROOT));
                if (JSON_OGTYPE_SCHEMA.contains(candidate)) {
                    metadata.pagetype = candidate;
                }
            }
        }

        // try to extract publisher
        if (elem.contains("\"publisher\"")) {
            Matcher mymatch = Re.search(JSON_PUBLISHER, elem);
            if (mymatch != null && !mymatch.group(1).contains(",")) {
                String candidate = normalize_json(mymatch.group(1));
                if (is_plausible_sitename(metadata, candidate)) {
                    metadata.sitename = candidate;
                }
            }
        }

        // category
        if (elem.contains("\"articleSection\"")) {
            Matcher mymatch = Re.search(JSON_CATEGORY, elem);
            if (mymatch != null) {
                metadata.categories = new ArrayList<>(List.of(normalize_json(mymatch.group(1))));
            }
        }

        // try to extract title
        for (Map.Entry<String, Pattern> seq : JSON_SEQ) {
            if (elem.contains(seq.getKey()) && !Py.truthy(metadata.title)) {
                Matcher mymatch = Re.search(seq.getValue(), elem);
                if (mymatch != null) {
                    metadata.title = normalize_json(mymatch.group(1));
                    break;
                }
            }
        }

        return metadata;
    }

    /** Normalize unicode strings and trim the output. */
    public static String normalize_json(String string) {
        if (string.contains("\\")) {
            string = string.replace("\\n", "").replace("\\r", "").replace("\\t", "");
            string = Re.sub(JSON_UNICODE_REPLACE, m -> String.valueOf((char) Integer.parseInt(m.group(1), 16)), string);
            // drop lone and paired surrogates, as the Python version does on code units
            StringBuilder sb = new StringBuilder(string.length());
            for (int i = 0; i < string.length(); i++) {
                char c = string.charAt(i);
                if (c < 0xD800 || c > 0xDFFF) {
                    sb.append(c);
                }
            }
            string = Py.unescape(sb.toString());
        }
        return trim(HTML_STRIP_TAGS.matcher(string).replaceAll(""));
    }

    /**
     * {@code s.encode().decode("unicode_escape")}: the UTF-8 bytes are read as Latin-1
     * with Python backslash escapes resolved.
     *
     * @throws IllegalArgumentException for a truncated or invalid escape (UnicodeDecodeError)
     */
    public static String decode_unicode_escape(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < bytes.length) {
            int b = bytes[i] & 0xff;
            if (b != '\\') {
                sb.append((char) b);
                i++;
                continue;
            }
            if (i + 1 >= bytes.length) {
                throw new IllegalArgumentException("\\ at end of string");
            }
            char c = (char) (bytes[i + 1] & 0xff);
            i += 2;
            switch (c) {
                case '\n' -> { }
                case '\\' -> sb.append('\\');
                case '\'' -> sb.append('\'');
                case '"' -> sb.append('"');
                case 'a' -> sb.append('\u0007');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'v' -> sb.append('\u000b');
                case 'x', 'u', 'U' -> {
                    int digits = c == 'x' ? 2 : c == 'u' ? 4 : 8;
                    if (i + digits > bytes.length) {
                        throw new IllegalArgumentException("truncated \\" + c + " escape");
                    }
                    String hex = new String(bytes, i, digits, StandardCharsets.ISO_8859_1);
                    int cp;
                    try {
                        cp = Integer.parseInt(hex, 16);
                    } catch (NumberFormatException err) {
                        throw new IllegalArgumentException("truncated \\" + c + " escape");
                    }
                    if (hex.startsWith("+") || hex.startsWith("-") || cp > 0x10FFFF) {
                        throw new IllegalArgumentException("illegal Unicode character");
                    }
                    sb.appendCodePoint(cp);
                    i += digits;
                }
                default -> {
                    if (c >= '0' && c <= '7') {
                        int end = i;
                        while (end < bytes.length && end < i + 2 && bytes[end] >= '0' && bytes[end] <= '7') {
                            end++;
                        }
                        sb.append((char) Integer.parseInt(c + new String(bytes, i, end - i, StandardCharsets.ISO_8859_1), 8));
                        i = end;
                    } else {
                        // unknown escapes are kept as they are
                        sb.append('\\').append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Normalize author info to focus on author names only. */
    public static String normalize_authors(String current_authors, String author_string) {
        List<String> new_authors = new ArrayList<>();
        if (author_string.toLowerCase(Locale.ROOT).startsWith("http") || Re.match(AUTHOR_EMAIL, author_string) != null) {
            return current_authors;
        }
        if (current_authors != null) {
            new_authors = new ArrayList<>(List.of(current_authors.split("; ", -1)));
        }
        // fix to code with unicode
        if (author_string.contains("\\u")) {
            try {
                author_string = decode_unicode_escape(author_string);
            } catch (IllegalArgumentException err) {
                LOGGER.debug("invalid unicode escape in author: {}", author_string);
            }
        }
        // fix html entities
        if (author_string.contains("&#") || author_string.contains("&amp;")) {
            author_string = Py.unescape(author_string);
        }
        // remove html tags
        author_string = HTML_STRIP_TAGS.matcher(author_string).replaceAll("");
        // examine names
        for (String author : Re.split(AUTHOR_SPLIT, author_string)) {
            author = trim(author);
            // remove emoji
            author = AUTHOR_EMOJI_REMOVE.matcher(author).replaceAll("");
            // remove @username
            author = AUTHOR_TWITTER.matcher(author).replaceAll("");
            // replace special characters with space
            author = trim(AUTHOR_REPLACE_JOIN.matcher(author).replaceAll(" "));
            author = AUTHOR_REMOVE_NICKNAME.matcher(author).replaceAll("");
            // remove special characters
            author = AUTHOR_REMOVE_SPECIAL.matcher(author).replaceAll("");
            author = AUTHOR_PREFIX.matcher(author).replaceAll("");
            author = AUTHOR_REMOVE_NUMBERS.matcher(author).replaceAll("");
            author = AUTHOR_REMOVE_PREPOSITION.matcher(author).replaceAll("");
            // skip empty or improbably long strings
            // simple heuristics, regex or vowel tests also possible
            if (author.isEmpty() || (author.length() >= 50 && !author.contains(" ") && !author.contains("-"))) {
                continue;
            }
            // title case
            if (!Character.isUpperCase(author.codePointAt(0))) {
                author = Py.title(author);
            }
            if (!new_authors.contains(author)) {
                new_authors.add(author);
            }
        }
        // keep only the fullest form of each name (drop names contained in another)
        List<String> fullest = new ArrayList<>();
        for (String n : new_authors) {
            boolean contained = false;
            for (String m : new_authors) {
                if (!n.equals(m) && m.contains(n)) {
                    contained = true;
                    break;
                }
            }
            if (!contained) {
                fullest.add(n);
            }
        }
        if (fullest.isEmpty()) {
            return current_authors;
        }
        return Py.strip(String.join("; ", fullest), "; ");
    }
}
