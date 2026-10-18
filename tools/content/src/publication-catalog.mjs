import { isPlainObject, parseProblemKey, parseReferenceKey, problemKey, referenceKey, setKey } from './content-types.mjs';
import { validateLibraryCatalog } from './library-catalog.mjs';
import { validatePackageDescriptor } from './package-contract.mjs';
import { formatSchemaErrors, getSchemaValidator, SCHEMA_IDS } from './schema-registry.mjs';

const validateCatalogSchema = getSchemaValidator(SCHEMA_IDS.publicationCatalog);
const validateManifestSchema = getSchemaValidator(SCHEMA_IDS.publicationManifest);

export function validatePublicationManifest(value) {
  const errors = [];
  if (!validateManifestSchema(value)) errors.push(...formatSchemaErrors(validateManifestSchema.errors));
  if (!isPlainObject(value)) return uniqueSorted(errors);
  addDuplicateErrors((value.includedSets ?? []).map((item) => {
    try { return setKey(item.collectionId, item.setId); } catch { return `${item?.collectionId}/${item?.setId}`; }
  }), '$.includedSets', 'included set', errors);
  addDuplicateErrors(value.includedReferenceCategories ?? [], '$.includedReferenceCategories', 'included reference category', errors);
  return uniqueSorted(errors);
}

export function validatePublicationCatalog(value) {
  const errors = [];
  if (!validateCatalogSchema(value)) errors.push(...formatSchemaErrors(validateCatalogSchema.errors));
  if (!isPlainObject(value)) return uniqueSorted(errors);
  const collections = new Set((value.collections ?? []).map((item) => item.id));
  const topics = new Set((value.topics ?? []).map((item) => item.id));
  const categories = new Set((value.referenceCategories ?? []).map((item) => item.id));

  validateIdentityList(value.collections, '$.collections', 'collection', errors);
  validateIdentityList(value.topics, '$.topics', 'topic', errors);
  validateIdentityList(value.referenceCategories, '$.referenceCategories', 'reference category', errors);
  addDuplicateErrors((value.problems ?? []).map((item) => item.key), '$.problems', 'problem key', errors);
  addDuplicateErrors((value.references ?? []).map((item) => item.key), '$.references', 'reference key', errors);
  const packagePaths = [];

  for (const [index, problem] of (value.problems ?? []).entries()) {
    const path = `$.problems[${index}]`;
    let parsed;
    try { parsed = parseProblemKey(problem.key); } catch { errors.push(`${path}.key: invalid canonical problem key`); }
    if (parsed && (parsed.collectionId !== problem.collectionId || parsed.setId !== problem.setId || parsed.problemId !== problem.problemId)) {
      errors.push(`${path}.key: does not match problem identity fields`);
    }
    if (!collections.has(problem.collectionId)) errors.push(`${path}.collectionId: unknown collection ${problem.collectionId}`);
    for (const topic of problem.topics ?? []) if (!topics.has(topic)) errors.push(`${path}.topics: unknown topic ${topic}`);
    errors.push(...validatePackageDescriptor(problem.package).map((error) => `${path}.package${error.slice(1)}`));
    if (problem.package?.contentType !== 'problem') errors.push(`${path}.package.contentType: expected problem`);
    packagePaths.push(problem.package?.path);
  }
  for (const [index, reference] of (value.references ?? []).entries()) {
    const path = `$.references[${index}]`;
    let parsed;
    try { parsed = parseReferenceKey(reference.key); } catch { errors.push(`${path}.key: invalid canonical reference key`); }
    if (parsed && (parsed.categoryId !== reference.categoryId || parsed.articleId !== reference.articleId)) {
      errors.push(`${path}.key: does not match reference identity fields`);
    }
    if (!categories.has(reference.categoryId)) errors.push(`${path}.categoryId: unknown reference category ${reference.categoryId}`);
    errors.push(...validatePackageDescriptor(reference.package).map((error) => `${path}.package${error.slice(1)}`));
    if (reference.package?.contentType !== 'reference') errors.push(`${path}.package.contentType: expected reference`);
    packagePaths.push(reference.package?.path);
  }
  addDuplicateErrors(packagePaths, '$', 'package path', errors);
  return uniqueSorted(errors);
}

export function buildPublicationCatalog({ publicationManifest, libraryCatalog, problemPackages, referencePackages }) {
  throwIfErrors('publication manifest', validatePublicationManifest(publicationManifest));
  throwIfErrors('library catalog', validateLibraryCatalog(libraryCatalog));
  const includedSets = new Set(publicationManifest.includedSets.map((item) => setKey(item.collectionId, item.setId)));
  const includedCategories = new Set(publicationManifest.includedReferenceCategories);
  const collectionById = new Map(libraryCatalog.collections.map((item) => [item.id, item]));

  const catalog = {
    schemaVersion: 1,
    catalogId: publicationManifest.catalogId,
    catalogRevision: publicationManifest.catalogRevision,
    generatedAt: publicationManifest.generatedAt,
    minAppVersionCode: publicationManifest.minAppVersionCode,
    collections: libraryCatalog.collections
      .filter((collection) => publicationManifest.includedSets.some((item) => item.collectionId === collection.id))
      .map(({ id, title, kind, displayOrder }) => ({ id, title, kind, displayOrder }))
      .sort(byOrderThenId),
    topics: structuredClone(libraryCatalog.topics).sort(byOrderThenId),
    referenceCategories: structuredClone(libraryCatalog.referenceCategories)
      .filter((category) => includedCategories.has(category.id)).sort(byOrderThenId),
    problems: problemPackages
      .filter((item) => includedSets.has(setKey(item.catalogMetadata.collectionId, item.catalogMetadata.setId)))
      .map((item) => ({
        ...structuredClone(item.catalogMetadata),
        rights: structuredClone(collectionById.get(item.catalogMetadata.collectionId)?.rights),
        package: structuredClone(item.descriptor),
      }))
      .sort((left, right) => left.collectionId.localeCompare(right.collectionId)
        || left.setId.localeCompare(right.setId) || left.ordinal - right.ordinal || left.problemId.localeCompare(right.problemId)),
    references: referencePackages
      .filter((item) => includedCategories.has(item.catalogMetadata.categoryId))
      .map((item) => ({ ...structuredClone(item.catalogMetadata), package: structuredClone(item.descriptor) }))
      .sort((left, right) => left.categoryId.localeCompare(right.categoryId)
        || left.displayOrder - right.displayOrder || left.articleId.localeCompare(right.articleId)),
  };
  throwIfErrors('publication catalog', validatePublicationCatalog(catalog));
  return catalog;
}

function validateIdentityList(items = [], path, label, errors) {
  addDuplicateErrors(items.map((item) => item.id), path, `${label} id`, errors);
  addDuplicateErrors(items.map((item) => item.displayOrder), path, `${label} display order`, errors);
}

function addDuplicateErrors(values, path, label, errors) {
  const seen = new Set();
  for (const [index, value] of values.entries()) {
    if (seen.has(value)) errors.push(`${path}[${index}]: duplicate ${label} ${value}`);
    seen.add(value);
  }
}

function byOrderThenId(left, right) {
  return left.displayOrder - right.displayOrder || left.id.localeCompare(right.id);
}

function throwIfErrors(label, errors) {
  if (errors.length) throw new Error(`Invalid ${label}:\n${errors.map((error) => `- ${error}`).join('\n')}`);
}

function uniqueSorted(errors) {
  return [...new Set(errors)].sort((left, right) => left.localeCompare(right));
}
