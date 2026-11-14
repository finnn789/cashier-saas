# TASK-081 W4 report (Python tests, Tier 1 + Tier 2)

Worker W4. Test code only. Nothing run (no unittest, no pytest); only `.venv\Scripts\python.exe -m compileall -q tests` (OK) and an AST unused-import scan. Line numbers below are the `da2c2cd` declaration lines. Line endings (CRLF/LF) of each file were preserved.

## Files touched (`def test_` before -> after)
Unit total 323 -> 259 (-64). Integration total (this file) 13 -> 9.

| File | Before | After |
|---|---|---|
| unit/test_ai.py | 16 | 13 |
| unit/test_channel.py | 12 | 12 |
| unit/test_conversation.py | 9 | 8 |
| unit/test_daily_summary.py | 22 | 19 |
| unit/test_database.py | 12 | 9 |
| unit/test_grpc_client.py | 7 | 5 |
| unit/test_grpc_client_security.py | 17 | 8 |
| unit/test_grpc_tools.py | 20 | 12 |
| unit/test_insights.py | 13 | 10 |
| unit/test_internal_daily_summaries.py | 21 | 17 |
| unit/test_internal_members.py | 34 | 27 |
| unit/test_langgraph_chat.py | 17 | 6 |
| unit/test_llm_error_codes.py | 6 | 5 |
| unit/test_migration_0003.py | 9 | 8 |
| unit/test_observability.py | 16 | 13 |
| unit/test_phone.py | 7 | 6 |
| unit/test_repository.py | 12 | 12 (kept, see deviations) |
| unit/test_tenant_identity.py | 19 | 18 |
| unit/test_tenant_intake.py | 14 | 12 |
| unit/test_tools.py | 4 | 3 |
| integration/test_internal_members_api.py | 13 | 9 |

Unchanged: test_config, test_memberships_sql, test_telegram_intake, test_telegram_sender, test_tenant_scope.

## Deviations from the task text (please review)
1. **test_repository.py:247 `test_minimal_metadata_constraints_are_declared` KEPT**, not deleted. The audit called it trivial because of the hard-coded counts. Only those two brittle asserts were removed (45 columns, 11 foreign keys, plus the now unused `ForeignKeyConstraint` import). The rest asserts things nothing else covers: the table set, index names, the unique constraints on `channel_users(platform, external_user_id)` and `channel_accounts(platform, external_account_id)`, `last_message_at` and `verified_at` defaults, and the check constraint on `messages`. If you still want it deleted, remove the method.
2. **test_internal_members.py:504 `test_models_declare_the_documented_shape` deleted** although no keeper was named. The migration SQL test `test_upgrade_sql_matches_the_models` (:474, kept) pins the same literals (lengths 160/15/32/16, NOT NULL, PK, partial index), so model and migration stay tied to the same values. Weakest of the Tier 1 deletes. Note that `Tenant`/`TenantMember` imports were removed with it.
3. **test_grpc_tools.py OrderStatusTool `test_execute_order_found`**: the `PROCESSING` fixture was a pass-through of an unknown status (label == code). I changed it to `COMPLETED` and updated the label and message assertions to "Selesai (sudah dibayar)" (same mapping already asserted in `test_execute_order_status_codes_mapped_to_text`). The other stale spots named in the task do not exist as stale text: test_grpc_tools.py:315 already uses COMPLETED. test_grpc_client.py:128 `READY_FOR_PICKUP` -> `COMPLETED` in both the fixture and the expected dict (pure pass-through).
4. Tier 1 deletes of tool schema tests (test_grpc_tools:107, :181) are judged trivial per the audit; their `required`-parameter and description assertions are not covered elsewhere except what I folded into test_tools (see below). Only insight tools' `required`/`description` asserts were preserved.

## Per item

### test_grpc_tools.py
- :47 `test_catalog_offline_fallback`, :57 `test_order_status_offline_fallback`: DELETED. Verified: `GrpcSettings` has no `service_key`, so the client returns early (`_has_service_key`) and never reaches the stub. Real coverage: test_grpc_client.py (merged rpc-error test) and test_grpc_client_security.py fail-closed tests.
- :67 `test_catalog_rpc_error_handling`, :85 `test_order_status_not_found_rpc_error`: DELETED (same false positive; they set `_stub` but no key). Their cases (UNAVAILABLE -> [], NOT_FOUND -> None) are asserted in the merged test in test_grpc_client.py. Removed now unused `import grpc`, `import grpc.aio`.
- :24 `test_settings_defaults`, :107 `test_tool_schema` (catalog), :181 `test_tool_schema` (order): DELETED (trivial).
- :165 + :266 `test_lazy_client_initialization` (catalog, order): MERGED into one `test_lazy_client_initialization` with a subTest over both tools (same inputs "roti"/"ORD-999", same assertions); kept in `CatalogSearchToolTests`.
- Status fixture: see deviation 3.

### test_tools.py / test_insights.py
- test_tools:72 `test_default_tool_registry`: DELETED (= test_grpc_tools `test_default_tool_registry_contains_all_tools`, which asserts the same 5 names and `len == 5`). Removed unused `get_default_tool_registry` import.
- test_insights:241 `test_default_registry_has_the_insight_tools`: DELETED (= test_grpc_tools:282).
- test_insights:331 `test_registry_dispatch_ignores_model_supplied_user_id`: DELETED (= test_tools:29, which asserts the model-supplied `user_id` is replaced by the trusted one). Removed unused `json` and `get_default_tool_registry` imports.
- test_insights:227 `test_schemas_hide_user_and_tenant_and_name_the_tools`: FOLDED into test_tools `test_tool_schema_hides_tenant_and_user`, which now loops over all 5 tools; the names list, `required` lists and the "dua kali" description assert of the insight tools moved with it (new import `app.tools.insights`).

### test_langgraph_chat.py (17 -> 6)
- :261 tool-call execution cycle: DELETED (= test_tenant_scope.py:241 `test_tool_execute_receives_the_verified_tenant_only`: same compiled graph, same injected-tenant/user stripping). Removed `MockTool`, `build_chat_graph`, `BaseTool`, `ToolRegistry`, `patch` imports.
- :467, :478, :488 (duplicate text, commands, invalid inputs): DELETED (= test_conversation.py:165/:177/:192; conversation versions assert strictly more). Removed `MAX_INPUT_CHARS` import.
- :508 `test_init_with_ai_settings_constructs_graph`: DELETED (trivial). `:519` unconfigured init test kept.
- Merged table 1 (:340, :353, :363, :373, :385) into `test_graph_exceptions_return_localized_message_and_persist_no_assistant` (5 cases, subTest). Each case keeps its input and expected text; the `db_events[-1] == ("history", 1)` assert of :340 now applies to all cases, plus `_assistant_inserts() == []` (true for all because tx 2 never starts). Uses `self.setUp()` per case to reset state.
- Merged table 2 (:403, :417, :448) into `test_graph_state_error_code_returns_localized_message_and_persists_nothing` (3 cases, same assertions incl. `_assistant_inserts() == []`). :432 (success path) untouched.
- Removed the now empty section header comments.

### test_grpc_client_security.py (17 -> 8)
- :92 `test_catalog_sends_service_key_metadata`, :105 `test_order_status_sends_service_key_metadata`: DELETED (= test_grpc_client.py success tests, which assert the exact `x-internal-service-key` metadata and timeout). Removed `SERVICE_KEY_HEADER` import, `_order_response` helper and the empty `GrpcClientMetadataTests` class.
- :221 `test_order_not_found_still_returns_none`: DELETED (= merged rpc-error NOT_FOUND case in test_grpc_client.py).
- :64 `test_default_service_key_is_empty`: DELETED (trivial; blank/absent env cases remain).
- :215 `test_missing_key_warning_never_contains_key_value`: DELETED (vacuous: the key is empty, so the secret can never appear).
- :154 + :158 + :162 + :167 limit clamp: MERGED into `test_limit_is_defaulted_capped_or_kept`; all 8 inputs kept (no limit, 0, -5, 500, 51, 1, 25, 50).
- :79 + :85 `test_load_without_env...` / `test_load_blank_env...`: MERGED into `test_load_without_or_with_blank_env_yields_empty_key` (subTest None / "   ").

### test_grpc_client.py (7 -> 5)
- :195 + :219 + :244: MERGED into `test_rpc_error_fallback` (3 subTests: catalog UNAVAILABLE -> [], order NOT_FOUND -> None, order UNAVAILABLE -> None, same inputs and details). Print noise dropped. Stale status fixed (deviation 3).

### test_observability.py (16 -> 13)
- :132 + :141: MERGED into `test_setup_is_idempotent_and_reconfigure_does_not_duplicate_handlers` (sequential, all asserts kept).
- :170 + :174 + :184: MERGED into `test_settings_parsing` (three `subTest` blocks, all asserts kept).

### test_llm_error_codes.py (6 -> 5)
- :58 + :68: MERGED into `test_wrapped_cause_and_context_chains` (two subTest blocks).

### test_ai.py (16 -> 13)
- :78 `test_trimming_keeps_newest_complete_pairs`: DELETED (subsumed by :96 emoji test: same newest-pair and byte-bound asserts with a harsher input).
- :182 `test_very_long_decimal_retry_after_remains_rate_limit`: MERGED into `test_rate_limit_delay_is_bounded` as case `("9"*5000, 3600)` (implementation clamps to 3600); the table loop now also asserts `response.exited` and `content.consumed == 0` for every case (carried from :182).
- :137 + :272: MERGED into `test_ambiguous_transport_is_safe_unknown_without_retry` (stage `on_enter` / `during_body` x `ClientError` / `TimeoutError`; body-stage keeps `exited` and `consumed == 1`).

### test_database.py (12 -> 9)
- :90 hard-coded `assertEqual(EXPECTED_ALEMBIC_REVISION, "0003_...")`: DELETED (duplicate of test_migration_0003:69).
- :61 + :71 + :79 + :88: MERGED into `test_missing_or_old_migration_revision_is_rejected` (None, 0001_telegram, 0001_channels, 0002_tenant_members; `assertNotEqual(EXPECTED, revision)` generalises the :80 assert; close/dispose asserts of :61 now apply to all).

### test_conversation.py (9 -> 8)
- :211 + :268: MERGED into `test_hard_ai_error_is_ephemeral_after_user_commit_and_never_retries` (the sleep-not-awaited check now runs inside :211's flow).

### test_daily_summary.py (22 -> 19)
- :253 (build_prompt bounded; = test_ai prompt tests), :226 (timeout constant == 8), :249 (substring asserts on SYSTEM): DELETED. Removed unused `MAX_PROMPT_BYTES`, `build_prompt` imports.

### test_internal_members.py (34 -> 27)
- DELETED: :447 chain test (covered by test_migration_0003:58 ordered `walk_revisions`), :504 (see deviation 2), :222 / :231 / :249 (only test `FakeStore` logic; real behaviour proven by the integration Postgres tests), :312 (settings echo), :460 (trivial). :395 `get_member`/`disable_member` test untouched (out of scope). Removed unused `Config`, `ScriptDirectory`, `Tenant`, `TenantMember`, `Settings` imports.

### test_migration_0003.py (9 -> 8)
- :72 `test_earlier_revisions_do_not_know_the_new_schema`: DELETED (trivial text-absence check).

### test_internal_daily_summaries.py (21 -> 17)
- :157 unset key 503: DELETED (auth dependency is shared and asserted in test_internal_members `test_missing_wrong_and_unset_key`). :265 missing restock/anomaly sections: DELETED (section omission asserted in test_daily_summary `test_empty_optional_sections_are_omitted`).
- :163 + :189 merged into :148, renamed `test_key_and_validation_precede_channel_state_and_nothing_runs` (401 and 422 asserts with `is_ready = False` appended).

### test_phone.py (7 -> 6)
- :21 `test_leading_zero_becomes_country_code`: DELETED (covered by the shared contract cases, e.g. `081234567890`, `  0812.3456.7890  `).

### test_tenant_identity.py (19 -> 18)
- :231 `test_disabled_member_is_not_matched`: DELETED (tests `FakeStore` filter; real SQL filter asserted in test_internal_members `test_find_active_by_phone_filters_status_and_returns_no_phone`).

### test_tenant_intake.py (14 -> 12)
- :139 forwarded own contact merged into :127 (third case in the table); :248 development fallback merged into :236, renamed `..._including_the_fallback` (appended block with `message_id=3`).

### test_channel.py
- :226 dead assignment `ping.side_effect = RuntimeError('dummy-secret')` removed (overwritten by the `OwnershipLost` on the next try-block line).

### integration/test_internal_members_api.py (13 -> 9)
- :126, :136, :149, :178: DELETED (unit versions in test_internal_members cover the same inputs/assertions; :109 and :166 kept). Removed unused `json` import and `EXAMPLES` constant.

## Not verified / risks
- Nothing was executed. Highest-risk edits: the `self.setUp()` re-entry inside subTest loops (test_langgraph_chat, test_database; relies on setUp being synchronous and idempotent), the `test_observability` merged setup test (second half now starts from an already configured root logger), and test_tenant_intake `message_id=3` fallback block.
- Pre-existing unused imports left alone: `SYSTEM` in test_ai.py, `cashier_service_pb2` in test_grpc_tools.py, `ToolMessage` in test_langgraph_chat.py.
