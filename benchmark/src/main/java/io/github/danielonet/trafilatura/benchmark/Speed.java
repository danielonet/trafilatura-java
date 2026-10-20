package io.github.danielonet.trafilatura.benchmark;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Throughput in pages per second over the whole corpus. One operation is one page; each thread
 * walks the corpus from its own starting point so threads don't extract the same page at once.
 * Run with -t 1 for single-threaded numbers and -t N for N threads, and -p corpus=... for the corpus.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 20)
@Measurement(iterations = 3, time = 20)
@Fork(value = 1, jvmArgs = {"-Xmx2g"})
public class Speed {
    @Param({"trafilatura-java fast", "trafilatura-java", "boilerpipe", "readability4j", "jsoup text"})
    public String tool;

    @Param("data/trafilatura-v2.3.1/tests")
    public String corpus;

    List<Corpus.Page> pages;
    Tools.Tool extractor;

    @Setup(Level.Trial)
    public void setup() {
        pages = new Corpus(Path.of(corpus)).pages;
        extractor = Tools.ALL.get(tool);
    }

    @State(Scope.Thread)
    public static class Cursor {
        int next = (int) (Thread.currentThread().threadId() * 7919);
    }

    @Benchmark
    public void extract(Cursor cursor, Blackhole sink) {
        Corpus.Page page = pages.get(Math.floorMod(cursor.next++, pages.size()));
        sink.consume(Evaluate.run(extractor, page.html()));
    }
}
