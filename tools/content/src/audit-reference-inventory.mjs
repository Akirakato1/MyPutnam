import { readFile } from 'node:fs/promises';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { validateReferenceArticle } from './validate-reference.mjs';

const REVIEW_HEADINGS = Object.freeze([
  'Identity',
  'Mathematical correctness',
  'Notation and conventions',
  'Worked example',
  'Search aliases',
  'Sources and rights',
  'Render review',
  'Findings',
  'Disposition',
]);
const DISPOSITIONS = new Set(['approved', 'changes-required', 'blocked-rights']);

export async function auditReferenceInventory({ sourceRoot, inventoryPath, categoryIds } = {}) {
  const root = resolve(sourceRoot ?? '.');
  const inventoryFile = resolve(inventoryPath ?? join(root, 'reference', 'core-handbook-v1.json'));
  const errors = [];
  const inventory = await readJson(inventoryFile, errors, displayPath(root, inventoryFile));
  const categoriesPath = join(root, 'reference', 'categories.json');
  const categoriesDocument = await readJson(categoriesPath, errors, displayPath(root, categoriesPath));
  if (!inventory || !categoriesDocument) return uniqueSorted(errors);

  const inventoryCategories = Array.isArray(inventory.categories) ? inventory.categories : [];
  const categories = Array.isArray(categoriesDocument.categories) ? categoriesDocument.categories : [];
  if (inventory.schemaVersion !== 1) errors.push(`${displayPath(root, inventoryFile)}: schemaVersion must equal 1`);
  if (categoriesDocument.schemaVersion !== 1) errors.push('reference/categories.json: schemaVersion must equal 1');
  validateInventoryIdentity(inventoryCategories, errors);
  validateCategoryAgreement(categories, inventoryCategories, errors);

  const frozenKeys = new Set(inventoryCategories.flatMap((category) =>
    (category.articles ?? []).map((article) => `math-reference/${category.id}/${article.id}`)));
  const selected = categoryIds == null ? null : new Set(categoryIds);
  if (selected) {
    for (const id of selected) {
      if (!inventoryCategories.some((category) => category.id === id)) errors.push(`inventory: unknown scoped category ${id}`);
    }
  }

  for (const category of inventoryCategories) {
    if (selected && !selected.has(category.id)) continue;
    for (const articleIdentity of category.articles ?? []) {
      const sourceRelative = `reference/articles/${category.id}/${articleIdentity.id}.json`;
      const article = await readJson(join(root, sourceRelative), errors, sourceRelative, 'article is missing');
      if (article) {
        const schemaErrors = validateReferenceArticle(article, { categories });
        errors.push(...schemaErrors.map((error) => `${sourceRelative}: ${error}`));
        if (article.categoryId !== category.id) errors.push(`${sourceRelative}: $.categoryId must equal ${category.id}`);
        if (article.articleId !== articleIdentity.id) errors.push(`${sourceRelative}: $.articleId must equal ${articleIdentity.id}`);
        if (article.displayOrder !== articleIdentity.displayOrder) {
          errors.push(`${sourceRelative}: $.displayOrder must equal ${articleIdentity.displayOrder}`);
        }
        const aliases = new Set((article.aliases ?? []).map(normalizeTerm));
        for (const alias of articleIdentity.requiredAliases ?? []) {
          if (!aliases.has(normalizeTerm(alias))) errors.push(`${sourceRelative}: missing required alias ${alias}`);
        }
        for (const [index, key] of (article.relatedReferences ?? []).entries()) {
          if (!frozenKeys.has(key)) errors.push(`${sourceRelative}: $.relatedReferences[${index}] is outside the frozen inventory`);
        }
        if (article.rights?.redistributionStatus !== 'cleared') {
          errors.push(`${sourceRelative}: rights.redistributionStatus must be cleared`);
        }
      }
      const reviewRelative = `reference/review/${category.id}/${articleIdentity.id}.md`;
      const review = await readText(join(root, reviewRelative));
      if (review === null) errors.push(`${reviewRelative}: review record is missing`);
      else auditReview(review, reviewRelative, errors);
    }
  }
  return uniqueSorted(errors);
}

function validateInventoryIdentity(categories, errors) {
  const categoryIds = new Set();
  const keys = new Set();
  categories.forEach((category, categoryIndex) => {
    if (categoryIds.has(category.id)) errors.push(`inventory: duplicate category id ${category.id}`);
    categoryIds.add(category.id);
    if (category.displayOrder !== categoryIndex + 1) {
      errors.push(`inventory: categories[${categoryIndex}].displayOrder must equal ${categoryIndex + 1}`);
    }
    const articleIds = new Set();
    (category.articles ?? []).forEach((article, articleIndex) => {
      if (articleIds.has(article.id)) errors.push(`inventory: duplicate article id ${category.id}/${article.id}`);
      articleIds.add(article.id);
      if (article.displayOrder !== articleIndex + 1) {
        errors.push(`inventory: ${category.id}.articles[${articleIndex}].displayOrder must equal ${articleIndex + 1}`);
      }
      const key = `math-reference/${category.id}/${article.id}`;
      if (keys.has(key)) errors.push(`inventory: duplicate article key ${key}`);
      keys.add(key);
    });
  });
}

function validateCategoryAgreement(categories, inventoryCategories, errors) {
  if (categories.length !== inventoryCategories.length) {
    errors.push(`reference/categories.json: expected ${inventoryCategories.length} categories, found ${categories.length}`);
  }
  const limit = Math.max(categories.length, inventoryCategories.length);
  for (let index = 0; index < limit; index += 1) {
    const category = categories[index];
    const frozen = inventoryCategories[index];
    if (!category || !frozen) continue;
    if (category.id !== frozen.id) errors.push(`reference/categories.json: categories[${index}].id must equal ${frozen.id}`);
    if (category.label !== frozen.label) errors.push(`reference/categories.json: categories[${index}].label must equal ${frozen.label}`);
    if (category.displayOrder !== index + 1) {
      errors.push(`reference/categories.json: categories[${index}].displayOrder must equal ${index + 1}`);
    }
  }
}

function auditReview(markdown, path, errors) {
  const headings = [...markdown.matchAll(/^## ([^\r\n]+)\s*$/gm)].map((match) => match[1]);
  for (const heading of REVIEW_HEADINGS) {
    if (!headings.includes(heading)) errors.push(`${path}: missing heading ${heading}`);
  }
  for (const heading of headings) {
    if (!REVIEW_HEADINGS.includes(heading)) errors.push(`${path}: unexpected heading ${heading}`);
  }
  if (headings.filter((heading) => REVIEW_HEADINGS.includes(heading)).join('|') !== REVIEW_HEADINGS.join('|')) {
    errors.push(`${path}: review headings are not in the required order`);
  }
  const findings = section(markdown, 'Findings');
  if (findings.trim() !== '- None.') errors.push(`${path}: findings must be resolved before approval`);
  const disposition = section(markdown, 'Disposition').trim().split(/\s+/)[0]?.toLowerCase() ?? '';
  if (!DISPOSITIONS.has(disposition)) errors.push(`${path}: invalid disposition ${disposition || '(missing)'}`);
  else if (disposition !== 'approved') errors.push(`${path}: disposition ${disposition} is not publishable`);
}

function section(markdown, heading) {
  const match = markdown.match(new RegExp(`^## ${escapeRegExp(heading)}\\s*$([\\s\\S]*?)(?=^## |(?![\\s\\S]))`, 'm'));
  return match?.[1]?.trim() ?? '';
}

async function readJson(path, errors, label, missingMessage = 'file is missing') {
  const text = await readText(path);
  if (text === null) {
    errors.push(`${label}: ${missingMessage}`);
    return null;
  }
  try {
    return JSON.parse(text);
  } catch (error) {
    errors.push(`${label}: invalid JSON: ${error.message}`);
    return null;
  }
}

async function readText(path) {
  try {
    return await readFile(path, 'utf8');
  } catch (error) {
    if (error?.code === 'ENOENT') return null;
    throw error;
  }
}

function displayPath(root, path) {
  return relative(root, path).replaceAll('\\', '/');
}

function normalizeTerm(value) {
  return String(value).trim().replace(/\s+/g, ' ').toLocaleLowerCase('en-US');
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function uniqueSorted(values) {
  return [...new Set(values)].sort((left, right) => left.localeCompare(right));
}

async function main() {
  const sourceRoot = process.argv[2] ?? 'content-src';
  const errors = await auditReferenceInventory({ sourceRoot });
  if (errors.length > 0) throw new Error(`Reference inventory audit failed:\n${errors.map((error) => `- ${error}`).join('\n')}`);
  process.stdout.write('Reference inventory audit passed\n');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
