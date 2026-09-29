import { mkdir, readdir, rm } from 'node:fs/promises';
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';

import { chromium } from 'playwright';

export async function renderReview(contentDir, screenshotDir, options = {}) {
  const contentRoot = resolve(contentDir);
  const screenshotRoot = resolve(screenshotDir);
  assertDisposableDirectory(screenshotRoot);

  const htmlFiles = await findHtmlFiles(contentRoot);
  if (htmlFiles.length === 0) {
    throw new Error(`No generated HTML files found beneath ${contentRoot}`);
  }

  await rm(screenshotRoot, { recursive: true, force: true });
  await mkdir(screenshotRoot, { recursive: true });

  const browser = await chromium.launch({ headless: true });
  const failures = [];
  try {
    for (const htmlFile of htmlFiles) {
      const page = await browser.newPage({
        viewport: options.viewport ?? { width: 860, height: 1100 },
        deviceScaleFactor: 1,
      });
      const relativePath = relative(contentRoot, htmlFile).replaceAll('\\', '/');
      const pageFailures = [];

      page.on('console', (message) => {
        if (message.type() === 'error') {
          pageFailures.push(`console: ${message.text()}`);
        }
      });
      page.on('pageerror', (error) => pageFailures.push(`page: ${error.message}`));
      page.on('requestfailed', (request) => {
        pageFailures.push(`asset: ${request.url()} (${request.failure()?.errorText ?? 'failed'})`);
      });

      try {
        await page.emulateMedia({ reducedMotion: 'reduce' });
        await page.goto(pathToFileURL(htmlFile).href, { waitUntil: 'load' });
        const inspection = await page.evaluate(() => ({
          blank: document.body.innerText.trim().length === 0,
          horizontalOverflow:
            document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
          brokenImages: [...document.images]
            .filter((image) => !image.complete || image.naturalWidth === 0)
            .map((image) => image.getAttribute('src')),
          brokenStylesheets: [...document.querySelectorAll('link[rel="stylesheet"]')]
            .filter((link) => link.sheet === null)
            .map((link) => link.getAttribute('href')),
        }));

        if (inspection.blank) {
          pageFailures.push('document body is blank');
        }
        if (inspection.horizontalOverflow) {
          pageFailures.push('document has horizontal overflow');
        }
        for (const image of inspection.brokenImages) {
          pageFailures.push(`broken image: ${image}`);
        }
        for (const stylesheet of inspection.brokenStylesheets) {
          pageFailures.push(`broken stylesheet: ${stylesheet}`);
        }

        const screenshotPath = join(
          screenshotRoot,
          `${relativePath.replaceAll('/', '__').replace(/\.html$/i, '')}.png`,
        );
        await mkdir(dirname(screenshotPath), { recursive: true });
        await page.screenshot({ path: screenshotPath, fullPage: true, animations: 'disabled' });
      } catch (error) {
        pageFailures.push(error.message);
      } finally {
        await page.close();
      }

      failures.push(...pageFailures.map((failure) => `${relativePath}: ${failure}`));
    }
  } finally {
    await browser.close();
  }

  if (failures.length > 0) {
    throw new Error(`Render review failed:\n${failures.map((failure) => `- ${failure}`).join('\n')}`);
  }

  return {
    files: htmlFiles.map((file) => relative(contentRoot, file).replaceAll('\\', '/')),
    screenshotDir: screenshotRoot,
  };
}

async function findHtmlFiles(root) {
  const files = [];
  await visit(root, files);
  return files.sort((left, right) => left.localeCompare(right));
}

async function visit(directory, files) {
  const entries = await readdir(directory, { withFileTypes: true });
  entries.sort((left, right) => left.name.localeCompare(right.name));
  for (const entry of entries) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      await visit(path, files);
    } else if (entry.isFile() && entry.name.endsWith('.html')) {
      files.push(path);
    }
  }
}

function assertDisposableDirectory(directory) {
  const filesystemRoot = resolve(directory, sep);
  if (directory === filesystemRoot || basename(directory).length < 2 || isAbsolute(basename(directory))) {
    throw new Error(`Refusing to replace unsafe screenshot directory: ${directory}`);
  }
}

async function main() {
  const [contentDir, screenshotDir] = process.argv.slice(2);
  if (!contentDir || !screenshotDir) {
    throw new Error('usage: node render-review.mjs CONTENT_DIR SCREENSHOT_DIR');
  }
  const result = await renderReview(contentDir, screenshotDir);
  process.stdout.write(`Reviewed ${result.files.length} HTML document(s)\n`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
