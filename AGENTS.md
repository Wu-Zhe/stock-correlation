# AGENTS.md

## Mission

Build a fast, explainable stock-replacement engine for tax-loss-harvesting research.

Simple rule: daily data finds risk. Pair TE finds candidates. Portfolio optimizer chooses replacement. Tax ledger guards trade. Hourly and 5-minute data help execution; they do not drive the core signal.

This is research software. It is not tax, legal, or investment advice, and it must not place live trades.

## Project map

```text
.
├── pom.xml                         Root Maven project
├── src/main/java/...               Current correlation/replacement engine
├── src/test/java/...               JUnit 5 tests
├── src/README.md                   Current engine usage and design notes
├── strategy-to-compute-correlation/ Design reviews and unified strategy
├── stock-extractor/                Separate legacy ZIP extraction Maven project
└── target/                         Generated Maven output; never commit
```

The root project builds `StockCorrelationApp`. `stock-extractor` has its own `pom.xml`; do not mix its source or output with the root engine.

## Stack and build

- Java release: 17.
- Runtime dependencies: JDK only.
- Test framework: JUnit 5.
- Coverage: JaCoCo.
- Coverage gate: at least 90% line coverage and 90% instruction coverage.

Run from the repository root:

```bash
mvn -B -ntp verify
```

This compiles, runs tests, builds the executable JAR, generates JaCoCo output, and fails below the coverage gate.

Useful commands:

```bash
mvn -B -ntp test
mvn -B -ntp package
java -jar target/stock-correlation.jar \
  --data-dir /Users/zhewu/data/stocks/20261109 \
  --tickers AAPL,MSFT,GOOG \
  --target AAPL
```

Coverage report: `target/site/jacoco/index.html`.

If the local Maven cache has a lock problem, use a temporary cache:

```bash
mvn -B -ntp -Dmaven.repo.local=/tmp/stock-correlation-m2 verify
```

## Data rules

Expected ZIP inputs:

```text
~/data/stocks/YYYYMMDD/d_us_txt.zip   daily
~/data/stocks/YYYYMMDD/h_us_txt.zip   hourly
~/data/stocks/YYYYMMDD/5_us_txt.zip   five-minute
```

Each text file uses columns like:

```text
<TICKER>,<PER>,<DATE>,<TIME>,<OPEN>,<HIGH>,<LOW>,<CLOSE>,<VOL>,<OPENINT>
```

Rules:

- Stream ZIP entries. Read only requested tickers; never extract a whole market ZIP by default.
- Normalize ticker names case-insensitively; map `BRK.B` and `brk-b` consistently.
- Skip headers, blank rows, and malformed rows without discarding a whole security.
- Reject non-finite or non-positive prices.
- Sort bars by timestamp and use the last close for duplicate timestamps.
- Use adjusted prices when available. Corporate actions must not create fake returns.
- Do not use global `dropna()` across the universe. Use pairwise-complete observations and a minimum overlap.
- Keep point-in-time constituents, issuer identity, sectors, sub-industries, index weights, liquidity, and delisted names when production data is added.
- Do not commit market ZIPs, extracted bulk data, or generated reports.

## Quant rules

The current algorithm follows these rules:

1. Daily close-to-close log returns are the core input.
2. Estimate daily risk with EWMA covariance; default half-life is 126 trading days.
3. Stabilize covariance/correlation with shrinkage.
4. Rank candidates by dollar-for-dollar tracking error, not correlation alone:

   ```text
   TE(A,B) = sqrt(var(A) + var(B) - 2 * cov(A,B))
   ```

5. Apply 21-day tail-divergence, liquidity, factor-distance, and stability checks.
6. Search same sub-industry first, then industry, then sector. Group is a prior, not an absolute wall.
7. Use single-stock replacement only for small positions with acceptable portfolio impact.
8. Use a long-only 2–5 name basket or portfolio optimizer for material positions.
9. Evaluate simultaneous harvests together. Good individual swaps can make a bad combined portfolio.
10. Use hourly and five-minute data for spread, volume, slippage, volatility regime, stress, and TCA only.
11. Do not use cointegration or five-minute lead/lag as the direct-indexing selection objective.

## Tax and compliance rules

- Statistical similarity does not prove legal tax eligibility.
- Maintain an issuer/legal relationship blacklist independent of price statistics.
- Block same-issuer share classes, ADR/ordinary pairs, parent/subsidiary links, and merger-linked instruments when policy requires.
- Use an account-family ledger for purchases, loss sales, DRIPs, options, automated trades, and relevant IRA/spouse scope.
- Track lot basis, holding period, loss amount, purchase dates, loss-sale dates, blackouts, and replacement taint.
- Do not use a simplistic “not already held” rule; direct-indexing portfolios may hold most candidate names.
- Do not automatically buy the original security back after the blackout. Re-optimize after costs, gains/losses, and current exposures.
- ETF bridges and same-issuer instruments require explicit tax/compliance approval.
- Never represent the program as guaranteeing wash-sale treatment or tax savings.

## Test rules

Every behavior change needs tests. Test both normal and bad data:

- ZIP entry selection, ticker normalization, frequency parsing, and CSV parsing;
- malformed rows, missing files, duplicate entries, quoted fields, and invalid prices;
- aligned returns, insufficient overlap, zero variance, EWMA statistics, TE, and tail gates;
- candidate ranking, group fallback, blocked pairs, and missing source securities;
- simplex constraints and basket optimizer convergence/failure;
- tax-ledger purchase/loss-sale windows and automated activity;
- CLI defaults, valid options, invalid options, and help output;
- an end-to-end run using temporary daily/hourly/five-minute ZIP files.

Use deterministic synthetic prices. Do not make unit tests depend on internet access or the user’s market-data directory. Keep tests fast enough for every commit.

After editing code:

```bash
mvn -B -ntp verify
```

Do not lower or bypass the coverage gate to make a build pass. Add focused tests or simplify untestable code.

## Code rules

- Keep the root engine dependency-free unless a dependency has a clear benefit and is added to `pom.xml` with tests.
- Prefer streaming ZIP I/O and bounded memory for input loading.
- Keep public behavior in `StockCorrelationApp` stable unless the CLI change is documented.
- Keep parsing, risk calculation, candidate selection, tax state, and execution diagnostics conceptually separate.
- Use immutable records for data passed between layers where practical.
- Normalize once at input boundaries; do not scatter ticker conventions through the algorithm.
- Avoid arbitrary thresholds in code. Put configurable thresholds in `Config` and test their effect.
- Preserve numerical safety: check finite values, clamp tiny negative variances to zero, and handle insufficient observations explicitly.
- Never silently turn missing data into zero returns.
- Keep output deterministic: stable ticker ordering, stable candidate tie-breakers, and reproducible tests.
- Document any change to the TE objective, EWMA window, shrinkage, tail gate, optimizer constraints, or tax ledger.

## Safety and workflow

- Inspect existing changes before editing. User changes are not yours to erase.
- Use `apply_patch` for source and documentation edits.
- Do not run destructive commands such as broad recursive deletion, reset, or checkout.
- Do not modify, delete, or overwrite source market data.
- Use temporary directories for generated test fixtures and caches.
- Run tests before reporting completion, and report failures honestly.
- For changes touching tax logic, call out the limitation and require professional review before live use.
