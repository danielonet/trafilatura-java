"""Score the outputs of Evaluate.java and run.py with upstream's eval_common, and compare Java with Python.

Writes <results>/results.csv and results.md (accuracy and speed per tool), parity.md (how often
Java and Python produce the same text) and f1.svg (the README chart).

Usage: python score.py <corpus tests dir> <results dir>
"""

import csv
import difflib
import json
import os
import platform
import sys
from html import escape

TESTS = os.path.abspath(sys.argv[1])
RESULTS = os.path.abspath(sys.argv[2])
sys.path.insert(0, TESTS)

from eval_common import ConfusionMatrix, count_item, load_evaldata, norm, validate  # noqa: E402

from trafilatura.utils import decode_file  # noqa: E402

# Java mode -> the Python mode it ports
PAIRS = {
    "trafilatura-java fast": "trafilatura fast",
    "trafilatura-java": "trafilatura",
    "trafilatura-java precision": "trafilatura precision",
    "trafilatura-java recall": "trafilatura recall",
}


def read_timing():
    rows = {}
    for lang in ("java", "python"):
        with open(os.path.join(RESULTS, f"{lang}-timing.csv"), encoding="utf-8") as f:
            for row in csv.DictReader(f):
                row["language"] = lang
                rows[row["tool"]] = row
    return rows


def output_file(row):
    name = row["tool"].replace("trafilatura-java", "trafilatura") if row["language"] == "java" else row["tool"]
    return os.path.join(RESULTS, "outputs", f"{row['language']}-{name.replace(' ', '-')}.jsonl")


def read_outputs(path):
    with open(path, encoding="utf-8") as f:
        return {line["url"]: line for line in map(json.loads, f)}


def score(evaldata, outputs):
    cm = ConfusionMatrix()
    per_page = {}
    for url, item in evaldata.items():
        counts = count_item(item, outputs[url]["text"])
        per_page[url] = counts
        cm.add(counts)
    return cm, per_page


def similarity(a, b):
    "Word-level similarity of two outputs, 1.0 when identical."
    if a == b:
        return 1.0
    wa, wb = (a or "").split(), (b or "").split()
    return difflib.SequenceMatcher(None, wa, wb, autojunk=False).ratio() if wa or wb else 1.0


def main():
    evaldata = load_evaldata(TESTS)
    validate(evaldata)
    timing = read_timing()
    outputs = {tool: read_outputs(output_file(row)) for tool, row in timing.items()}

    table = []
    per_page = {}
    for tool, row in timing.items():
        cm, per_page[tool] = score(evaldata, outputs[tool])
        precision, recall, accuracy, f1 = cm.scores()
        empty = sum(1 for line in outputs[tool].values() if not line["text"])
        table.append({"tool": tool, "language": row["language"], "library": row["library"], "version": row["version"],
                      "precision": precision, "recall": recall, "accuracy": accuracy, "f1": f1,
                      "pages_per_second": float(row["pages_per_second"]),
                      "alloc_mean_kb": row["alloc_mean_kb"], "alloc_p95_kb": row["alloc_p95_kb"],
                      "errors": int(row["errors"]), "empty": empty})

    # ceilings, as in upstream evaluate.py: the whole page, and nothing at all
    for name, fn in (("everything (whole page)", decode_file), ("nothing", lambda html: "")):
        cm = ConfusionMatrix()
        for item in evaldata.values():
            path = next(p for p in (os.path.join(TESTS, d, item["file"]) for d in ("cache", "eval")) if os.path.isfile(p))
            with open(path, "rb") as f:
                cm.add(count_item(item, fn(f.read())))
        precision, recall, accuracy, f1 = cm.scores()
        table.append({"tool": name, "language": "-", "library": "-", "version": "-", "precision": precision,
                      "recall": recall, "accuracy": accuracy, "f1": f1, "pages_per_second": "", "alloc_mean_kb": "",
                      "alloc_p95_kb": "", "errors": "", "empty": ""})

    table.sort(key=lambda r: -r["f1"])
    write_results(table, len(evaldata))
    write_parity(evaldata, outputs, per_page)
    write_chart([r for r in table if r["language"] != "-" and "precision" not in r["tool"] and "recall" not in r["tool"]])


def write_results(table, pages):
    columns = ["tool", "language", "library", "version", "precision", "recall", "accuracy", "f1",
               "pages_per_second", "alloc_mean_kb", "alloc_p95_kb", "errors", "empty"]
    with open(os.path.join(RESULTS, "results.csv"), "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, columns)
        writer.writeheader()
        for row in table:
            writer.writerow({k: (f"{v:.4f}" if isinstance(v, float) and k not in ("pages_per_second",) else v)
                             for k, v in row.items()})
    lines = [f"Corpus: trafilatura's evaluation set, {pages} pages. Single thread, after warm-up. "
             f"Python {platform.python_version()}.", "",
             "| Tool | Language | Version | Precision | Recall | F1 | Pages/s | Errors |",
             "| --- | --- | --- | --- | --- | --- | --- | --- |"]
    for r in table:
        speed = f"{r['pages_per_second']:.1f}" if r["pages_per_second"] != "" else ""
        lines.append(f"| {r['tool']} | {r['language']} | {r['version']} | {r['precision']:.3f} | {r['recall']:.3f} "
                     f"| **{r['f1']:.3f}** | {speed} | {r['errors']} |")
    with open(os.path.join(RESULTS, "results.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


def write_parity(evaldata, outputs, per_page):
    lines = ["| Mode | Identical output | Same after whitespace normalization | Mean word similarity "
             "| Pages Java scores lower | Pages Java scores higher |", "| --- | --- | --- | --- | --- | --- |"]
    worst = []
    for java, python in PAIRS.items():
        if java not in outputs or python not in outputs:
            continue
        same = same_norm = 0
        sims = []
        lower = higher = 0
        for url in evaldata:
            a, b = outputs[java][url]["text"], outputs[python][url]["text"]
            same += a == b
            same_norm += norm(a or "") == norm(b or "")
            sim = similarity(a, b)
            sims.append(sim)
            # a page "scores lower" when Java finds fewer wanted chunks or keeps more unwanted ones
            jt, jf, _, _ = per_page[java][url]
            pt, pf, _, _ = per_page[python][url]
            if (jt - jf) < (pt - pf):
                lower += 1
            elif (jt - jf) > (pt - pf):
                higher += 1
            if java == "trafilatura-java":
                worst.append((sim, url, evaldata[url]["file"], len(a or ""), len(b or "")))
        n = len(evaldata)
        lines.append(f"| {python} | {same / n:.1%} | {same_norm / n:.1%} | {sum(sims) / n:.3f} | {lower} | {higher} |")
    worst.sort()
    lines += ["", "Pages where the default modes differ most (word similarity, output length in characters):", "",
              "| Similarity | File | Java chars | Python chars |", "| --- | --- | --- | --- |"]
    for sim, url, file, ja, py in worst[:20]:
        lines.append(f"| {sim:.3f} | [{file}]({url}) | {ja} | {py} |")
    with open(os.path.join(RESULTS, "parity.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines[:6]))


# palette: dataviz reference instance (series-1 blue, series-2 orange, muted gray for the rest)
CHART_STYLE = """
  .surface { fill: #fcfcfb; }
  .label { fill: #0b0b0b; } .muted { fill: #52514e; } .grid { stroke: #e4e3df; }
  .java { fill: #2a78d6; } .python { fill: #eb6834; } .other { fill: #a9a8a3; }
  @media (prefers-color-scheme: dark) {
    .surface { fill: #1a1a19; }
    .label { fill: #f0efec; } .muted { fill: #c3c2b7; } .grid { stroke: #3a3a37; }
    .java { fill: #3987e5; } .python { fill: #d95926; } .other { fill: #74736e; }
  }
  text { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif; font-size: 13px; }
"""


def write_chart(rows):
    "Horizontal bars of F1 per tool, zero baseline, value at the end of each bar."
    rows = sorted(rows, key=lambda r: -r["f1"])
    left, right, top, bar, gap = 210, 60, 58, 22, 10
    width = 720
    plot = width - left - right
    height = top + len(rows) * (bar + gap) + 34
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" width="{width}" height="{height}" '
           f'role="img" aria-label="F1 score by tool on the trafilatura evaluation set">',
           f"<style>{CHART_STYLE}</style>", f'<rect class="surface" width="{width}" height="{height}" rx="6"/>',
           f'<text class="label" x="16" y="24" font-weight="600" font-size="15">F1 on trafilatura\'s evaluation set '
           f'(990 pages, higher is better)</text>']
    # legend
    lx = 16
    for cls, text in (("java", "trafilatura-java"), ("python", "Python trafilatura 2.3.1"), ("other", "other JVM tools and a plain-text floor")):
        out.append(f'<rect class="{cls}" x="{lx}" y="36" width="12" height="12" rx="2"/>'
                   f'<text class="muted" x="{lx + 18}" y="46">{escape(text)}</text>')
        lx += 34 + len(text) * 6.5
    for tick in (0, 0.25, 0.5, 0.75, 1.0):
        x = left + plot * tick
        out.append(f'<line class="grid" x1="{x:.1f}" x2="{x:.1f}" y1="{top - 4}" y2="{height - 28}" stroke-width="1"/>'
                   f'<text class="muted" x="{x:.1f}" y="{height - 12}" text-anchor="middle">{tick:g}</text>')
    for i, r in enumerate(rows):
        y = top + i * (bar + gap)
        cls = "java" if r["tool"].startswith("trafilatura-java") else "python" if r["language"] == "python" else "other"
        name = r["tool"] if r["language"] == "java" else f"{r['tool']} (Python)"
        if r["tool"] == "jsoup text":
            name = "jsoup text (no removal)"
        w = plot * r["f1"]
        # bars anchored at the baseline, rounded at the data end only
        out.append(f'<text class="label" x="{left - 10}" y="{y + bar / 2 + 4.5}" text-anchor="end">{escape(name)}</text>'
                   f'<path class="{cls}" d="M{left},{y} h{w - 4:.1f} a4,4 0 0 1 4,4 v{bar - 8} a4,4 0 0 1 -4,4 '
                   f'h{-(w - 4):.1f} z"/>'
                   f'<text class="label" x="{left + w + 6:.1f}" y="{y + bar / 2 + 4.5}">{r["f1"]:.3f}</text>')
    out.append("</svg>")
    with open(os.path.join(RESULTS, "f1.svg"), "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
