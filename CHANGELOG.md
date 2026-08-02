# Changelog

All notable changes to `@chromia/chromia-lsp-mcp` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.0.7] — 2026-08-02

### Added
- Self-contained Java runtime bundles: the server now prefers downloading a jlink-trimmed Temurin 21 runtime with the LSP JAR inside (published per LSP version to this project's generic package registry), so users no longer need Java installed. Supported platforms: Linux x64/arm64, macOS x64/arm64, Windows x64/arm64.
- Fallback to the user's Java when no bundle exists for the platform or version: `JAVA_HOME` is honored first (it was previously ignored), then `PATH`.
- `scripts/build-jlink-bundles.sh` and the `build-lsp-runtimes` CI job (scheduled or manual) that cross-build the bundles for all platforms on a single Linux runner and publish them.

### Fixed
- Runtime bundles are verified to actually run (`java -version`) before being used; on systems where the bundle's glibc-linked binary cannot execute (e.g. musl-based Alpine), the server now falls back to the JAR with system Java instead of crashing on spawn. A failed spawn of the language server also no longer takes down the whole MCP server.
- LSP message parsing no longer hangs when the language server writes non-LSP output to stdout (e.g. the `kotlin-logging` banner printed at startup). Previously this blocked every message behind it, so `start_lsp` always timed out.
- LSP messages are framed by byte count on a raw buffer instead of character count on a decoded string, so messages containing non-ASCII text no longer desync the stream. This also removes the old "adjust to the last closing brace" workaround.
- All log levels now write to stderr. `info` and `notice` previously went to stdout, the same channel as MCP JSON-RPC, corrupting the protocol stream.
- Diagnostics cache URI normalization no longer corrupts URIs that already use the `file:///` form; only the server's short `file:/` form is rewritten.
- `start_lsp` reports the root directory actually passed instead of the previously stored one.

### Changed
- The language server JAR is prefetched when the MCP server starts instead of lazily during the first `start_lsp` call, and the download is logged at `info` level with size and duration. A failed prefetch is retried on the next `start_lsp`.
- The `initialize` request has its own 60-second timeout (other requests keep 10 seconds), since JVM startup plus project indexing can exceed 10 seconds on larger projects. Timeout errors now include the elapsed limit.

## [0.0.6] — 2026-07-30

### Added
- `deploy-npm-gitlab` CI job publishing the package to the GitLab npm registry, alongside the existing npmjs.com deploy.

### Documentation
- Rewrote the README as a user-facing install guide (npx / global install, MCP client configuration).
- Documented GitLab npm registry installation in `docs/Setup.md`.
- Added `CLAUDE.md` with repository guidance for AI-assisted development.
- Updated Rell LSP version examples to 0.16.2.

## [0.0.5] — 2026-07-20

### Changed
- Updated the Rell language server Maven coordinates to `net.postchain.rell:rell-toolbox-language-server` (0.16.1): the language server moved from the rell-toolbox project to the rell project.
- Version resolution now prefers `<release>` over `<latest>` in `maven-metadata.xml` to avoid auto-downloading `-SNAPSHOT` builds.
- Migrated the package manager from npm to pnpm (lockfile, CI, and docs).

### Fixed
- Release CI job no longer breaks on stray cache directories: `.npm` and `.pnpm-store` are gitignored.

### Documentation
- Added development documentation under `docs/` (Introduction, Architecture, Functional, Setup).

## [0.0.4] — 2025-09-17

### Added
- `save_document` tool sending `textDocument/didSave`, so diagnostics are re-run immediately and `get_diagnostics` returns up-to-date results.

## [0.0.3] — 2025-09-15

### Documentation
- Updated the README.

## [0.0.2] — 2025-09-15

### Changed
- Renamed the published binary from `lsp-mcp-server` to `chromia-lsp-mcp`.

### Fixed
- Release CI job: switched to a custom image with npm available.

## [0.0.1] — 2025-09-15

Initial release, published as `@chromia/chromia-lsp-mcp`.

### Added
- MCP server (stdio transport) exposing Rell LSP features as tools: `start_lsp`, `open_document`, `close_document`, `get_info_on_location` (hover), `get_completions`, `get_diagnostics`, `get_code_actions`, `restart_lsp_server`, `set_log_level`.
- MCP resources with subscription support: `lsp-diagnostics://`, `lsp-hover://`, `lsp-completions://`.
- Automatic download and caching of the Rell language server JAR from the GitLab Maven registry, run as a Java subprocess.
- Leveled logging with runtime level control (`LOG_LEVEL`, `logging/setLevel`).
- Integration test suite against a fixture Rell project.
- GitLab CI pipeline publishing to the npm registry.

[0.0.7]: https://gitlab.com/chromaway/core-tools/chromia-lsp-mcp/-/compare/0.0.6...0.0.7
[0.0.6]: https://gitlab.com/chromaway/core-tools/chromia-lsp-mcp/-/compare/0.0.5...0.0.6
[0.0.5]: https://gitlab.com/chromaway/core-tools/chromia-lsp-mcp/-/compare/0.0.4...0.0.5
[0.0.4]: https://gitlab.com/chromaway/core-tools/chromia-lsp-mcp/-/compare/0.0.3...0.0.4
[0.0.3]: https://gitlab.com/chromaway/core-tools/chromia-lsp-mcp/-/compare/0.0.2...0.0.3
[0.0.2]: https://gitlab.com/chromaway/core-tools/chromia-lsp-mcp/-/compare/0.0.1...0.0.2
[0.0.1]: https://gitlab.com/chromaway/core-tools/chromia-lsp-mcp/-/tags/0.0.1
