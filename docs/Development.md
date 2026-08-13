# Development

## Prerequisites

A JDK 21 or newer. Gradle comes from the wrapper, and the Rell language server the tests and image
need is resolved as a dependency from the GitLab Maven registry.

## Everyday commands

```sh
./gradlew build          # compile and run every test
./gradlew test           # tests only
./gradlew run            # run the server on stdio, rooted at the current directory
./gradlew stageRellLsp   # download the Rell language server into build/rell-lsp/
```

## Tests

`src/test/kotlin` holds two kinds. The unit tests cover the text-edit maths and the stdout
preamble filter and need nothing external. `RellLspMcpTest` is the real thing: it starts the
server as a subprocess, speaks MCP to it with the SDK's client, and drives a real Rell language
server against the fixture project in `src/test/resources/rell-project`.

The integration cases run in a fixed order against one server process, because each depends on the
state the previous ones left: nothing can be queried before `start_lsp`, nothing about a file
before it is open. Their coordinates are hardcoded against `example.rell` — editing that fixture
moves all of them. The fixture is copied to a temporary directory first, since the later cases
rename, format, and quick-fix their way through it.

Gradle points the tests at the language server it stages for the image. To try a different one:

```sh
RELL_LSP_JAR=/path/to/language-server.jar ./gradlew test
```

Without a JAR at all the integration class skips itself and the unit tests still run.

## Building the image

```sh
./gradlew jib -Pversion=0.1.0 -PimageTags=0.1.0,latest -Pimage=registry.example.com/chromia-lsp-mcp
```

`jib` pushes straight to a registry. For a local image, `jibBuildTar` writes
`build/jib-image.tar`, which needs a single platform because a tar holds one image rather than a
manifest list:

```sh
./gradlew jibBuildTar -PimagePlatforms=arm64
container image load -i build/jib-image.tar   # or: docker load -i build/jib-image.tar
```

Driving the result by hand is worth doing after protocol-level changes — MCP over stdio is
newline-delimited JSON, one message per line:

```sh
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"1"}}}' \
  | docker run --rm -i -v "$PWD:$PWD" -w "$PWD" chromia-lsp-mcp:latest
```

## Upgrading the Rell language server

Bump `rell-lsp` in `gradle/libs.versions.toml`, regenerate the dependency verification metadata,
and run the tests. That version is what gets baked into the next image.

```sh
./gradlew --write-verification-metadata sha256 help
./gradlew build
```

Every dependency is pinned by SHA-256 in `gradle/verification-metadata.xml` — the language server
JAR included — so a bump fails the build until the metadata is regenerated. Review the diff of the
regenerated file before committing: those checksums are what CI trusts to go into the image.

## Releasing

The git tag is the version — no file holds it. `gradle.properties` carries a placeholder for local
builds, and the deploy job passes the tag in as `-Pversion`.

Before releasing, finalize the changelog on `dev` (or a `support/*` branch). The helper renames the
`## [Unreleased]` section to the version about to be tagged and refreshes the compare links:

```sh
./scripts/finalize-changelog.sh 0.0.9 0.1.0     # previous tag, next tag
git commit -am "docs: finalize CHANGELOG.md for 0.1.0"
```

Then run the manual `release-patch` or `release-minor` job. Both come from the shared
`gitlab-automation` template: they read the latest tag, compute the next one, and push it. The tag
pipeline builds and pushes `<tag>` and `latest` to the project's container registry.

`container-scanning` runs grype against the published `latest` image; it is gated on
`RUN_DEPENDENCY_CHECK == "true"`, so schedule a pipeline with that variable set to use it.
