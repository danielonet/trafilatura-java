package io.github.danielonet.trafilatura.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** trafilatura's evaluation corpus: tests/evaldata.json plus the pages in tests/cache and tests/eval. */
final class Corpus {
    record Page(String url, String file, byte[] html) {
    }

    final List<Page> pages = new ArrayList<>();

    Corpus(Path testsDir) {
        try {
            Map<String, Map<String, Object>> evaldata = new ObjectMapper().readValue(
                    testsDir.resolve("evaldata.json").toFile(), new TypeReference<TreeMap<String, Map<String, Object>>>() { });
            for (Map.Entry<String, Map<String, Object>> entry : evaldata.entrySet()) {
                String file = (String) entry.getValue().get("file");
                pages.add(new Page(entry.getKey(), file, Files.readAllBytes(resolve(testsDir, file))));
            }
        } catch (IOException err) {
            throw new UncheckedIOException(err);
        }
    }

    // eval_common.resolve(): cache/ first, then eval/
    static Path resolve(Path testsDir, String file) {
        for (String subdir : List.of("cache", "eval")) {
            Path path = testsDir.resolve(subdir).resolve(file);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new IllegalStateException("HTML file not found: " + file);
    }
}
