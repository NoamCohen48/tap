package io.github.noamcohen48.tap.daemon.core

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** A redaction pattern the caller gave that cannot be used: why. */
class InvalidRedactionException(
    message: String,
) : IllegalArgumentException(message)

/**
 * Replaces secrets in log text with [REDACTED], on the way out of the daemon: the buffer keeps
 * the device's lines as they were, so a pattern added later covers lines captured earlier.
 *
 * Three sources, applied in this order: the exact text of every `sensitive` value typed in the
 * attachment ([literal]), the caller's patterns ([add]), and the built-in shapes of common
 * secrets ([BUILT_IN]). A pattern with a capturing group has only its first group replaced, so
 * the name of a header or parameter stays readable.
 */
internal class LogRedactor {
    private val lock = Any()
    private var literals = emptyList<String>()
    private var patterns = emptyList<Pattern>()

    /** Redacts [value] wherever it appears; ignored when shorter than [MIN_LITERAL_CHARS]. */
    fun literal(value: String) {
        if (value.length < MIN_LITERAL_CHARS) return
        synchronized(lock) {
            // Longest first, so a value that contains another is replaced whole.
            if (value !in literals && literals.size < MAX_LITERALS) literals = (literals + value).sortedByDescending { it.length }
        }
    }

    /** Adds caller patterns; all or none. */
    fun add(expressions: List<String>) {
        val compiled =
            expressions.map { expression ->
                if (expression.isEmpty() || expression.length > MAX_PATTERN_CHARS) {
                    throw InvalidRedactionException("a redaction pattern must be 1 to $MAX_PATTERN_CHARS characters")
                }
                try {
                    Pattern.compile(expression).also {
                        if (it.matcher("").matches()) throw InvalidRedactionException("redaction pattern '$expression' matches the empty string")
                    }
                } catch (error: PatternSyntaxException) {
                    throw InvalidRedactionException("redaction pattern '$expression' is not a regular expression: ${error.description}")
                }
            }
        synchronized(lock) {
            val known = patterns.map { it.pattern() }.toSet()
            val added = compiled.filter { it.pattern() !in known }.distinctBy { it.pattern() }
            if (patterns.size + added.size > MAX_PATTERNS) {
                throw InvalidRedactionException("an attachment takes at most $MAX_PATTERNS redaction patterns")
            }
            patterns = patterns + added
        }
    }

    /** [text] with every secret replaced. */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        val (literals, patterns) = synchronized(lock) { literals to patterns }
        var result = text
        for (literal in literals) if (literal in result) result = result.replace(literal, REDACTED)
        for (pattern in patterns) result = replace(pattern, result)
        for (pattern in BUILT_IN) result = replace(pattern, result)
        return result
    }

    private fun replace(
        pattern: Pattern,
        text: String,
    ): String {
        val matcher = pattern.matcher(text)
        if (!matcher.find()) return text
        val result = StringBuilder(text.length)
        var copied = 0
        do {
            val grouped = matcher.groupCount() >= 1 && matcher.start(1) >= 0
            val start = if (grouped) matcher.start(1) else matcher.start()
            val end = if (grouped) matcher.end(1) else matcher.end()
            if (end > start) {
                result.append(text, copied, start).append(REDACTED)
                copied = end
            }
        } while (matcher.find())
        return result.append(text, copied, text.length).toString()
    }

    companion object {
        const val REDACTED = "<redacted>"
        const val MIN_LITERAL_CHARS = 4
        const val MAX_LITERALS = 256
        const val MAX_PATTERNS = 64
        const val MAX_PATTERN_CHARS = 512

        /** The secret shapes redacted in every attachment; group 1 is the secret. */
        val BUILT_IN: List<Pattern> =
            listOf(
                // `Authorization: Bearer abc`, `Cookie: a=b; c=d`: the rest of the line.
                """(?i)\b(?:proxy-)?(?:authorization|cookie|set-cookie|x-api-key|x-auth-token|x-csrf-token)\b"?\s*[:=]\s*"?(.+)""",
                // `password=abc`, `"access_token": "abc"`, `&api_key=abc`: the value.
                """(?i)\b(?:password|passwd|pwd|passcode|secret|client_secret|token|access_token|refresh_token|id_token|auth_token|api_?key|session_?id)\b"?\s*[:=]\s*"?([^\s"&,;}]+)""",
                """(?i)\bbearer\s+([A-Za-z0-9._~+/=-]{8,})""",
                // A JSON web token: three base64url parts, the first two starting as `{"`.
                """\beyJ[A-Za-z0-9_-]{5,}\.eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*""",
            ).map(Pattern::compile)
    }
}
