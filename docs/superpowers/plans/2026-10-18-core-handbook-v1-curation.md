# Core Handbook v1 Curation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Curate a mathematically reliable, searchable, properly attributed Core Handbook v1 covering the approved twelve high-school-through-undergraduate categories and requested formula/method families.

**Architecture:** Freeze an explicit article inventory, then curate one category at a time into validated reference JSON plus a separate review record. Every batch passes schema, math/notation, sources/rights, search-alias, relationship, package, and 336/512/1280-width rendering checks before it merges. A final cross-category audit proves inventory and relation closure and builds packages/catalog data without rebuilding the APK.

**Tech Stack:** Reference schema/rich-document v2, Node.js 22/AJV/KaTeX/Playwright, deterministic package/publication tools, authoritative mathematical sources, JSON source and Markdown review records.

**Spec:** `docs/superpowers/specs/2026-10-18-github-content-library-and-math-reference-design.md`

## Global Constraints

- Depends on the completed Math Reference platform source validator, renderer, package builder, and review tooling.
- Write original explanations/examples. Mathematical facts do not authorize copying prose, tables, or diagrams.
- Use authoritative sources and record source URL, attribution, copyright holder when known, license/permission basis, and honest redistribution status.
- Every article includes notation/definitions, results or method, conditions/conventions, at least one worked example, pitfalls/limitations, related keys, aliases/keywords, and attribution.
- TeX belongs only in math runs. Tables remain structured rich-document data.
- Keep symbols and conventions consistent across related articles; explicitly state alternatives where common conventions differ.
- Each article needs an independent correctness/notation/source review recorded under `content-src/reference/review/`; the author cannot mark an unresolved review item passed.
- Do not include review records, source notes, or screenshots in packages.
- Do not create a distributable APK per category. Package/catalog generation is local verification only until the final release plan.

## Review Focus

- Conditions matter as much as formulas: domains, branch conventions, convergence regions, regularity assumptions, invertibility, and distributional caveats must be visible.
- Examples must demonstrate correct use without becoming duplicate competition solutions.
- Search aliases must include common spoken/written names and symbols without pretending to recognize arbitrary formula glyphs.
- Related-reference links must be deliberate and cycle-safe; missing related problems may remain typed optional links.
- Laplace/Fourier articles must share a documented convention matrix and never mix normalization/sign conventions silently.

---

### Task 1: Freeze the handbook inventory and review contract

**Files:**
- Create: `content-src/reference/categories.json`
- Create: `content-src/reference/core-handbook-v1.json`
- Create: `content-src/reference/REVIEW.md`
- Create: `tools/content/src/audit-reference-inventory.mjs`
- Create: `tools/content/test/core-handbook-inventory.test.mjs`
- Modify: `tools/content/package.json`

**Interfaces:**
- `core-handbook-v1.json` freezes category IDs/order and required article keys below.
- `auditReferenceInventory({ sourceRoot, inventoryPath }): Promise<string[]>` checks exact inventory, identity/order uniqueness, required sections, aliases, relation closure, review-record presence/status, rights, and package eligibility.
- Review records use exact headings: `Identity`, `Mathematical correctness`, `Notation and conventions`, `Worked example`, `Search aliases`, `Sources and rights`, `Render review`, `Findings`, and `Disposition`.

**Frozen inventory:**

| Category ID | Required article IDs |
|---|---|
| `algebra-inequalities` | `algebraic-identities`, `exponent-logarithm-laws`, `polynomial-roots-vieta`, `binomial-multinomial-theorems`, `am-gm-and-power-means`, `cauchy-schwarz-holder-minkowski`, `jensen-convexity`, `rearrangement-chebyshev` |
| `sequences-series-recurrences` | `arithmetic-geometric-sequences`, `finite-sums-and-telescoping`, `power-sums`, `infinite-series-tests`, `linear-recurrences-homogeneous`, `linear-recurrences-nonhomogeneous`, `ordinary-generating-functions` |
| `trigonometry` | `angle-addition-identities`, `double-half-multiple-angle`, `product-sum-power-reduction`, `triangle-trigonometry`, `inverse-trig-conventions`, `harmonic-linear-combination`, `weierstrass-substitution` |
| `geometry-vectors` | `coordinate-geometry`, `lines-circles-conics`, `triangle-area-centers`, `circle-power-radical-axis`, `dot-product-projections`, `cross-product-area-volume`, `vector-lines-planes` |
| `complex-numbers` | `complex-algebra`, `modulus-argument`, `polar-form-de-moivre`, `complex-roots`, `roots-of-unity`, `complex-plane-geometry` |
| `number-theory` | `divisibility-gcd-bezout`, `primes-factorization`, `modular-arithmetic`, `fermat-euler-theorems`, `euler-totient`, `chinese-remainder-theorem`, `multiplicative-order`, `p-adic-valuations-legendre`, `divisor-functions-diophantine` |
| `combinatorics-generating-functions` | `permutations-combinations`, `binomial-identities`, `inclusion-exclusion`, `pigeonhole-extremal`, `double-counting`, `recurrence-counting`, `ordinary-generating-functions-combinatorics`, `exponential-generating-functions`, `catalan-numbers` |
| `calculus` | `limits-continuity`, `derivative-rules-table`, `taylor-maclaurin`, `extrema-convexity`, `fundamental-theorem-calculus`, `antiderivative-table`, `substitution`, `integration-by-parts`, `partial-fractions`, `trig-integrals-substitutions`, `reduction-formulas`, `improper-integrals` |
| `linear-algebra` | `matrices-linear-systems`, `determinants`, `vector-spaces-basis`, `rank-nullity`, `eigenvalues-diagonalization`, `inner-products-orthogonality`, `quadratic-forms` |
| `probability` | `probability-counting`, `conditional-probability-bayes`, `discrete-distributions`, `continuous-distributions`, `expectation`, `variance-covariance`, `generating-functions-probability`, `probability-inequalities` |
| `differential-equations` | `separable-equations`, `first-order-linear`, `exact-and-bernoulli`, `second-order-homogeneous`, `forced-second-order`, `variation-of-parameters`, `linear-systems` |
| `laplace-fourier-transforms` | `laplace-definition-convergence`, `laplace-properties`, `laplace-transform-table`, `inverse-laplace`, `convolution-and-ode`, `fourier-series`, `fourier-transform-conventions`, `fourier-transform-properties`, `fourier-transform-table`, `convolution-parseval-distributions` |

- [ ] **Step 1: Write a failing exact-inventory test**

Assert exactly twelve categories in approved order and every key in the table above, with no duplicates or undeclared extras. Pin required high-value aliases such as `phi`, `totient`, `integration by parts`, `Weierstrass substitution`, `linear recurrence`, `Laplace table`, and `Fourier transform`.

- [ ] **Step 2: Write failing audit-contract tests**

Use incomplete fixture articles/reviews to prove each missing section, unresolved review finding, relation error, missing rights field, and category/order mismatch fails with an exact path.

- [ ] **Step 3: Run tests and verify RED**

```powershell
node --test tools/content/test/core-handbook-inventory.test.mjs
```

- [ ] **Step 4: Implement the inventory, review rubric, and independent audit**

The inventory defines required identity, not article prose. A review disposition is one of `approved`, `changes-required`, or `blocked-rights`; only `approved` plus cleared rights is publishable/bundleable.

- [ ] **Step 5: Run tests and commit**

```powershell
git add content-src/reference/categories.json content-src/reference/core-handbook-v1.json content-src/reference/REVIEW.md tools/content/src/audit-reference-inventory.mjs tools/content/test/core-handbook-inventory.test.mjs tools/content/package.json
git commit -m "docs: freeze core handbook inventory"
```

### Shared category-batch procedure

Every Tasks 2–13 uses this exact sequence for the named category and files:

- Source path: `content-src/reference/articles/<categoryId>/<articleId>.json` for every frozen article ID in that task.
- Review path: `content-src/reference/review/<categoryId>/<articleId>.md` for every source file.
- Test path: `tools/content/test/core-handbook-<categoryId>.test.mjs`.
- Disposable render output: `build/reference-review/<categoryId>/<articleId>/{336,512,1280}.png` plus an audit receipt; do not package it.

For each batch:

- [ ] Write the category inventory/coverage test first and verify RED because the sources/reviews are absent.
- [ ] Research authoritative sources, write original structured articles, and record honest rights metadata.
- [ ] Add aliases, keywords, related-reference keys, optional related-problem keys, conditions, worked examples, and pitfalls.
- [ ] Run schema, TeX, relation, alias, and deterministic package tests.
- [ ] Render every article at 336, 512, and 1280 CSS px; inspect clipping, KaTeX, table-local overflow, and readability.
- [ ] Request an independent mathematical/notation/sources review; record all findings and resolve every `changes-required` item.
- [ ] Run the category test, `audit-reference-inventory` scoped to the batch, and the full content-tool suite.
- [ ] Commit only approved source/review/test files with the task's specified message.

### Task 2: Curate Algebra and inequalities

**Files:**
- Create the source/review pairs for all eight `algebra-inequalities` IDs frozen in Task 1.
- Create: `tools/content/test/core-handbook-algebra-inequalities.test.mjs`

**Interfaces:**
- Consumes: the frozen inventory, reference schema/renderer/package builder, and review rubric.
- Produces: eight approved algebra/inequality articles, reviews, aliases, relations, and deterministic packages.

**Required coverage:** Core field/radical/fraction identities and safe-domain caveats; exponent/log laws; polynomial division, roots and Vieta; binomial/multinomial coefficients; AM-GM and power means; Cauchy–Schwarz/Hölder/Minkowski; Jensen and convexity conditions; rearrangement and Chebyshev sum inequality with ordering assumptions.

- [ ] **Step 1: Write the exact category test and verify RED** because all eight source/review pairs are absent.
- [ ] **Step 2: Research and author all eight structured articles** with original prose, conditions, examples, pitfalls, relations, and rights metadata.
- [ ] **Step 3: Run schema/math/package tests and render every article at 336/512/1280 px.**
- [ ] **Step 4: Obtain and record independent math/notation/source review; resolve every required change.**
- [ ] **Step 5: Run the category test, scoped inventory audit, and `npm --prefix tools/content test`; verify GREEN.**
- [ ] **Step 6: Commit:**

```powershell
git add content-src/reference/articles/algebra-inequalities content-src/reference/review/algebra-inequalities tools/content/test/core-handbook-algebra-inequalities.test.mjs
git commit -m "content: curate algebra reference batch"
```

### Task 3: Curate Sequences, series, and recurrences

**Files:**
- Create the source/review pairs for all seven `sequences-series-recurrences` IDs.
- Create: `tools/content/test/core-handbook-sequences-series-recurrences.test.mjs`

**Interfaces:**
- Consumes: the frozen inventory and shared reference authoring/review pipeline.
- Produces: seven approved sequences/series/recurrence articles and verified packages.

**Required coverage:** Arithmetic/geometric finite and infinite formulas with ratio conditions; telescoping and finite summation; common power sums; convergence tests and hypotheses; characteristic-polynomial closed forms including repeated/complex roots; standard particular-solution methods and resonance; ordinary generating functions and coefficient extraction.

- [ ] **Step 1: Write the exact category test and verify RED** on the seven absent source/review pairs.
- [ ] **Step 2: Research and author all seven articles**, including recurrence closed forms/resonance and generating-function conventions.
- [ ] **Step 3: Run schema/math/package checks and render at all three required widths.**
- [ ] **Step 4: Obtain independent review and resolve every mathematical, notation, source, or alias finding.**
- [ ] **Step 5: Run the category test, scoped inventory audit, and full content-tool suite; verify GREEN.**
- [ ] **Step 6: Commit:**

```powershell
git add content-src/reference/articles/sequences-series-recurrences content-src/reference/review/sequences-series-recurrences tools/content/test/core-handbook-sequences-series-recurrences.test.mjs
git commit -m "content: curate sequences and recurrences batch"
```

### Task 4: Curate Trigonometry

**Files:**
- Create the source/review pairs for all seven `trigonometry` IDs.
- Create: `tools/content/test/core-handbook-trigonometry.test.mjs`

**Interfaces:**
- Consumes: the frozen inventory and shared reference authoring/review pipeline.
- Produces: seven approved trigonometry articles with a convention-safe Weierstrass method.

**Required coverage:** Angle addition/subtraction; double/half/multiple angle; product-to-sum/sum-to-product/power reduction; sine/cosine laws and triangle area; inverse-trig domains/principal values; reducing `a sin x + b cos x`; Weierstrass substitution with mapping, differential, endpoint, and improper-integral caveats.

- [ ] **Step 1: Write the exact category test and verify RED** on the seven absent source/review pairs and required Weierstrass alias/link.
- [ ] **Step 2: Research and author all seven articles**, including principal-value/domain and endpoint caveats.
- [ ] **Step 3: Run schema/math/package checks and render at all three required widths.**
- [ ] **Step 4: Obtain independent review and resolve every finding, including the calculus cross-link.**
- [ ] **Step 5: Run the category test, scoped inventory audit, and full content-tool suite; verify GREEN.**
- [ ] **Step 6: Commit:**

```powershell
git add content-src/reference/articles/trigonometry content-src/reference/review/trigonometry tools/content/test/core-handbook-trigonometry.test.mjs
git commit -m "content: curate trigonometry reference batch"
```

### Task 5: Curate Geometry and vectors

**Files:**
- Create the source/review pairs for all seven `geometry-vectors` IDs.
- Create: `tools/content/test/core-handbook-geometry-vectors.test.mjs`

**Interfaces:**
- Consumes: the frozen inventory and shared reference authoring/review pipeline.
- Produces: seven approved geometry/vector articles with consistent coordinate and orientation conventions.

**Required coverage:** Coordinate distances/slopes/loci; line/circle/basic conic forms; triangle area and center formulas with degeneracy notes; circle power/radical axis; dot products/projections/angles; cross products, area and scalar triple volume; parametric/vector lines and planes with parallel/intersection tests.

- [ ] **Step 1: Write the exact category test and verify RED** on the seven absent source/review pairs.
- [ ] **Step 2: Research and author all seven articles** with degeneracy/orientation/dimension conditions.
- [ ] **Step 3: Run schema/math/package checks and render at all three required widths.**
- [ ] **Step 4: Obtain independent review and resolve every correctness, notation, diagram-free clarity, source, or alias finding.**
- [ ] **Step 5: Run the category test, scoped inventory audit, and full content-tool suite; verify GREEN.**
- [ ] **Step 6: Commit:**

```powershell
git add content-src/reference/articles/geometry-vectors content-src/reference/review/geometry-vectors tools/content/test/core-handbook-geometry-vectors.test.mjs
git commit -m "content: curate geometry and vectors batch"
```

### Task 6: Curate Complex numbers

**Files:**
- Create the source/review pairs for all six `complex-numbers` IDs.
- Create: `tools/content/test/core-handbook-complex-numbers.test.mjs`

**Interfaces:**
- Consumes: the frozen inventory and shared reference authoring/review pipeline.
- Produces: six approved complex-number articles with explicit argument/root conventions.

**Required coverage:** Algebra/conjugation/division; modulus/argument and branch conventions; polar/exponential form and De Moivre; nth roots and multiplicity; roots-of-unity sums/products/filtering; plane geometry through complex coordinates, rotations, and distances.

- [ ] **Step 1: Write the exact category test and verify RED** on the six absent source/review pairs.
- [ ] **Step 2: Research and author all six articles**, explicitly handling argument branches and root multiplicity.
- [ ] **Step 3: Run schema/math/package checks and render at all three required widths.**
- [ ] **Step 4: Obtain independent review and resolve every correctness, convention, source, or alias finding.**
- [ ] **Step 5: Run the category test, scoped inventory audit, and full content-tool suite; verify GREEN.**
- [ ] **Step 6: Commit:**

```powershell
git add content-src/reference/articles/complex-numbers content-src/reference/review/complex-numbers tools/content/test/core-handbook-complex-numbers.test.mjs
git commit -m "content: curate complex numbers batch"
```

### Task 7: Curate Number theory

**Files:**
- Create the source/review pairs for all nine `number-theory` IDs.
- Create: `tools/content/test/core-handbook-number-theory.test.mjs`

**Interfaces:**
- Consumes: the frozen inventory and shared reference authoring/review pipeline.
- Produces: nine approved number-theory articles, including the searchable Euler-totient reference.

**Required coverage:** Euclidean algorithm/gcd/lcm/Bézout; prime factorization; congruence algebra and modular inverses; Fermat and Euler theorems with coprimality; Euler's totient formulas/multiplicativity; CRT compatibility/construction; multiplicative order; valuations and Legendre's factorial formula; divisor-count/sum functions and standard linear Diophantine solution forms.

- [ ] **Step 1: Write the exact category test and verify RED** on nine absent pairs and aliases `phi`, `Euler phi`, and `totient`.
- [ ] **Step 2: Research and author all nine articles** with coprimality, existence, modulus, and valuation conditions.
- [ ] **Step 3: Run schema/math/package checks and render at all three required widths.**
- [ ] **Step 4: Obtain independent review and resolve every theorem-condition, notation, source, relation, or alias finding.**
- [ ] **Step 5: Run the category test, scoped inventory audit, and full content-tool suite; verify GREEN.**
- [ ] **Step 6: Commit:**

```powershell
git add content-src/reference/articles/number-theory content-src/reference/review/number-theory tools/content/test/core-handbook-number-theory.test.mjs
git commit -m "content: curate number theory batch"
```

### Task 8: Curate Combinatorics and generating functions

**Files:**
- Create the source/review pairs for all nine `combinatorics-generating-functions` IDs.
- Create: `tools/content/test/core-handbook-combinatorics-generating-functions.test.mjs`

**Interfaces:**
- Consumes: the frozen inventory and shared reference authoring/review pipeline.
- Produces: nine approved combinatorics/generating-function articles with compatible coefficient conventions.

**Required coverage:** Permutations/combinations with repetition; binomial identities; inclusion–exclusion; pigeonhole/extremal counting; double counting; recurrence modeling; ordinary and exponential generating-function operations/coefficient conventions; Catalan forms, recurrence, generating function, and common interpretations.

- [ ] **Step 1: Write the exact category test and verify RED** on nine absent pairs and the required sequences-OGF relation.
- [ ] **Step 2: Research and author all nine articles**, reconciling OGF notation and coefficient extraction with the sequences batch.
- [ ] **Step 3: Run schema/math/package checks and render at all three required widths.**
- [ ] **Step 4: Obtain independent review and resolve every counting-condition, convention, source, relation, or alias finding.**
- [ ] **Step 5: Run the category test, scoped inventory audit, and full content-tool suite; verify GREEN.**
- [ ] **Step 6: Commit:**

```powershell
git add content-src/reference/articles/combinatorics-generating-functions content-src/reference/review/combinatorics-generating-functions tools/content/test/core-handbook-combinatorics-generating-functions.test.mjs
git commit -m "content: curate combinatorics reference batch"
```

### Task 9: Curate Differential and integral calculus

**Files:**
- Create the source/review pairs for all twelve `calculus` IDs.
- Create: `tools/content/test/core-handbook-calculus.test.mjs`

**Interfaces:**
- Consumes: the frozen inventory and shared reference authoring/review pipeline.
- Produces: twelve approved calculus articles, structured derivative/integral tables, and checked reduction formulas.

**Required coverage:** Limit/continuity laws and indeterminate-form cautions; derivative rules/table; Taylor/Maclaurin with remainder/validity; extrema/convexity tests; FTC hypotheses; practical antiderivative table; substitution; integration by parts; partial fractions by factor type; trigonometric integrals/substitutions; representative reduction formulas with base cases; improper-integral definitions/convergence. Cross-link Weierstrass substitution without duplicating it inconsistently.

- [ ] **Step 1: Write the exact category test and verify RED** on twelve absent pairs, required techniques/tables, and Weierstrass cross-link.
- [ ] **Step 2: Research and author all twelve articles** with hypotheses, base cases, convergence conditions, examples, and pitfalls.
- [ ] **Step 3: Run schema/math/package checks, symbolic/numerical spot checks, and three-width renders; record evidence.**
- [ ] **Step 4: Obtain independent calculus review and resolve every formula, condition, notation, table, source, relation, or alias finding.**
- [ ] **Step 5: Run the category test, scoped inventory audit, and full content-tool suite; verify GREEN.**
- [ ] **Step 6: Commit:**

```powershell
git add content-src/reference/articles/calculus content-src/reference/review/calculus tools/content/test/core-handbook-calculus.test.mjs
git commit -m "content: curate calculus reference batch"
```

### Task 10: Curate Linear algebra

**Files:**
- Create the source/review pairs for all seven `linear-algebra` IDs.
- Create: `tools/content/test/core-handbook-linear-algebra.test.mjs`

**Interfaces:**
- Consumes: the frozen inventory and shared reference authoring/review pipeline.
- Produces: seven approved linear-algebra articles with explicit field/dimension/diagonalizability assumptions.

**Required coverage:** Matrix operations and linear systems; determinant properties/expansion; vector spaces/subspaces/bases/dimension; rank-nullity; eigenvalues/eigenvectors/diagonalization conditions; inner products, Gram–Schmidt, orthogonal projections and least squares; quadratic forms and definiteness criteria with assumptions.

- [ ] **Step 1: Write the exact category test and verify RED** on the seven absent source/review pairs.
- [ ] **Step 2: Research and author all seven articles** with field, dimension, rank, orthogonality, and diagonalizability conditions.
- [ ] **Step 3: Run schema/math/package checks and render at all three required widths.**
- [ ] **Step 4: Obtain independent review and resolve every correctness, convention, source, relation, or alias finding.**
- [ ] **Step 5: Run the category test, scoped inventory audit, and full content-tool suite; verify GREEN.**
- [ ] **Step 6: Commit:**

```powershell
git add content-src/reference/articles/linear-algebra content-src/reference/review/linear-algebra tools/content/test/core-handbook-linear-algebra.test.mjs
git commit -m "content: curate linear algebra batch"
```

### Task 11: Curate Probability

**Files:**
- Create the source/review pairs for all eight `probability` IDs.
- Create: `tools/content/test/core-handbook-probability.test.mjs`

**Interfaces:**
- Consumes: the frozen inventory and shared reference authoring/review pipeline.
- Produces: eight approved probability articles with explicit support/parameter/existence conventions.

**Required coverage:** Finite counting/model assumptions; conditional probability/independence/Bayes; common discrete and continuous distributions with parameter conventions/support; expectation and indicator method; variance/covariance and total expectation/variance; probability/PGF/MGF convention and existence conditions; Markov/Chebyshev/union bounds and appropriate caveats.

- [ ] **Step 1: Write the exact category test and verify RED** on the eight absent source/review pairs.
- [ ] **Step 2: Research and author all eight articles**, explicitly stating model assumptions, supports, parameters, and moment existence.
- [ ] **Step 3: Run schema/math/package checks and render at all three required widths.**
- [ ] **Step 4: Obtain independent review and resolve every probabilistic-condition, convention, source, relation, or alias finding.**
- [ ] **Step 5: Run the category test, scoped inventory audit, and full content-tool suite; verify GREEN.**
- [ ] **Step 6: Commit:**

```powershell
git add content-src/reference/articles/probability content-src/reference/review/probability tools/content/test/core-handbook-probability.test.mjs
git commit -m "content: curate probability reference batch"
```

### Task 12: Curate Differential equations

**Files:**
- Create the source/review pairs for all seven `differential-equations` IDs.
- Create: `tools/content/test/core-handbook-differential-equations.test.mjs`

**Interfaces:**
- Consumes: the frozen inventory and shared reference authoring/review pipeline.
- Produces: seven approved ODE articles with existence/domain/resonance conditions and worked solution checks.

**Required coverage:** Separable equations and lost solutions; integrating factors for first-order linear equations; exactness and Bernoulli substitution; second-order constant-coefficient homogeneous cases; standard forcing/undetermined coefficients and resonance; variation of parameters/Wronskian conditions; first-order linear systems and matrix exponential/eigenvalue cases.

- [ ] **Step 1: Write the exact category test and verify RED** on the seven absent source/review pairs.
- [ ] **Step 2: Research and author all seven articles**, retaining lost-solution, interval, Wronskian, and resonance caveats.
- [ ] **Step 3: Run schema/math/package checks, solution-substitution spot checks, and three-width renders.**
- [ ] **Step 4: Obtain independent review and resolve every method, condition, convention, source, relation, or alias finding.**
- [ ] **Step 5: Run the category test, scoped inventory audit, and full content-tool suite; verify GREEN.**
- [ ] **Step 6: Commit:**

```powershell
git add content-src/reference/articles/differential-equations content-src/reference/review/differential-equations tools/content/test/core-handbook-differential-equations.test.mjs
git commit -m "content: curate differential equations batch"
```

### Task 13: Curate Laplace and Fourier transforms

**Files:**
- Create the source/review pairs for all ten `laplace-fourier-transforms` IDs.
- Create: `tools/content/test/core-handbook-laplace-fourier-transforms.test.mjs`
- Create: `content-src/reference/laplace-fourier-conventions.json`

**Interfaces:**
- Consumes: the frozen inventory, shared reference pipeline, and a tracked transform-convention registry.
- Produces: ten approved transform articles whose formulas/tables cite one consistent convention ID.

**Required coverage:** Laplace definition, exponential-order/convergence and region assumptions; linearity/scaling/shifting/differentiation/integration/initial-final-value conditions; a practical transform table; inverse methods and partial fractions; convolution and ODE use; Fourier-series coefficient conventions/convergence caveats; Fourier-transform sign/normalization convention matrix; properties; practical transform table; convolution/Parseval plus delta/distributional caveats. “Common table” is explicitly practical, not exhaustive.

- [ ] **Step 1: Write failing exact-inventory and convention-consistency tests before articles.**

Require every transform formula/table row to name one convention ID from `laplace-fourier-conventions.json`; reject mixed sign or normalization pairs and missing domain/ROC/distribution notes.

- [ ] **Step 2: Research and author all ten articles** with explicit definitions, signs, normalization, domains/ROC, inverse conditions, and distributional caveats.

Numerically/symbolically spot-check transform pairs where ordinary functions permit it; clearly separate formal/distributional identities.

- [ ] **Step 3: Run schema/math/package checks, transform-pair spot checks, and three-width table renders; record evidence.**
- [ ] **Step 4: Obtain two independent reviews, including a transform specialist, and resolve every required change.**
- [ ] **Step 5: Run the category test, convention audit, scoped inventory audit, and full content-tool suite; verify GREEN.**
- [ ] **Step 6: Commit:**

```powershell
git add content-src/reference/articles/laplace-fourier-transforms content-src/reference/review/laplace-fourier-transforms content-src/reference/laplace-fourier-conventions.json tools/content/test/core-handbook-laplace-fourier-transforms.test.mjs
git commit -m "content: curate laplace and fourier batch"
```

### Task 14: Run cross-category closure, packaging, and independent handbook review

**Files:**
- Modify: `tools/content/test/core-handbook-inventory.test.mjs`
- Create: `tools/content/test/core-handbook-integration.test.mjs`
- Modify: `content-src/publications/content-v1.json`
- Modify only for review findings: article/review/tool files from Tasks 1–13.
- Generate only in ignored output: `build/content-publication/content/v1/packages/reference/**/<sha256>.zip`
- Generate only in ignored output: `build/content-publication/content/v1/catalog.json`

**Interfaces:**
- Consumes: all twelve approved category batches and the deterministic publication pipeline.
- Produces: a closed, reviewed, rights-cleared 97-article handbook catalog/package set ready for final Android integration. The combined public feed remains blocked by the Putnam corpus's intentionally unresolved redistribution status.

- [ ] **Step 1: Add failing global inventory/alias/relation tests**

Assert all 12 categories and 97 frozen article keys exist exactly once; every category/order is unique; aliases normalize without ambiguous exact collisions unless explicitly disambiguated; all related-reference keys resolve; all requested high-value families map to at least one article; and review/rights state is publishable.

- [ ] **Step 2: Add global notation/convention tests**

Pin shared meanings for common symbols and document intentional variants. Check duplicate formulas across articles for convention drift, especially log bases, Fourier normalization, transform variables, complex argument branches, recurrence indexing, and probability distribution parameters.

- [ ] **Step 3: Build every reference package twice**

```powershell
npm --prefix tools/content test
node tools/content/src/build-publication.mjs --source content-src --library content-src/library.json --publication content-src/publications/content-v1.json --output build/content-publication
node tools/content/src/audit-publication.mjs --root build/content-publication
```

Assert deterministic bytes, catalog/package/source agreement, no review/provenance leakage, and successful render audit at all widths.

- [ ] **Step 4: Request an independent whole-handbook review**

Assign reviewers by mathematical area and one cross-category editor. Resolve every Critical/Important correctness, convention, source, or usability finding and record the disposition in the affected review files.

- [ ] **Step 5: Run the cleared-rights publication audit**

```powershell
node tools/content/src/audit-publication.mjs --root build/content-publication --require-cleared-rights
```

Expected: the 97-article handbook-only publication passes. The combined 72-problem/97-article publication fails only for the 72 Putnam records whose redistribution status is intentionally unresolved. That failure remains a public-feed release blocker, not a reason to weaken the gate or falsify rights metadata; the ordinary local/offline audit must still pass.

- [ ] **Step 6: Commit integrated curation metadata/tests**

```powershell
git add content-src/reference content-src/publications/content-v1.json tools/content/test/core-handbook-inventory.test.mjs tools/content/test/core-handbook-integration.test.mjs
git commit -m "content: complete core handbook v1"
```

- [ ] **Step 7: Confirm no APK was rebuilt for category batches**

Run `git status --short` and confirm `dist/` was not replaced. The release plan will embed the final package set and build the one signed APK.
