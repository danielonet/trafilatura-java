// Java port of tests/cli_tests.py
//
// stdout/stderr are captured with System.setOut/setErr (redirect_stdout, capsys), stdin with
// System.setIn; the "trafilatura" executable of test_climain is the Cli main class run in a child JVM.
package trafilatura.tests;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import trafilatura.compat.Pair;
import trafilatura.compat.Py;
import trafilatura.compat.SystemExit;
import trafilatura.courlan.UrlStore;
import trafilatura.trafilatura.Cli;
import trafilatura.trafilatura.Cli.Args;
import trafilatura.trafilatura.CliUtils;
import trafilatura.trafilatura.Settings;
import trafilatura.trafilatura.Settings.Extractor;
import trafilatura.trafilatura.Spider;
import trafilatura.trafilatura.Utils;
import trafilatura.trafilatura.Utils.Response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static trafilatura.tests.T.b;
import static trafilatura.trafilatura.Downloads.add_to_compressed_dict;
import static trafilatura.trafilatura.Downloads.fetch_response;
import static trafilatura.trafilatura.Downloads.fetch_url;

@ExtendWith(Conftest.MockNetwork.class)
public class CliTests {
    static final String RESOURCES_DIR = Conftest.RESOURCES_DIR.toString();
    static final Path DOCS_DIR = Conftest.TEST_DIR.resolve("docs");

    private int savedMaxFiles;
    private BiFunction<Object, Extractor, String> savedExtract;
    private CliUtils.ResponseDownloads savedDownloads;
    private UrlStore savedUrlStore;
    private PrintStream savedOut;
    private PrintStream savedErr;
    private InputStream savedIn;

    @BeforeEach
    void setUp() {
        savedMaxFiles = Settings.MAX_FILES_PER_DIRECTORY;
        savedExtract = CliUtils.EXTRACT;
        savedDownloads = CliUtils.BUFFERED_RESPONSE_DOWNLOADS;
        savedUrlStore = Spider.URL_STORE;
        savedOut = System.out;
        savedErr = System.err;
        savedIn = System.in;
        // settings.MAX_FILES_PER_DIRECTORY = 1 at module level
        Settings.MAX_FILES_PER_DIRECTORY = 1;
    }

    @AfterEach
    void tearDown() {
        Settings.MAX_FILES_PER_DIRECTORY = savedMaxFiles;
        CliUtils.EXTRACT = savedExtract;
        CliUtils.BUFFERED_RESPONSE_DOWNLOADS = savedDownloads;
        Spider.URL_STORE = savedUrlStore;
        System.setOut(savedOut);
        System.setErr(savedErr);
        System.setIn(savedIn);
    }

    /** {@code with redirect_stdout(f): body} returning f.getvalue(). */
    static String captureStdout(Runnable body) {
        PrintStream saved = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.out.flush();
            System.setOut(saved);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /** capsys.readouterr().err for the body. */
    static String captureStderr(Runnable body) {
        PrintStream saved = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.err.flush();
            System.setErr(saved);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    static String readResource(String name) {
        return Conftest.read_text(Conftest.RESOURCES_DIR.resolve(name));
    }

    /** test argument parsing for the command-line interface */
    @Test
    void test_parser() {
        Args args = Cli.parse_args("-fvv", "--xmltei", "--no-tables", "-u", "https://www.example.org");
        assertTrue(args.fast);
        assertEquals(2, args.verbose);
        assertFalse(args.tables);
        assertTrue(args.xmltei);
        assertEquals("https://www.example.org", args.URL);
        args = Cli.map_args(args);
        assertEquals("xmltei", args.output_format);
        args = Cli.parse_args("--output-format", "csv", "--no-tables", "-u", "https://www.example.org");
        assertFalse(args.fast);
        assertEquals(0, args.verbose);
        assertEquals("csv", args.output_format);
        assertFalse(args.tables);
        // test args mapping
        args = Cli.map_args(Cli.parse_args("--markdown"));
        assertEquals("markdown", args.output_format);
        // precision + recall accepted at parse time; Extractor warns and recall wins
        args = Cli.map_args(Cli.parse_args("--xml", "--no-comments", "--precision", "--recall"));
        assertEquals("xml", args.output_format);
        assertFalse(args.comments);
        assertTrue(args.precision);
        assertTrue(args.recall);
        assertEquals("recall", Settings.args_to_extractor(args).focus);
        args.xml = false;
        args.csv = true;
        args = Cli.map_args(args);
        assertEquals("csv", args.output_format);
        args.csv = false;
        args.json = true;
        args = Cli.map_args(args);
        assertEquals("json", args.output_format);
        args = Cli.map_args(Cli.parse_args("--only-with-metadata"));
        assertTrue(args.only_with_metadata);
        // process_args
        args.input_dir = "/dev/null";
        args.verbose = 1;
        args.blacklist = Path.of(RESOURCES_DIR, "list-discard.txt").toString();
        Cli.process_args(args);
        assertEquals(3, ((Set<?>) args.blacklist).size());
        // filter
        args = Cli.parse_args("-i", "resources/list-discard.txt", "--url-filter", "test1", "test2", "-vvv");
        assertEquals("resources/list-discard.txt", args.input_file);
        assertEquals(List.of("test1", "test2"), args.url_filter);
        args.input_file = Path.of(RESOURCES_DIR, "list-discard.txt").toString();
        args.blacklist = Path.of(RESOURCES_DIR, "list-discard.txt").toString();
        Args filterArgs = args;
        assertEquals(0, captureStdout(() -> Cli.process_args(filterArgs)).length());
        // input directory
        Args dirArgs = Cli.parse_args("--input-dir", "resources/test/");
        assertEquals(0, captureStdout(() -> Cli.process_args(dirArgs)).length());
        // version
        SystemExit[] exit = new SystemExit[1];
        String out = captureStdout(() -> exit[0] = assertThrows(SystemExit.class, () -> Cli.parse_args("--version")));
        assertEquals(0, exit[0].code);
        assertTrue(out.matches("(?s)Trafilatura [0-9]\\.[0-9]+\\.[0-9] - Java [0-9]+.*"), out);
    }

    /** Regression: parse_args must parse its argument list. */
    @Test
    void test_parse_args_honors_argument() {
        Args args = Cli.parse_args(List.of("--xml", "-u", "https://example.org"));
        assertTrue(args.xml);
        assertEquals("https://example.org", args.URL);
    }

    /** --keep-dirs without -o must exit with an error. */
    @Test
    void test_keep_dirs_requires_output_dir() {
        captureStderr(() -> assertThrows(SystemExit.class, () -> Cli.parse_args("--keep-dirs")));
    }

    /** --list warns about extraction/format options that have no effect in list mode. */
    @Test
    void test_list_ignores_extraction_opts() {
        String err = captureStderr(() -> Cli.parse_args("--list", "--json"));
        assertTrue(err.contains("--list only prints URLs"));
        assertTrue(err.contains("output_format"));
    }

    /** args_to_extractor must propagate fast, images, links, comments, tables to Extractor. */
    @Test
    void test_args_to_extractor_kwargs() {
        Args args = Cli.parse_args("--fast", "--images", "--links", "--no-comments", "--no-tables");
        Extractor options = Settings.args_to_extractor(args);
        assertTrue(options.fast);
        assertTrue(options.images);
        assertTrue(options.links);
        assertFalse(options.comments);
        assertFalse(options.tables);
    }

    /** Result of a child JVM running the CLI main class. */
    record Run(int returncode, String out) {
    }

    /** {@code subprocess.run(["trafilatura", *args], input=...)}: the main class in a child JVM. */
    static Run runCli(byte[] input, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "trafilatura.trafilatura.Cli"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (var stdin = process.getOutputStream()) {
            if (input != null) {
                stdin.write(input);
            }
        }
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS));
        return new Run(process.exitValue(), out);
    }

    /** test arguments and main CLI entrypoint */
    @Test
    void test_climain() throws IOException, InterruptedException {
        // help display
        assertEquals(0, runCli(null, "--help").returncode());
        // piped input
        byte[] empty_input = b("<html><body><article>" + "<p>ABC</p>".repeat(100) + "</article></body></html>");
        Run result = runCli(empty_input);
        assertEquals(0, result.returncode());
        assertTrue(Py.strip(result.out()).endsWith("ABC"));
        // input directory walking and processing
        assertEquals(0, runCli(null, "--input-dir", RESOURCES_DIR).returncode());
        // compressed file
        byte[] compressed_input = Conftest._resource("webpage.html.gz");
        result = runCli(compressed_input);
        assertEquals(0, result.returncode());
        assertTrue(Py.strip(result.out()).endsWith("in deep-red West Virginia."));
    }

    /** test input type errors */
    @Test
    void test_input_type() {
        Cli.main_argv(List.of("", "-u", "http"));
        Args args = Cli.parse_args("-v");
        byte[] teststring = Conftest.read_bytes(DOCS_DIR.resolve("trafilatura-demo.gif"));
        assertNull(CliUtils.examine(teststring, args));
        // a list is not accepted by the Java signature beyond Object: rejected as wrong data type
        assertNull(CliUtils.examine(List.of(1, 2, 3), args));
        assertNull(CliUtils.examine(Conftest.read_text(DOCS_DIR.resolve("usage.rst")), args));
        // test file list
        int count = CliUtils.generate_filelist(RESOURCES_DIR).size();
        assertTrue(10 <= count && count <= 21, String.valueOf(count));
    }

    /** Input read directly from STDIN when no URL/file/dir is given. */
    @Test
    void test_cli_stdin() {
        Args args = Cli.parse_args("-v");
        byte[] html = b("<html><body><article>" + "<p>Piped paragraph content.</p>".repeat(5) + "</article></body></html>");
        System.setIn(new ByteArrayInputStream(html));
        assertTrue(captureStdout(() -> Cli.process_args(args)).contains("Piped paragraph content."));
    }

    /** examine() swallows extraction errors and reports them on stderr. */
    @Test
    void test_cli_examine_error() {
        Args args = Cli.parse_args("-v");
        CliUtils.EXTRACT = (htmlstring, options) -> {
            throw new RuntimeException("kaboom");
        };
        String html = "<html><body><article><p>" + "content ".repeat(20) + "</p></article></body></html>";
        String err = captureStderr(() -> assertNull(CliUtils.examine(html, args)));
        assertTrue(err.contains("kaboom"));
    }

    /** test command-line output with respect to CLI arguments */
    @Test
    void test_sysoutput() {
        Args args = Cli.parse_args("--csv", "-o", "/root/forbidden/");
        Pair<String, String> output = CliUtils.determine_output_path(args, args.output_dir, "");
        assertTrue(output.first().length() >= 10);
        assertTrue(output.first().endsWith(".csv"));
        assertEquals("/root/forbidden/", output.second());
        assertFalse(CliUtils.check_outputdir_status(args.output_dir));
        args = Cli.parse_args("--xml", "-o", System.getProperty("java.io.tmpdir") + "/you-touch-my-tralala");
        assertTrue(CliUtils.check_outputdir_status(args.output_dir));
        // test fileslug for name
        output = CliUtils.determine_output_path(args, args.output_dir, "", -1, "AAZZ");
        assertTrue(output.first().endsWith("AAZZ.xml"));
        // test json output
        Args args2 = args;
        args2.xml = false;
        args2.json = true;
        args2 = Cli.map_args(args2);
        Pair<String, String> output2 = CliUtils.determine_output_path(args, args.output_dir, "", -1, "AAZZ");
        assertTrue(output2.first().endsWith("AAZZ.json"));
        assertTrue(output2.second().contains("you-touch-my-tralala"));
        // test directory counter
        assertEquals("testdir/1", CliUtils.determine_counter_dir("testdir", 0));
        // test file writing
        args = Cli.parse_args("--markdown", "-o", "/dev/null/", "-b", "/dev/null/");
        String result = "DADIDA";
        CliUtils.write_result(result, args);
        args.output_dir = System.getProperty("java.io.tmpdir");
        args.backup_dir = null;
        CliUtils.write_result(result, args);
        // process with backup directory and no counter
        Extractor options = Settings.args_to_extractor(args);
        assertEquals("markdown", options.format);
        assertTrue(options.formatting);
        assertEquals(-1, CliUtils.process_result("DADIDA", args, -1, options));

        // with counter
        String teststring = readResource("httpbin_sample.html");
        assertEquals(2, CliUtils.process_result(teststring, args, 1, options));

        // test keeping dir structure
        args = Cli.parse_args("-i", "myinputdir/", "-o", "test/", "--keep-dirs");
        output = CliUtils.determine_output_path(args, "testfile.txt", "");
        assertEquals("test/testfile.txt", output.first());
        // test hash as output file name
        assertTrue(args.keep_dirs);
        args.keep_dirs = false;
        output = CliUtils.determine_output_path(args, "testfile.txt", "");
        assertEquals("test/uOHdo6wKo4IK0pkL.txt", output.first());
    }

    /** test page download and command-line interface */
    @Test
    void test_download() {
        assertEquals(0, CliUtils._define_exit_code(List.of(), 0));
        assertEquals(126, CliUtils._define_exit_code(List.of("a"), 1));
        assertEquals(1, CliUtils._define_exit_code(List.of("a"), 2));

        Args args = Cli.parse_args("-v");
        captureStderr(() -> {
            assertNull(CliUtils.examine(null, args));
            assertNull(CliUtils.examine(" ", args));
            assertNull(CliUtils.examine("0".repeat((int) 10e7), args));
        });
        String url = "https://httpbun.com/html";
        String teststring = fetch_url(url);
        assertNotNull(teststring);
        assertNotNull(CliUtils.examine(teststring, args, url, null));
        // test exit code for faulty URLs
        Args faulty = Cli.parse_args("-u", "https://1234.yz/");
        SystemExit e = assertThrows(SystemExit.class, () -> Cli.process_args(faulty));
        assertEquals(126, e.code);
    }

    /** test command-line processing pipeline */
    @Test
    void test_cli_pipeline() {
        // test URL listing
        Args args = Cli.parse_args("--list");
        assertEquals(0, CliUtils.url_processing_pipeline(args, new UrlStore()));

        // test inputlist + blacklist
        args = Cli.parse_args("-i", Path.of(RESOURCES_DIR, "list-process.txt").toString());
        List<String> my_urls = CliUtils.load_input_urls(args);
        assertNotNull(my_urls);
        assertEquals(3, my_urls.size());
        args = Cli.parse_args("-i", Path.of(RESOURCES_DIR, "list-process.txt").toString(),
                "--blacklist", Path.of(RESOURCES_DIR, "list-discard.txt").toString(), "--archived");
        assertNotNull(args.blacklist);
        // a file name, not a set: no filtering (as in Python, where the string is iterated)
        UrlStore url_store = add_to_compressed_dict(my_urls, args.blacklist_set(), null, null, false, false);
        CliUtils.url_processing_pipeline(args, url_store);
        // test blacklist and empty dict
        args.blacklist = CliUtils.load_blacklist((String) args.blacklist);
        assertEquals(3, args.blacklist_set().size());
        url_store = add_to_compressed_dict(my_urls, args.blacklist_set(), null, null, false, false);
        CliUtils.url_processing_pipeline(args, url_store);
        // test backup
        args = Cli.parse_args("--backup-dir", System.getProperty("java.io.tmpdir"));
        CliUtils.archive_html("00Test", args);
        // test date-based exclusion
        String teststring = readResource("httpbin_sample.html");
        args = Cli.parse_args("--output-format", "xml", "--only-with-metadata");
        assertNull(CliUtils.examine(teststring, args));
        args = Cli.parse_args("--output-format", "xml", "--only-with-metadata", "--precision");
        assertNull(CliUtils.examine(teststring, args));
        // test JSON output
        args = Cli.parse_args("--output-format", "json", "--recall");
        assertNotNull(CliUtils.examine(teststring, args));
        // sitemaps: tested in --explore
        Args sitemapArgs = Cli.parse_args("--sitemap", "https://sitemaps.org/sitemap.xml", "--list", "--parallel", "1");
        String out = captureStdout(() -> Cli.process_args(sitemapArgs));
        assertTrue(Py.strip(out).endsWith("https://www.sitemaps.org/zh_TW/terms.html"), out);
        // CLI options
        args = Cli.parse_args("--links", "--images");
        teststring = readResource("http_sample.html");
        String result = CliUtils.examine(teststring, args);
        assertTrue(result.contains("[link](testlink.html)"));
        assertTrue(result.contains("test.jpg"));
        // HTML format as option
        args = Cli.parse_args("--html");
        result = CliUtils.examine(teststring, args);
        assertTrue(result.startsWith("<html"));
        assertTrue(result.endsWith("</html>"));
    }

    /** Test file processing pipeline on actual directories. */
    @Test
    void test_file_processing() {
        Settings.MAX_FILES_PER_DIRECTORY = 0;

        // dry-run file processing pipeline
        Args args = Cli.parse_args("--parallel", "1", "--input-dir", "/dev/null");
        captureStdout(() -> {
            CliUtils.file_processing_pipeline(args);
            // file processing pipeline on resources/
            args.input_dir = RESOURCES_DIR;
            CliUtils.file_processing_pipeline(args);
            // test manually
            for (String f : CliUtils.generate_filelist(args.input_dir)) {
                CliUtils.file_processing(f, args, -1, null);
            }
            Extractor options = Settings.args_to_extractor(args);
            args.output_dir = "/dev/null";
            for (String f : CliUtils.generate_filelist(args.input_dir)) {
                CliUtils.file_processing(f, args, -1, options);
            }
        });
    }

    /** Test if the configuration file is loaded correctly from the CLI. */
    @Test
    void test_cli_config_file() {
        Args args = Cli.parse_args("--input-dir", "/dev/null", "--config-file", "newsettings.cfg");
        String teststring = readResource("httpbin_sample.html");
        args.config_file = Path.of(RESOURCES_DIR, args.config_file).toString();
        Extractor options = Settings.args_to_extractor(args);
        assertNull(CliUtils.examine(teststring, args, null, options));
    }

    /** test internal functions to filter urls */
    @Test
    void test_input_filtering() {
        Args args = Cli.parse_args();

        // load dictionary
        args.input_file = Path.of(RESOURCES_DIR, "list-process.txt").toString();
        UrlStore url_store = CliUtils.load_input_dict(args);
        assertEquals(3, url_store.find_known_urls("https://httpbin.org").size());
        args.blacklist = Set.of("httpbin.org/status/404");
        url_store = CliUtils.load_input_dict(args);
        assertEquals(2, url_store.find_known_urls("https://httpbin.org").size());

        // deduplication and filtering
        List<String> inputlist = List.of("https://example.org/1", "https://example.org/2", "https://example.org/2",
                "https://example.org/3", "https://example.org/4", "https://example.org/5", "https://example.org/6");
        args.blacklist = Set.of("example.org/1", "example.org/3", "example.org/5");
        url_store = add_to_compressed_dict(inputlist, args.blacklist_set(), null, null, false, false);
        assertEquals(List.of("https://example.org/2", "https://example.org/4", "https://example.org/6"),
                url_store.find_known_urls("https://example.org"));

        // URL in blacklist
        List<String> my_urls = CliUtils.load_input_urls(args);
        Set<String> my_blacklist = CliUtils.load_blacklist(Path.of(RESOURCES_DIR, "list-discard.txt").toString());
        url_store = add_to_compressed_dict(my_urls, my_blacklist, null, null, false, false);
        assertEquals(0, url_store.dump_urls().size());
        // other method
        args.blacklist = CliUtils.load_blacklist(Path.of(RESOURCES_DIR, "list-discard.txt").toString());
        url_store = CliUtils.load_input_dict(args);
        assertEquals(0, url_store.dump_urls().size());

        // URL filter
        my_urls = CliUtils.load_input_urls(args);
        url_store = add_to_compressed_dict(my_urls, null, List.of("status"), null, false, false);
        assertEquals(1, url_store.get_known_domains().size());
        url_store = add_to_compressed_dict(my_urls, null, List.of("teststring"), null, false, false);
        assertEquals(0, url_store.get_known_domains().size());
        url_store = add_to_compressed_dict(my_urls, null, List.of("status", "teststring"), null, false, false);
        assertEquals(1, url_store.get_known_domains().size());

        // malformed URLs
        url_store = add_to_compressed_dict(List.of("123345", "https://www.example.org/1"));
        assertEquals(1, url_store.get_known_domains().size());

        // double URLs
        args.input_file = Path.of(RESOURCES_DIR, "redundant-urls.txt").toString();
        my_urls = CliUtils.load_input_urls(args);
        url_store = add_to_compressed_dict(my_urls);
        assertEquals(1, url_store.find_known_urls("https://example.org").size());

        // filter before exploration
        UrlStore input_store = add_to_compressed_dict(List.of("https://example.org/1", "https://sitemaps.org/test"));
        List<String> input_urls = List.of("https://example.org", "http://sitemaps.org/", "https://test.info/");
        url_store = CliUtils.build_exploration_dict(input_store, input_urls, args);
        assertEquals(List.of("https://test.info"), url_store.get_known_domains());
    }

    /** Test crawling and exploration functions. */
    @Test
    void test_crawling() {
        CliUtils.cli_crawler(Cli.parse_args("--crawl", ""));
        CliUtils.cli_crawler(Cli.parse_args("--crawl", " "));

        Args args = Cli.parse_args("--crawl", "https://httpbun.com/html");
        Args crawlArgs = args;
        assertEquals("https://httpbun.com/html\n", captureStdout(() -> Cli.process_args(crawlArgs)));

        Spider.URL_STORE = new UrlStore(false, false);
        // links permitted
        Args linkArgs = Cli.parse_args("--crawl", "https://httpbun.com/links/1/1", "--list", "--parallel", "1");
        String out = captureStdout(() -> CliUtils.cli_crawler(linkArgs));
        // possibly a bug on Github actions, should be 2 URLs
        assertTrue(List.of("https://httpbun.com/links/1/1\nhttps://httpbun.com/links/1/0\n",
                "https://httpbun.com/links/1/1\n").contains(out), out);
        Spider.URL_STORE = new UrlStore(false, false);
        // 0 links permitted
        linkArgs.crawl = "https://httpbun.com/links/4/4";
        out = captureStdout(() -> CliUtils.cli_crawler(linkArgs, 0, null, null));
        // should be 6 (5 URLs as output), possibly a bug on Actions CI/CD
        assertTrue(List.of(2, 6).contains(out.split("\n", -1).length), out);

        // responses from an unregistered base (e.g. cross-host redirect) are skipped
        Spider.URL_STORE = new UrlStore(false, false);
        Response start = fetch_response("https://httpbun.com/links/2/2");
        LinkedList<Pair<String, Response>> extra = new LinkedList<>(List.of(
                Pair.of("https://stray.example/", new Response(b("<html><body><a href='/x'>x</a></body></html>"), 200,
                        "https://stray.example/")),
                Pair.of(start.url, start)));
        CliUtils.ResponseDownloads real_downloads = CliUtils.BUFFERED_RESPONSE_DOWNLOADS;
        CliUtils.BUFFERED_RESPONSE_DOWNLOADS = (bufferlist, threads, options) -> {
            List<Pair<String, Response>> first = new ArrayList<>();
            while (!extra.isEmpty()) {
                first.add(extra.removeLast());
            }
            Iterator<Pair<String, Response>> rest = real_downloads.download(bufferlist, threads, options);
            return new Iterator<>() {
                final Iterator<Pair<String, Response>> head = first.iterator();

                @Override
                public boolean hasNext() {
                    return head.hasNext() || rest.hasNext();
                }

                @Override
                public Pair<String, Response> next() {
                    return head.hasNext() ? head.next() : rest.next();
                }
            };
        };
        Args strayArgs = Cli.parse_args("--crawl", "https://httpbun.com/links/2/2", "--list");
        String err = captureStderr(() -> captureStdout(() -> CliUtils.cli_crawler(strayArgs)));
        assertEquals(1, T.count(err, "no crawl parameters for https://stray.example/"), err);
        assertTrue(Spider.URL_STORE.find_known_urls("https://httpbun.com").contains("https://httpbun.com/links/2/0"));
        assertTrue(Spider.URL_STORE.find_known_urls("https://stray.example").isEmpty());
        CliUtils.BUFFERED_RESPONSE_DOWNLOADS = real_downloads;
        Spider.URL_STORE = new UrlStore(false, false);

        // Exploration (Sitemap + Crawl)
        Args exploreArgs = Cli.parse_args("--explore", "https://httpbun.com/html", "--list");
        assertEquals("https://httpbun.com/html", Py.strip(captureStdout(() -> Cli.process_args(exploreArgs))));
    }

    /** Test webpage probing functions. */
    @Test
    void test_probing() {
        String url = "https://example.org/";
        String conf = Path.of(RESOURCES_DIR, "zerolength.cfg").toString();
        Args args = Cli.parse_args("--probe", url, "--target-language", "de", "--config-file", conf);
        String out = captureStdout(() -> Cli.process_args(args));
        if (Utils.LANGID_FLAG) {
            assertEquals("", Py.strip(out));
            args.target_language = "en";
            assertEquals(url, Py.strip(captureStdout(() -> Cli.process_args(args))));
        } else {
            assertEquals(url, Py.strip(out));
        }
    }

    static boolean exists(String path) {
        return Files.exists(Path.of(path));
    }
}
