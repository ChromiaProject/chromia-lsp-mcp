# Architecture

The server is a thin, stateful bridge between two protocols. MCP comes in on stdio from the
client; LSP goes out on stdio to a Rell language server running as a child process. Everything
else is bookkeeping around that.

```
MCP client  ──stdio──▶  Server (Kotlin)  ──stdio──▶  Rell language server (Java)
                          │
                          ├─ tools      (17, registered on the MCP SDK's Server)
                          ├─ resources  (diagnostics, hover, completions)
                          └─ prompts    (lsp_guide)
```

## Layers

`Main.kt` builds the MCP server, wires it to stdio, and owns the process lifetime. Registration of
tools, resources, and prompts happens in `mcp/`, all of it against a single `ServerContext` that
holds the language server client and the current project root. `lsp/RellLspClient` owns the child
process, the LSP handshake, document state, and the diagnostics cache. LSP4J does the JSON-RPC
framing and gives typed models for every message.

Requests flow one way: an MCP tool call is parsed into arguments, converted from 1-based editor
coordinates to 0-based LSP ones, sent as an LSP request, and the reply is serialized back to JSON
text as the tool result. Diagnostics flow the other way, pushed by the server as notifications.

## Decisions worth knowing

**The language server is a separate process.** Both sides are JVM code and could share one, but
the Rell server brings its own Koin container, log4j configuration, and a large shaded dependency
set. A child process keeps the classpaths apart, and its failures stay recoverable — that is what
`restart_lsp_server` restarts.

**Stdout belongs to the protocol alone.** `claimStdout()` takes file descriptor 1 for the MCP
stream and repoints `System.out` at stderr before anything else runs. Libraries do print to
stdout — kotlin-logging, which the MCP SDK logs through, announces itself there on first use —
and any such line would be read as a JSON-RPC message and break the session. Everything humans
read goes to stderr; the client gets the same messages as MCP log notifications.

**The language server's own preamble is filtered.** It writes a logging banner to its stdout
before the first LSP message, which would desync LSP4J's header parser.
`PreambleFilteringInputStream` drops bytes up to the first `Content-Length:` and passes the rest
through untouched.

**Coordinates convert in exactly one place.** MCP tool and resource arguments are 1-based;
`position(line, column)` in `mcp/Arguments.kt` is where they become the 0-based positions LSP
speaks. `RellLspClient` deals only in LSP coordinates.

**Read-only queries degrade to empty.** Hover, completions, code actions, symbols, and formatting
return an empty result when the language server errors or times out (10 seconds; 60 for
`initialize`, which covers JVM startup and project indexing). An empty result therefore means "no
data, or the request failed" — turn the log level up to `debug` to tell the two apart.

**Edits are written from disk, not from memory.** `applyWorkspaceEdit` re-reads each file, applies
the edits right-to-left so earlier offsets stay valid, writes it back, and resyncs the language
server's copy if the file is open. Nothing is cached in between, so edits made outside this
process are never clobbered by a stale buffer.

**Resource URIs are matched by scheme, not by path segments.** The SDK's default template matcher
requires the URI and the template to have the same number of `/`-separated segments, which no
absolute file path can satisfy. `SchemePrefixMatcher` matches on the scheme and hands the handler
everything after `://`, which is how `lsp-hover:///a/b/c.rell?line=6&column=8` resolves.

**Diagnostics subscriptions are handled here, not by the SDK.** The SDK's built-in subscription
bookkeeping fires when a registered resource changes; diagnostics arrive as pushes from the
language server instead. `Main.kt` replaces the subscribe and unsubscribe handlers with its own
set and notifies subscribers when the language server republishes. Notifications carry only the
URI, per the MCP spec — the client re-reads the resource to get the content.

**Open documents appear as resources.** Opening or closing a document re-syncs the per-file
`lsp-diagnostics://` resources so a client listing resources sees exactly the files the language
server currently holds.

## Packaging

The image is built by Jib, which assembles it without a Docker daemon: base image, dependency
layer, application classes, and the Rell language server JAR staged into `/opt/rell-lsp`. The
language server version is a Gradle dependency (`gradle/libs.versions.toml`), so an image tag
pins exactly one of them, recorded in the `com.chromaway.rell-lsp.version` image label.

Adding a tool means one `addTool` call in `mcp/Tools.kt` — the schema and the handler live
together, so there is no second place to keep in sync.
