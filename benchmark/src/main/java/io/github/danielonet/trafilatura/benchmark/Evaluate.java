package io.github.danielonet.trafilatura.benchmark;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Runs every Java extractor over the corpus and writes, per tool, one JSON line per page
 * (url, text, error) to outputs/, plus single-thread timings and allocations to java-timing.csv.
 * Scoring happens in python/score.py with upstream's own eval_common, so both languages are
 * scored by the same code.
 *
 * Usage: Evaluate <corpus tests dir> <results dir> [tool...]
 */
public final class Evaluate {
    private Evaluate() {
    }

    public static void main(String[] args) throws IOException {
        Corpus corpus = new Corpus(Path.of(args[0]));
        Path results = Path.of(args[1]);
        Files.createDirectories(results.resolve("outputs"));
        List<String> names = args.length > 2 ? Arrays.asList(args).subList(2, args.length) : List.copyOf(Tools.ALL.keySet());
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        ObjectMapper json = new ObjectMapper();

        try (PrintWriter timing = new PrintWriter(Files.newBufferedWriter(results.resolve("java-timing.csv"), StandardCharsets.UTF_8))) {
            timing.println("tool,library,version,pages,seconds,pages_per_second,alloc_mean_kb,alloc_p95_kb,errors");
            for (String name : names) {
                Tools.Tool tool = Tools.ALL.get(name);
                // untimed pass: JIT warm-up and one-time initialisation
                for (Corpus.Page page : corpus.pages) {
                    run(tool, page.html());
                }
                // the timed pass must not reuse results from the warm-up (python/run.py does the same)
                io.github.danielonet.trafilatura.Meta.reset_caches();
                long[] alloc = new long[corpus.pages.size()];
                long nanos = 0;
                int errors = 0;
                Path out = results.resolve("outputs").resolve(slug(name) + ".jsonl");
                try (Writer writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                    for (int i = 0; i < corpus.pages.size(); i++) {
                        Corpus.Page page = corpus.pages.get(i);
                        long allocBefore = threads.getCurrentThreadAllocatedBytes();
                        long start = System.nanoTime();
                        Object result = run(tool, page.html());
                        nanos += System.nanoTime() - start;
                        alloc[i] = threads.getCurrentThreadAllocatedBytes() - allocBefore;
                        Map<String, Object> line = new LinkedHashMap<>();
                        line.put("url", page.url());
                        if (result instanceof Throwable err) {
                            errors++;
                            line.put("text", null);
                            line.put("error", err.toString());
                        } else {
                            line.put("text", result);
                        }
                        writer.write(json.writeValueAsString(line));
                        writer.write('\n');
                    }
                }
                Arrays.sort(alloc);
                double seconds = nanos / 1e9;
                timing.printf(Locale.ROOT, "%s,%s,%s,%d,%.3f,%.2f,%.0f,%.0f,%d%n", name, tool.library(), tool.version(),
                        alloc.length, seconds, alloc.length / seconds,
                        Arrays.stream(alloc).average().orElse(0) / 1024, alloc[(int) (alloc.length * 0.95)] / 1024.0, errors);
                timing.flush();
                System.out.printf(Locale.ROOT, "%-28s %7.1f pages/s  %d errors%n", name, alloc.length / seconds, errors);
            }
        }
    }

    // a crash counts as a miss, as in upstream's run_and_count
    static Object run(Tools.Tool tool, byte[] html) {
        try {
            return tool.extract().apply(html);
        } catch (RuntimeException | StackOverflowError err) {
            return err;
        }
    }

    static String slug(String name) {
        return "java-" + name.replace("trafilatura-java", "trafilatura").replace(' ', '-');
    }
}
