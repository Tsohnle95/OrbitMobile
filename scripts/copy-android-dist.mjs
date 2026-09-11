#!/usr/bin/env node
/**
 * Copies the freshly built signed release APK to dist/ under a versioned name,
 * and prints its SHA-256 so a specific build can be identified when sharing.
 *
 * Run via `bun run dist:android` (builds first), or standalone after a build.
 */

import { copyFileSync, existsSync, mkdirSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const src = join(root, 'packages/mobile/android/app/build/outputs/apk/release/app-release.apk');

if (!existsSync(src)) {
  console.error(`Release APK not found at ${src}\nBuild it first: bun run build:android:release`);
  process.exit(1);
}

const { version } = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
const outDir = join(root, 'dist');
mkdirSync(outDir, { recursive: true });
const out = join(outDir, `orbit-mobile-${version}.apk`);
copyFileSync(src, out);

const sha256 = createHash('sha256').update(readFileSync(out)).digest('hex');
console.log(`\nAPK      ${out}`);
console.log(`Version  ${version}`);
console.log(`SHA-256  ${sha256}`);
