#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR=$(cd "$(dirname "$0")/.." && pwd)
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
command -v javac >/dev/null || { echo "Instala un JDK completo o define JAVA_HOME." >&2; exit 1; }
OUT_DIR="$PROJECT_DIR/build/unit-tests"
MAIN_DIR="$PROJECT_DIR/app/src/main/java/es/inhouse/batterycheck"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
javac -encoding UTF-8 --release 17 -d "$OUT_DIR" \
  "$MAIN_DIR/Sample.java" "$MAIN_DIR/Analytics.java" \
  "$MAIN_DIR/AdvancedReport.java" "$MAIN_DIR/UsageTimeline.java" \
  "$PROJECT_DIR/tests/AnalyticsTest.java" \
  "$PROJECT_DIR/tests/AdvancedReportTest.java" \
  "$PROJECT_DIR/tests/UsageTimelineTest.java"
for TEST_CLASS in AnalyticsTest AdvancedReportTest UsageTimelineTest; do
  java -cp "$OUT_DIR" "es.inhouse.batterycheck.$TEST_CLASS"
done
