import assert from 'node:assert/strict';
import test from 'node:test';

import { PACKAGE_LIMITS } from '../src/package-contract.mjs';
import { auditPackageAsset, auditPackageFragment } from '../src/safe-package-asset.mjs';

const bytes = (value) => Buffer.from(value, 'utf8');
const safeSvg = bytes('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><path id="shape_1" d="M1 1h8v8H1z" fill="#123456"/></svg>');

test('asset audit accepts minimal safe SVG and supported raster signatures', () => {
  assert.deepEqual(auditPackageAsset({ path: 'assets/square.svg', mediaType: 'image/svg+xml', bytes: safeSvg }), []);
  assert.deepEqual(auditPackageAsset({
    path: 'assets/pixel.png', mediaType: 'image/png',
    bytes: Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  }), []);
  assert.deepEqual(auditPackageAsset({
    path: 'assets/photo.jpg', mediaType: 'image/jpeg', bytes: Buffer.from([0xff, 0xd8, 0xff, 0xd9]),
  }), []);
  assert.deepEqual(auditPackageAsset({
    path: 'assets/image.webp', mediaType: 'image/webp',
    bytes: Buffer.from('RIFF0000WEBP', 'ascii'),
  }), []);
});

test('asset audit rejects unsafe paths, media mismatches, invalid signatures, and oversized bytes', () => {
  assert.ok(auditPackageAsset({ path: '../escape.svg', mediaType: 'image/svg+xml', bytes: safeSvg }).length > 0);
  assert.ok(auditPackageAsset({ path: 'assets/square.png', mediaType: 'image/svg+xml', bytes: safeSvg })
    .some((error) => error.includes('extension')));
  assert.ok(auditPackageAsset({ path: 'assets/fake.png', mediaType: 'image/png', bytes: bytes('not png') })
    .some((error) => error.includes('signature')));
  assert.ok(auditPackageAsset({
    path: 'assets/huge.png', mediaType: 'image/png', bytes: Buffer.alloc(PACKAGE_LIMITS.maxEntryBytes + 1),
  }).some((error) => error.includes('maximum')));
});

test('asset audit rejects active or externally referential SVG constructs', () => {
  const unsafe = [
    '<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>',
    '<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"></svg>',
    '<svg xmlns="http://www.w3.org/2000/svg"><foreignObject/></svg>',
    '<svg xmlns="http://www.w3.org/2000/svg"><image href="https://bad.test/x.png"/></svg>',
    '<svg xmlns="http://www.w3.org/2000/svg"><image href="data:image/png;base64,AAAA"/></svg>',
    '<svg xmlns="http://www.w3.org/2000/svg"><style>path{fill:url(https://bad.test/x)}</style></svg>',
    '<svg xmlns="http://www.w3.org/2000/svg"><path id="bad id" d="M0 0"/></svg>',
    '<!DOCTYPE svg [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><svg xmlns="http://www.w3.org/2000/svg">&xxe;</svg>',
  ];
  for (const svg of unsafe) {
    const errors = auditPackageAsset({ path: 'assets/unsafe.svg', mediaType: 'image/svg+xml', bytes: bytes(svg) });
    assert.ok(errors.length > 0, svg);
  }
});

test('asset audit permits local fragment references but rejects escaping and encoded URLs', () => {
  const local = bytes('<svg xmlns="http://www.w3.org/2000/svg"><defs><path id="safe-id" d="M0 0"/></defs><use href="#safe-id"/></svg>');
  assert.deepEqual(auditPackageAsset({ path: 'assets/local.svg', mediaType: 'image/svg+xml', bytes: local }), []);
  for (const href of ['../other.svg#x', '//bad.test/x', 'javascript:alert(1)', 'https&#58;//bad.test/x']) {
    const svg = bytes(`<svg xmlns="http://www.w3.org/2000/svg"><use href="${href}"/></svg>`);
    assert.ok(auditPackageAsset({ path: 'assets/unsafe.svg', mediaType: 'image/svg+xml', bytes: svg }).length > 0, href);
  }
});

test('fragment audit accepts renderer-shaped inert HTML with local assets', () => {
  const fragment = bytes('<p><span class="katex"><span style="height:0.8em"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><path d="M0 0h10"/></svg></span></span></p><figure><img src="assets/figure.svg" alt="Figure"></figure>');
  assert.deepEqual(auditPackageFragment({
    path: 'documents/statement.fragment.html',
    mediaType: 'text/html; charset=utf-8',
    bytes: fragment,
  }), []);
});

test('fragment audit rejects malformed UTF-8 and executable or nonlocal HTML', () => {
  const unsafe = [
    Buffer.from([0xc3, 0x28]),
    bytes('<script>alert(1)</script>'),
    bytes('<img src=https://bad.test/image.png>'),
    bytes('<a href="javascript&#58;alert(1)">bad</a>'),
    bytes('<a href=" javascript:alert(1)">bad</a>'),
    bytes('<img srcset="https://bad.test/a.png 1x">'),
    bytes('<svg><image xlink:href="https://bad.test/a.svg"/></svg>'),
    bytes('<p onclick="alert(1)">bad</p>'),
    bytes('<span style="background:url(https://bad.test/x)">bad</span>'),
    bytes('<span style=background:url(https://bad.test/x)>bad</span>'),
  ];
  for (const value of unsafe) {
    const errors = auditPackageFragment({
      path: 'documents/statement.fragment.html',
      mediaType: 'text/html; charset=utf-8',
      bytes: value,
    });
    assert.ok(errors.length > 0, Buffer.from(value).toString('hex'));
  }
});
