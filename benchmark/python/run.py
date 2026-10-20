"""Run Python trafilatura over the evaluation corpus, mirroring Evaluate.java.

Writes one JSON line per page (url, text, error) per mode to <results>/outputs/ and
single-thread timings to <results>/python-timing.csv. Scoring is done by score.py.

Usage: python run.py <corpus tests dir> <results dir>
"""

import csv
import json
import os
import sys
import time
from functools import partial
from importlib.metadata import version

TESTS = os.path.abspath(sys.argv[1])
RESULTS = os.path.abspath(sys.argv[2])
sys.path.insert(0, TESTS)

from eval_common import EXTRACT_OPTS, load_evaldata, read_corpus  # noqa: E402

from trafilatura import extract  # noqa: E402
from trafilatura.meta import reset_caches  # noqa: E402

# the same four configurations as Tools.java
RUNNERS = {
    "trafilatura fast": partial(extract, fast=True, **EXTRACT_OPTS),
    "trafilatura": partial(extract, fast=False, **EXTRACT_OPTS),
    "trafilatura precision": partial(extract, fast=False, favor_precision=True, **EXTRACT_OPTS),
    "trafilatura recall": partial(extract, fast=False, favor_recall=True, **EXTRACT_OPTS),
}

WARMUP_PAGES = 20


def slug(name):
    return "python-" + name.replace(" ", "-")


def main():
    docs = read_corpus(TESTS, load_evaldata(TESTS))
    os.makedirs(os.path.join(RESULTS, "outputs"), exist_ok=True)
    trafilatura_version = version("trafilatura")
    with open(os.path.join(RESULTS, "python-timing.csv"), "w", newline="", encoding="utf-8") as f:
        timing = csv.writer(f)
        timing.writerow(["tool", "library", "version", "pages", "seconds", "pages_per_second",
                         "alloc_mean_kb", "alloc_p95_kb", "errors"])
        for name, fn in RUNNERS.items():
            # no JIT to warm up: a few pages pay the imports and lazy initialisation
            for _, htmlbinary in list(docs.values())[:WARMUP_PAGES]:
                fn(htmlbinary)
            reset_caches()
            seconds, errors = 0.0, 0
            with open(os.path.join(RESULTS, "outputs", slug(name) + ".jsonl"), "w", encoding="utf-8") as out:
                for url, (_, htmlbinary) in docs.items():
                    line = {"url": url}
                    start = time.perf_counter()
                    try:
                        line["text"] = fn(htmlbinary)
                    except Exception as err:  # a crash counts as a miss, as in eval_common
                        line["text"], line["error"] = None, f"{type(err).__name__}: {err}"
                        errors += 1
                    seconds += time.perf_counter() - start
                    out.write(json.dumps(line, ensure_ascii=False) + "\n")
            timing.writerow([name, "trafilatura", trafilatura_version, len(docs), f"{seconds:.3f}",
                             f"{len(docs) / seconds:.2f}", "", "", errors])
            f.flush()
            print(f"{name:<28} {len(docs) / seconds:7.1f} pages/s  {errors} errors")


if __name__ == "__main__":
    main()
