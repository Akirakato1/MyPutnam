package com.putnamworkbook.offline.data.library.packages

class HtmlFragmentAuditor {
    fun audit(bytes: ByteArray): List<String> {
        val source = try { decodeUtf8(bytes) } catch (_: PackageValidationException) { return listOf("Fragment must be valid UTF-8") }
        val errors = mutableListOf<String>()
        if (FORBIDDEN_ELEMENT.containsMatchIn(source)) errors += "Fragment contains a forbidden active or shell element"
        if (EVENT_HANDLER.containsMatchIn(source)) errors += "Fragment event handlers are forbidden"
        if (SRCDOC.containsMatchIn(source)) errors += "Fragment srcdoc attributes are forbidden"
        if (UNSUPPORTED_URL_ATTRIBUTE.containsMatchIn(source)) errors += "Fragment contains an unsupported URL-bearing attribute"

        val uriMatches = URI_ATTRIBUTE.findAll(source).toList()
        if (uriMatches.size != URL_ATTRIBUTE_COUNT.findAll(source).count()) errors += "Fragment contains a malformed URL attribute"
        uriMatches.forEach { match ->
            val value = match.groupValues.drop(1).firstOrNull(String::isNotEmpty).orEmpty()
            if (value != value.trim() || '&' in value || (!isSafeMemberPath(value) && !ANCHOR.matches(value))) {
                errors += "Fragment contains a nonlocal or encoded URL"
            }
        }

        val styleMatches = STYLE_ATTRIBUTE.findAll(source).toList()
        if (styleMatches.size != STYLE_ATTRIBUTE_COUNT.findAll(source).count() || styleMatches.any { it.groupValues[3].isNotEmpty() }) {
            errors += "Fragment contains a malformed style attribute"
        }
        styleMatches.forEach { match ->
            val value = match.groupValues.drop(1).firstOrNull(String::isNotEmpty).orEmpty()
            if (UNSAFE_STYLE.containsMatchIn(value)) errors += "Fragment contains an unsafe style value"
        }
        return errors.distinct().sorted()
    }

    private companion object {
        val FORBIDDEN_ELEMENT = Regex("</?(?:html|head|body|script|style|base|link|meta|frame|iframe|form|object|embed|audio|video|foreignObject)\\b", RegexOption.IGNORE_CASE)
        val EVENT_HANDLER = Regex("\\son[a-z][a-z\\d:_-]*\\s*=", RegexOption.IGNORE_CASE)
        val SRCDOC = Regex("\\ssrcdoc\\s*=", RegexOption.IGNORE_CASE)
        val UNSUPPORTED_URL_ATTRIBUTE = Regex("\\s(?:srcset|poster|action|formaction|data|xlink:href)\\s*=", RegexOption.IGNORE_CASE)
        val URI_ATTRIBUTE = Regex("\\s(?:src|href)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))", RegexOption.IGNORE_CASE)
        val URL_ATTRIBUTE_COUNT = Regex("\\s(?:src|href)\\s*=", RegexOption.IGNORE_CASE)
        val STYLE_ATTRIBUTE = Regex("\\sstyle\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))", RegexOption.IGNORE_CASE)
        val STYLE_ATTRIBUTE_COUNT = Regex("\\sstyle\\s*=", RegexOption.IGNORE_CASE)
        val UNSAFE_STYLE = Regex("(?:url\\s*\\(|expression\\s*\\(|@import\\b|behavior\\s*:|-moz-binding\\b)", RegexOption.IGNORE_CASE)
        val ANCHOR = Regex("^#[A-Za-z_][A-Za-z0-9_.:-]*$")
    }
}
