package io.github.danielonet.trafilatura.compat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Subset of Python's {@code configparser.ConfigParser}: INI sections, a DEFAULT
 * section inherited by all others, case-insensitive option names, indented
 * continuation lines and full-line comments.
 */
public class ConfigParser {
    public static final String DEFAULT_SECTION = "DEFAULT";

    private final Map<String, Map<String, String>> sections = new LinkedHashMap<>();

    public ConfigParser() {
        sections.put(DEFAULT_SECTION, new LinkedHashMap<>());
    }

    /** Reads the given files, silently skipping the ones that cannot be read; returns the files read. */
    public List<String> read(String... filenames) {
        List<String> ok = new ArrayList<>();
        for (String filename : filenames) {
            Path path = Path.of(filename);
            if (!Files.isReadable(path)) {
                continue;
            }
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                read_file(reader);
                ok.add(filename);
            } catch (IOException err) {
                // configparser ignores files which cannot be opened
            }
        }
        return ok;
    }

    public List<String> read(List<String> filenames) {
        return read(filenames.toArray(String[]::new));
    }

    /** Reads a classpath resource (stands in for files shipped next to the Python module). */
    public void read_resource(String resource) {
        try (InputStream in = ConfigParser.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new UncheckedIOException(new IOException("resource not found: " + resource));
            }
            read_file(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException err) {
            throw new UncheckedIOException(err);
        }
    }

    /** {@code config.read_dict({section: {option: value}})}; DEFAULT is the default section. */
    public void read_dict(Map<String, Map<String, String>> dictionary) {
        dictionary.forEach((section, values) -> {
            add_section(section);
            values.forEach((option, value) -> set(section, option, value));
        });
    }

    public void read_string(String content) {
        try {
            read_file(new StringReader(content));
        } catch (IOException err) {
            throw new UncheckedIOException(err);
        }
    }

    public void read_file(Reader reader) throws IOException {
        BufferedReader br = new BufferedReader(reader);
        Map<String, String> current = null;
        String lastKey = null;
        String line;
        while ((line = br.readLine()) != null) {
            String stripped = line.strip();
            if (stripped.startsWith("#") || stripped.startsWith(";")) {
                continue;
            }
            if (stripped.isEmpty()) {
                lastKey = null;
                continue;
            }
            boolean indented = Character.isWhitespace(line.charAt(0));
            if (indented && current != null && lastKey != null) {
                String previous = current.get(lastKey);
                current.put(lastKey, previous.isEmpty() ? stripped : previous + "\n" + stripped);
                continue;
            }
            if (stripped.startsWith("[") && stripped.endsWith("]")) {
                String name = stripped.substring(1, stripped.length() - 1);
                current = sections.computeIfAbsent(name, k -> new LinkedHashMap<>());
                lastKey = null;
                continue;
            }
            if (current == null) {
                throw new IllegalArgumentException("File contains no section headers.");
            }
            int eq = indexOfDelimiter(stripped);
            if (eq < 0) {
                current.put(stripped.toLowerCase(Locale.ROOT), "");
                lastKey = stripped.toLowerCase(Locale.ROOT);
            } else {
                String key = stripped.substring(0, eq).strip().toLowerCase(Locale.ROOT);
                current.put(key, stripped.substring(eq + 1).strip());
                lastKey = key;
            }
        }
    }

    private static int indexOfDelimiter(String line) {
        int eq = line.indexOf('=');
        int colon = line.indexOf(':');
        if (eq < 0) {
            return colon;
        }
        return colon < 0 ? eq : Math.min(eq, colon);
    }

    public List<String> sections() {
        List<String> names = new ArrayList<>(sections.keySet());
        names.remove(DEFAULT_SECTION);
        return names;
    }

    public boolean has_option(String section, String option) {
        return lookup(section, option) != null;
    }

    private String lookup(String section, String option) {
        String key = option.toLowerCase(Locale.ROOT);
        Map<String, String> values = sections.get(section);
        if (values != null && values.containsKey(key)) {
            return values.get(key);
        }
        return sections.get(DEFAULT_SECTION).get(key);
    }

    public String get(String section, String option) {
        if (!sections.containsKey(section)) {
            throw new NoSuchElementException("No section: " + section);
        }
        String value = lookup(section, option);
        if (value == null) {
            throw new NoSuchElementException("No option '" + option + "' in section: " + section);
        }
        return value;
    }

    /** {@code config.get(section, option, fallback=...)} */
    public String get(String section, String option, String fallback) {
        String value = sections.containsKey(section) ? lookup(section, option) : null;
        return value != null ? value : fallback;
    }

    public int getint(String section, String option) {
        return Integer.parseInt(get(section, option).strip());
    }

    public double getfloat(String section, String option) {
        return Double.parseDouble(get(section, option).strip());
    }

    public boolean getboolean(String section, String option) {
        String value = get(section, option).strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "1", "yes", "true", "on" -> true;
            case "0", "no", "false", "off" -> false;
            default -> throw new IllegalArgumentException("Not a boolean: " + value);
        };
    }

    /** {@code config.set(section, option, value)} / {@code config[section][option] = value} */
    public void set(String section, String option, String value) {
        Map<String, String> values = sections.get(section);
        if (values == null) {
            throw new NoSuchElementException("No section: " + section);
        }
        values.put(option.toLowerCase(Locale.ROOT), value);
    }

    public void add_section(String section) {
        sections.putIfAbsent(section, new LinkedHashMap<>());
    }

    /** Deep copy ({@code copy.deepcopy(config)}). */
    public ConfigParser copy() {
        ConfigParser clone = new ConfigParser();
        sections.forEach((name, values) -> clone.sections.put(name, new LinkedHashMap<>(values)));
        return clone;
    }

    /** Snapshot of all sections and values, for comparisons. */
    public Map<String, Map<String, String>> state() {
        Map<String, Map<String, String>> snapshot = new LinkedHashMap<>();
        sections.forEach((name, values) -> snapshot.put(name, new LinkedHashMap<>(values)));
        return snapshot;
    }
}
