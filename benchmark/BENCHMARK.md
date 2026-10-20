# Benchmark

How accurate and how fast trafilatura-java is, compared with Python trafilatura and with
the article extractors already available on the JVM. Everything here is produced by
[`run.sh`](run.sh); the numbers below are copied from [`results/`](results/).

## Results

trafilatura-java scores **F1 0.923** on trafilatura's 990-page evaluation set, against
0.926 for Python trafilatura 2.3.1, and is **1.3× faster** on one thread on a current
server CPU (55.1 vs 42.8 pages/s), 1.5× on a 2012 laptop. It is ahead of the article
extractors already on the JVM: Readability4J scores 0.886 and boilerpipe 0.851. Measured
on 2026-10-20 (machines under [Environment](#environment)); the accuracy results are
identical on both machines.

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

Single thread, one timed pass over all 990 pages after warm-up, on a GitHub Actions
runner (AMD EPYC 9V74) and on a 2012 laptop (Intel i5-3317U):

| Tool | Mode | Pages/s, EPYC | Pages/s, laptop | Java ÷ Python, EPYC / laptop | Allocated per page (mean / p95) |
| --- | --- | --- | --- | --- | --- |
| **trafilatura-java** | fast | **62.7** | **32.5** | 1.10× / 1.25× | 10.6 MB / 27.4 MB |
| Python trafilatura 2.3.1 | fast | 57.2 | 26.1 | | |
| **trafilatura-java** | default | **55.1** | **29.8** | 1.29× / 1.54× | 12.9 MB / 32.8 MB |
| Python trafilatura 2.3.1 | default | 42.8 | 19.3 | | |
| **trafilatura-java** | favor_recall | 66.4 | 38.1 | 1.04× / 1.32× | 9.8 MB / 24.0 MB |
| Python trafilatura 2.3.1 | favor_recall | 64.0 | 28.9 | | |
| Readability4J 1.0.8 | | 113.5 | 57.3 | | 5.4 MB / 14.5 MB |
| boilerpipe 1.2.3 | | 223.3 | 102.5 | | 4.4 MB / 10.1 MB |
| jsoup text | | 628.7 | 311.6 | | 1.5 MB / 4.2 MB |

Java's lead is largest in default mode, where both languages run the fallback
extractors, and small in `fast` mode. Part of the default-mode lead is not like for like:
Java's jusText fallback is a stub, so it skips work that Python does. `fast` mode, which
runs no fallback, is the cleanest comparison of the two implementations. A shared cloud runner is a noisier place to time
things than a dedicated machine, so treat the EPYC column as indicative. Raw files:
[`results/java-timing.csv`](results/java-timing.csv) and [`results/ci-epyc/`](results/ci-epyc/).

Threads (JMH on the laptop, pages/s):

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

Laptop (accuracy, single-thread speed, JMH):

| | |
| --- | --- |
| Date | 2026-10-20 |
| CPU | Intel(R) Core(TM) i5-3317U CPU @ 1.70GHz (4 threads) |
| OS | Linux 7.0.0-38-generic |
| JDK | openjdk version "21.0.12.1" 2026-08-18 |
| Python | Python 3.12.3, trafilatura 2.3.1 |
| trafilatura-java | 0.1.1-SNAPSHOT (commit 260465d) |

GitHub Actions runner (accuracy, single-thread speed), from the `benchmark` workflow:

| | |
| --- | --- |
| Date | 2026-10-20 |
| CPU | AMD EPYC 9V74 80-Core Processor (4 threads) |
| OS | Linux 6.17.0-1022-azure |
| JDK | openjdk version "21.0.12.1" 2026-08-18 LTS |
| Python | Python 3.12.15, trafilatura 2.3.1 |
| trafilatura-java | 0.1.1-SNAPSHOT (commit c6fcc69) |
