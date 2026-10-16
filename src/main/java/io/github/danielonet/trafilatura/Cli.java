// Java port of trafilatura/cli.py
package io.github.danielonet.trafilatura;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import io.github.danielonet.trafilatura.compat.ArgumentParser;
import io.github.danielonet.trafilatura.compat.SystemExit;
import io.github.danielonet.trafilatura.courlan.UrlStore;

import static io.github.danielonet.trafilatura.CliUtils.cli_crawler;
import static io.github.danielonet.trafilatura.CliUtils.cli_discovery;
import static io.github.danielonet.trafilatura.CliUtils.examine;
import static io.github.danielonet.trafilatura.CliUtils.file_processing_pipeline;
import static io.github.danielonet.trafilatura.CliUtils.load_blacklist;
import static io.github.danielonet.trafilatura.CliUtils.load_input_dict;
import static io.github.danielonet.trafilatura.CliUtils.probe_homepage;
import static io.github.danielonet.trafilatura.CliUtils.url_processing_pipeline;
import static io.github.danielonet.trafilatura.CliUtils.write_result;
import static io.github.danielonet.trafilatura.Settings.PARALLEL_CORES;
import static io.github.danielonet.trafilatura.Settings.SUPPORTED_FMT_CLI;

/** Implementing a basic command-line interface. */
public final class Cli {
    private Cli() {
    }

    // options that --list neither downloads nor extracts, hence ignores
    public static final Set<String> _LIST_IGNORED_OPTS = Set.of(
            "fast", "formatting", "precision", "recall", "images", "links", "with_metadata", "only_with_metadata",
            "comments", "tables", "deduplicate", "output_format", "archived", "backup_dir");

    /** Parsed command-line arguments ({@code argparse.Namespace}); field names are the argparse destinations. */
    public static class Args {
        // Input
        public String input_file;
        public String input_dir;
        public String URL;
        public int parallel = PARALLEL_CORES;
        /** File name as parsed, replaced by the loaded set of URLs in process_args(). */
        public Object blacklist;
        // Output
        public boolean list;
        public String output_dir;
        public String backup_dir;
        public boolean keep_dirs;
        // Navigation: False, True or a URL
        public Object feed = false;
        public Object sitemap = false;
        public Object crawl = false;
        public Object explore = false;
        public Object probe = false;
        public boolean archived;
        public List<String> url_filter;
        // Extraction
        public boolean fast;
        public Boolean formatting;
        public boolean links;
        public boolean images;
        public boolean comments = true;
        public boolean tables = true;
        public boolean only_with_metadata;
        public boolean with_metadata;
        public String target_language;
        public boolean deduplicate;
        public String config_file;
        public boolean precision;
        public boolean recall;
        // Format
        public String output_format = "txt";
        public boolean csv;
        public boolean html;
        public boolean json;
        public boolean markdown;
        public boolean xml;
        public boolean xmltei;
        public boolean validate_tei;
        public int verbose;

        /** {@code getattr(args, name)} */
        public Object getattr(String name) {
            return ArgumentParser.getattr(this, name);
        }

        /** {@code setattr(args, name, value)} */
        public void setattr(String name, Object value) {
            ArgumentParser.setattr(this, name, value);
        }

        @SuppressWarnings("unchecked")
        public Set<String> blacklist_set() {
            return blacklist instanceof Set<?> set ? (Set<String>) set : null;
        }
    }

    /** Add argument groups and arguments to parser. */
    public static ArgumentParser add_args(ArgumentParser parser) {
        ArgumentParser.Group group1 = parser.add_argument_group("Input", "URLs, files or directories to process");
        ArgumentParser.Group group1_ex = group1.add_mutually_exclusive_group();
        ArgumentParser.Group group2 = parser.add_argument_group("Output", "Determines if and how files will be written");
        ArgumentParser.Group group3 = parser.add_argument_group("Navigation", "Link discovery and web crawling");
        ArgumentParser.Group group3_ex = group3.add_mutually_exclusive_group();
        ArgumentParser.Group group4 = parser.add_argument_group("Extraction", "Customization of text and metadata processing");
        ArgumentParser.Group group5 = parser.add_argument_group("Format", "Selection of the output format");
        ArgumentParser.Group group5_ex = group5.add_mutually_exclusive_group();

        group1_ex.add_argument("-i", "--input-file").help("name of input file for batch processing").type("str");
        group1_ex.add_argument("--input-dir").help("read files from a specified directory (relative path)").type("str");
        group1_ex.add_argument("-u", "--URL").help("custom URL download").type("str");

        group1.add_argument("--parallel")
                .help("specify a number of cores/threads for downloads and/or processing")
                .type("int")
                .defaultValue(PARALLEL_CORES);
        group1.add_argument("-b", "--blacklist").help("file containing unwanted URLs to discard during processing").type("str");

        group2.add_argument("--list").help("display a list of URLs without downloading them").action("store_true");
        group2.add_argument("-o", "--output-dir").help("write results in a specified directory (relative path)").type("str");
        group2.add_argument("--backup-dir").help("preserve a copy of downloaded files in a backup directory").type("str");
        group2.add_argument("--keep-dirs").help("keep input directory structure and file names").action("store_true");

        group3_ex.add_argument("--feed")
                .help("look for feeds and/or pass a feed URL as input")
                .nargs("?").constant(true).defaultValue(false);
        group3_ex.add_argument("--sitemap")
                .help("look for sitemaps for the given website and/or enter a sitemap URL")
                .nargs("?").constant(true).defaultValue(false);
        group3_ex.add_argument("--crawl")
                .help("crawl a fixed number of pages within a website starting from the given URL")
                .nargs("?").constant(true).defaultValue(false);
        group3_ex.add_argument("--explore")
                .help("explore the given websites (combination of sitemap and crawl)")
                .nargs("?").constant(true).defaultValue(false);
        group3_ex.add_argument("--probe")
                .help("probe for extractable content (works best with target language)")
                .nargs("?").constant(true).defaultValue(false);
        group3.add_argument("--archived")
                .help("try to fetch URLs from the Internet Archive if downloads fail")
                .action("store_true");
        group3.add_argument("--url-filter")
                .help("only process/output URLs containing these patterns (space-separated strings)")
                .nargs("+").type("str");

        group4.add_argument("-f", "--fast").help("fast (without fallback detection)").action("store_true");
        group4.add_argument("--formatting").help("include text formatting (bold, italic, etc.)").action("store_true")
                .defaultValue(null);
        group4.add_argument("--links").help("include links along with their targets").action("store_true");
        group4.add_argument("--images").help("include image sources in output").action("store_true");
        group4.add_argument("--no-comments").dest("comments").help("don't output any comments").action("store_false");
        group4.add_argument("--no-tables").dest("tables").help("don't output any table elements").action("store_false");
        group4.add_argument("--only-with-metadata")
                .help("only output those documents with title, URL and date")
                .action("store_true");
        group4.add_argument("--with-metadata").help("extract and add metadata to the output").action("store_true");
        group4.add_argument("--target-language").help("select a target language (ISO 639-1 codes)").type("str");
        group4.add_argument("--deduplicate").help("filter out duplicate documents and sections").action("store_true");
        group4.add_argument("--config-file").help("override standard extraction parameters with a custom config file").type("str");
        group4.add_argument("--precision").help("favor extraction precision (less noise, possibly less text)").action("store_true");
        group4.add_argument("--recall").help("favor extraction recall (more text, possibly more noise)").action("store_true");

        // https://docs.python.org/3/library/argparse.html#argparse.ArgumentParser.add_mutually_exclusive_group
        group5_ex.add_argument("--output-format").help("determine output format").choices(SUPPORTED_FMT_CLI)
                .defaultValue("txt");
        group5_ex.add_argument("--csv").help("shorthand for CSV output").action("store_true");
        group5_ex.add_argument("--html").help("shorthand for HTML output").action("store_true");
        group5_ex.add_argument("--json").help("shorthand for JSON output").action("store_true");
        group5_ex.add_argument("--markdown").help("shorthand for MD output").action("store_true");
        group5_ex.add_argument("--xml").help("shorthand for XML output").action("store_true");
        group5_ex.add_argument("--xmltei").help("shorthand for XML TEI output").action("store_true");
        group5.add_argument("--validate-tei").help("validate XML TEI output").action("store_true");

        parser.add_argument("-v", "--verbose")
                .action("count")
                .defaultValue(0)
                .help("increase logging verbosity (-v or -vv)");
        parser.add_argument("--version")
                .help("show version information and exit")
                .action("version")
                .version("Trafilatura " + TrafilaturaInit.__version__ + " - Java " + System.getProperty("java.version"));

        return parser;
    }

    /** Catch cross-group incompatibilities that argparse cannot express. */
    public static void _validate_args(ArgumentParser parser, Args args) {
        if (args.keep_dirs && args.output_dir == null) {
            throw parser.error("--keep-dirs requires an output directory (-o/--output-dir)");
        }
        if (args.list) {
            List<String> ignored = new ArrayList<>();
            for (String o : new TreeSet<>(_LIST_IGNORED_OPTS)) {
                if (!Objects.equals(args.getattr(o), parser.get_default(o))) {
                    ignored.add(o);
                }
            }
            if (!ignored.isEmpty()) {
                // emitted before logging is configured, so print directly
                System.err.println("--list only prints URLs; these options are ignored: " + String.join(", ", ignored));
            }
        }
    }

    /** Define parser for command-line arguments. */
    public static Args parse_args(List<String> args) {
        ArgumentParser parser = new ArgumentParser("Command-line interface for Trafilatura");
        parser = add_args(parser);
        Args parsed = map_args(parser.parse_args(args, new Args()));
        _validate_args(parser, parsed);
        return parsed;
    }

    public static Args parse_args(String... args) {
        return parse_args(Arrays.asList(args));
    }

    /** Map existing options to format and output choices. */
    public static Args map_args(Args args) {
        // formats
        for (String otype : List.of("csv", "html", "json", "markdown", "xml", "xmltei")) {
            if (Boolean.TRUE.equals(args.getattr(otype))) {
                args.output_format = otype;
                break;
            }
        }
        return args;
    }

    /**
     * {@code main()} with {@code sys.argv}: the first element is the program name.
     * Errors and non-zero exit codes are raised as {@link SystemExit}.
     */
    public static void main_argv(List<String> argv) {
        Args args = parse_args(argv.subList(1, argv.size()));
        process_args(args);
    }

    /** Run as a command-line utility. */
    public static void main(String[] argv) {
        // the simple logger reads its level once, before the first logger is created
        int verbosity = 0;
        for (String arg : argv) {
            if (arg.equals("--verbose") || arg.equals("-v")) {
                verbosity++;
            } else if (arg.matches("-[A-Za-z]+") && "iuob".indexOf(arg.charAt(1)) < 0) {
                // clusters of short flags such as -vv or -fvv (but not -i/-u/-o/-b with an inline value)
                verbosity += (int) arg.chars().filter(c -> c == 'v').count();
            }
        }
        configure_logging(verbosity);
        try {
            Args args = parse_args(argv);
            process_args(args);
        } catch (SystemExit exit) {
            if (exit.text != null) {
                System.err.println(exit.text);
            }
            System.out.flush();
            System.exit(exit.code);
        }
    }

    /** Logging level from the verbosity count (simple logger: set before the first logger is created). */
    public static void configure_logging(int verbose) {
        String level = verbose >= 2 ? "debug" : verbose == 1 ? "info" : "warn";
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level);
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
    }

    private static boolean truthy(Object value) {
        return io.github.danielonet.trafilatura.compat.Py.truthy(value);
    }

    /** Perform the actual processing according to the arguments. */
    public static void process_args(Args args) {
        int exit_code = 0;

        if (args.blacklist instanceof String filename && !filename.isEmpty()) {
            args.blacklist = load_blacklist(filename);
        }

        // processing according to mutually exclusive options

        if (truthy(args.explore) || truthy(args.feed) || truthy(args.sitemap)) {
            // fetch urls from a feed or a sitemap
            exit_code = cli_discovery(args);
        } else if (truthy(args.crawl)) {
            // activate crawler/spider
            cli_crawler(args);
        } else if (truthy(args.probe)) {
            // probe and print only
            probe_homepage(args);
        } else if (truthy(args.input_dir)) {
            // read files from an input directory
            file_processing_pipeline(args);
        } else if (truthy(args.input_file) || truthy(args.URL)) {
            // read url list from input file or process input URL
            UrlStore url_store = load_input_dict(args);
            exit_code = url_processing_pipeline(args, url_store);
        } else {
            // read input on STDIN directly
            byte[] input;
            try {
                input = System.in.readAllBytes();
            } catch (IOException err) {
                throw new SystemExit("ERROR: cannot read standard input: " + err.getMessage());
            }
            String result = examine(input, args, args.URL, null);
            write_result(result, args);
        }

        // change exit code if there are errors
        if (exit_code != 0) {
            throw new SystemExit(exit_code);
        }
    }
}
