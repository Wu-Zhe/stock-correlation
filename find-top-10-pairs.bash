SP500="$(sed '1d' stock-extractor/input/sp500_tickers.txt | tr '\n' ' ')"
JAR="target/stock-correlation.jar"
OUT="/tmp/stock-correlation-windows"

mkdir -p "$OUT"

while read -r window bars; do
  java -jar "$JAR" \
    --data-dir /Users/zhewu/data/stocks/20261109 \
    --tickers "$SP500" \
    --analysis-rows "$bars" \
    --min-overlap 21 \
    --top-k 1000 \
    --sample-rows 1 \
    > "$OUT/all_${window}.log"

  echo
  echo "===== Top 10 correlation pairs: ${window} ====="

  awk '
    /^Candidates for / {
      source=$3
      sub(/:$/, "", source)
      next
    }

    /^  [a-z0-9-]+[[:space:]]+overlap=/ {
      candidate=$1
      correlation=$4

      if (source < candidate) key=source "|" candidate
      else key=candidate "|" source

      if (!(key in seen)) {
        seen[key]=1
        print source, candidate, correlation
      }
    }
  ' "$OUT/all_${window}.log" \
    | sort -k3,3nr \
    | head -10

done <<'WINDOWS'
1m 22
1q 64
6m 127
12m 253
WINDOWS
