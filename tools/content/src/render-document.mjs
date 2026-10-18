import { readFileSync } from 'node:fs';

import katex from 'katex';

import { isSafeLocalPath } from './content-types.mjs';
import { findDocumentMathRuns, validateRichDocument } from './rich-document.mjs';

export { findDocumentMathRuns };

const template = readFileSync(new URL('../templates/document.html', import.meta.url), 'utf8');

export function renderDocument(document, options = {}) {
  const richDocumentVersion = options.richDocumentVersion ?? 1;
  const content = renderDocumentFragment(document, { richDocumentVersion });
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

export function renderDocumentFragment(document, { richDocumentVersion = 1 } = {}) {
  const errors = validateRichDocument(document, { version: richDocumentVersion });
  if (errors.length > 0) {
    throw new TypeError(`Invalid rich-document v${richDocumentVersion}:\n${errors.map((error) => `- ${error}`).join('\n')}`);
  }
  const fragment = document.blocks.map((block) => renderBlock(block, richDocumentVersion)).join('\n');
  auditRenderedDocument(document, fragment);
  auditRenderedFragment(fragment);
  return fragment;
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

function renderBlock(block, richDocumentVersion) {
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
    case 'table': {
      if (richDocumentVersion !== 2) throw new TypeError('Tables require rich-document v2');
      const caption = `<caption>${renderRuns(block.caption)}</caption>`;
      const headers = block.headers.map((runs) => `<th scope="col">${renderRuns(runs)}</th>`).join('');
      const rows = block.rows.map((row) => `<tr>${row.map((cell) =>
        `<td>${cell.blocks.map((child) => renderBlock(child, 1)).join('\n')}</td>`).join('')}</tr>`).join('');
      return `<div class="table-scroll"><table>${caption}<thead><tr>${headers}</tr></thead><tbody>${rows}</tbody></table></div>`;
    }
    default:
      throw new TypeError(`Unsupported rich-document block: ${block.type}`);
  }
}

function auditRenderedFragment(fragment) {
  const html = String(fragment);
  if (/<\/?(?:html|head|body|script|style|base|frame|iframe|form|object|embed)\b/i.test(html)) {
    throw new Error('Rendered fragment contains a forbidden executable or shell element');
  }
  if (/\son[a-z][a-z\d:_-]*\s*=/i.test(html)) {
    throw new Error('Rendered fragment contains an inline event handler');
  }
  for (const match of html.matchAll(/\s(?:src|href)="([^"]*)"/gi)) {
    const value = match[1];
    if (!isSafeLocalPath(value) && !/^#[A-Za-z_][A-Za-z0-9_.:-]*$/.test(value)) {
      throw new Error(`Rendered fragment contains a nonlocal URL: ${value}`);
    }
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
