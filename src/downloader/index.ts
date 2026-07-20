import axios from 'axios';
import { join } from 'path';
import { homedir } from 'os';
import fs from 'fs-extra';
import { pipeline } from 'stream/promises';
import { XMLParser } from 'fast-xml-parser';
import { debug } from "./../logging/index.js";

const GROUP_ID = 'net.postchain.rell';
const ARTIFACT_ID = 'rell-toolbox-language-server';
const PROJECT_ID = '32802097';
const GITLAB_MAVEN_URL = `https://gitlab.com/api/v4/projects/${PROJECT_ID}/packages/maven`;
const groupPath = GROUP_ID.replace(/\./g, '/');

const xmlParser = new XMLParser();
const JAR_DIR = join(homedir(), '.chromia', 'lsp-mcp');

const getJarFileName = (version: string): string =>
    `rell-toolbox-language-server-${version}-all.jar`;

const getJarFilePath = (version: string): string => 
    join(JAR_DIR, getJarFileName(version));

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

const downloadJarFile = async (version: string): Promise<string> => {
    const filePath = getJarFilePath(version);
    const tempFilePath = `${filePath}.download`;
    const downloadUrl = `${GITLAB_MAVEN_URL}/${groupPath}/${ARTIFACT_ID}/${version}/${getJarFileName(version)}`;
    
    debug(`Downloading rell-toolbox-language-server ${version} from GitLab...`);
    debug(`URL: ${downloadUrl}`);
    
    try {
        const response = await axios.get(downloadUrl, {
            responseType: 'stream',
            timeout: 30000
        });
        
        await pipeline(response.data, fs.createWriteStream(tempFilePath));
        await fs.move(tempFilePath, filePath, { overwrite: true });
        
        debug(`Downloaded ${getJarFileName(version)} successfully`);
        return filePath;
        
    } catch (error: any) {
        if (await fs.pathExists(tempFilePath)) {
            await fs.remove(tempFilePath);
        }
        
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

export const getLocalVersions = async (): Promise<string[]> => {
    if (!await fs.pathExists(JAR_DIR)) {
        return [];
    }
    
    const files = await fs.readdir(JAR_DIR);
    const jarFiles = files.filter(file =>
        file.startsWith('rell-toolbox-language-server-') &&
        file.endsWith('.jar')
    );

    return jarFiles
        .map(file => file.match(/rell-toolbox-language-server-(.+)-all\.jar/)?.[1] || null)
        .filter((version): version is string => version !== null);
};

export const getLatestLocalVersion = async (): Promise<string | null> => {
    const versions = await getLocalVersions();
    if (versions.length === 0) {
        return null;
    }
    
    versions.sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
    return versions[0];
};

export const getLspServerPath = async (requestedVersion?: string): Promise<string> => {
    if (requestedVersion) {
        return await downloadVersion(requestedVersion);
    }
    
    const latestLocalVersion = await getLatestLocalVersion();
    if (latestLocalVersion) {
        debug(`Using latest local version: ${latestLocalVersion}`);
        return getJarFilePath(latestLocalVersion);
    }
    
    debug('No local versions found, downloading latest version...');
    const latestVersion = await fetchLatestVersion();
    return await downloadVersion(latestVersion);
};
