package io.github.danielonet.trafilatura.compat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Port of Python's {@code urllib.robotparser.RobotFileParser} (parsing and queries only). */
public class RobotFileParser {
    private static final class RuleLine {
        final String path;
        final boolean allowance;

        RuleLine(String path, boolean allowance) {
            if (path.isEmpty() && !allowance) {
                // an empty value means allow all
                allowance = true;
            }
            this.path = normalize(path);
            this.allowance = allowance;
        }

        boolean appliesTo(String filename) {
            return path.equals("*") || filename.startsWith(path);
        }
    }

    private static final class Entry {
        final List<String> useragents = new ArrayList<>();
        final List<RuleLine> rulelines = new ArrayList<>();
        Double delay;

        boolean appliesTo(String useragent) {
            String agent = useragent.split("/")[0].toLowerCase(Locale.ROOT);
            for (String a : useragents) {
                if (a.equals("*") || agent.contains(a.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }

        Boolean allowance(String filename) {
            for (RuleLine line : rulelines) {
                if (line.appliesTo(filename)) {
                    return line.allowance;
                }
            }
            return true;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private Entry defaultEntry;
    private String url = "";
    public boolean disallow_all;
    public boolean allow_all;
    private boolean lastChecked;

    public void set_url(String url) {
        this.url = url;
    }

    public String url() {
        return url;
    }

    private static String normalize(String path) {
        String decoded = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
        return decoded.isEmpty() ? "/" : decoded;
    }

    /** Parse the input lines from a robots.txt file. */
    public void parse(List<String> lines) {
        int state = 0;
        Entry entry = new Entry();
        lastChecked = true;
        for (String rawLine : lines) {
            String line = rawLine;
            if (line.isEmpty()) {
                if (state == 1) {
                    entry = new Entry();
                    state = 0;
                } else if (state == 2) {
                    addEntry(entry);
                    entry = new Entry();
                    state = 0;
                }
            }
            int comment = line.indexOf('#');
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = URLDecoder.decode(line.substring(colon + 1).strip().replace("+", "%2B"), StandardCharsets.UTF_8);
            switch (key) {
                case "user-agent" -> {
                    if (state == 2) {
                        addEntry(entry);
                        entry = new Entry();
                    }
                    entry.useragents.add(value);
                    state = 1;
                }
                case "disallow" -> {
                    if (state != 0) {
                        entry.rulelines.add(new RuleLine(value, false));
                        state = 2;
                    }
                }
                case "allow" -> {
                    if (state != 0) {
                        entry.rulelines.add(new RuleLine(value, true));
                        state = 2;
                    }
                }
                case "crawl-delay" -> {
                    if (state != 0) {
                        try {
                            entry.delay = Double.parseDouble(value.strip());
                        } catch (NumberFormatException err) {
                            // ignore invalid values like Python does
                        }
                        state = 2;
                    }
                }
                default -> {
                    // sitemaps and other fields are ignored
                }
            }
        }
        if (state == 2) {
            addEntry(entry);
        }
    }

    private void addEntry(Entry entry) {
        if (entry.useragents.contains("*")) {
            if (defaultEntry == null) {
                defaultEntry = entry;
            }
        } else {
            entries.add(entry);
        }
    }

    /** Using the parsed robots.txt decide if useragent can fetch url. */
    public boolean can_fetch(String useragent, String url) {
        if (disallow_all) {
            return false;
        }
        if (allow_all) {
            return true;
        }
        if (!lastChecked) {
            return false;
        }
        String path = url;
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            int slash = path.indexOf('/', scheme + 3);
            path = slash >= 0 ? path.substring(slash) : "/";
        }
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        path = normalize(path);
        for (Entry entry : entries) {
            if (entry.appliesTo(useragent)) {
                return entry.allowance(path);
            }
        }
        if (defaultEntry != null) {
            return defaultEntry.allowance(path);
        }
        return true;
    }

    /** Crawl delay for the user agent, or null. */
    public Double crawl_delay(String useragent) {
        for (Entry entry : entries) {
            if (entry.appliesTo(useragent)) {
                return entry.delay;
            }
        }
        return defaultEntry != null ? defaultEntry.delay : null;
    }
}
