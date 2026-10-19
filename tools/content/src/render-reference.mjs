import { renderDocumentFragment } from './render-document.mjs';

const SECTIONS = Object.freeze([
  ['Notation', 'notation'],
  ['Definitions', 'definitions'],
  ['Results and methods', 'results'],
  ['Conditions and conventions', 'conditions'],
]);

export function renderReferenceArticle(record) {
  const parts = [
    `<h1>${escapeHtml(record.title)}</h1>`,
    '<h2>Aliases</h2>',
    `<p>${record.aliases.map(escapeHtml).join(', ')}</p>`,
  ];
  for (const [heading, field] of SECTIONS) {
    parts.push(`<h2>${heading}</h2>`, renderDocumentFragment(record[field], { richDocumentVersion: 2 }));
  }
  parts.push('<h2>Worked examples</h2>');
  for (const example of record.examples) {
    parts.push(`<h3>${escapeHtml(example.title)}</h3>`,
      renderDocumentFragment(example.document, { richDocumentVersion: 2 }));
  }
  parts.push(
    '<h2>Pitfalls and limitations</h2>',
    renderDocumentFragment(record.pitfalls, { richDocumentVersion: 2 }),
    '<h2>Related references</h2>',
    renderKeyList(record.relatedReferences),
    '<h2>Related problems</h2>',
    renderKeyList(record.relatedProblems),
    '<h2>Attribution</h2>',
    `<p>${escapeHtml(record.rights.attribution)}</p>`,
    `<p>Sources: ${record.sources.map(({ label }) => escapeHtml(label)).join('; ')}</p>`,
  );
  return parts.join('\n');
}

function renderKeyList(values) {
  if (values.length === 0) return '<p>None.</p>';
  return `<ul>${values.map((value) => `<li>${escapeHtml(value)}</li>`).join('')}</ul>`;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}
