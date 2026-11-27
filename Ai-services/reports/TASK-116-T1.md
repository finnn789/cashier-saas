# TASK-116 T1 report (Python RED tests)

Test status: NOT RUN (orchestrator runs RED).

## Files
- `Ai-services/tests/unit/test_analysis.py` (new)
- `Ai-services/tests/unit/test_product_analysis.py` (new)
No existing tests modified; nothing skipped (all behaviours new).

## Behaviours -> test
- ABC classes/share/cumulative (50/30/15/5 -> A,A,B,C): `AbcTests.test_classes_use_cumulative_share_before_the_item`
- ABC single product / zero sales: `AbcTests.test_single_product_is_a_and_zero_total_sales_is_empty`
- Matrix 4 quadrants + mix/unit_margin: `MenuMatrixTests.test_each_product_lands_in_its_quadrant_with_mix_and_unit_margin`
- COST_UNKNOWN isolation, thresholds unchanged: `MenuMatrixTests.test_cost_unknown_product_is_isolated_and_does_not_move_thresholds`
- 5 keys on empty, qty 0 ignored: `MenuMatrixTests.test_empty_input_has_all_five_keys_and_zero_quantity_is_ignored`
- rank top/worst/tie: `RankTests.test_top_by_quantity_worst_by_sales_and_name_breaks_ties`
- growth_pct: `GrowthTests.test_growth_percent_with_none_for_zero_previous_and_string_inputs`
- money formats: `MoneyTests.test_rupiah_and_number_formats`
- invalid date/start>end/unknown kind -> invalid_input, missing user_id and RPC None -> UNAVAILABLE: `ProductAnalysisToolTests.test_invalid_input_and_unavailable_never_reach_or_survive_the_rpc`
- abc text: `ProductAnalysisToolTests.test_abc_text_has_rupiah_sales_and_class_letters`
- empty outlet: `ProductAnalysisToolTests.test_outlet_without_products_says_no_sales`
- growth two RPC calls + "baru": `ProductAnalysisToolTests.test_growth_calls_previous_and_current_period_and_marks_new_products`

## Expected RED reason
All fail with ImportError/ModuleNotFoundError (`app.analysis`, `app.tools.product_analysis` missing).

## Assumptions / open questions
- Product dict keys: `name, quantity, gross_sales, cost_of_goods_sold, items_without_cost` (from ProductTotal); packet did not list them explicitly.
- Tool text formats are not fixed by the packet; tests only assume per-product lines contain name, Rupiah sales, class letter, and growth as `50,0%` or `baru`.
- `abc_classes` output is assumed sorted (gross desc, name); `cumulative_pct` includes the item.
- Tool `execute` kwargs: `tenant_id, user_id, start_date, end_date, kind`; client called with keyword args `tenant_id, user_id, start_date, end_date`.
- start > end returns `status: invalid_input` from the tool.
