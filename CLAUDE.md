# CLAUDE.md

Project rules live in `AGENTS-1.md`, which describes current behavior accurately. `AGENTS.md` is the original target-design document; do not edit it.

@AGENTS-1.md

## Claude Code notes

- `AGENTS.md` says to use `apply_patch`. In Claude Code, use the Edit and Write tools instead.
- Main code is one file: `src/main/java/com/zhewu/stockcorrelation/StockCorrelationApp.java`. Tests: `src/test/java/com/zhewu/stockcorrelation/StockCorrelationAppTest.java`.
- Verify every code change with `mvn -B -ntp verify`. It must exit 0 and keep line and instruction coverage at 90% or higher.
- Do not treat a feature listed under "Target design" in `AGENTS-1.md` as implemented. Check the code first.
- Check `git status` before staging. Never stage `stock-extractor/sp500hourly/`, `stock-extractor/output/`, market ZIPs, or reports.
- Do not commit unless the user asks.
