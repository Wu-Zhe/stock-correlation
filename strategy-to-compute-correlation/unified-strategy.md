# Unified Strategy for Tax-Loss-Harvesting Replacements

## Decision

Use correlation only to generate replacement candidates. Make the actual trade decision by minimizing **portfolio-level tracking error and trading cost**, subject to tax-lot, wash-sale, liquidity, factor-exposure, and concentration constraints.

For the S&P 500, exact pairwise computation is cheap. The hard problems are risk estimation, point-in-time data quality, tax-state tracking, transaction costs, and avoiding overfit—not search speed.

## Comparison of the three approaches

| Approach | Best contribution | Main weakness | Unified treatment |
|---|---|---|---|
| Correlation/factor candidate pipeline | Clean data discipline, multi-horizon stability, factor awareness, walk-forward testing | A weighted pair score remains ad hoc and pair-centric | Keep as candidate generation and diagnostics |
| Detailed tracking-error pair method | Direct TE ranking, EWMA/shrinkage, tail gates, position-size awareness, wash-sale ledger, strong baselines | A single peer is still an inefficient portfolio hedge; custom covariance code needs careful validation | Use as the first production-quality candidate layer |
| Multi-tier daily/hourly/5-minute method | Recognizes execution friction and proposes baskets, factor matching, ETF fallback, and TCA | Cointegration and 5-minute lead/lag solve a pairs-trading problem, not direct indexing | Drop cointegration/lead-lag; retain basket optimization and intraday TCA |

The three reviews converge on the same architecture: **daily risk model → approved candidate set → portfolio optimizer → tax/cost gate → monitoring**.

## Unified production strategy

### 1. Define the objective correctly

For a proposed harvest and replacement, minimize:

```text
portfolio tracking error
+ expected spread and market impact
+ concentration / factor-drift penalties
+ unwind risk and cost
- time-value-adjusted tax benefit
```

Correlation, cointegration, and a pair's standalone score are not the final objective.

### 2. Build point-in-time, total-return data

Required inputs:

- split- and dividend-adjusted daily prices;
- point-in-time constituents, index weights, sectors, and sub-industries;
- stable issuer identifiers and corporate-action history;
- delisted securities where available;
- volume/ADV and bid-ask or executable-price estimates;
- lot-level basis, holding period, account, purchase, and sale records.

Compute close-to-close log returns without a global `dropna()`. Use pairwise-complete observations with a minimum history, such as 250 trading days. Keep raw returns for tail-risk tests; robustly clip only the data used for ranking.

### 3. Estimate risk primarily from daily data

Use a daily exponentially weighted covariance matrix with a starting half-life near 126 trading days. Stabilize it with Ledoit-Wolf shrinkage or shrink correlations toward a sub-industry/group target. Validate the half-life and shrinkage out of sample.

Use five years of daily history for context, but weight recent observations more heavily. Estimate:

- covariance and volatility;
- market, sector, and style-factor exposures;
- 21- and 42-day return-difference tails;
- regime and stress sensitivity;
- overnight versus intraday divergence where adjusted open data is reliable.

Do not use cointegration or five-minute lead/lag as selection objectives. Use hourly and five-minute data only for liquidity, spread, volume profile, slippage, recent volatility regime, and stress diagnostics.

### 4. Generate a small approved candidate set

Apply non-statistical exclusions first:

- same issuer, dual share class, ADR/ordinary pair, parent/subsidiary, merger-linked, or otherwise compliance-restricted relationships;
- active wash-sale blackouts and replacement-taint restrictions;
- insufficient history or liquidity.

Search the same sub-industry first, then industry and sector if necessary. This is a prior, not an absolute wall.

Rank all eligible candidates by dollar-for-dollar tracking error from the shrunk covariance matrix:

```text
TE(A,B) = sqrt(var(A) + var(B) - 2 * cov(A,B))
```

Do not pre-filter on correlation; it is already embedded in TE and can reject a lower-volatility, better-matched candidate. Retain roughly 5–10 candidates per stock after:

- unclipped tail-divergence gate;
- liquidity and transaction-cost gate;
- factor-distance and exposure checks;
- rolling/regime stability checks.

The pair table is an explainable fallback and a warm start for the optimizer—not the final trade rule.

### 5. Choose the replacement at portfolio level

Select according to position and portfolio impact:

- **Small position:** use the best approved single peer only if incremental portfolio TE and tail risk are below limits.
- **Material position:** use a constrained basket of 2–5 names.
- **Concentrated or poorly covered position:** use a full portfolio optimizer; consider a sector/index proxy only when separately approved by tax/compliance policy.

Solve a long-only convex problem such as:

```text
minimize    active_weight' * covariance * active_weight
          + transaction_cost
          + factor_drift_penalty
          + concentration_penalty

subject to  proceeds invested within a cash tolerance
            issuer and wash-sale exclusions
            position, sector, factor, turnover, and liquidity limits
            portfolio tracking-error budget
```

The optimizer must evaluate all simultaneous harvests together. Several individually acceptable swaps can create an unacceptable combined active exposure.

### 6. Maintain an independent tax-control layer

The statistical engine must never decide whether two securities are legally “substantially identical.” Maintain a tax/compliance-approved relationship map and a family-level ledger covering relevant taxable accounts, IRAs, spouse/controlled accounts where applicable, DRIPs, options, and automated purchases.

Track at least:

- every lot's basis and holding period;
- purchases and loss sales within the applicable ±30-day window;
- blackout dates;
- disallowed-loss basis adjustments;
- replacement purchases that can block a later harvest of the replacement;
- specific-lot identification and trade settlement state.

Tax treatment is fact-specific. ETF bridges and same-issuer instruments require explicit approval rather than a statistical assumption.

### 7. Harvest only when expected net value is positive

For each lot, require:

```text
time-value-adjusted expected tax benefit
> spread + market impact + fees
  + tracking-risk charge
  + expected unwind cost
  + operational safety margin
```

Model tax-loss harvesting as tax deferral unless the investor's circumstances justify a different terminal assumption. Prioritize lots by expected after-tax value, not raw loss percentage alone.

After the blackout period, do not automatically buy the original stock back. Re-optimize among keeping the replacement, returning to the original, or choosing a new portfolio allocation after accounting for gains/losses, costs, and current exposures.

### 8. Validate with walk-forward loss events

At every simulated trade date, use only information then available and point-in-time membership. Evaluate the same loss events under:

1. no harvest;
2. cash for the blackout period;
3. broad-market proxy;
4. sector/industry proxy;
5. highest-correlation peer;
6. lowest-TE shrunk peer;
7. constrained 2–5-name basket;
8. full portfolio optimizer.

Primary metrics:

- net after-tax benefit after spread, impact, and basis-reset effects;
- portfolio-level predicted and realized TE;
- realized/predicted TE calibration over 21 and 42 trading days;
- 95th/99th percentile cumulative return divergence;
- coverage and rejection rate;
- turnover, liquidity usage, and concentration/factor drift;
- wash-sale or ledger-control failures, with a target of zero.

Tune on one period and preserve a final untouched test period. Evaluate loss-triggered and stress events separately because relationships can change exactly when harvesting occurs.

## Operating cadence

- Recompute the daily covariance, factors, and candidate table weekly or after material events.
- Update tax lots, blackouts, holdings, and eligibility before every trade decision.
- Use intraday data at execution time for order sizing, spread/impact estimates, and scheduling.
- Monitor portfolio TE and active exposures daily while replacement positions are open.
- Invalidate candidates after corporate actions, issuer changes, or material relationship breakdowns.

## Implementation sequence

1. **Baseline:** exact S&P 500 TE table using adjusted daily returns, EWMA/shrinkage, legal exclusions, tail/liquidity gates, and walk-forward tests.
2. **Portfolio version:** basket/full optimizer with factor, turnover, concentration, and total TE constraints.
3. **Production controls:** complete tax ledger, cross-account/DRIP integration, transaction-cost model, audit trail, and post-trade monitoring.

This sequence preserves the simplicity and auditability of pair ranking while making the production decision portfolio-aware, tax-controlled, and economically testable.

## Tax reference

General wash-sale guidance: [IRS Publication 550, Investment Income and Expenses](https://www.irs.gov/publications/p550). The implementation should be reviewed by qualified tax and compliance professionals before live use.
