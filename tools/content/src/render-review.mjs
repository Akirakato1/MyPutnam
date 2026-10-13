import { mkdir, readdir, rm, writeFile } from 'node:fs/promises';
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';

import { chromium } from 'playwright';

export async function renderReview(contentDir, screenshotDir, options = {}) {
  const contentRoot = resolve(contentDir);
  const screenshotRoot = resolve(screenshotDir);
  assertDisposableDirectory(screenshotRoot);
  const widths = validateWidths(options.widths ?? [480, 720]);

  const htmlFiles = await findHtmlFiles(contentRoot);
  if (htmlFiles.length === 0) {
    throw new Error(`No generated HTML files found beneath ${contentRoot}`);
  }

  await rm(screenshotRoot, { recursive: true, force: true });
  await mkdir(screenshotRoot, { recursive: true });

  const browser = await chromium.launch({ headless: true });
  const failures = [];
  const screenshots = [];
  const entries = [];
  try {
    for (const htmlFile of htmlFiles) {
      const relativePath = relative(contentRoot, htmlFile).replaceAll('\\', '/');
      for (const width of widths) {
        const page = await browser.newPage({
          viewport: { width, height: options.height ?? 1100 },
          deviceScaleFactor: 1,
        });
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
        await page.route('**/*', async (route) => {
          const url = route.request().url();
          if (/^https?:/i.test(url)) {
            pageFailures.push(`external request: ${url}`);
            await route.abort('blockedbyclient');
          } else {
            await route.continue();
          }
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
          katexErrors: [...document.querySelectorAll('.katex-error')]
            .map((element) => element.textContent?.trim() || 'unknown KaTeX error'),
          containsReplacementGlyph: document.body.innerText.includes('\uFFFD'),
          overflowingElements: [...document.querySelectorAll('body *')]
            .filter((element) => {
              if (element.closest('.katex-mathml')) return false;
              // SVG glyphs (notably KaTeX radical tails) may intentionally use a
              // very wide internal coordinate box that is sliced by a fitted
              // HTML wrapper. Their DOMRect is not a layout-overflow signal;
              // the containing HTML box remains covered by this check.
              const radicalTail = element.closest('.hide-tail');
              if (element.namespaceURI === 'http://www.w3.org/2000/svg'
                  && radicalTail?.closest('.katex-html')) return false;
              const style = getComputedStyle(element);
              if (style.display === 'none' || style.visibility === 'hidden') return false;
              const rect = element.getBoundingClientRect();
              return rect.width > 0 && rect.height > 0
                && (rect.left < -1 || rect.right > window.innerWidth + 1);
            })
            .slice(0, 10)
            .map((element) => {
              const rect = element.getBoundingClientRect();
              const label = element.id
                ? element.tagName.toLowerCase() + '#' + element.id
                : element.tagName.toLowerCase()
                  + [...element.classList].slice(0, 2).map((name) => '.' + name).join('');
              return label + ' [' + Math.round(rect.left) + ', ' + Math.round(rect.right) + ']';
            }),
          }));

          if (inspection.blank) pageFailures.push('document body is blank');
          if (inspection.horizontalOverflow) pageFailures.push('document has horizontal overflow');
          for (const image of inspection.brokenImages) pageFailures.push(`broken image: ${image}`);
          for (const stylesheet of inspection.brokenStylesheets) pageFailures.push(`broken stylesheet: ${stylesheet}`);
          for (const katexError of inspection.katexErrors) pageFailures.push(`KaTeX error: ${katexError}`);
          if (inspection.containsReplacementGlyph) {
            pageFailures.push('document contains a missing-glyph replacement character');
          }
          for (const element of inspection.overflowingElements) {
            pageFailures.push('element exceeds viewport: ' + element);
          }

          const screenshot = `${relativePath.replaceAll('/', '__').replace(/\.html$/i, '')}--w${width}.png`;
          const screenshotPath = join(screenshotRoot, screenshot);
          await mkdir(dirname(screenshotPath), { recursive: true });
          await page.screenshot({ path: screenshotPath, fullPage: true, animations: 'disabled' });
          screenshots.push(screenshot);
          entries.push({
            file: relativePath,
            problemKey: problemKeyFromPath(relativePath),
            width,
            screenshot,
          });
        } catch (error) {
          pageFailures.push(error.message);
        } finally {
          await page.close();
        }

        failures.push(...pageFailures.map((failure) => `${relativePath} @ ${width}px: ${failure}`));
      }
    }
  } finally {
    await browser.close();
  }

  if (failures.length > 0) {
    throw new Error(`Render review failed:\n${failures.map((failure) => `- ${failure}`).join('\n')}`);
  }

  await writeFile(
    join(screenshotRoot, 'review-index.json'),
    JSON.stringify({ schemaVersion: 1, widths, entries }, null, 2) + '\n',
    'utf8',
  );

  return {
    files: htmlFiles.map((file) => relative(contentRoot, file).replaceAll('\\', '/')),
    widths,
    screenshots,
    screenshotDir: screenshotRoot,
  };
}

function validateWidths(values) {
  if (!Array.isArray(values) || values.length === 0) throw new Error('widths must be a non-empty array');
  const widths = values.map((value) => {
    if (!Number.isInteger(value) || value < 240 || value > 2400) {
      throw new Error('review widths must be integers from 240 through 2400 CSS px');
    }
    return value;
  });
  if (new Set(widths).size !== widths.length) throw new Error('review widths must be unique');
  return widths;
}

function problemKeyFromPath(path) {
  const match = path.match(/^content\/([^/]+)\/([^/]+)\/problems\/([^/]+)\//);
  return match ? `${match[1]}/${match[2]}/${match[3]}` : null;
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
