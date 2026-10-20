# Benchmark

How accurate and how fast trafilatura-java is, compared with Python trafilatura and with
the article extractors already available on the JVM. Everything here is produced by
[`run.sh`](run.sh); the numbers below are copied from [`results/`](results/).

## Results

trafilatura-java scores **F1 0.923** on trafilatura's 990-page evaluation set, against
0.926 for Python trafilatura 2.3.1, and is **1.5× faster** on one thread (29.8 vs 19.3
pages/s). It is ahead of the article extractors already on the JVM: Readability4J
scores 0.886 and boilerpipe 0.851. Measured on 2026-10-20 on a 2012 laptop (details under
[Environment](#environment)); absolute speeds are low, the ratios are what carries over.

![F1 by tool on trafilatura's evaluation set](results/f1.svg)

### Accuracy

| Tool | Mode | Precision | Recall | F1 |
| --- | --- | --- | --- | --- |
| Python trafilatura 2.3.1 | default | 0.907 | 0.946 | **0.926** |
| **trafilatura-java** | default | 0.908 | 0.939 | **0.923** |
| Python trafilatura 2.3.1 | favor_precision | 0.924 | 0.921 | 0.922 |
| **trafilatura-java** | fast | 0.909 | 0.934 | **0.921** |
| Python trafilatura 2.3.1 | fast | 0.907 | 0.934 | 0.920 |
| Python trafilatura 2.3.1 | favor_recall | 0.898 | 0.943 | 0.920 |
| **trafilatura-java** | favor_precision | 0.925 | 0.915 | 0.920 |
| **trafilatura-java** | favor_recall | 0.899 | 0.940 | 0.919 |
| Readability4J 1.0.8 | | 0.868 | 0.905 | 0.886 |
| boilerpipe 1.2.3 | ArticleExtractor | 0.846 | 0.856 | 0.851 |
| jsoup 1.18.3, whole page as text | | 0.532 | 0.991 | 0.692 |
| Raw HTML (upstream's "everything" row) | | 0.528 | 0.906 | 0.667 |

The Python rows reproduce the F1 values upstream pins for v2.3.1 in
[`tests/eval_baseline.json`](https://github.com/adbar/trafilatura/blob/v2.3.1/tests/eval_baseline.json)
(0.9204 fast, 0.9261 default), which confirms the setup measures the same thing upstream
does. Readability4J crashed on 1 page (`topCandidate.parent() must not be null`), scored as
a miss; no other tool raised an error. All rows: [`results/results.csv`](results/results.csv).

### Speed

Single thread, one timed pass over all 990 pages after warm-up:

| Tool | Mode | Pages/s | Allocated per page (mean / p95) |
| --- | --- | --- | --- |
| **trafilatura-java** | fast | **32.5** | 10.6 MB / 27.4 MB |
| Python trafilatura 2.3.1 | fast | 26.1 | |
| **trafilatura-java** | default | **29.8** | 12.9 MB / 32.8 MB |
| Python trafilatura 2.3.1 | default | 19.3 | |
| **trafilatura-java** | favor_recall | 38.1 | 9.8 MB / 24.0 MB |
| Python trafilatura 2.3.1 | favor_recall | 28.9 | |
| Readability4J 1.0.8 | | 57.3 | 5.4 MB / 14.5 MB |
| boilerpipe 1.2.3 | | 102.5 | 4.4 MB / 10.1 MB |
| jsoup text | | 311.6 | 1.5 MB / 4.2 MB |

Threads (JMH, pages/s):

| Tool | 1 thread | 4 threads |
| --- | --- | --- |
| trafilatura-java fast | 31.4 | 63.6 |
| trafilatura-java | 29.2 | 52.4 |
| Readability4J | 61.0 | 118.8 |
| boilerpipe | 119.8 | 247.5 |
| jsoup text | 314.0 | 701.8 |

The test CPU has 2 physical cores with hyper-threading, so about 2× from 4 threads is the
ceiling there; all tools scale alike. JMH's 99.9% confidence intervals are wide
([`results/jmh-4threads.csv`](results/jmh-4threads.csv)) because each 20 s iteration covers a
different slice of the corpus and page costs vary by two orders of magnitude; the
single-pass numbers above, which cover every page once, agree with the 1-thread JMH run
within 4% for trafilatura-java (15% for boilerpipe). Python trafilatura's threads share the GIL, so the way to scale it is one
process per core.

### Parity with Python

How often trafilatura-java returns exactly what Python trafilatura returns for the same page:

| Mode | Identical output | Identical after whitespace normalization | Mean word similarity | Pages where Java scores lower / higher |
| --- | --- | --- | --- | --- |
| fast | 96.4% | 96.9% | 0.996 | 4 / 4 |
| default | 93.0% | 93.5% | 0.978 | 19 / 11 |
| favor_precision | 94.7% | 95.1% | 0.985 | 14 / 8 |
| favor_recall | 95.8% | 96.3% | 0.996 | 8 / 5 |

"Scores lower" means Java finds fewer of the page's wanted snippets or keeps more unwanted
ones than Python does on that page.

In default mode, the outputs differ on 69 pages:

- **35 differ only in the fallback stage**: both languages give the same `fast` output, then
  Python's fallback extractors change it and Java's do not, or less. On 23 of them Python's
  fallback replaced the result while Java's left it as it was, and Java's text is the shorter
  one on 28. This is the jusText stub: Python falls back to jusText on thin extractions, the
  Java port cannot yet.
- **34 already differ in fast mode**: HTML parsing (jsoup builds HTML5 trees, libxml2 does
  not repair the same way) and the simplified courlan and htmldate.

The 20 pages that differ most are listed with links in [`results/parity.md`](results/parity.md).
Porting jusText is the single change that would close most of the gap in default mode.

### Environment

| | |
| --- | --- |
| Date | 2026-10-20 |
| CPU | Intel(R) Core(TM) i5-3317U CPU @ 1.70GHz (4 threads) |
| OS | Linux 7.0.0-38-generic |
| JDK | openjdk version "21.0.12.1" 2026-08-18 |
| Python | Python 3.12.3, trafilatura 2.3.1 |
| trafilatura-java | 0.1.1-SNAPSHOT (commit 260465d) |

## Method

### Corpus and scoring

The corpus is trafilatura's own evaluation set at the ported version (v2.3.1): 990 web
pages in many languages and layouts (news, blogs, forums, shops, institutional sites),
listed in upstream's [`tests/evaldata.json`](https://github.com/adbar/trafilatura/blob/v2.3.1/tests/evaldata.json).
For each page, annotators picked up to six text snippets that belong to the main content
("with") and up to six that are boilerplate ("without").

Scoring is upstream's, unchanged: [`python/score.py`](python/score.py) imports
`count_item` and `ConfusionMatrix` from upstream's
[`tests/eval_common.py`](https://github.com/adbar/trafilatura/blob/v2.3.1/tests/eval_common.py).
After whitespace normalization, a "with" snippet found in the output is a true positive and
a "without" snippet found in it is a false positive. Precision, recall and F1 are computed
over all snippets of all pages. A tool that crashes on a page scores that page as a full
miss. Java and Python outputs go through the same scoring code, so the matching rules
cannot differ between them.

The pages are not committed here: they belong to their sites. `fetch-corpus.sh` downloads
them from the upstream repository.

### Tools and settings

| Tool | Language | How it is called |
| --- | --- | --- |
| trafilatura-java | Java | `Core.extract(bytes, ...)` with upstream's benchmark settings: no comments, tables included, no formatting. Four modes: `fast`, default (with fallbacks), `favor_precision`, `favor_recall`. |
| trafilatura 2.3.1 | Python | `trafilatura.extract(bytes, ...)` with the same settings and modes ([`python/run.py`](python/run.py)). |
| boilerpipe 1.2.3 | Java | `ArticleExtractor.INSTANCE.getText(html)`, the extractor upstream's own benchmark uses for boilerpipe. This is the `com.robbypond` release of boilerpipe 1.2.0 on Maven Central; the original `de.l3s` 1.1.0 dates from 2010. |
| Readability4J 1.0.8 | Kotlin/JVM | `new Readability4J(url, html).parse().getTextContent()`, a port of Mozilla Readability. |
| jsoup 1.18.3 text | Java | `Jsoup.parse(html).text()`: the whole page as text, a floor with no boilerplate removal. |

trafilatura (both languages) receives the raw bytes and detects the encoding itself, as in
upstream's benchmark. The other tools receive the page decoded by trafilatura-java's
`decode_file`, the equivalent of upstream's setup.

Crux (`com.chimbori.crux`) was planned but left out: its current version, 5.1.0, extracts
metadata (title, favicon, canonical URL), and none of its default plugins fills the
article text.

### Speed

- **Single thread** (the Pages/s column): [`Evaluate.java`](src/main/java/io/github/danielonet/trafilatura/benchmark/Evaluate.java)
  makes one untimed pass over the corpus to warm up the JIT, clears trafilatura's caches,
  then times a second pass. Python makes a short warm-up (it has no JIT), clears its
  `lru_cache`s with `reset_caches()`, then times one pass. Only the extraction call is
  timed, not the scoring.
- **Several threads**: [`Speed.java`](src/main/java/io/github/danielonet/trafilatura/benchmark/Speed.java)
  is a JMH benchmark (2 × 20 s warm-up, 3 × 20 s measurement, one fork, `-Xmx2g`), run with
  1 thread and with one thread per CPU. Python trafilatura is not run multi-threaded: the
  GIL keeps it at single-thread speed, and scaling it means one process per core.
- **Memory**: bytes allocated per page on the extracting thread
  (`ThreadMXBean.getThreadAllocatedBytes`). This measures garbage produced, not peak heap.

### Reproducing

```bash
cd benchmark
./run.sh            # corpus download, Python venv, accuracy, parity, JMH
./run.sh accuracy   # accuracy and parity only
```

It needs JDK 21, git and Python 3.10+. Per-page outputs land in `results/outputs/`
(not committed) for anyone who wants to look at a specific page.
