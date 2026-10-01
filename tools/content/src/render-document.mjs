import { readFileSync } from 'node:fs';

import katex from 'katex';

import { isSafeLocalPath } from './content-types.mjs';

const template = readFileSync(new URL('../templates/document.html', import.meta.url), 'utf8');

export function renderDocument(document) {
  if (!document || !Array.isArray(document.blocks) || document.blocks.length === 0) {
    throw new TypeError('RichDocument.blocks must be a non-empty array');
  }

  const content = document.blocks.map(renderBlock).join('\n');
  const html = template.replace('{{CONTENT}}', content);
  auditRenderedDocument(document, html);
  return html;
}

export function findDocumentMathRuns(document, basePath = '$') {
  if (!document || !Array.isArray(document.blocks)) {
    return [];
  }

  const mathRuns = [];
  document.blocks.forEach((block, blockIndex) => {
    const blockPath = `${basePath}.blocks[${blockIndex}]`;
    if (block?.type === 'displayMath' && typeof block.tex === 'string') {
      mathRuns.push({ path: `${blockPath}.tex`, tex: block.tex, displayMode: true });
    }
    if (block?.type === 'paragraph' || block?.type === 'heading') {
      appendMathRuns(block.runs, `${blockPath}.runs`, mathRuns);
    }
    if (block?.type === 'list' && Array.isArray(block.items)) {
      block.items.forEach((runs, itemIndex) => {
        appendMathRuns(runs, `${blockPath}.items[${itemIndex}]`, mathRuns);
      });
    }
    if (block?.type === 'figure' && block.caption !== undefined) {
      appendMathRuns(block.caption, `${blockPath}.caption`, mathRuns);
    }
  });
  return mathRuns;
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

function appendMathRuns(runs, path, output) {
  if (!Array.isArray(runs)) {
    return;
  }
  runs.forEach((run, index) => {
    if (run?.type === 'inlineMath' && typeof run.tex === 'string') {
      output.push({ path: `${path}[${index}].tex`, tex: run.tex, displayMode: false });
    }
  });
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
