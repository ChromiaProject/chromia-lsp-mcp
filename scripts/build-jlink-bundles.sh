#!/usr/bin/env bash
# Builds self-contained jlink runtime bundles of the Rell LSP server for every
# supported platform and uploads them to this project's GitLab generic package
# registry. Each bundle is a trimmed Temurin JRE with the LSP fat JAR inside as
# `language-server.jar`; the MCP server downloads the bundle for its platform and
# spawns `bin/java -jar language-server.jar`, so end users never install Java.
#
# jlink cross-targets: it assembles an image for a foreign platform from that
# platform's jmods, so one Linux job builds all six bundles. It does, however,
# refuse jmods newer than itself, so the host-platform Temurin JDK is downloaded
# too and its jdeps/jlink do the work (the CI image's JDK stays out of it).
#
# Inputs (all optional):
#   RELL_LSP_VERSION  LSP version to bundle; default: <release> from Maven metadata
#   CI_JOB_TOKEN      auth for the upload; without it the script builds but skips upload
set -euo pipefail

RELL_PROJECT_ID=32802097
MAVEN_URL="https://gitlab.com/api/v4/projects/${RELL_PROJECT_ID}/packages/maven/net/postchain/rell/rell-toolbox-language-server"
GENERIC_URL="${CI_API_V4_URL:-https://gitlab.com/api/v4}/projects/${CI_PROJECT_ID:-74441198}/packages/generic/rell-lsp-runtime"

# Latest Temurin 21 GA at the time of writing. Update all entries together:
# https://api.adoptium.net/v3/assets/latest/21/hotspot?vendor=eclipse
JDK_RELEASE="jdk-21.0.12%2B8"
JDK_BASE_URL="https://github.com/adoptium/temurin21-binaries/releases/download/${JDK_RELEASE}"
TARGETS=(
  "linux-x64    OpenJDK21U-jdk_x64_linux_hotspot_21.0.12_8.tar.gz       e4446ff06a276155697597cc0f1b15da004ff083f4964a35271ecee567177370"
  "linux-aarch64 OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.12_8.tar.gz  eba38e871b02d407897bfe017ea35352dfc1420ef6d2112425b0c67325ca509d"
  "macos-x64    OpenJDK21U-jdk_x64_mac_hotspot_21.0.12_8.tar.gz         6b85c260eea574a995eacd0b3ee23c8042aa93b23a08e6478edafca0a0333d7f"
  "macos-aarch64 OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12_8.tar.gz    021d629349ebc12a409faa517b837ec80ceee8f58a5ac85c788ecad07ca6881c"
  "windows-x64  OpenJDK21U-jdk_x64_windows_hotspot_21.0.12_8.zip        9ba963ee2371874a74185d18bc7bb2ab9407df7683300855ed7606e0662321d0"
  "windows-aarch64 OpenJDK21U-jdk_aarch64_windows_hotspot_21.0.12_8.zip e7ddf1cf45885cae2cfb8a018db60b0041ef69e96843b93a92b447d9a4cba676"
)

# Modules jdeps' static analysis cannot see: reflective/SPI use by log4j
# (jdk.management), TLS (jdk.crypto.ec), non-default charsets, jar filesystem.
EXTRA_MODULES="jdk.management,jdk.crypto.ec,jdk.charsets,jdk.zipfs"

WORK_DIR="${WORK_DIR:-$(pwd)/build/jlink-bundles}"
mkdir -p "$WORK_DIR"/{jdks,images,out}

fetch() { curl -sSfL --retry 3 --retry-delay 5 "$@"; }

checksum() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64)   HOST_CLASSIFIER=linux-x64 ;;
  Linux-aarch64)  HOST_CLASSIFIER=linux-aarch64 ;;
  Darwin-arm64)   HOST_CLASSIFIER=macos-aarch64 ;;
  Darwin-x86_64)  HOST_CLASSIFIER=macos-x64 ;;
  *) echo "Unsupported build host: $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

if [ -z "${RELL_LSP_VERSION:-}" ]; then
  RELL_LSP_VERSION=$(fetch "${MAVEN_URL}/maven-metadata.xml" | sed -n 's:.*<release>\(.*\)</release>.*:\1:p')
  [ -n "$RELL_LSP_VERSION" ] || { echo "Could not resolve <release> version from Maven metadata" >&2; exit 1; }
fi
echo "Building jlink bundles for Rell LSP ${RELL_LSP_VERSION}"

# Idempotence for scheduled runs: if the last bundle we upload is already
# present, this version is fully published and there is nothing to do.
SENTINEL="${GENERIC_URL}/${RELL_LSP_VERSION}/rell-toolbox-language-server-${RELL_LSP_VERSION}-windows-aarch64.tar.gz"
if fetch -o /dev/null "$SENTINEL" 2>/dev/null; then
  echo "Bundles for ${RELL_LSP_VERSION} already published, nothing to do"
  exit 0
fi

JAR="${WORK_DIR}/rell-toolbox-language-server-${RELL_LSP_VERSION}-all.jar"
if [ ! -f "$JAR" ]; then
  echo "Downloading LSP fat JAR ${RELL_LSP_VERSION}..."
  fetch -o "$JAR" "${MAVEN_URL}/${RELL_LSP_VERSION}/rell-toolbox-language-server-${RELL_LSP_VERSION}-all.jar"
fi

extract_jdk() { # classifier archive
  local dir="${WORK_DIR}/jdks/$1"
  if [ ! -d "$dir" ]; then
    mkdir -p "$dir"
    case "$2" in
      *.zip) (cd "$dir" && "${HOST_JDK_BIN:?}/jar" --extract --file "$2") ;;
      *)     tar -xzf "$2" -C "$dir" ;;
    esac
  fi
}

download_jdk() { # classifier archive-name sha256
  local archive="${WORK_DIR}/jdks/$2"
  if [ ! -f "$archive" ]; then
    echo "Downloading Temurin JDK for $1..."
    fetch -o "$archive" "${JDK_BASE_URL}/$2"
  fi
  local actual
  actual=$(checksum "$archive")
  [ "$actual" = "$3" ] || { echo "Checksum mismatch for $2: expected $3, got $actual" >&2; exit 1; }
}

# The host JDK comes first: its jar/jdeps/jlink tools drive everything else.
for entry in "${TARGETS[@]}"; do
  read -r classifier archive sha <<<"$entry"
  if [ "$classifier" = "$HOST_CLASSIFIER" ] && [ ! -d "${WORK_DIR}/jdks/${classifier}" ]; then
    download_jdk "$classifier" "$archive" "$sha"
    mkdir -p "${WORK_DIR}/jdks/${classifier}"
    tar -xzf "${WORK_DIR}/jdks/${archive}" -C "${WORK_DIR}/jdks/${classifier}"
  fi
done
HOST_JDK_BIN=$(dirname "$(find "${WORK_DIR}/jdks/${HOST_CLASSIFIER}" -type f -name jdeps -path '*/bin/*' | head -1)")
[ -n "$HOST_JDK_BIN" ] || { echo "Host JDK tools not found" >&2; exit 1; }

echo "Computing module set with jdeps..."
MODULES=$("${HOST_JDK_BIN}/jdeps" --multi-release 21 --print-module-deps --ignore-missing-deps "$JAR")
echo "jdeps: ${MODULES}"

for entry in "${TARGETS[@]}"; do
  read -r classifier archive sha <<<"$entry"
  download_jdk "$classifier" "$archive" "$sha"
  extract_jdk "$classifier" "${WORK_DIR}/jdks/${archive}"

  jmods=$(find "${WORK_DIR}/jdks/${classifier}" -type d -name jmods | head -1)
  [ -n "$jmods" ] || { echo "No jmods directory for $classifier" >&2; exit 1; }

  # Keep only modules the target JDK actually ships (jdeps under a different
  # vendor's JDK can resolve modules Temurin does not have).
  available=$(ls "$jmods" | sed 's/\.jmod$//')
  modules=$(echo "${MODULES},${EXTRA_MODULES}" | tr ',' '\n' | sort -u | while read -r m; do
    echo "$available" | grep -qx "$m" && echo "$m" || true
  done | paste -sd, -)

  image="${WORK_DIR}/images/rell-toolbox-language-server-${RELL_LSP_VERSION}-${classifier}"
  rm -rf "$image"
  echo "jlink ${classifier} (modules: ${modules})..."
  "${HOST_JDK_BIN}/jlink" \
    --module-path "$jmods" \
    --add-modules "$modules" \
    --output "$image" \
    --no-header-files \
    --no-man-pages \
    --compress zip-6
  cp "$JAR" "$image/language-server.jar"

  bundle="${WORK_DIR}/out/rell-toolbox-language-server-${RELL_LSP_VERSION}-${classifier}.tar.gz"
  tar -czf "$bundle" -C "${WORK_DIR}/images" "$(basename "$image")"
  echo "Built $(basename "$bundle") ($(du -h "$bundle" | cut -f1))"
done

echo "Smoke-testing host image (${HOST_CLASSIFIER})..."
"${WORK_DIR}/images/rell-toolbox-language-server-${RELL_LSP_VERSION}-${HOST_CLASSIFIER}/bin/java" -version

if [ -z "${CI_JOB_TOKEN:-}" ]; then
  echo "CI_JOB_TOKEN not set; skipping upload. Bundles are in ${WORK_DIR}/out"
  exit 0
fi

# windows-aarch64 (the sentinel) uploads last so a partial upload is retried by
# the next scheduled run instead of being mistaken for a complete one.
for entry in "${TARGETS[@]}"; do
  read -r classifier _ _ <<<"$entry"
  bundle="${WORK_DIR}/out/rell-toolbox-language-server-${RELL_LSP_VERSION}-${classifier}.tar.gz"
  echo "Uploading $(basename "$bundle")..."
  fetch -o /dev/null -H "JOB-TOKEN: ${CI_JOB_TOKEN}" \
    --upload-file "$bundle" \
    "${GENERIC_URL}/${RELL_LSP_VERSION}/$(basename "$bundle")"
done
echo "Published jlink bundles for Rell LSP ${RELL_LSP_VERSION}"
