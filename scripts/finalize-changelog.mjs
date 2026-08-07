#!/usr/bin/env node
// Renames the "## [Unreleased]" section in CHANGELOG.md to the version/date being
// released, reinserts a fresh empty Unreleased heading above it, and updates the
// compare-link references at the bottom. Run by release-patch/release-minor in CI,
// right after package.json's version is bumped and before that change is committed.

import { readFileSync, writeFileSync } from 'fs';

const [previousVersion, nextVersion] = process.argv.slice(2);
if (!previousVersion || !nextVersion) {
  console.error('Usage: finalize-changelog.mjs <previousVersion> <nextVersion>');
  process.exit(1);
}

const projectUrl = (process.env.CI_PROJECT_URL ?? 'https://gitlab.com/chromaway/core-tools/chromia-lsp-mcp').replace(/\/$/, '');
const branch = process.env.CI_COMMIT_REF_NAME ?? 'dev';
const date = new Date().toISOString().slice(0, 10);
const path = 'CHANGELOG.md';
const original = readFileSync(path, 'utf8');

const headingMatch = original.match(/^## \[Unreleased\]\r?\n/m);
if (!headingMatch) {
  console.log('No "## [Unreleased]" section found in CHANGELOG.md; leaving it untouched.');
  process.exit(0);
}

let content = original.slice(0, headingMatch.index)
  + `## [Unreleased]\n\n## [${nextVersion}] — ${date}\n`
  + original.slice(headingMatch.index + headingMatch[0].length);

// Drop any stale "[Unreleased]:" link line so re-running this doesn't duplicate it.
content = content.replace(/^\[Unreleased\]:.*\r?\n/m, '');

const newLinks = `[Unreleased]: ${projectUrl}/-/compare/${nextVersion}...${branch}\n`
  + `[${nextVersion}]: ${projectUrl}/-/compare/${previousVersion}...${nextVersion}\n`;

const firstLinkLine = content.match(/^\[\d[^\]]*\]:.*$/m);
content = firstLinkLine
  ? content.slice(0, firstLinkLine.index) + newLinks + content.slice(firstLinkLine.index)
  : content.replace(/\n*$/, '\n\n') + newLinks;

writeFileSync(path, content);
console.log(`CHANGELOG.md: [Unreleased] -> [${nextVersion}] - ${date}`);
