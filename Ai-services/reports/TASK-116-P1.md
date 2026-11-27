# TASK-116 P1 report

Status: DONE (code written). Test status: NOT RUN (orchestrator runs GREEN).
Baseline: 545ac38 (dirty, TASK-114 uncommitted).

## Changed files
- `Ai-services/app/analysis/__init__.py` (new, docstring only)
- `Ai-services/app/analysis/money.py` (new): `to_decimal`, `format_rupiah`, `format_number`; non-finite values (NaN/Infinity) also become 0 (previously `_decimal("NaN")` would make `format_rupiah` raise).
- `Ai-services/app/analysis/product.py` (new): `abc_classes`, `menu_matrix`, `rank`, `growth_pct` per D1-D3. Matrix popularity is the exact integer-style check `qty*n*10 >= total_qty*7`; `COST_UNKNOWN` is decided first (even for quantity 0); `abc_classes` classifies on exact cumulative before rounding.
- `Ai-services/app/chat/daily_summary.py`: helper definitions removed; imports `format_rupiah`, `format_number as _number`, `to_decimal as _decimal` (names stay importable; `_int` remains local).
- `Ai-services/app/tools/insights.py`: `_fmt(value, fmt=str, suffix="")` now takes a formatter instead of a prefix; money uses `format_rupiah`, percents `format_number` + `%`; empty still `-`. Units (`10.000 L`) untouched.

## Existing assertions broken by the Rupiah/percent change (tests NOT edited)
`Ai-services/tests/unit/test_insights.py`:
- line 510: `total rugi Rp 12500.00` -> now `total rugi Rp12.500`
- line 571: `target margin 60.00%` -> now `60%`
- lines 573-574: `harga Rp 25000.00, HPP Rp 12000.00, margin 52.00% ... saran harga Rp 30000.00` -> `Rp25.000`, `Rp12.000`, `52%`, `Rp30.000`
- line 578: `harga Rp 5000.00` -> `Rp5.000` (`HPP -`, `margin -`, `saran harga -` unchanged)
Unaffected: lines 512 and 514 (`persen -`, `rugi -`, `total rugi -`), all of `test_daily_summary.py` (format_rupiah/_number behaviour identical).

## Deviations / open questions
- `GEMINI.md` is not present under `Ai-services/` (not read).
- Matrix output keeps input order within each quadrant (packet silent).
