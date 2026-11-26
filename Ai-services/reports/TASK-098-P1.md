# TASK-098 P1 report

Status: DONE (implementation), tests NOT RUN (policy). py_compile not run (Bash limited to protoc/git by worker rules).

Changed files:
- Ai-services/app/chat/daily_summary.py: `MAX_LEAKAGE_LINES`, `MAX_MARGIN_LINES`; `build_summary(..., leakage=None, margins=None)`; leakage section (only when `stock_count_count > 0`, top 5 by `loss_amount`, "dan N bahan lain"); margin section (only when `menus` non-empty, lowest 5 `margin_pct`, "dan N menu lain").
- Ai-services/app/api/internal_daily_summaries.py `_summary_for`: fetches `get_leakage_report` (date_from=date_to=day) and `get_menu_margins`, picks the outlet via `_outlet_section`, passes both to `build_summary`.

Contract points: D1-D5 done; None result omits the section; existing positional callers unchanged.

Notes:
- Leakage `lines` carry no per-line flag; the chat tool shows "Tidak ada bahan yang menyimpang" when empty, so lines are already the flagged ones. Used as is.
- Header shows total_loss/flagged_count from the outlet section.

Deviations: none. Tests needing updates: any existing test asserting `_summary_for` gRPC calls on a fake client (fake needs `get_leakage_report`/`get_menu_margins`). Open questions: none.
