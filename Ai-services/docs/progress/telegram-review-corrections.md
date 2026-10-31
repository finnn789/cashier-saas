# Source review corrections — candidate 1

Return corrected FULL FILE CONTENTS only for affected files, plus updated worker
progress note. Tool-free/read-only execution stays in force. Do not edit/run anything.
Previous proposal is not applied yet. All original contract and Python style rules apply.

1. app/core/database.py: make_url raises sqlalchemy.exc.ArgumentError, and malformed
   port may raise ValueError. Catch both and keep DATABASE_URL/credentials out of
   exceptions. Add explicit asyncpg connect timeout and command_timeout to avoid
   indefinitely blocked intake/ping/shutdown. Do not invent credentials/default URL.
   close() must ensure engine.dispose() in finally even if connection.close() fails.
   Releasing dedicated advisory ownership must occur reliably; avoid returning a
   still-owned physical connection to a live pool. NullPool is a simple option, plus
   dispose finally. Do not silently reconnect after ownership loss.
2. app/channels/telegram/bot.py stop_poller: installed start_polling creates private
   child tasks then awaits asyncio.wait. Cancelling runner there enters finally
   without cancel/await of child tasks. Current 10s fallback task.cancel() can orphan
   pollers. Avoid cancelling a STARTED runner. Use dispatcher startup observer/public
   startup event so stop_polling is called only once its running lock and signals
   exist; finish its native graceful child teardown (DB I/O bounded from item1).
   If cleanup/supervisor cancellation occurs while stopping, protect a single owned
   graceful-stop task with shield/await. No private SDK attrs, monkeypatching, global
   task enumeration, BaseException child hacks, or new Dispatcher implementation.
   The existing early-start RuntimeError fallback must not cancel a potentially
   already-started runner; startup/finished signals must resolve the race.
3. bot.cleanup: awaiting shield(worker_task) when supervisor already cancelled the
   worker raises CancelledError not caught by Exception. That currently exits cleanup
   before bot/database.close. Handle completed/cancelled worker outcome explicitly;
   use finally for resource closure, await all owned tasks. Must handle normal stop,
   supervised failure, startup cancellation and repeated stop calls. Keep it readable.
4. app/chat/worker.py: command() connection parameter lacks type hint; add AsyncConnection.
   If any other function in emitted files has an untyped parameter, type it. migration
   env migrate(connection) needs sqlalchemy.engine.Connection hint. Remove unused imports.
5. Long code lines in build_prompt, constructors and SQL calls should be wrapped
   sensibly (around 100 columns); preserve readable arguments/names, no compressed
   clever syntax. Do not mechanically rewrite unchanged whole project.
6. README and worker progress must reflect actual recovery semantics: PROCESSING
   provider crash is handled as error without automatic regeneration, unlike earlier
   design generic 'resume' wording. State source-only review; no static/test pass claim.
   Update progress with these concrete review corrections and changed file list.

If needed emit app/ai/prompts.py, app/chat/repositories.py for readability only;
do not change reviewed persistence constraints or scope logic unnecessarily.
