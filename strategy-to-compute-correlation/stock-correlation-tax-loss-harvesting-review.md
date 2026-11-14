# Objective Review: Correlation-Based Stock Substitution for Tax-Loss Harvesting

## Executive conclusion

The proposed method is a reasonable candidate-generation system, but it is not sufficient by itself to implement reliable direct indexing or tax-loss harvesting.

Its strongest idea is to use historical return relationships to find replacement securities. Its weakest assumption is that a highly correlated stock will preserve the portfolio's economic exposure after the original stock is sold. Correlation is descriptive, unstable, and incomplete: it does not capture beta, sector concentration, factor exposure, valuation, liquidity, or idiosyncratic risk.

The recommended architecture is therefore:

1. Use correlation and factor similarity to generate a short list of candidates.
2. Filter candidates through tax, liquidity, and security-specific rules.
3. Select replacements with a portfolio-level tracking-error optimizer.
4. Evaluate the entire process with walk-forward, transaction-cost-aware backtests.

The result should be called a **substitute-security ranking system**, not a wash-sale avoidance system. Tax eligibility requires separate legal and operational controls.

## What the original method gets right

### 1. It uses returns rather than price levels

Correlation of price levels is often misleading because two unrelated stocks can trend upward together. Correlation of split- and dividend-adjusted returns is the appropriate starting point.

### 2. It recognizes that multiple time horizons are useful

Daily data over one, three, and five years can reveal both recent and longer-term relationships. Rolling windows are more informative than one full-period correlation because they expose instability.

### 3. It separates candidate discovery from execution data

Using daily data for substitution research and higher-frequency data for execution and risk monitoring is sensible. Five-minute data is usually too noisy to be the primary signal for a tax-loss-harvesting substitute.

### 4. It proposes matrix computation rather than nested loops

For roughly 500 stocks, an exact daily correlation matrix is computationally easy. If `R` is a standardized return matrix with shape `T x N`, the correlation matrix can be computed approximately as:

```text
C = R.T @ R / (T - 1)
```

Optimized linear algebra is preferable to Python-level pairwise loops.

### 5. It identifies look-ahead bias as a risk

Historical relationships must be estimated using only information available before each simulated trade. A full-history correlation matrix cannot be used to evaluate historical decisions without contaminating the test.

## Main weaknesses and failure modes

### 1. Correlation is not equivalence

Two stocks can have a correlation of 0.90 and still produce materially different portfolio outcomes. Correlation measures co-movement, not:

- equal volatility;
- equal market beta;
- equal sector or industry exposure;
- equal size, value, growth, or momentum exposure;
- equal dividend behavior;
- equal tail risk;
- equal sensitivity to rates, commodities, currencies, or specific events.

A replacement can therefore reduce wash-sale risk while introducing a large unintended tracking error.

### 2. High correlation can be caused by a common factor

Two stocks may look interchangeable only because both move with the S&P 500 or a sector ETF. Their residual, company-specific returns may be weakly related.

The system should compare both raw returns and residual returns after controlling for market, sector, and other relevant factors. Residual correlation should not necessarily be maximized—some market exposure is desirable—but it should be measured and reported.

### 3. Historical correlation is not reliably predictive

Correlation is regime-dependent. Relationships can break after:

- earnings surprises;
- mergers or spin-offs;
- changes in business mix;
- regulatory decisions;
- commodity-price shocks;
- interest-rate changes;
- index additions or deletions;
- sector rotations.

Five years of history can make a relationship look stable even when its economic basis has disappeared. Conversely, a short-term relationship can be temporary and overfit.

### 4. The method optimizes pair similarity, not portfolio similarity

Replacing stock A with stock B should be evaluated in the context of the complete portfolio. A pair that looks good in isolation may create excessive exposure when combined with the investor's existing holdings.

For example, replacing a technology stock with another technology stock may preserve broad market exposure but unintentionally increase the portfolio's concentration in a particular industry or factor.

### 5. Equal-dollar replacement is not necessarily risk-preserving

Purchasing the same dollar amount of B as the proceeds from A does not guarantee the same market sensitivity. A beta-adjusted notional can be a useful approximation:

```text
notional_B = notional_A * beta_A / beta_B
```

However, beta adjustment alone is insufficient because beta is estimated with error and does not preserve sector, factor, or idiosyncratic exposures.

### 6. Pairwise ranking creates a multiple-testing problem

With 500 stocks there are approximately 125,000 unordered pairs. Some pairs will appear exceptionally correlated by chance, especially when many windows, frequencies, and thresholds are tested.

The system should require economic plausibility, persistence across non-overlapping periods, and out-of-sample validation. Otherwise, the top-ranked pairs may be statistical coincidences.

### 7. The S&P 500 universe can introduce survivorship bias

If the data contains only today's S&P 500 constituents, historical tests omit companies that later left the index, failed, were acquired, or declined substantially. This can materially overstate the quality of the strategy.

Backtests should use point-in-time index membership and include delisted securities where possible.

### 8. Price and corporate-action data quality can dominate the result

The data must correctly handle:

- splits and reverse splits;
- cash and special dividends;
- spin-offs;
- mergers and ticker changes;
- delistings;
- stale or missing prices;
- different trading halts;
- time-zone and holiday mismatches.

Unadjusted prices can create artificial returns. Forward-filled prices can create artificial correlations.

### 9. Intraday correlation has special problems

Hourly and five-minute observations are not automatically better. They are affected by:

- non-synchronous trading;
- bid-ask bounce;
- price discreteness;
- opening and closing auction effects;
- stale quotes;
- different liquidity profiles;
- market microstructure noise.

Intraday data is best used for execution cost, slippage, liquidity, and short-horizon risk analysis unless it has been carefully cleaned and sampled.

## Correlation versus alternative similarity measures

Correlation should remain in the system, but it should not be the only measure.

### Pearson correlation

Useful for linear co-movement and easy to compute. Sensitive to outliers and regime changes.

### Spearman correlation

More robust to nonlinear monotonic relationships and outliers. It can be useful as a secondary diagnostic, but it does not solve the substitution problem.

### Beta and tracking error

Usually more directly relevant than correlation for preserving portfolio behavior. A candidate with slightly lower correlation but similar beta and lower tracking error may be preferable.

### Factor exposure similarity

Compare exposures to market, sector, industry, size, value, momentum, profitability, investment, and volatility factors. This is often more interpretable and more stable than raw pairwise correlation.

### Cointegration

Cointegration concerns long-run price relationships rather than short-run return similarity. It is not automatically appropriate for tax-loss harvesting and can be unstable. It should be treated as an optional diagnostic, not the default selection criterion.

### Covariance and portfolio-level tracking error

For a real portfolio, the most relevant question is often: “Which replacement minimizes the expected change in portfolio risk?” This requires the portfolio covariance matrix and should be handled by an optimizer rather than a standalone pair score.

## Recommended candidate score

A practical ranking score could combine:

```text
score(A, B) =
    w1 * rolling_return_similarity
  + w2 * factor_exposure_similarity
  + w3 * residual_correlation
  - w4 * expected_tracking_error
  - w5 * transaction_cost
  - w6 * liquidity_penalty
  - w7 * instability_penalty
```

The weights should be selected using a validation period, not chosen solely because they improve in-sample results.

Useful minimum conditions include:

```text
minimum observations       >= threshold
one-year correlation       >= threshold
three-year correlation     >= threshold
rolling-correlation median >= threshold
rolling-correlation spread <= threshold
tracking error              <= threshold
liquidity                   >= threshold
tax eligibility             = approved
```

The thresholds should be calibrated to the intended holding period and the investor's tolerance for tracking error. A universal value such as 0.85 is not intrinsically correct.

## Better system architecture

### Stage 1: Data layer

Store cleaned, point-in-time, corporate-action-aware observations in a columnar format such as Parquet. Keep security identifiers, ticker history, sector history, index membership, and trading status separate from price data.

### Stage 2: Feature layer

Generate daily returns, rolling volatility, beta, factor exposures, liquidity measures, drawdown behavior, and transaction-cost estimates. Use hourly and five-minute data mainly for execution-related features.

### Stage 3: Candidate generation

Use matrix correlation and factor-distance calculations to produce the top 10–20 candidates per stock. This is fast enough for a 500-stock universe without approximate nearest-neighbor infrastructure.

### Stage 4: Candidate validation

Require stability across rolling windows, non-overlapping subperiods, market regimes, and out-of-sample data. Reject candidates whose relationship depends on one unusual period.

### Stage 5: Tax and operational filter

Apply an explicit approved/restricted-security table. Track purchases in all relevant accounts, including accounts that may be outside the trading engine.

### Stage 6: Portfolio optimizer

Select the replacement or replacement basket that minimizes portfolio-level tracking error subject to:

- tax-loss amount and lot constraints;
- transaction costs;
- liquidity limits;
- position limits;
- sector and factor constraints;
- restricted-security rules;
- minimum holding or turnover rules.

### Stage 7: Monitoring

After a substitution, monitor realized tracking error and invalidate the relationship if it deteriorates. Candidate rankings should be refreshed periodically rather than treated as permanent mappings.

## Backtest requirements

A credible backtest should include:

1. Point-in-time universe membership.
2. Only information available at the simulated decision time.
3. Realistic bid-ask spreads, commissions, fees, and market impact.
4. Tax lots, holding periods, and loss eligibility.
5. A 30-day replacement-monitoring window and longer-term tracking results.
6. Delisted and acquired securities.
7. Comparison against simple baselines:
   - no harvesting;
   - same-sector replacement;
   - industry ETF replacement;
   - factor-matched replacement;
   - the proposed correlation-only method.

The relevant output is not just tax loss harvested. It should include:

- after-tax benefit;
- realized tracking error;
- turnover;
- transaction costs;
- slippage;
- concentration changes;
- factor exposure changes;
- frequency of rejected or failed substitutions;
- performance after the replacement is eventually sold.

## Tax and wash-sale boundary

The IRS wash-sale rule generally concerns buying substantially identical stock or securities within 30 days before or after a loss sale. IRS guidance states that securities of different corporations are ordinarily not substantially identical, but the conclusion depends on the facts and circumstances. The rule can also involve spouses, controlled corporations, and IRA or Roth IRA purchases. Correlation is not a legal safe harbor.

Source: [IRS Publication 550, Investment Income and Expenses](https://www.irs.gov/publications/p550).

The software should therefore treat tax eligibility as a separately governed input. A tax adviser or written compliance policy should define restricted securities and account-monitoring requirements. This document is a technical review, not tax advice.

## Final assessment

The original method is technically feasible and a reasonable first prototype, especially for a 500-stock daily universe. The exact correlation calculation is not the difficult part. The difficult parts are:

- preventing look-ahead and survivorship bias;
- distinguishing real economic similarity from common-factor correlation;
- controlling tracking error at the whole-portfolio level;
- modeling trading costs and liquidity;
- handling corporate actions and security identity;
- maintaining tax and account-level controls.

The best development path is to implement the correlation engine first, but treat it only as candidate generation. The production decision should come from a factor-aware, portfolio-level optimizer with walk-forward validation and separately approved tax rules.
