package io.github.noamcohen48.tap.daemon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LogRedactorTest {
    private val redactor = LogRedactor()

    @Test
    fun `auth headers lose their value and keep their name`() {
        assertEquals("Authorization: <redacted>", redactor.redact("Authorization: Bearer abc.def.ghi"))
        assertEquals("--> Cookie: <redacted>", redactor.redact("--> Cookie: session=1; theme=dark"))
        assertEquals("x-api-key=<redacted>", redactor.redact("x-api-key=k_live_123"))
    }

    @Test
    fun `secret values in parameters and json are redacted up to their end`() {
        assertEquals("GET /login?user=noam&password=<redacted>&next=home", redactor.redact("GET /login?user=noam&password=hunter2&next=home"))
        assertEquals("""{"access_token": "<redacted>", "ttl": 60}""", redactor.redact("""{"access_token": "abc123", "ttl": 60}"""))
        assertEquals("apiKey=<redacted>, retries=3", redactor.redact("apiKey=abcdef, retries=3"))
    }

    @Test
    fun `bearer tokens and json web tokens are redacted wherever they stand`() {
        assertEquals("sent bearer <redacted> to server", redactor.redact("sent bearer abcdefgh12345 to server"))
        assertEquals("jwt <redacted> ok", redactor.redact("jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.c2lnbmF0dXJl ok"))
    }

    @Test
    fun `ordinary lines are left as they are`() {
        val line = "onPayClicked: order 42 for the tokenizer at 12:01, password field focused"

        assertEquals(line, redactor.redact(line))
    }

    @Test
    fun `a caller pattern replaces its match or only its first group`() {
        redactor.add(listOf("""card \d{4}""", """order=(\w+)"""))

        assertEquals("paid with <redacted>, order=<redacted> done", redactor.redact("paid with card 4242, order=A17 done"))
    }

    @Test
    fun `a sensitive value is redacted literally and a short one is ignored`() {
        redactor.literal("s3cr3t-pin")
        redactor.literal("12")

        assertEquals("typed <redacted> into field 12", redactor.redact("typed s3cr3t-pin into field 12"))
    }

    @Test
    fun `an unusable pattern is refused and adds nothing`() {
        assertFailsWith<InvalidRedactionException> { redactor.add(listOf("""ok\d""", "(unclosed")) }
        assertFailsWith<InvalidRedactionException> { redactor.add(listOf("x*")) }
        assertFailsWith<InvalidRedactionException> { redactor.add(List(LogRedactor.MAX_PATTERNS + 1) { "p$it" }) }

        assertEquals("ok1", redactor.redact("ok1"))
    }
}
