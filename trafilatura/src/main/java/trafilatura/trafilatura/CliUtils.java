// Java port of trafilatura/cli_utils.py
package trafilatura.trafilatura;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import trafilatura.compat.Pair;
import trafilatura.compat.Py;
import trafilatura.compat.SystemExit;
import trafilatura.courlan.Courlan;
import trafilatura.courlan.UrlStore;
import trafilatura.trafilatura.Cli.Args;
import trafilatura.trafilatura.Settings.Extractor;
import trafilatura.trafilatura.Utils.Response;

import static trafilatura.trafilatura.Baseline.html2txt;
import static trafilatura.trafilatura.Deduplication.generate_bow_hash;
import static trafilatura.trafilatura.Downloads.add_to_compressed_dict;
import static trafilatura.trafilatura.Downloads.buffered_downloads;
import static trafilatura.trafilatura.Downloads.buffered_response_downloads;
import static trafilatura.trafilatura.Downloads.load_download_buffer;
import static trafilatura.trafilatura.Feeds.find_feed_urls;
import static trafilatura.trafilatura.Meta.reset_caches;
import static trafilatura.trafilatura.Settings.FILENAME_LEN;
import static trafilatura.trafilatura.Settings.MAX_FILES_PER_DIRECTORY;
import static trafilatura.trafilatura.Settings.args_to_extractor;
import static trafilatura.trafilatura.Sitemaps.sitemap_search;
import static trafilatura.trafilatura.Utils.LANGID_FLAG;
import static trafilatura.trafilatura.Utils.URL_BLACKLIST_REGEX;
import static trafilatura.trafilatura.Utils.is_acceptable_length;
import static trafilatura.trafilatura.Utils.language_classifier;
import static trafilatura.trafilatura.Utils.make_chunks;

/** Functions dedicated to command-line processing. */
public final class CliUtils {
    private CliUtils() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CliUtils.class);

    public static final boolean HAS_GZIP = true;

    // make generated file names reproducible (not the same sequence as Python's random module)
    public static final Random RANDOM = new Random(345);
    public static final String CHAR_CLASS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    public static final Pattern STRIP_DIR = Pattern.compile("[^/]+$");
    public static final Pattern STRIP_EXTENSION = Pattern.compile("\\.[a-z]{2,5}$");

    public static final Pattern CLEAN_XML = Pattern.compile("<[^<]+?>");

    public static final List<String> INPUT_URLS_ARGS = List.of("URL", "crawl", "explore", "probe", "feed", "sitemap");

    public static final Map<String, String> EXTENSION_MAPPING = Map.of(
            "csv", ".csv",
            "json", ".json",
            "xml", ".xml",
            "xmltei", ".xml");

    /** Read list of URLs to process or derive one from command-line arguments. */
    public static List<String> load_input_urls(Args args) {
        List<String> input_urls = new ArrayList<>();

        if (Py.truthy(args.input_file)) {
            try {
                // optional: errors='strict', buffering=1
                String content = StandardCharsets.UTF_8.newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(Path.of(args.input_file))))
                        .toString();
                for (String line : content.split("\n", -1)) {
                    input_urls.add(Py.strip(line));
                }
                // a trailing newline does not produce an extra line when reading a file line by line
                if (content.endsWith("\n")) {
                    input_urls.remove(input_urls.size() - 1);
                }
            } catch (CharacterCodingException err) {
                throw new SystemExit("ERROR: system, file type or buffer encoding");
            } catch (IOException err) {
                throw new UncheckedIOException(err);
            }
        } else {
            for (String arg : INPUT_URLS_ARGS) {
                Object value = args.getattr(arg);
                if (Py.truthy(value)) {
                    input_urls = new ArrayList<>(List.of(value instanceof String s ? s : String.valueOf(value)));
                    break;
                }
            }
        }

        if (input_urls.isEmpty()) {
            LOGGER.warn("No input provided");
        }

        // uniq URLs while preserving order (important)
        return new ArrayList<>(new LinkedHashSet<>(input_urls));
    }

    /** Read list of unwanted URLs. */
    public static Set<String> load_blacklist(String filename) {
        Set<String> blacklist = new HashSet<>();
        try {
            for (String line : Files.readAllLines(Path.of(filename), StandardCharsets.UTF_8)) {
                blacklist.add(URL_BLACKLIST_REGEX.matcher(Py.strip(line)).replaceAll(""));
            }
        } catch (IOException err) {
            throw new UncheckedIOException(err);
        }
        return blacklist;
    }

    /** Read input list of URLs to process and build a domain-aware dictionary. */
    public static UrlStore load_input_dict(Args args) {
        List<String> inputlist = load_input_urls(args);
        // deduplicate, filter and convert to dict
        return add_to_compressed_dict(inputlist, args.blacklist_set(), args.url_filter, null,
                Py.truthy(args.sitemap) && !args.list, args.verbose > 0);
    }

    /** Check if the output directory is within reach and writable. */
    public static boolean check_outputdir_status(String directory) {
        try {
            Files.createDirectories(Path.of(directory));
        } catch (IOException | RuntimeException err) {
            System.err.print("ERROR: Destination directory cannot be created: " + directory + "\n");
            return false;
        }
        return true;
    }

    /** {@code os.path.join(a, b)} keeping the separators given by the caller. */
    public static String join(String dirname, String name) {
        if (name.startsWith("/")) {
            return name;
        }
        if (dirname.isEmpty() || dirname.endsWith("/")) {
            return dirname + name;
        }
        return dirname + "/" + name;
    }

    /** Return a destination directory based on a file counter. */
    public static String determine_counter_dir(String dirname, int c) {
        if (c < 0) {
            return dirname;
        }
        return join(dirname, String.valueOf(c / MAX_FILES_PER_DIRECTORY + 1));
    }

    /** Find a writable path and return it along with its random file name. */
    public static Pair<String, String> get_writable_path(String destdir, String extension) {
        while (true) {
            StringBuilder filename = new StringBuilder();
            for (int i = 0; i < FILENAME_LEN; i++) {
                filename.append(CHAR_CLASS.charAt(RANDOM.nextInt(CHAR_CLASS.length())));
            }
            String output_path = join(destdir, filename + extension);
            if (!Files.exists(Path.of(output_path))) {
                return Pair.of(output_path, filename.toString());
            }
        }
    }

    /**
     * Create a filename-safe string by hashing the given content
     * after deleting potential XML tags.
     */
    public static String generate_hash_filename(String content) {
        return Base64.getUrlEncoder().encodeToString(generate_bow_hash(CLEAN_XML.matcher(content).replaceAll(""), 12));
    }

    /** Pick a directory based on selected options and a file name based on output type (path, directory). */
    public static Pair<String, String> determine_output_path(Args args, String orig_filename, String content, int counter,
            String new_filename) {
        // determine extension, TXT by default
        String extension = EXTENSION_MAPPING.getOrDefault(args.output_format, ".txt");

        String destination_dir;
        String filename;
        if (args.keep_dirs) {
            // strip directory
            String original_dir = STRIP_DIR.matcher(orig_filename).replaceAll("");
            destination_dir = join(args.output_dir, original_dir);
            // strip extension
            filename = STRIP_EXTENSION.matcher(orig_filename).replaceAll("");
        } else {
            destination_dir = determine_counter_dir(args.output_dir, counter);
            // use cryptographic hash on file contents to define name
            filename = Py.truthy(new_filename) ? new_filename : generate_hash_filename(content);
        }

        String output_path = join(destination_dir, filename + extension);
        return Pair.of(output_path, destination_dir);
    }

    public static Pair<String, String> determine_output_path(Args args, String orig_filename, String content) {
        return determine_output_path(args, orig_filename, content, -1, null);
    }

    /** Write a copy of raw HTML in backup directory. */
    public static String archive_html(String htmlstring, Args args, int counter) {
        String destination_directory = determine_counter_dir(args.backup_dir, counter);
        Pair<String, String> writable = get_writable_path(destination_directory, ".html.gz");
        // check the directory status
        if (check_outputdir_status(destination_directory) && HAS_GZIP) {
            // write
            try (OutputStream outputfile = new GZIPOutputStream(Files.newOutputStream(Path.of(writable.first())))) {
                outputfile.write(htmlstring.getBytes(StandardCharsets.UTF_8));
            } catch (IOException err) {
                throw new UncheckedIOException(err);
            }
        }
        return writable.second();
    }

    public static String archive_html(String htmlstring, Args args) {
        return archive_html(htmlstring, args, -1);
    }

    /** Deal with result (write to STDOUT or to file). */
    public static void write_result(String result, Args args, String orig_filename, int counter, String new_filename) {
        if (result == null) {
            return;
        }
        if (args.output_dir == null) {
            System.out.print(result + "\n");
            System.out.flush();
        } else {
            Pair<String, String> output = determine_output_path(args, orig_filename, result, counter, new_filename);
            // check the directory status
            if (check_outputdir_status(output.second())) {
                try {
                    Files.writeString(Path.of(output.first()), result, StandardCharsets.UTF_8);
                } catch (IOException err) {
                    throw new UncheckedIOException(err);
                }
            }
        }
    }

    public static void write_result(String result, Args args) {
        write_result(result, args, "", -1, null);
    }

    /** Walk the directory tree and output all file names (symbolic links to directories are not followed). */
    public static List<String> generate_filelist(String inputdir) {
        List<String> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Path.of(inputdir))) {
            walk.filter(Files::isRegularFile).forEach(p -> files.add(p.toString()));
        } catch (IOException err) {
            // os.walk ignores unreadable directories
            LOGGER.debug("cannot walk {}: {}", inputdir, err.toString());
        }
        return files;
    }

    /** Aggregated functions to process a file in a list. */
    public static void file_processing(String filename, Args args, int counter, Extractor options) {
        if (options == null) {
            options = args_to_extractor(args);
        }
        options.source = filename;

        Path path = Path.of(filename);
        byte[] htmlstring;
        long ref_timestamp;
        try {
            htmlstring = Files.readAllBytes(path);
            ref_timestamp = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException err) {
            throw new UncheckedIOException(err);
        }
        try {
            // min(st_ctime, st_mtime): the inode change time is only available on POSIX systems
            FileTime ctime = (FileTime) Files.getAttribute(path, "unix:ctime");
            ref_timestamp = Math.min(ref_timestamp, ctime.toMillis());
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException err) {
            // keep the modification time
        }
        options.date_params = new java.util.LinkedHashMap<>(options.date_params);
        options.date_params.put("max_date", Instant.ofEpochMilli(ref_timestamp).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));

        String result = examine(htmlstring, args, null, options);
        write_result(result, args, filename, counter, null);
    }

    /** Extract text and metadata from a download webpage and eventually write out the result. */
    public static int process_result(String htmlstring, Args args, int counter, Extractor options) {
        // backup option
        String fileslug = Py.truthy(args.backup_dir) ? archive_html(htmlstring, args, counter) : "";
        // process
        String result = examine(htmlstring, args, null, options);
        write_result(result, args, fileslug, counter, fileslug);
        // increment written file counter
        if (counter >= 0 && Py.truthy(result)) {
            counter += 1;
        }
        return counter;
    }

    /** Implement a download queue consumer, single- or multi-threaded: (errors, counter). */
    public static Pair<List<String>, Integer> download_queue_processing(UrlStore url_store, Args args, int counter,
            Extractor options) {
        List<String> errors = new ArrayList<>();
        double sleep_time = options.config.getfloat("DEFAULT", "SLEEP_TIME");

        while (!url_store.done) {
            Pair<List<String>, UrlStore> buffer = load_download_buffer(url_store, sleep_time);
            url_store = buffer.second();
            // process downloads
            Iterator<Pair<String, String>> results = buffered_downloads(buffer.first(), args.parallel, options);
            while (results.hasNext()) {
                Pair<String, String> item = results.next();
                // handle result
                if (Py.truthy(item.second())) {
                    options.url = item.first();
                    counter = process_result(item.second(), args, counter, options);
                } else {
                    LOGGER.warn("No result for URL: {}", item.first());
                    errors.add(item.first());
                }
            }
        }
        return Pair.of(errors, counter);
    }

    /** Group CLI functions dedicated to URL discovery. */
    public static int cli_discovery(Args args) {
        UrlStore url_store = load_input_dict(args);
        List<String> input_urls = url_store.dump_urls();
        if (args.list) {
            url_store.reset();
        }

        Extractor options = args_to_extractor(args);
        boolean external = options.config.getboolean("DEFAULT", "EXTERNAL_URLS");
        double sleep_time = options.config.getfloat("DEFAULT", "SLEEP_TIME");
        Function<String, List<String>> func = Py.truthy(args.feed)
                ? url -> find_feed_urls(url, args.target_language, external, sleep_time, options.config)
                : url -> sitemap_search(url, args.target_language, external, sleep_time, Settings.MAX_SITEMAPS_SEEN,
                        options.config);
        Object lock = new Object();

        // link discovery and storage
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, args.parallel));
        try {
            CompletionService<List<String>> completion = new ExecutorCompletionService<>(executor);
            for (String url : input_urls) {
                completion.submit(() -> func.apply(url));
            }
            // process results from the parallel threads and add them
            // to the compressed URL dictionary for further processing
            for (int i = 0; i < input_urls.size(); i++) {
                List<String> found;
                try {
                    found = completion.take().get();
                } catch (InterruptedException err) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(err);
                } catch (ExecutionException err) {
                    throw new IllegalStateException(err.getCause());
                }
                if (found != null) {
                    url_store.add_urls(found);
                    // empty buffer in order to spare memory
                    if (args.list && url_store.get_known_domains().size() >= args.parallel) {
                        synchronized (lock) {
                            url_store.print_unvisited_urls();
                            url_store.reset();
                            reset_caches();
                        }
                    }
                }
            }
        } finally {
            executor.shutdown();
        }

        // process the (rest of the) links found
        int exit_code = url_processing_pipeline(args, url_store);

        // activate site explorer
        if (Py.truthy(args.explore)) {
            // add to compressed dict and crawl the remaining websites
            UrlStore control_dict = build_exploration_dict(url_store, input_urls, args);
            cli_crawler(args, 30, control_dict, options);
        }

        return exit_code;
    }

    /** Find domains for which nothing has been found and add info to the crawl dict. */
    public static UrlStore build_exploration_dict(UrlStore url_store, List<String> input_urls, Args args) {
        Set<String> still_to_crawl = new HashSet<>();
        for (String u : input_urls) {
            still_to_crawl.add(Courlan.extract_domain(u));
        }
        for (String u : url_store.get_known_domains()) {
            still_to_crawl.remove(Courlan.extract_domain(u));
        }
        List<String> new_input_urls = new ArrayList<>();
        for (String u : input_urls) {
            if (still_to_crawl.contains(Courlan.extract_domain(u))) {
                new_input_urls.add(u);
            }
        }
        return add_to_compressed_dict(new_input_urls, args.blacklist_set(), args.url_filter, null, false, args.verbose > 0);
    }

    /**
     * Start a focused crawler which downloads a fixed number of URLs within a website
     * and prints the links found in the process.
     */
    public static void cli_crawler(Args args, int n, UrlStore url_store, Extractor options) {
        if (options == null) {
            options = args_to_extractor(args);
        }
        double sleep_time = options.config.getfloat("DEFAULT", "SLEEP_TIME");
        Map<String, Spider.CrawlParameters> param_dict = new HashMap<>();

        // load input URLs
        if (url_store == null) {
            Spider.URL_STORE.add_urls(load_input_urls(args));
        } else {
            Spider.URL_STORE = url_store;
        }

        // load crawl data
        for (String hostname : Spider.URL_STORE.get_known_domains()) {
            if (!Spider.URL_STORE.find_known_urls(hostname).isEmpty()) {
                String startpage = Spider.URL_STORE.get_url(hostname, false);
                if (Py.truthy(startpage)) {
                    Spider.CrawlParameters params = Spider.init_crawl(startpage, args.target_language, null, null, null,
                            null, options.config);
                    // key by the crawl base: it may differ from hostname after a https upgrade,
                    // but the store may still hold URLs under the original base
                    param_dict.put(params.base, params);
                    param_dict.putIfAbsent(Courlan.get_base_url(startpage), params);
                }
            }
        }

        // iterate until the threshold is reached
        while (!Spider.URL_STORE.done) {
            Pair<List<String>, UrlStore> buffer = load_download_buffer(Spider.URL_STORE, sleep_time);
            Spider.URL_STORE = buffer.second();
            Iterator<Pair<String, Response>> results = buffered_response_downloads(buffer.first(), args.parallel, options);
            while (results.hasNext()) {
                Pair<String, Response> item = results.next();
                if (item.second() != null && item.second().isTruthy()) {
                    // not necessarily registered, e.g. after a cross-host homepage redirect
                    Spider.CrawlParameters crawl_params = param_dict.get(Courlan.get_base_url(item.first()));
                    if (crawl_params != null) {
                        Spider.process_response(item.second(), crawl_params);
                    } else {
                        LOGGER.warn("no crawl parameters for {}", item.first());
                    }
                }
            }
            // early exit if maximum count is reached
            if (Spider.URL_STORE.get_all_counts().stream().anyMatch(c -> c >= n)) {
                break;
            }
        }

        System.out.println(String.join("\n", Spider.URL_STORE.dump_urls()));
    }

    public static void cli_crawler(Args args) {
        cli_crawler(args, 30, null, null);
    }

    /** Probe websites for extractable content and print the fitting ones. */
    public static void probe_homepage(Args args) {
        List<String> input_urls = load_input_urls(args);
        Extractor options = args_to_extractor(args);

        Iterator<Pair<String, String>> results = buffered_downloads(input_urls, args.parallel, options);
        while (results.hasNext()) {
            Pair<String, String> item = results.next();
            if (item.second() != null) {
                String result = html2txt(item.second());
                if (!result.isEmpty() && result.length() > options.min_extracted_size
                        && result.codePoints().anyMatch(Character::isLetter)) {
                    if (!LANGID_FLAG || !Py.truthy(args.target_language)
                            || args.target_language.equals(language_classifier(result, ""))) {
                        System.out.println(item.first());
                        System.out.flush();
                    }
                }
            }
        }
    }

    /**
     * Compute exit code based on the number of errors:
     * 0 if there are no errors, 126 if there are too many, 1 otherwise.
     */
    public static int _define_exit_code(List<String> errors, int total) {
        double ratio = total > 0 ? (double) errors.size() / total : 0;

        if (ratio > 0.99) {
            return 126;
        }
        if (!errors.isEmpty()) {
            return 1;
        }
        return 0;
    }

    /** Aggregated functions to show a list and download and process an input list. */
    public static int url_processing_pipeline(Args args, UrlStore url_store) {
        if (args.list) {
            url_store.print_unvisited_urls(); // and not write_result()
            return 0; // and not sys.exit(0)
        }

        Extractor options = args_to_extractor(args);
        int url_count = url_store.total_url_number();
        int counter = url_count > MAX_FILES_PER_DIRECTORY ? 0 : -1;

        // download strategy
        Pair<List<String>, Integer> processed = download_queue_processing(url_store, args, counter, options);
        List<String> errors = processed.first();
        counter = processed.second();
        LOGGER.debug("{} / {} URLs could not be found", errors.size(), url_count);

        if (args.archived) {
            url_store = new UrlStore();
            List<String> archived = new ArrayList<>();
            for (String e : errors) {
                archived.add("https://web.archive.org/web/20/" + e);
            }
            url_store.add_urls(archived);
            if (!url_store.find_known_urls("https://web.archive.org").isEmpty()) {
                List<String> archived_errors = download_queue_processing(url_store, args, counter, options).first();
                LOGGER.debug("{} archived URLs out of {} could not be found", archived_errors.size(), errors.size());
                // pass information along if URLs are missing
                return _define_exit_code(archived_errors, url_store.total_url_number());
            }
        }

        return _define_exit_code(errors, url_count);
    }

    /** Define batches for parallel file processing and perform the extraction. */
    public static void file_processing_pipeline(Args args) {
        int filecounter = -1;
        Extractor options = args_to_extractor(args);

        // a thread pool stands in for the Python process pool
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, args.parallel));
        try {
            // chunk input: https://github.com/python/cpython/issues/74028
            Iterator<List<String>> batches = make_chunks(generate_filelist(args.input_dir), MAX_FILES_PER_DIRECTORY);
            while (batches.hasNext()) {
                List<String> filebatch = batches.next();
                if (filecounter < 0 && filebatch.size() >= MAX_FILES_PER_DIRECTORY) {
                    filecounter = 0;
                }
                int counter = filecounter;
                for (String filename : filebatch) {
                    // each task works on its own copy: file_processing() sets the source and max_date
                    Extractor taskOptions = options.copy();
                    executor.submit(() -> file_processing(filename, args, counter, taskOptions));
                }
                // update counter
                if (filecounter >= 0) {
                    filecounter += filebatch.size();
                }
            }
        } finally {
            // the executor context manager waits for the submitted tasks; as in the Python version the
            // EXTRACTION_TIMEOUT setting is not enforced since the map() results are never consumed
            executor.shutdown();
            try {
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
            } catch (InterruptedException err) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    /** Generic safeguards and triggers around extraction function. */
    public static String examine(Object htmlstring, Args args, String url, Extractor options) {
        String result = null;
        if (options == null) {
            options = args_to_extractor(args, url);
        }
        // safety check
        if (htmlstring == null) {
            System.err.print("ERROR: empty document\n");
        } else if (!is_acceptable_length(htmlstring instanceof byte[] b ? b.length : ((String) htmlstring).length(), options)) {
            System.err.print("ERROR: file size\n");
        } else {
            // proceed
            try {
                result = Core.extract(htmlstring, Core.kw().options(options));
            } catch (RuntimeException err) {
                // ugly but efficient
                StringWriter trace = new StringWriter();
                err.printStackTrace(new PrintWriter(trace));
                System.err.print("ERROR: " + err + "\n" + trace + "\n");
            }
        }
        return result;
    }

    public static String examine(Object htmlstring, Args args) {
        return examine(htmlstring, args, null, null);
    }
}
