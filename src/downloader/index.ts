import axios from 'axios';
import { join } from 'path';
import { homedir } from 'os';
import fs from 'fs-extra';
import { pipeline } from 'stream/promises';
import { execFile, execSync } from 'child_process';
import { promisify } from 'util';
import { XMLParser } from 'fast-xml-parser';
import { debug, info, warning } from "./../logging/index.js";

const execFileAsync = promisify(execFile);

const GROUP_ID = 'net.postchain.rell';
const ARTIFACT_ID = 'rell-toolbox-language-server';
const PROJECT_ID = '32802097';
const GITLAB_MAVEN_URL = `https://gitlab.com/api/v4/projects/${PROJECT_ID}/packages/maven`;
const groupPath = GROUP_ID.replace(/\./g, '/');

// This project's generic package registry, where CI publishes jlink runtime bundles
// (a trimmed JRE with the LSP fat JAR inside) built by scripts/build-jlink-bundles.sh.
const MCP_PROJECT_ID = '74441198';
const GITLAB_GENERIC_URL = `https://gitlab.com/api/v4/projects/${MCP_PROJECT_ID}/packages/generic/rell-lsp-runtime`;

const xmlParser = new XMLParser();
const JAR_DIR = join(homedir(), '.chromia', 'lsp-mcp');

/** How to launch the LSP server: a Java executable and the server JAR to pass to it. */
export interface LspServerLaunch {
    javaPath: string;
    jarPath: string;
    /** True when javaPath comes from a downloaded jlink runtime bundle rather than the user's Java. */
    bundled: boolean;
}

// Bundle classifier for this machine, or null when no bundle is published for it.
const RUNTIME_CLASSIFIER: string | null = (() => {
    const os: Record<string, string> = { linux: 'linux', darwin: 'macos', win32: 'windows' };
    const arch: Record<string, string> = { x64: 'x64', arm64: 'aarch64' };
    return os[process.platform] && arch[process.arch]
        ? `${os[process.platform]}-${arch[process.arch]}`
        : null;
})();

const getJarFileName = (version: string): string =>
    `rell-toolbox-language-server-${version}-all.jar`;

const getJarFilePath = (version: string): string =>
    join(JAR_DIR, getJarFileName(version));

const getRuntimeDirPath = (version: string): string =>
    join(JAR_DIR, `runtime-${version}-${RUNTIME_CLASSIFIER}`);

const getRuntimeJavaPath = (runtimeDir: string): string =>
    join(runtimeDir, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');

const getRuntimeJarPath = (runtimeDir: string): string =>
    join(runtimeDir, 'language-server.jar');

const fetchLatestVersion = async (): Promise<string> => {
    try {
        const versionsUrl = `${GITLAB_MAVEN_URL}/${groupPath}/${ARTIFACT_ID}/maven-metadata.xml`;

        const response = await axios.get(versionsUrl);
        const parsed = xmlParser.parse(response.data);

        // Prefer <release> over <latest>: <latest> may point to a -SNAPSHOT version
        const latestVersion = parsed.metadata?.versioning?.release
            ?? parsed.metadata?.versioning?.latest;
        if (!latestVersion) {
            throw new Error('Invalid maven-metadata.xml format: latest version not found');
        }
        debug(`Found latest version: ${latestVersion}`);
        return latestVersion;
    } catch (error: any) {
        if (error.response?.status === 404) {
            throw new Error('Maven metadata not found from GitLab');
        }
        throw new Error(`Failed to get latest version: ${error.message}`);
    }
};

const downloadFile = async (url: string, filePath: string, description: string): Promise<void> => {
    const tempFilePath = `${filePath}.download`;

    info(`Downloading ${description} from GitLab...`);
    debug(`URL: ${url}`);
    const startedAt = Date.now();

    try {
        const response = await axios.get(url, {
            responseType: 'stream',
            timeout: 30000
        });

        await pipeline(response.data, fs.createWriteStream(tempFilePath));
        await fs.move(tempFilePath, filePath, { overwrite: true });

        const stats = await fs.stat(filePath);
        const seconds = ((Date.now() - startedAt) / 1000).toFixed(1);
        info(`Downloaded ${description} (${(stats.size / 1024 / 1024).toFixed(1)} MB) in ${seconds}s`);
    } catch (error: any) {
        if (await fs.pathExists(tempFilePath)) {
            await fs.remove(tempFilePath);
        }
        throw error;
    }
};

const downloadJarFile = async (version: string): Promise<string> => {
    const filePath = getJarFilePath(version);
    const downloadUrl = `${GITLAB_MAVEN_URL}/${groupPath}/${ARTIFACT_ID}/${version}/${getJarFileName(version)}`;

    try {
        await downloadFile(downloadUrl, filePath, `rell-toolbox-language-server ${version}`);
        return filePath;
    } catch (error: any) {
        if (error.response?.status === 404) {
            throw new Error(`Version ${version} not found in GitLab registry`);
        }
        throw new Error(`Failed to download version ${version}: ${error.message}`);
    }
};

const downloadVersion = async (version: string): Promise<string> => {
    const filePath = getJarFilePath(version);
    await fs.ensureDir(JAR_DIR);

    if (await fs.pathExists(filePath)) {
        debug(`Using cached version: ${version}`);
        return filePath;
    }

    return await downloadJarFile(version);
};

// Downloads and unpacks the jlink runtime bundle for this platform. Extraction uses the
// system tar: present on Linux, macOS, and Windows 10+ (bsdtar), and the only consumer
// of a bundle is a platform recent enough to have it.
const downloadRuntimeBundle = async (version: string): Promise<string> => {
    const runtimeDir = getRuntimeDirPath(version);
    const bundleName = `rell-toolbox-language-server-${version}-${RUNTIME_CLASSIFIER}.tar.gz`;
    const bundlePath = join(JAR_DIR, bundleName);
    const tempDir = `${runtimeDir}.extract`;

    await fs.ensureDir(JAR_DIR);
    await downloadFile(`${GITLAB_GENERIC_URL}/${version}/${bundleName}`, bundlePath, `Rell LSP runtime bundle ${version} (${RUNTIME_CLASSIFIER})`);

    try {
        await fs.emptyDir(tempDir);
        // --strip-components=1 drops the bundle's single top-level directory.
        await execFileAsync('tar', ['-xzf', bundlePath, '-C', tempDir, '--strip-components=1']);
        await fs.remove(runtimeDir);
        await fs.move(tempDir, runtimeDir);
        return runtimeDir;
    } catch (error: any) {
        throw new Error(`Failed to extract runtime bundle: ${error.message}`);
    } finally {
        await fs.remove(tempDir);
        await fs.remove(bundlePath);
    }
};

// The user's Java, tried only when no runtime bundle is available: JAVA_HOME first, then PATH.
const findSystemJava = async (): Promise<string | null> => {
    const javaHome = process.env.JAVA_HOME;
    if (javaHome) {
        const javaPath = join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
        if (await fs.pathExists(javaPath)) {
            return javaPath;
        }
        warning(`JAVA_HOME is set but ${javaPath} does not exist; falling back to PATH lookup`);
    }

    try {
        if (process.platform === 'win32') {
            return execSync('where java', { encoding: 'utf8' }).trim().split('\n')[0];
        } else {
            return execSync('which java', { encoding: 'utf8' }).trim();
        }
    } catch (error) {
        debug('Java not found in PATH: ' + (error instanceof Error ? error.message : String(error)));
        return null;
    }
};

export const getLocalVersions = async (): Promise<string[]> => {
    if (!await fs.pathExists(JAR_DIR)) {
        return [];
    }

    const entries = await fs.readdir(JAR_DIR);
    const versions = entries
        .map(entry =>
            entry.match(/^rell-toolbox-language-server-(.+)-all\.jar$/)?.[1]
            ?? (RUNTIME_CLASSIFIER ? entry.match(new RegExp(`^runtime-(.+)-${RUNTIME_CLASSIFIER}$`))?.[1] : null)
            ?? null)
        .filter((version): version is string => version !== null);

    return [...new Set(versions)];
};

export const getLatestLocalVersion = async (): Promise<string | null> => {
    const versions = await getLocalVersions();
    if (versions.length === 0) {
        return null;
    }

    versions.sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
    return versions[0];
};

const resolveVersion = async (requestedVersion?: string): Promise<string> => {
    if (requestedVersion) {
        return requestedVersion;
    }

    const latestLocalVersion = await getLatestLocalVersion();
    if (latestLocalVersion) {
        debug(`Using latest local version: ${latestLocalVersion}`);
        return latestLocalVersion;
    }

    debug('No local versions found, resolving latest version...');
    return await fetchLatestVersion();
};

/**
 * Resolves how to launch the LSP server for a version: preferably a self-contained jlink
 * runtime bundle for this platform (no Java required on the machine), falling back to the
 * plain fat JAR run with the user's Java (JAVA_HOME, then PATH) when no bundle exists for
 * this platform or version.
 */
export const resolveLspServer = async (requestedVersion?: string): Promise<LspServerLaunch> => {
    const version = await resolveVersion(requestedVersion);

    if (RUNTIME_CLASSIFIER) {
        const cachedRuntimeDir = getRuntimeDirPath(version);
        if (await fs.pathExists(getRuntimeJavaPath(cachedRuntimeDir))) {
            debug(`Using cached runtime bundle: ${cachedRuntimeDir}`);
            return { javaPath: getRuntimeJavaPath(cachedRuntimeDir), jarPath: getRuntimeJarPath(cachedRuntimeDir), bundled: true };
        }

        try {
            const runtimeDir = await downloadRuntimeBundle(version);
            return { javaPath: getRuntimeJavaPath(runtimeDir), jarPath: getRuntimeJarPath(runtimeDir), bundled: true };
        } catch (error: any) {
            const status = error.response?.status;
            const reason = status === 404 ? `no bundle published for ${version}-${RUNTIME_CLASSIFIER}` : error.message;
            info(`Runtime bundle unavailable (${reason}); falling back to JAR with system Java`);
        }
    } else {
        debug(`No runtime bundle for platform ${process.platform}-${process.arch}; using JAR with system Java`);
    }

    const jarPath = await downloadVersion(version);
    const javaPath = await findSystemJava();
    if (!javaPath) {
        throw new Error('Java 21+ is required to run the Rell LSP server on this platform (no runtime bundle is available for it). Install a JDK or set JAVA_HOME.');
    }
    return { javaPath, jarPath, bundled: false };
};
