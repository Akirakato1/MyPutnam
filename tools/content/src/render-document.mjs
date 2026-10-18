import { readFileSync } from 'node:fs';

import katex from 'katex';

import { isSafeLocalPath } from './content-types.mjs';
import { findDocumentMathRuns } from './rich-document.mjs';

export { findDocumentMathRuns };

const template = readFileSync(new URL('../templates/document.html', import.meta.url), 'utf8');

export function renderDocument(document, options = {}) {
  if (!document || !Array.isArray(document.blocks) || document.blocks.length === 0) {
    throw new TypeError('RichDocument.blocks must be a non-empty array');
  }

  const content = document.blocks.map(renderBlock).join('\n');
  const sharedAssetPrefix = options.sharedAssetPrefix ?? '../../shared';
  if (!/^(?:\.\.\/)+shared$/.test(sharedAssetPrefix)) {
    throw new TypeError(`Unsafe shared asset prefix: ${sharedAssetPrefix}`);
  }
  const html = template
    .replace('{{SHARED_ASSET_PREFIX}}', escapeAttribute(sharedAssetPrefix))
    .replace('{{SHARED_ASSET_PREFIX}}', escapeAttribute(sharedAssetPrefix))
    .replace('{{TITLE}}', escapeHtml(options.title ?? 'Offline Mathematics Workbook'))
    .replace('{{CONTENT}}', content);
  auditRenderedDocument(document, html);
  return html;
}

export function auditRenderedDocument(document, html, label = '$') {
  const mathRunCount = findDocumentMathRuns(document, label).length;
  const katexRootCount = (String(html).match(/class="katex"/g) ?? []).length;
  if (katexRootCount !== mathRunCount) {
    throw new Error(
      `${label}: expected ${mathRunCount} KaTeX root(s), found ${katexRootCount}`,
    );
  }
  return { mathRunCount, katexRootCount };
}

function renderBlock(block) {
  switch (block.type) {
    case 'paragraph':
      return `<p>${renderRuns(block.runs)}</p>`;
    case 'displayMath':
      return `<div class="math-display">${renderMath(block.tex, true)}</div>`;
    case 'heading':
      return `<h${block.level}>${renderRuns(block.runs)}</h${block.level}>`;
    case 'list': {
      const tag = block.ordered ? 'ol' : 'ul';
      const items = block.items.map((runs) => `<li>${renderRuns(runs)}</li>`).join('');
      return `<${tag}>${items}</${tag}>`;
    }
    case 'figure': {
      if (!isSafeLocalPath(block.path)) {
        throw new TypeError(`Unsafe figure path: ${block.path}`);
      }
      const caption = block.caption ? `<figcaption>${renderRuns(block.caption)}</figcaption>` : '';
      return `<figure><img src="${escapeAttribute(block.path)}" alt="${escapeAttribute(block.alt)}">${caption}</figure>`;
    }
    default:
      throw new TypeError(`Unsupported rich-document block: ${block.type}`);
  }
}

function renderRuns(runs) {
  if (!Array.isArray(runs) || runs.length === 0) {
    throw new TypeError('Inline runs must be a non-empty array');
  }

  return runs.map((run) => {
    if (run.type === 'text') {
      return escapeHtml(run.text);
    }
    if (run.type === 'inlineMath') {
      return renderMath(run.tex, false);
    }
    throw new TypeError(`Unsupported inline run: ${run.type}`);
  }).join('');
}

function renderMath(tex, displayMode) {
  return katex.renderToString(tex, {
    displayMode,
    output: 'htmlAndMathml',
    strict: 'error',
    throwOnError: true,
    trust: false,
  });
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function escapeAttribute(value) {
  return escapeHtml(value).replaceAll('`', '&#96;');
}
