# Stock correlation / replacement engine

Dependency-free Java 17 implementation of the unified strategy in `strategy-to-compute-correlation/unified-strategy.md`.

The program:

- streams only requested securities from the daily/hourly/five-minute ZIP files;
- samples the first 100 rows from each ZIP by default;
- computes daily close-to-close log returns;
- ranks replacement candidates by EWMA tracking error with a 21-day tail gate;
- blocks common same-issuer share-class pairs;
- fits a long-only fully invested basket for the selected target;
- keeps hourly and five-minute data out of the core replacement signal.

## Compile and run

From the repository root:

```bash
mkdir -p /tmp/stock-correlation-out
javac -d /tmp/stock-correlation-out \
  src/main/java/com/zhewu/stockcorrelation/StockCorrelationApp.java

java -cp /tmp/stock-correlation-out \
  com.zhewu.stockcorrelation.StockCorrelationApp \
  --data-dir /Users/zhewu/data/stocks/20261109 \
  --tickers AAPL,MSFT,GOOG \
  --target AAPL
```

`--analysis-rows 0` reads all selected daily history. Set it to a positive value for a bounded smoke test. `--sample-rows 100` controls the input sample displayed for each of the three ZIP files.

This is a research implementation. A live system still needs point-in-time index membership, issuer metadata, transaction-cost calibration, lot-level cross-account wash-sale controls, factor constraints, and walk-forward validation.
