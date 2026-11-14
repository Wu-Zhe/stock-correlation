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

## Maven build, tests, and coverage

```bash
mvn -B -ntp verify
```

The build runs JUnit 5 tests and fails if either line or instruction coverage is below 90%. The HTML report is generated at `target/site/jacoco/index.html`.

## Compile and run manually

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

`--analysis-rows 0` reads all selected daily history. Set it to a positive value to keep only the newest N daily bars per ticker (bars are sorted by timestamp first, so unsorted files still keep the most recent history). `--sample-rows 100` controls the input sample displayed for each of the three ZIP files.

`--min-overlap` must be at least 21, the tail-divergence window. Pairs with fewer aligned returns than that window are skipped as insufficient data rather than scored.

`TaxLedger` treats tickers in the Compliance related-pair list (default: GOOG/GOOGL, FOX/FOXA, NWS/NWSA, BRK.A/BRK.B) as conflicting with each other in the 30-day windows. The CLI does not yet run any tax-ledger check and prints a notice saying so. Tax logic is a research control only and needs professional review before any live use.

This is a research implementation. A live system still needs point-in-time index membership, issuer metadata, transaction-cost calibration, lot-level cross-account wash-sale controls, factor constraints, and walk-forward validation.
