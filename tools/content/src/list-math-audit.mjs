const rules = [
  { name: 'tex-command', pattern: /(?<!\\)\\[A-Za-z]+/ },
  { name: 'math-script', pattern: /[A-Za-z0-9})\]][_^](?:\{[^{}\n]+\}|[A-Za-z0-9])/ },
  { name: 'symbolic-relation', pattern: /[A-Za-z0-9})\]]['’]*\s*[=<>]\s*[A-Za-z0-9({\[]/ },
];

export function findRawMathInListText(document, basePath = '$') {
  const findings = [];
  if (!Array.isArray(document?.blocks)) {
    return findings;
  }

  document.blocks.forEach((block, blockIndex) => {
    if (block?.type !== 'list' || !Array.isArray(block.items)) {
      return;
    }

    block.items.forEach((runs, itemIndex) => {
      if (!Array.isArray(runs)) {
        return;
      }

      runs.forEach((run, runIndex) => {
        if (run?.type !== 'text' || typeof run.text !== 'string') {
          return;
        }

        // File names and URLs use punctuation literally, rather than as math.
        const text = run.text
          .replace(/\b(?:https?:\/\/|www\.)\S+/gi, ' ')
          .replace(/\b[\w-]+(?:\.[A-Za-z][\w-]*)+\b/g, ' ')
          .replace(/\\\\/g, '');
        const rule = rules.find(({ pattern }) => pattern.test(text));
        if (rule) {
          findings.push({
            path: `${basePath}.blocks[${blockIndex}].items[${itemIndex}][${runIndex}].text`,
            text: run.text,
            rule: rule.name,
          });
        }
      });
    });
  });

  return findings;
}
