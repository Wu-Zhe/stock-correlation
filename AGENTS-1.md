# AGENTS.md (revision 1)

Revision of `AGENTS.md` that separates what the code does today from the target design. Where the two files disagree, trust this one for current behavior and `AGENTS.md` for intent.

## Mission

Build a fast, explainable stock-replacement engine for tax-loss-harvesting research.

Target pipeline: daily data finds risk. Pair TE finds candidates. Portfolio optimizer chooses replacement. Tax ledger guards trade. Hourly and 5-minute data help execution; they do not drive the core signal.

Today the CLI covers the first two steps plus a single-target basket fit. The tax ledger exists as a library class but the CLI never calls it. See "Current implementation" and "Known gaps".

This is research software. It is not tax, legal, or investment advice, and it must not place live trades.

## Project map

```text
.
├── pom.xml                          Root Maven project
├── src/main/java/...                Correlation/replacement engine (single file: StockCorrelationApp.java)
├── src/test/java/...                JUnit 5 tests
├── src/README.md                    Engine usage and design notes
├── strategy-to-compute-correlation/ Design reviews and unified strategy (UNTRACKED in git; may be absent in a clone)
├── stock-extractor/                 Separate legacy ZIP extraction Maven project
└── target/                          Generated Maven output; never commit
```

The root project builds `StockCorrelationApp`. `stock-extractor` has its own `pom.xml`; do not mix its source or output with the root engine.

## Stack and build

- Java release: 17.
- Runtime dependencies: JDK only.
- Test framework: JUnit 5.
- Coverage: JaCoCo, gate at 90% line and 90% instruction coverage.

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

Implemented:

- Stream ZIP entries and read only requested tickers. The whole market ZIP is never extracted. Each selected entry is read fully into memory.
- Normalize tickers case-insensitively; `BRK.B` and `brk-b` both map to `brk-b`.
- Skip headers, blank rows, and malformed rows without discarding a whole security.
- Reject non-finite or non-positive prices.
- Sort bars by timestamp. For duplicate timestamps the last close wins.
- `--analysis-rows N` keeps the newest N bars after sorting. `0` keeps all history.
- Use pairwise-complete observations with a minimum overlap (`--min-overlap`, at least 21). No global `dropna()`.

Not implemented yet:

- Adjusted-price handling. The engine uses the close column as given; corporate actions in raw data can create fake returns.
- Point-in-time constituents, issuer identity, sectors, sub-industries, index weights, liquidity, and delisted names.

Repository hygiene:

- Do not commit market ZIPs, extracted bulk data, or generated reports.
- Known violation: about 500 extracted `stock-extractor/sp500daily/*.us.txt` files are already tracked. Do not add more. Removing them from git is the owner's decision.
- `.gitignore` excludes `*.zip`, `stock-extractor/output/`, `stock-extractor/sp500hourly/`, and `stock-extractor/target/`. Never run `git add .` without checking `git status` first.

## Quant rules

### Current implementation

1. Daily close-to-close log returns are the core input.
2. Pair statistics use EWMA weights; default half-life is 126 trading days (`--half-life`).
3. No shrinkage is applied. Covariance, correlation, and TE use raw EWMA moments.
4. Dollar-for-dollar tracking error:

   ```text
   TE(A,B) = sqrt(var(A) + var(B) - 2 * cov(A,B))
   ```

5. Candidates are ranked by a blended score, not TE alone:

   ```text
   score = TE + 0.25 * tail95Abs21Day * sqrt(252 / 21)
   ```

   Ties break on TE, then ticker. The 21-day tail divergence is a penalty, not a hard gate. Pairs with too few aligned returns for a 21-day tail estimate are skipped.
6. No liquidity, factor-distance, or stability checks exist.
7. Group prior: `RiskEngine.rank` accepts one group level (same group first, then everything else). The CLI always passes an empty group map, so no group prior applies in CLI runs.
8. Compliance blocked pairs are excluded from candidates. Defaults: GOOG/GOOGL, FOX/FOXA, NWS/NWSA, BRK.A/BRK.B. Add more with `--blocked-pair A:B`.
9. Basket: for the `--target` ticker only, the CLI fits a long-only, fully invested basket over its top candidates (at most 5) with projected gradient descent (5000 iterations max). Weights below 1e-5 are dropped, so the basket can end with a single name.
10. Each source is ranked on its own. Simultaneous harvests are not evaluated together.
11. Hourly and five-minute ZIPs are sampled for input validation only.

### Target design (not implemented)

- Shrinkage of covariance/correlation.
- Hard 21-day tail, liquidity, factor-distance, and stability gates.
- Sub-industry, then industry, then sector search, fed by real classification data. Group is a prior, not an absolute wall.
- Single-stock replacement only for small positions with acceptable portfolio impact; a 2–5 name basket or portfolio optimizer for material positions. Needs a position-size input.
- Joint evaluation of simultaneous harvests.
- Hourly and five-minute data for spread, volume, slippage, volatility regime, stress, and TCA only.
- Do not use cointegration or five-minute lead/lag as the selection objective.

### Hardcoded thresholds (known debt)

The rule is to keep thresholds in `Config` and test their effect. These are still hardcoded in `StockCorrelationApp.java`: tail weight `0.25`, tail window `TAIL_WINDOW_DAYS = 21`, basket size `5`, basket iterations `5000`, weight cutoff `1e-5`, convergence tolerance `1e-10`. When you touch one, move it into `Config` with a test. Do not refactor them as a side effect of unrelated changes.

## Tax and compliance rules

### Current implementation

- `TaxLedger` records purchases (optionally automated) and loss sales per account family.
- It checks a 30-day prior window: `canPurchase` after a loss sale, `canHarvest` after a purchase.
- It treats tickers in the Compliance related-pair list as conflicting with each other. The no-arg constructor uses `Compliance.defaultBlockedPairs()`.
- It does not track lot basis, holding period, the 30-day window after a purchase, DRIPs, options, or replacement taint.
- The CLI never builds or checks a `TaxLedger`. It prints a notice saying no tax check was run.

### Rules that always apply

- Statistical similarity does not prove legal tax eligibility.
- Maintain an issuer/legal relationship blacklist independent of price statistics.
- Block same-issuer share classes, ADR/ordinary pairs, parent/subsidiary links, and merger-linked instruments when policy requires.
- Target: an account-family ledger for purchases, loss sales, DRIPs, options, automated trades, and relevant IRA/spouse scope, with lot basis, holding period, loss amount, purchase dates, loss-sale dates, blackouts, and replacement taint.
- Do not use a simplistic "not already held" rule; direct-indexing portfolios may hold most candidate names.
- Do not automatically buy the original security back after the blackout. Re-optimize after costs, gains/losses, and current exposures.
- ETF bridges and same-issuer instruments require explicit tax/compliance approval.
- Never represent the program as guaranteeing wash-sale treatment or tax savings.
- Any change to tax logic must call out its limits and requires professional review before live use.

## Test rules

Every behavior change needs tests. Test both normal and bad data:

- ZIP entry selection, ticker normalization, frequency parsing, and CSV parsing;
- malformed rows, missing files, duplicate entries, quoted fields, and invalid prices;
- newest-row truncation, aligned returns, insufficient overlap, zero variance, EWMA statistics, TE, and tail handling;
- candidate ranking, group fallback, blocked pairs, and missing source securities;
- simplex constraints and basket optimizer convergence/failure;
- tax-ledger purchase/loss-sale windows, related-pair conflicts, and automated activity;
- CLI defaults, valid options, invalid options, and help output;
- an end-to-end run using temporary daily/hourly/five-minute ZIP files.

Use deterministic synthetic prices. Do not make unit tests depend on internet access or the user's market-data directory. Keep tests fast enough for every commit.

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
- Put new configurable thresholds in `Config` and test their effect (see "Hardcoded thresholds").
- Preserve numerical safety: check finite values, clamp tiny negative variances to zero, and handle insufficient observations explicitly.
- Never silently turn missing data into zero returns.
- Keep output deterministic: stable ticker ordering, stable candidate tie-breakers, and reproducible tests.
- Document any change to the TE objective, ranking score, EWMA window, shrinkage, tail handling, optimizer constraints, or tax ledger in `src/README.md` and this file.

## Safety and workflow

- Inspect existing changes before editing. User changes are not yours to erase.
- Do not run destructive commands such as broad recursive deletion, reset, or checkout.
- Do not modify, delete, or overwrite source market data.
- Use temporary directories for generated test fixtures and caches.
- Run tests before reporting completion, and report failures honestly.
- For changes touching tax logic, call out the limitation and require professional review before live use.
