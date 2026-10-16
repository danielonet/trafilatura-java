// Java port of docs/docs_tests.py
package io.github.danielonet.trafilatura.docs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Build the documentation with Sphinx to catch broken pages or references (GitHub issue #698).
 * The Sphinx sources live in the Python project: point the system property
 * {@code io.github.danielonet.trafilatura.docs.dir} to its docs directory. Skipped when Sphinx is not installed.
 */
class DocsTests {
    static final Path DOCS = Path.of(System.getProperty("io.github.danielonet.trafilatura.docs.dir", "docs"));

    record Result(int returncode, String stdout, String stderr) {
    }

    static Result run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).start();
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Sphinx build timed out");
        }
        return new Result(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    static boolean sphinxAvailable() {
        try {
            return run(List.of("sphinx-build", "--version")).returncode() == 0 && Files.isRegularFile(DOCS.resolve("conf.py"));
        } catch (IOException | InterruptedException err) {
            return false;
        }
    }

    /** The documentation compiles cleanly (warnings treated as errors). */
    @Test
    void test_docs_build(@TempDir Path tmp_path) throws Exception {
        assumeTrue(sphinxAvailable(), "sphinx-build or the docs directory not available");
        Result result = run(List.of("sphinx-build", "-W", "-b", "html", DOCS.toString(), tmp_path.toString()));
        if (result.returncode() != 0) {
            fail("Sphinx build failed (exit " + result.returncode() + ")\n"
                    + "STDOUT:\n" + result.stdout() + "\nSTDERR:\n" + result.stderr());
        }
        assertTrue(Files.exists(tmp_path.resolve("index.html")));
    }

    /** `.. doctest::`-marked examples run and match their documented output. */
    @Test
    void test_docs_doctest(@TempDir Path tmp_path) throws Exception {
        assumeTrue(sphinxAvailable(), "sphinx-build or the docs directory not available");
        Result result = run(List.of("sphinx-build", "-b", "doctest", DOCS.toString(), tmp_path.toString()));
        if (result.returncode() != 0) {
            fail("Doctest build failed (exit " + result.returncode() + ")\n"
                    + "STDOUT:\n" + result.stdout() + "\nSTDERR:\n" + result.stderr());
        }
    }
}
