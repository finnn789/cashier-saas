# Ruflo setup for Cashier SaaS

Configured on 7 October 2026 for native Windows and Codex, using Ruflo CLI 3.54.1.

## What Ruflo provides

Ruflo exposes memory, task routing, and swarm coordination through MCP. Codex connects to a local stdio server. Claude Code console/mod integrations have a separate client-specific installation path.

Official sources:

- [Ruflo GitHub README](https://github.com/ruvnet/ruflo)
- [Ruflo user guide](https://github.com/ruvnet/ruflo/blob/main/docs/USERGUIDE.md)
- [Installation guide](https://github.com/ruvnet/ruflo/wiki/Installation)
- [Codex MCP configuration](https://developers.openai.com/codex/mcp)

The guides include historical version numbers. The installed package version was checked locally; current command options were read from its CLI help.

## Installed configuration

- Node: 24.21.0; npm: 11.19.0.
- Project MCP entry: `.codex/config.toml`, server name `ruflo`.
- Command: `cmd.exe /d /c npx.cmd -y ruflo@3.54.1 mcp start`.
- Working directory: `E:/Java/cashier-saas`.
- Runtime configuration: `.claude-flow/config.yaml`.
- MCP memory observed at `.swarm/memory.db`, with HNSW index files alongside it.
- Automatic daemon startup is disabled through `RUFLO_DAEMON_AUTOSTART=0` in the MCP environment. Runtime hook auto-execution is disabled. Application tests require the existing explicit user authorization.
- No application code, migrations, or test code were changed for this setup.

`.codex/` is already ignored by this repository, so the MCP entry is machine-local. A fresh checkout needs that entry recreated. NPX caches the package outside the repository; no global Ruflo installation or Maven dependency is required. The top-level version is pinned; dependency resolution is not a repository lockfile.

The published initializer also wrote Claude plugin settings despite `--skip-claude`; those newly generated settings were removed. This setup uses the runtime and explicit Codex MCP entry, preserving the existing skills and executor configuration.

Later CLI diagnostics generated `.claude/proven-config.json`, its version stamp, and empty helper directories. Automatic approval review rejected recursive cleanup because provenance of the entire directory was not established. That directory is retained and ignored; it has no `settings.json` enabling plugins.

## Verification

`codex mcp get ruflo --json` recognizes the enabled project entry. A separate Node client started the exact configured launcher and completed:

- MCP initialize and tools/list: 358 advertised tools.
- Required tools present: `memory_store`, `memory_search`, `hooks_route`, `swarm_init`, `agent_spawn`.
- Store, retrieve, and search of setup metadata in namespace `cashier-setup`.
- `hooks_route` with keyword routing; semantic routing was disabled for that check.

Memory reported `sql.js + HNSW`, with 384-dimensional embeddings. Search was checked with threshold 0 and an exact setup query; this verifies the search path, not semantic retrieval quality. Native AgentDB bridging is disabled by Ruflo on Windows. The package identifies itself as 3.54.1; the MCP server reports serverInfo version 3.0.0.

The local diagnostic `.codex/ruflo-smoke.cjs` can repeat this infrastructure check. It stores setup metadata and calls routing; it does not invoke worker execution or application tests.

## Activate in Codex

Restart the Ruflo MCP server from the client's MCP settings, or reopen Codex for this trusted project. Confirm Ruflo appears among connected servers. The already-running chat did not gain new callable Ruflo tools during setup; protocol checks were performed through a separate client.

For future project work, discover the available Ruflo tools, search relevant memory, and use routing/swarm coordination when useful. Keep `.ai` state canonical and apply the existing reserved-write worker workflow. `agent_spawn` availability alone does not prove a code-writing worker runs: actual executor integration remains unverified, and `[agents] enabled = false` was preserved in the project configuration.

Do not paste API keys into tracked files. No provider API key was needed for the verified MCP/memory operations. Model execution, provider credentials, worker execution, and automatic learning hooks were not configured or verified.

## Verified worker path (7 October 2026)

Later verification in the active chat established callable MCP tools, swarm initialization and registration. Native `agent_execute` failed because its installed implementation selects Anthropic, OpenRouter or Ollama via provider API keys; it does not reuse Codex CLI login.

Use Ruflo for coordination and an authenticated `codex exec` subprocess for review execution. This is an external executor arrangement, not an added provider inside Ruflo. Codex reuses saved CLI authentication by default: [official non-interactive documentation](https://learn.chatgpt.com/docs/non-interactive-mode).

Connectivity returned `RUFLO_CODEX_WORKER_OK`. Two independent architecture reviewers subsequently completed with exit 0; evidence is in `reports/RUFLO-ARCH-SECURITY.md`, `reports/RUFLO-ARCH-FNB.md` and `.ai/ARCHITECTURE.md`.

The parent sandbox initially denied Codex initialization; approved launches succeeded outside it while retaining the worker's read-only sandbox. Worker shell reads were then rejected by environment policy. To keep reviewers tool-free, the orchestrator prepared source bundles and piped them to stdin. Example for the prepared security review:

```powershell
Get-Content -LiteralPath reports/RUFLO-ARCH-SECURITY-input.txt -Raw | codex exec --ignore-user-config --ephemeral --sandbox read-only --cd E:/Java/cashier-saas --output-last-message reports/RUFLO-ARCH-SECURITY.md 'Analyze only the supplied task and source bundle. Do not use tools or read files. Return the completed architecture review.'
```

Use distinct task/input/report paths for each reviewer. Parent launches still follow approval policy. This verification proves model execution and source-bundle review, not direct filesystem/code-writing capability or native Ruflo lifecycle/cost integration. No API keys were added, no application tests ran, and project agents/hooks/daemon configuration was unchanged. The exploratory Claude CLI process was terminated without a successful result; Claude is not needed for the verified path.
