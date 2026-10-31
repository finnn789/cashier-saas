# SQLAlchemy chat persistence refactor: worker proposal

Baseline: parent memory version 29 and `sqlalchemy-protected-baseline.json`, as identified in the supplied contract. This report describes proposed contents for orchestrator review; it does not claim workspace edits.

Phase 1 review accepted the five mapped tables and query and worker structure. The orchestrator reported that metadata for 72 columns, nine composite foreign keys, and six indexes compiled against installed SQLAlchemy without a database. Phase 2 makes the required annotation correction in `app/chat/repositories.py`: `_scope` and `_owner` accept `FromClause`, which covers both tables and the aliases used by completed history. Query logic is unchanged.

The phase 2 reservation contains `tests/unit/test_repository.py`, `tests/unit/test_conversation.py`, and this report, plus `app/chat/repositories.py` for that one type and import correction. No other phase 1 files, migration revisions, dependencies, or removals are proposed.

Repository unit tests now compile captured SQLAlchemy expressions with the PostgreSQL dialect. They check identity and inbox conflict targets and profile values; session reuse and reset; scoped sequence update and no message insert on mismatch; message and ordered outbox values; completed history joins, ownership, role and DONE filters, current inbox exclusion, limit and chronological result; scoped queue reads; correlated recovery tail suppression; and delivery updates. They do not assert database constraint enforcement.

Worker unit tests mock named repository methods with a `Repository` spec. Transaction markers preserve checks for PROCESSING commit before provider I/O; current input once and prompt order; USER reuse; ASSISTANT, outbox, and READY in one transaction; rollback on outbox failure; command behavior; retry and hard error paths; SENDING commit before send; delayed retry wait without a transaction or extra query; failed or unknown tail handling; DONE and DELIVERY_FAILED completion; and conservative PROCESSING recovery without provider regeneration.

Checks: NOT RUN BY POLICY. No tools, imports, tests, application startup, migrations, or database operations were executed in this authorship turn. The orchestrator must compare the full proposed files against the protected baseline, inspect the production annotation diff, and review the adapted assertions before applying them. Relevant tests remain recommended only after the user explicitly requests execution.

Remaining risk: these proposed tests and the annotation correction have not been compiled or executed in this turn. PostgreSQL statement compilation checks in the proposed tests are assertions to run later, not evidence of a successful test run.
