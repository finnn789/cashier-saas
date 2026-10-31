# Progress executor Telegram — 13 Oktober 2026

Baseline canonical orchestrator: 25. Sumber kerja adalah proposal lengkap dan source
Dispatcher installed yang diberikan user. Proposal sebelumnya belum diterapkan.
Baseline hash dan working tree tidak diverifikasi: tools/shell dilarang.

## Batas pelaksanaan

Koreksi diserahkan sebagai full file contents untuk review/apply orchestrator.
Tidak memakai tools, filesystem read/write, shell, network, nested agent, provider,
Telegram, DB, migration runtime, startup, formatter, static checker, atau tests.
Tidak membaca .env asli, parent .ai, credential, Java, .codex, atau .agents.
Tidak memperbarui canonical memory/TASKS atau membuat task reservation baru.
Test status: NOT RUN BY POLICY. Static checks: NOT EXECUTED.

## Checkpoint koreksi source review — candidate 1

Database URL validation menangkap sqlalchemy.exc.ArgumentError dan ValueError,
termasuk port malformed, dengan pesan aman dan exception chaining disembunyikan.
Tidak menambahkan URL atau kredensial default. Asyncpg connect timeout 10 detik dan
command_timeout 15 detik diterapkan pada engine aplikasi dan migrasi online.
Engine aplikasi memakai NullPool agar owner connection tidak kembali ke pool hidup
sebagai sesi yang masih memegang advisory lock. close memakai finally untuk selalu
mencoba engine.dispose meskipun connection.close gagal. Owner connection yang closed
atau invalidated tetap fatal; tidak ada reconnect atau reacquire otomatis.

Polling memakai observer startup publik. Source installed menunjukkan observer
dipanggil setelah running lock dan stop signals diinisialisasi. Stop awal menunggu
startup atau runner completion; tidak membatalkan runner yang mungkin sudah mulai.
Satu owned graceful-stop task dibagi supervisor/cleanup/stop berulang, dilindungi
shield dan ditunggu sampai selesai ketika pemanggil cancelled. Tidak ada fallback
runner.cancel setelah 10 detik, private SDK attrs, monkeypatch, global task enumeration,
Dispatcher pengganti, atau BaseException workaround untuk child polling.
Stop request publik juga ditunggu atau dibatalkan dan ditunggu jika runner lebih dulu
selesai; race RuntimeError diterima hanya ketika finished signal sudah terpasang.

Cleanup worker memakai asyncio.wait untuk grace, lalu mengambil hasil task cancelled,
failed, atau completed secara eksplisit. Tidak memakai shield(worker_task) yang dapat
memotong cleanup ketika worker sudah cancelled. Worker selalu awaited; nested finally
memastikan bot session dan database closure tetap dicoba. Cleanup task tunggal tetap
melindungi normal stop, supervised failure, startup cancellation, dan repeated stop.

Worker.command connection diberi AsyncConnection hint. Migrasi migrate connection
memakai sqlalchemy.engine.Connection. Unused TelegramAPIError dan os imports dihapus.
Parameter callback startup ditandai Bot dan Any; parameter fungsi lain pada file yang
diemisikan tetap bertipe, selain implicit self. Constructor, prompt construction,
worker SQL calls, dan migration configuration dibungkus agar mudah dibaca sekitar
100 kolom. Repository dan migration schema tidak diubah pada koreksi ini.

README menjelaskan recovery berdasarkan status, bukan generic resume: PROCESSING
menjadi AI_CRASH_UNKNOWN dengan outbox ERROR dan tanpa provider regeneration;
SENDING menjadi UNKNOWN tanpa resend; PENDING/RETRY/READY mengikuti antrean.
Dokumentasi shutdown menjelaskan native graceful teardown tanpa batas total 10 detik,
worker grace 15 detik, bounded DB I/O, dan closure saat worker sudah cancelled.

Evidence: review tekstual/control flow atas source yang diberikan dan konten authored
saja. Tidak ada AST/import/format/dependency/static/test pass claim.

## File yang berubah pada koreksi ini

- app/core/database.py
- app/channels/telegram/bot.py
- app/ai/prompts.py
- app/chat/worker.py
- migrations/env.py
- README.md
- docs/progress/telegram-worker-progress.md

File lain pada proposal awal tetap menggunakan konten proposal sebelumnya.
Tidak ada removal tambahan; removal app/openrouter.py dari proposal awal tetap pending.
Tidak ada perubahan filesystem yang dilakukan.

## Handoff dan pekerjaan tersisa

Status: corrected source candidate, menunggu review/apply orchestrator.
uv.lock tetap NOT RESOLVED dan tidak diemisi. Orchestrator perlu review dependencies,
resolve lock, lalu melakukan static checks sebelum acceptance runtime dinilai.
Belum ada bukti runtime READY FOR TESTING atau acceptance lulus.

Persistence constraints dan operational scope tidak diubah: lima tabel, composite
scope/bot/identity/chat FKs, ACTIVE session uniqueness, inbox update uniqueness,
USER inbox uniqueness, ASSISTANT reply uniqueness, sequence/outbox chunk uniqueness.
Tidak ada akses data/tindakan Cashier. Provider/send I/O tetap di luar transaksi DB.

Remaining setup/risk: dedicated PostgreSQL AI; semua deployment bot menggunakan DB
sama; operator Alembic upgrade; secret/scope environment; model window >=32768 dengan
asumsi byte-token konservatif; retention decision. Global retry menunda user lain.
UNKNOWN delivery perlu keputusan operator di luar aplikasi. Pending Telegram sebelum
commit maksimal 24 jam. Owner loss membutuhkan restart. Source review tidak membuktikan
perilaku timeout/close driver atau teardown SDK pada runtime.

Recommended later explicitly requested tests: URL invalid dan malformed port tanpa
credential disclosure; timeout DB connect/command; owner loss tanpa reconnect;
connection.close failure dengan dispose; advisory ownership dilepas saat close;
stop sebelum/sesudah startup observer; startup failure sebelum observer;
supervisor cancellation saat graceful stop; completed/failed/cancelled worker;
repeated/concurrent stop; native child teardown dengan DB I/O tertunda;
PROCESSING crash tanpa regenerasi; SENDING UNKNOWN dan tail suppression;
identity/profile/history/new ordering, duplicate intake, scope isolation,
allowlist, provider retry/timeout, UTF-16 chunks, readiness failure.

Test status final: NOT RUN BY POLICY. Static execution: NOT EXECUTED.

## Final scoped correction authorship (tool-free worker)

Source review confirmed retry busy-spinning and unbounded asyncpg graceful close. Authored scoped replacements to wait outside the transaction without changing ordering or attempts, bound close, invalidate through the public API on failure, sanitize cleanup errors, and always attempt engine disposal. Polling runner and lifecycle ownership unchanged. No tools, tests, or runtime execution; replacements await orchestrator review.
