# Rell LSP MCP Server

Rell language intelligence for AI coding agents, over the
[Model Context Protocol](https://modelcontextprotocol.io). It ships as a container image with the
Rell language server inside, so there is nothing to install beyond a container runtime.

> **The canonical repository is on GitLab:
> [gitlab.com/chromaway/core-tools/chromia-lsp-mcp](https://gitlab.com/chromaway/core-tools/chromia-lsp-mcp).**
> The image is published to that project's container registry. The old npm package
> `@chromia/chromia-lsp-mcp` is no longer maintained.

## What it gives an agent

Agents like Claude Code and Cursor only see Rell as text. This server gives them what an IDE has:

- Types, docs, and the meaning of the symbol at any position
- Completions actually in scope there
- The compiler's and linter's own errors and warnings
- Navigation: definitions, references, outlines, project-wide symbol search
- Edits written to disk: rename, quick fixes, formatting

## Requirements

Only an OCI compatible container runtime.

Images are published for `linux/amd64` and `linux/arm64`, so Apple Silicon runs natively.

## Setup

An MCP entry is bound to one project: the project directory appears twice in the run command, once
as the mount and once as the working directory. Configure this server per project, not once
globally — a single global entry cannot follow you from one dapp to the next, and aiming one at a
parent directory holding several projects makes the language server index all of them at once.
Every client below is therefore configured inside the project it serves.

Mount the project root — the directory holding `chromia.yml` and `src/` — because only what is
mounted is visible to the language server.

### Pick your runtime

On Linux, use `docker` with `--user "$(id -u):$(id -g)"` — without it, files the server edits come
back owned by root. Podman needs no such flag.

On macOS, use [Apple's `container`](https://github.com/apple/container), started once per boot with
`container system start`, or Docker Desktop or Colima with `docker`. No `--user` needed. Docker
Desktop shares only `/Users` by default.

On Windows, work in WSL2 with Docker Desktop's WSL integration and follow the Linux line, keeping
the project on the WSL filesystem rather than `/mnt/c/...`. Windows-native Docker cannot work: a
Linux container has no `C:\...` path.

Examples below use `docker`; substitute `container` or `podman`.

### Claude Code

Run this from the project root. The shell expands `$(pwd)` as the entry is written, so it is
pinned to this project. On Linux:

```sh
claude mcp add chromia-lsp -- docker run --rm -i \
  --user "$(id -u):$(id -g)" \
  -v "$(pwd):$(pwd)" -w "$(pwd)" \
  registry.gitlab.com/chromaway/core-tools/chromia-lsp-mcp:latest
```

On macOS, with Apple's runtime started (`container system start`):

```sh
claude mcp add chromia-lsp -- container run --rm -i \
  -v "$(pwd):$(pwd)" -w "$(pwd)" \
  registry.gitlab.com/chromaway/core-tools/chromia-lsp-mcp:latest
```

`claude mcp add` defaults to local scope, which is what you want here: the entry is stored under
this project's path and loads only in it. Avoid `--scope user`, which would offer one project's
hardcoded path in every other project you open. `claude mcp list` shows whether it connects, and
`claude mcp remove chromia-lsp` undoes it.

For a team, `--scope project` writes the entry to `.mcp.json` in the repository instead. That file
travels to other machines, so the absolute path has to come from somewhere per-developer — Claude
Code expands `${VAR}` in arguments, so have everyone set one variable in their shell:

```json
"args": [
  "run", "--rm", "-i",
  "-v", "${RELL_PROJECT_DIR}:${RELL_PROJECT_DIR}",
  "-w", "${RELL_PROJECT_DIR}",
  "registry.gitlab.com/chromaway/core-tools/chromia-lsp-mcp:latest"
]
```

### Cursor

Cursor keeps project servers in `.cursor/mcp.json` at the project root. It substitutes
`${workspaceFolder}`, so the file resolves to the right path on every machine and can be committed:

```json
{
  "mcpServers": {
    "chromia-lsp": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "-v", "${workspaceFolder}:${workspaceFolder}",
        "-w", "${workspaceFolder}",
        "registry.gitlab.com/chromaway/core-tools/chromia-lsp-mcp:latest"
      ]
    }
  }
}
```

`~/.cursor/mcp.json` is the global equivalent and the wrong home for this server: one entry cannot
serve two projects.

### Other MCP clients

Copilot in VS Code reads `.vscode/mcp.json` in the project, names the key `servers` instead of
`mcpServers`, and substitutes `${workspaceFolder}` the same way Cursor does. The entry is otherwise
identical.

A client with no variable substitution needs the project's absolute path spelled out:

```json
{
  "mcpServers": {
    "chromia-lsp": {
      "type": "stdio",
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "-v", "/home/you/my-dapp:/home/you/my-dapp",
        "-w", "/home/you/my-dapp",
        "registry.gitlab.com/chromaway/core-tools/chromia-lsp-mcp:latest"
      ]
    }
  }
}
```

Keep that file out of version control — the path in it is true only on your machine.

### Pinning a version

`:latest` follows releases. Pin a specific one when you want the language server
version to stay put; each image tag carries one Rell language server, recorded in the image label
`com.chromia.rell-lsp.version`. Image tags match the repository's git tags.

## Using it

Your MCP client starts the container; you never run it by hand. Ask the AI agent to work on Rell
code and it drives the tools itself, starting with `start_lsp`. That one defaults to the mounted
working directory, so in the setups above it needs no argument — if the agent ever picks the wrong
place, name the right one:

> Start the Rell LSP server with root directory /home/you/my-dapp

The container lives as long as the client session and holds the language server's index in memory,
so the first query after startup is the slow one.

### Tools

| Tool                    | What it does                                                  |
|-------------------------|---------------------------------------------------------------|
| `start_lsp`             | Start the language server on a project root. Call this first. |
| `restart_lsp_server`    | Restart it, optionally on a different root                    |
| `open_document`         | Open a file for analysis                                      |
| `save_document`         | Push the file's current content so diagnostics refresh        |
| `close_document`        | Close a file and release what it holds                        |
| `get_diagnostics`       | Errors and warnings for one file or all open files            |
| `get_info_on_location`  | Hover: types, docs, and context at a position                 |
| `get_completions`       | Completions valid at a position                               |
| `get_code_actions`      | Quick fixes and refactorings for a range                      |
| `apply_code_action`     | Apply one of them, writing the result to disk                 |
| `get_definition`        | Where a symbol is declared                                    |
| `get_references`        | Every use of a symbol                                         |
| `get_document_symbols`  | Outline of one file                                           |
| `get_workspace_symbols` | Find a symbol anywhere in the project                         |
| `rename_symbol`         | Rename across every file that references it                   |
| `format_document`       | Format a file or a range within it                            |
| `set_log_level`         | Change logging verbosity at runtime                           |

Line and column arguments are 1-based, the way an editor reports them.

### Resources

- `lsp-diagnostics://` — diagnostics for every open file, and `lsp-diagnostics:///path/to/file.rell`
  for one of them. Subscribe to either and the server notifies you whenever the language server
  republishes.
- `lsp-hover:///path/to/file.rell?line=6&column=8`
- `lsp-completions:///path/to/file.rell?line=25&column=10`

### Logging

The server reports what it is doing as MCP log notifications. Ask the assistant to set the log
level to `debug`, or start it that way:

```
-e LOG_LEVEL=debug
```

added to the `docker run` arguments. In Claude Code, `claude --mcp-debug` additionally shows the
raw traffic between client and server. Everything the server writes for humans goes to stderr;
stdout carries the protocol and nothing else.

### Environment variables

| Variable             | Default                             | Purpose                                                                         |
|----------------------|-------------------------------------|---------------------------------------------------------------------------------|
| `LOG_LEVEL`          | `info`                              | `debug`, `info`, `notice`, `warning`, `error`, `critical`, `alert`, `emergency` |
| `RELL_LSP_JAR`       | `/opt/rell-lsp/language-server.jar` | Language server to run                                                          |
| `RELL_LSP_JAVA_OPTS` | —                                   | JVM flags for the language server process, e.g. `-Xmx2g`                        |

## How the mount works

The server runs inside a container, but the agent talks about files by path — the paths it sees on
your machine. So the project is mounted **at the same path inside the container**:

```
-v "/home/you/my-dapp:/home/you/my-dapp" -w "/home/you/my-dapp"
```

With that, `/home/you/my-dapp/src/main.rell` means the same file on both sides and no translation
is needed anywhere. Every example in this README follows that pattern.

## Troubleshooting

`Cannot read /home/you/my-dapp/src/main.rell` means the file is outside the mount. Check that the
`-v` path is the project root and that `-w` matches it.

Empty diagnostics are usually a timing problem: the language server pushes them after indexing,
and only for open files. Open the file first and retry; on a large project the first pass takes a
few seconds.

Files coming back owned by root on Linux means the `--user "$(id -u):$(id -g)"` flag is missing
from the run arguments.

If every tool returns an empty result, the language server probably failed to start. Set
`LOG_LEVEL=debug` and read the client's MCP server log — the language server's own output is
forwarded there.

## Security

An MCP server runs on your machine with your files, so what it executes matters. The retired npm
package downloaded the language server — and on most platforms a whole Java runtime — from package
registries at run time and executed them, with no checksum standing between a registry compromise
and your machine. The image closes that channel: nothing is fetched at run time. The language
server version is pinned in this repository, every build dependency is pinned by SHA-256 in
`gradle/verification-metadata.xml`, the base image is pinned by digest, and each release is an
immutable image you can pin by tag or digest yourself. The container also bounds what the server
can touch: it reads and writes only the project directory you mount.

The supply chain behind the build shrank with the move too. The npm package's lockfile pinned 288
packages, each one an independently owned npm account and a separate thing to trust; MCP's
documented install idiom, `npx -y <server>`, additionally fetches the latest of all of that at
launch, and that channel has already burned the MCP ecosystem — the September 2025 chalk/debug
compromise reached transitive dependencies of the official MCP TypeScript SDK. This server's
runtime classpath is about 35 JARs from a handful of organizations, JetBrains and Eclipse for the
most part, and it changes only when a commit to this repository changes the pinned checksums.
