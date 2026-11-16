#!/usr/bin/env bash
# QuickBite on Redis -- compile and run. Needs Java 21+ and a running Redis (docker compose up -d).
#
#   ./run.sh                    start the API server on http://localhost:8080
#   ./run.sh check [A|B|C|D|E]  run the automatic checks (default: all tracks)
#   ./run.sh worker w1          start a kitchen worker named w1   (add --crash to simulate a crash)
set -e
cd "$(dirname "$0")"
rm -rf out
javac -Xlint:none -nowarn -d out src/*.java checks/*.java 2>&1 | grep -v '^Note:' || true
[ -f out/App.class ] || { echo "Compilation failed (see errors above)"; exit 1; }

case "${1:-app}" in
  check)  java -cp out Check "${2:-all}" ;;
  worker) shift; java -cp out KitchenWorker "$@" ;;
  app)    java -cp out App ;;
  *)      echo "usage: ./run.sh [check [A|B|C|D|E] | worker <name> [--crash]]"; exit 1 ;;
esac
