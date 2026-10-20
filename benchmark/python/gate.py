"""Fail when trafilatura-java's F1 drops below the floors in floors.json (as upstream's eval_gate.py does).

Usage: python gate.py <results dir>
"""

import csv
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def main(results):
    with open(os.path.join(HERE, "..", "floors.json"), encoding="utf-8") as f:
        floors = {k: v for k, v in json.load(f).items() if not k.startswith("_")}
    with open(os.path.join(results, "results.csv"), encoding="utf-8") as f:
        measured = {row["tool"]: float(row["f1"]) for row in csv.DictReader(f)}
    failed = False
    for tool, floor in floors.items():
        f1 = measured[tool]
        status = "ok" if f1 >= floor else "REGRESSION"
        failed |= f1 < floor
        print(f"{tool:>24}: F1={f1:.4f} (floor {floor:.4f}) {status}")
    return int(failed)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
