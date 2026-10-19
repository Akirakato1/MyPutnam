# Math reference source format

Math-reference articles use `reference-article.schema.json` version 1 and rich-document version 2. Their stable key is derived, never stored:

`math-reference/<categoryId>/<articleId>`

Store sources at `content-src/reference/articles/<categoryId>/<articleId>.json`. Optional local figures live under the sibling directory `<articleId>/` and use normalized relative paths in `figure` blocks.

Every article provides:

1. title, display order, aliases, and search keywords;
2. notation, definitions, results or methods, and conditions or conventions;
3. one or more worked examples;
4. pitfalls or limitations;
5. typed related-reference and related-problem keys;
6. authoritative source labels/HTTPS URLs and complete rights metadata.

Write prose as text runs and TeX only as `inlineMath` or `displayMath`. Rich-document v2 additionally permits semantic `table` blocks with a caption, column headers, and rectangular rows of rich-document cells. Tables are rendered inside a local horizontal-scroll wrapper; do not replace them with screenshots.

The package compiler emits only:

- `package.json`;
- `source/reference.json`;
- `documents/article.fragment.html`;
- referenced, audited files below `assets/`.

Review notes, provenance records, PDFs, TeX source, and remote resources are never package members. Start from `templates/reference.template.json`, validate/package through the content tools, and keep independent review records outside the package source tree.
