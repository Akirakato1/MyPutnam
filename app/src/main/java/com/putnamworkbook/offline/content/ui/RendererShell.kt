package com.putnamworkbook.offline.content.ui

class RendererShell {
    fun wrap(fragment: String, packageBaseUrl: String): String = """<!doctype html>
<html><head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="$CSP">
<meta name="viewport" content="width=device-width, initial-scale=1">
<base href="$packageBaseUrl">
<link rel="stylesheet" href="/renderer-static/katex/katex.min.css">
<link rel="stylesheet" href="/renderer-static/content.css">
</head><body>$fragment</body></html>"""

    companion object {
        const val CSP = "default-src 'none'; img-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self'; base-uri 'self'; form-action 'none'; frame-ancestors 'none'"
    }
}
