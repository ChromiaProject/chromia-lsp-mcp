# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An MCP server (`@chromia/chromia-lsp-mcp`, stdio transport) that exposes Rell LSP features — hover, completions, diagnostics, code actions — as MCP tools and `lsp-*://` resources. It downloads the Rell language server (`net.postchain.rell:rell-toolbox-language-server`) and drives it as a Java subprocess: preferably a self-contained jlink runtime bundle from this project's generic package registry, otherwise the fat JAR from the GitLab Maven registry run with the user's Java.

## Commands

```sh
pnpm install                 # also runs build via the `prepare` script
pnpm run build               # tsc → dist/ (the only type-check; strict is on)
pnpm run watch               # tsc --watch
pnpm test                    # node test/rell-lsp.test.js
```

No linter or formatter is configured; `tsc` is the whole verification story.

`pnpm test` requires a prior build (it aborts if `dist/index.js` is missing), Java 21+ on `PATH`, and network access on first run to fetch the LSP JAR. It is a single hand-rolled integration script with no filtering — there is no way to run one case; edit the `runTest(...)` calls in `runTests()` to narrow it. It never throws on a failing case: each is recorded and the process exits 1 if any failed, so read the `=== Test Results ===` summary rather than the exit code alone.

The fixture project lives in `test/rell-project/`, and the tests assert against hardcoded coordinates in `src/example.rell` (hover at 6:8, completions at 25:10, code actions over 48:1–48:20). Editing that file shifts every one of them.

Run the built server directly with `node dist/index.js [rellLspVersion]`.

## Architecture

Five layers, one direction of flow:

`index.ts` registers MCP request handlers → per-request lookup in a handler map → `LSPClient` → JSON-RPC over stdio → Rell LSP server (Java).

`index.ts` owns two pieces of mutable module state, `lspClient` and `rootDir`, plus setters that are threaded into the handler factories. This is why handler maps are rebuilt on *every* request (`getToolsHandlers()`, `getResourceHandlers(lspClient)`): they close over whatever the client currently is, since it doesn't exist until `start_lsp` runs. Don't hoist those calls to module scope.

Tool dispatch is an exact name lookup in the map from `getToolHandlers`, with Zod `safeParse` on the arguments. Resource dispatch is prefix matching — `uri.startsWith(key)` against the map keys `lsp-diagnostics://`, `lsp-hover://`, `lsp-completions://`.

Positions cross a 1-based/0-based boundary at the handler layer: MCP tool and resource arguments are 1-based, and `src/tools/index.ts` / `src/resources/index.ts` subtract 1 before calling `LSPClient`, which speaks pure 0-based LSP. Never subtract twice.

`LSPClient` (`src/lspClient.ts`) handles framing, an id→promise map with a 10-second timeout per request, document open/version tracking, and the diagnostics cache. Hover, completion, and code-action failures are caught and returned as empty string / empty array, so an empty result means "no data or the request failed", not "no such symbol" — check logs at `debug` when a result looks wrong.

Diagnostics are push-only: they arrive via `textDocument/publishDiagnostics` notifications and are cached per URI, so `get_diagnostics` returns whatever has landed so far and requires the document to be open first. Cache keys are normalized from the short form `file:/…` (which the Rell server emits) to the `file:///…` form `createFileUri` produces. Subscriptions to `lsp-diagnostics://` register a callback on the client and push `notifications/resources/update`; the subscription handler strips the scheme with a hardcoded `uri.slice(18)`.

### Server resolution (`src/downloader/index.ts`)

The Rell LSP version is only ever read from `process.argv[2]`. With an explicit version, that version is cached-or-downloaded. Without one, the newest install already in `~/.chromia/lsp-mcp/` (JAR or runtime bundle) wins and the registry is never consulted — so a cached install never self-updates. To pick up a newer LSP, pass the version explicitly or clear the cache directory. `fetchLatestVersion` prefers `<release>` over `<latest>` in `maven-metadata.xml` because `<latest>` can point at a `-SNAPSHOT`.

`resolveLspServer` returns a `{javaPath, jarPath, bundled}` launch spec. For the six supported platforms it first tries a jlink runtime bundle (`runtime-<version>-<classifier>/` in the cache dir, downloaded from this project's generic package registry and extracted with the system `tar`); any bundle failure falls back to the fat JAR run with the user's Java — `JAVA_HOME` first, then `PATH`. Bundles are cross-built and published by `scripts/build-jlink-bundles.sh` via the `build-lsp-runtimes` CI job (scheduled/manual); a version released before its bundles are published just logs the 404 at `info` and falls back.

Note that the `start_lsp` handler constructs `new LSPClient()` with no version when `lspClient` is null, which would drop the CLI pin; in practice `runServer()` always creates the client first, so that branch is dead.

### Logging (`src/logging/index.ts`)

This module globally reassigns `console.log`/`warn`/`error` to route through the level system. Every level goes to **stderr** — stdout is reserved for the MCP JSON-RPC stream, so never write to it directly and don't introduce bare `console.log`. Initial level comes from `LOG_LEVEL`, defaulting to `info`; clients can change it at runtime via `set_log_level` or the MCP `logging/setLevel` request.

## Adding a tool

Three separate places must stay in sync, and nothing catches a mismatch at compile time:

1. Zod schema in `src/types/index.ts`
2. `{schema, handler}` entry in `getToolHandlers` (`src/tools/index.ts`)
3. Entry with `zodToJsonSchema(...)` in `getToolDefinitions` (same file)

Resources are analogous: handler in `getResourceHandlers`, template in `getResourceTemplates`, plus a subscribe/unsubscribe pair if it supports live updates.

## Release flow (GitLab CI)

Before triggering `release-patch` / `release-minor`, update `CHANGELOG.md`: add a new `## [x.y.z] — YYYY-MM-DD` entry (Keep a Changelog format, `Added`/`Changed`/`Fixed` sections) summarizing what merged since the last entry, and commit it on `dev` (or `support/*`) first. The release jobs bump and tag `package.json` but do not touch the changelog themselves.

Version bumps are not done by hand. The manual `release-patch` / `release-minor` jobs on `dev` (or `support/*`) bump `package.json`, commit, and push a tag. `deploy-npm` is a manual job gated on the tag matching `package.json` version exactly. CI builds and tests inside a node22-java21 image.

## Docs

`docs/{Introduction,Architecture,Functional,Setup}.md` are hand-written prose covering the same ground in more detail; they are not generated, so they drift. Verify against source before trusting a specific claim there.
