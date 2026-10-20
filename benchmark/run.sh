#!/usr/bin/env bash
# Reproduces benchmark/results/: accuracy and parity on trafilatura's evaluation corpus, then JMH throughput.
#   ./run.sh            everything (about an hour on a 4-thread laptop)
#   ./run.sh accuracy   accuracy and parity only
#   ./run.sh speed      JMH only
# Needs JDK 21, git and Python 3.10+.
set -euo pipefail
cd "$(dirname "$0")"
WHAT=${1:-all}
CORPUS=data/trafilatura-v2.3.1/tests
RESULTS=results
VERSION=$(sed -n '/<artifactId>trafilatura-java<\/artifactId>/{n;s:.*<version>\(.*\)</version>.*:\1:p;q}' ../pom.xml)
JAVA="java -Xmx2g -Dtrafilatura.version=$VERSION"

./fetch-corpus.sh

if [ ! -x python/.venv/bin/python ]; then
    python3 -m venv python/.venv
    python/.venv/bin/python -m pip install -q -r python/requirements.txt
fi

echo "building trafilatura-java $VERSION and the benchmark"
(cd .. && ./mvnw -q install -DskipTests)
../mvnw -q package -Dtrafilatura.version="$VERSION"
mkdir -p "$RESULTS"

{
    echo "| | |"
    echo "| --- | --- |"
    echo "| Date | $(date -u +%Y-%m-%d) |"
    echo "| CPU | $(lscpu 2>/dev/null | sed -n 's/^Model name: *//p' || sysctl -n machdep.cpu.brand_string) ($(nproc 2>/dev/null || sysctl -n hw.ncpu) threads) |"
    echo "| OS | $(uname -sr) |"
    echo "| JDK | $(java -version 2>&1 | head -1) |"
    echo "| Python | $(python/.venv/bin/python --version 2>&1), trafilatura $(python/.venv/bin/python -c 'import trafilatura; print(trafilatura.__version__)') |"
    echo "| trafilatura-java | $VERSION (commit $(git rev-parse --short HEAD)) |"
} > "$RESULTS/environment.md"

if [ "$WHAT" = all ] || [ "$WHAT" = accuracy ]; then
    $JAVA -cp target/benchmarks.jar io.github.danielonet.trafilatura.benchmark.Evaluate "$CORPUS" "$RESULTS"
    python/.venv/bin/python python/run.py "$CORPUS" "$RESULTS"
    python/.venv/bin/python python/score.py "$CORPUS" "$RESULTS"
fi

if [ "$WHAT" = all ] || [ "$WHAT" = speed ]; then
    THREADS=$(nproc 2>/dev/null || sysctl -n hw.ncpu)
    for t in 1 "$THREADS"; do
        $JAVA -jar target/benchmarks.jar Speed -t "$t" -p corpus="$CORPUS" -rf csv -rff "$RESULTS/jmh-${t}threads.csv"
    done
fi
