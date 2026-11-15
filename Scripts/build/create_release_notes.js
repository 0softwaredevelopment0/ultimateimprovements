#!/usr/bin/env node
'use strict';

// Builds the release body from CHANGELOG.md.
//
// Usage:
//   node Scripts/build/create_release_notes.js [version] [baseline]
//
//   version   release version; defaults to the `version = '...'` line of
//             build.gradle.
//   baseline  the OLDEST changelog section that belongs to this release,
//             INCLUDED. Defaults to the first beta of the same line
//             (1.8.5-beta.1 for a beta or release of 1.8.5), which is what a
//             beta.1 release covers.
//
// Examples:
//   node Scripts/build/create_release_notes.js
//   node Scripts/build/create_release_notes.js 1.8.5-release.1 1.8.5-beta.22

const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const buildFile = fs.readFileSync(path.join(root, 'build.gradle'), 'utf8');
const changelog = fs.readFileSync(path.join(root, 'CHANGELOG.md'), 'utf8');

const versionLine = buildFile.split('\n').find(line => line.trim().startsWith('version = '));
const version = process.argv[2] || (versionLine ? versionLine.split("'")[1] : null);
if (!version) {
  throw new Error('Could not determine project version from build.gradle.');
}

// `1.8.5-beta.21` -> `1.8.5`; the baseline default is the first beta of that line.
const lineNumber = version.split('-')[0];
const defaultBaseline = lineNumber + '-beta.1';
const baseline = process.argv[3] || defaultBaseline;
if (!/^\d+\.\d+\.\d+-[a-z]+\.\d+$/.test(baseline)) {
  throw new Error('Expected a baseline such as 1.8.5-beta.22; received: ' + baseline);
}

const selected = [];
let started = false;
let finished = false;

for (const rawLine of changelog.split('\n')) {
  const line = rawLine.replace(/\r$/, '');

  if (line.startsWith('## [')) {
    if (finished) {
      break;
    }

    const closingBracket = line.indexOf(']', 4);
    if (closingBracket < 0) {
      throw new Error('Malformed changelog heading: ' + line);
    }

    const sectionVersion = line.slice(4, closingBracket);
    if (!started && sectionVersion === version) {
      started = true;
    } else if (started && sectionVersion === baseline) {
      // The baseline section is included, then the collection stops.
      selected.push(line);
      finished = true;
      continue;
    }
  }

  if (started) {
    selected.push(line);
  }
}

if (!started) {
  throw new Error('No CHANGELOG.md section found for ' + version + '.');
}
if (!finished) {
  throw new Error('The ' + baseline + ' baseline section is not in the ' + version +
    ' range; it must follow the ' + version + ' section.');
}

const notes = selected.join('\n').trim();
const intro = baseline === version
  ? 'Release ' + version + '.'
  : 'Changes from ' + baseline + ' through ' + version + '.';
process.stdout.write('# UltimateImprovements ' + version + '\n\n' + intro + '\n\n' + notes + '\n');
