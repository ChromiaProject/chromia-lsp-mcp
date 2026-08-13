# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An MCP server (stdio transport) that exposes Rell LSP features — hover, completions, diagnostics,
navigation, rename, quick fixes — as MCP tools and `lsp-*://` resources. Kotlin on the MCP Kotlin
SDK and LSP4J. It runs the Rell language server (`net.postchain.rell:rell-toolbox-language-server`)
as a child process and speaks LSP to it over stdio.

It ships as a container image, built by Jib and pushed to this project's GitLab container registry, with
the language server JAR baked in at `/opt/rell-lsp/language-server.jar`. There is no npm package
and no runtime download any more.

## Commands

```sh
./gradlew build          # compile + all tests
./gradlew test           # tests only
./gradlew run            # run on stdio, rooted at the current directory
./gradlew jibBuildTar -PimagePlatforms=arm64   # local image tarball
./gradlew jib -Pimage=<repo> -PimageTags=<tags>  # build and push, no daemon needed
```

`./gradlew build` downloads the Rell language server named by `rell-lsp` in
`gradle/libs.versions.toml` and runs the integration tests against it; `RELL_LSP_JAR` overrides
which one. Without any JAR the integration class skips and only unit tests run.

Dependencies are pinned by SHA-256 in `gradle/verification-metadata.xml`, so any dependency
change — including a `rell-lsp` bump — fails the build until the file is regenerated with
`./gradlew --write-verification-metadata sha256 help`. Review the diff before committing it:
those checksums are what CI trusts.

## Architecture

`Main.kt` builds the SDK `Server`, connects `StdioServerTransport`, and holds the process open
until the client disconnects. `mcp/` registers tools, resources, and prompts against a single
`ServerContext` (language server client plus current root). `lsp/RellLspClient` owns the child
process, the handshake, document versions, and the diagnostics cache.

Things that will bite if you don't know them:

- `claimStdout()` in `Main.kt` takes fd 1 for the protocol and repoints `System.out` at stderr.
  Never write to stdout, and don't remove that call — libraries print there (kotlin-logging's
  startup banner is the one that already broke it once).
- Positions cross the 1-based/0-based boundary in exactly one place: `position()` in
  `mcp/Arguments.kt`. `RellLspClient` is 0-based throughout. Never subtract twice.
- Read-only LSP queries return empty on error or timeout (10s; 60s for `initialize`). An empty
  result means "no data or the request failed" — check the logs at `debug` when a result looks
  wrong.
- Diagnostics are push-only. They arrive as notifications, are cached per URI, and `get_diagnostics`
  returns whatever has landed, so the document must be open first. Cache keys normalize the short
  `file:/…` form the Rell server emits to the `file:///…` form we send.
- Resource templates are matched by `SchemePrefixMatcher`, not the SDK's default matcher, which
  requires equal `/`-segment counts and so can never match a file path.
- Subscribe/unsubscribe handlers are replaced in `Main.kt` because the SDK's built-in versions fire
  on registry changes, not on server pushes.
- The Rell language server needs `workspaceFolders` set to a real boolean in the client
  capabilities; it unboxes the value without a null check and throws NPE on `initialize` otherwise.

## Adding a tool

One `addTool(...)` call in `mcp/Tools.kt` — name, description, schema, and handler in one place.
Build the schema with the helpers in `mcp/Arguments.kt` and read arguments through the typed
accessors there so a bad argument produces a message naming it.

## Tests

`src/test/kotlin`: unit tests for the text-edit maths and the stdout preamble filter, plus
`RellLspMcpTest`, which starts the built server as a subprocess and drives it with the SDK's MCP
client against a real language server.

Assertions come from `kotlin.test`. Keep the last expression of a `= runBlocking { ... }` test
returning `Unit` — a value-returning assertion such as `kotlin.test.assertNotNull` makes the test
function return that value, and JUnit silently skips methods that do not return `Unit`.

The integration cases are ordered and share one server process — each depends on state the
previous ones left. Their coordinates are hardcoded against
`src/test/resources/rell-project/src/example.rell`; editing that fixture shifts every one of them.
The fixture is copied to a temp directory because the later cases rename, format, and quick-fix it.

## Release flow (GitLab CI)

The git tag is the version, as in the other core-tools repos: `release-patch` / `release-minor`
come from the shared `gitlab-automation` template and only push a tag, and the `deploy` job builds
the image with `-Pversion=$CI_COMMIT_TAG`, pushing `<tag>` and `latest`. `gradle.properties` holds
a placeholder that is never bumped.

Before triggering a release job, finalize `CHANGELOG.md` on `dev` (or `support/*`) by running
`./scripts/finalize-changelog.sh <previous-tag> <next-tag>` and committing the result — CI does not
touch the changelog.

The image is built by Jib on the organisation's `chromia-images/java21` base, pinned by digest,
the same way chromia-cli builds `chr`. No Docker daemon is involved anywhere in the pipeline.

## Docs

`docs/{Architecture,Development}.md` are hand-written and cover the same ground in more detail.
They are not generated, so verify against source before trusting a specific claim.
