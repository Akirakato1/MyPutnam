# Core Handbook v1 review contract

Every article has one independent review record at `review/<categoryId>/<articleId>.md`. The reviewer must inspect the source JSON, rendered article at 336, 512, and 1280 CSS pixels, cited sources, and the deterministic package candidate. Article authors must not approve their own unresolved findings.

Review records contain exactly these level-two headings, in this order:

1. `Identity`
2. `Mathematical correctness`
3. `Notation and conventions`
4. `Worked example`
5. `Search aliases`
6. `Sources and rights`
7. `Render review`
8. `Findings`
9. `Disposition`

`Findings` is `- None.` only after every mathematical, notation, source, rights, alias, relation, rendering, and usability issue has been resolved in the article. Otherwise it lists concrete open findings.

`Disposition` is exactly one of:

- `approved`: the article is mathematically reviewed, all findings are resolved, all required renders pass, and rights are cleared for the intended package.
- `changes-required`: one or more findings remain; the article is not package eligible.
- `blocked-rights`: mathematical review may be complete, but distribution rights are not cleared; the article is not package eligible.

Only `approved` articles whose `rights.redistributionStatus` is `cleared` may be bundled or published.
