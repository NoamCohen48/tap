package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.TAP_API_VERSION

/**
 * The client↔server version rule. `tap.v1` only gains fields and calls, so a server at least as
 * new as the engine this client's stubs come from ([TAP_API_VERSION]) understands every request;
 * an older one would silently ignore new fields or answer `UNIMPLEMENTED` mid-test.
 */
internal object ServerVersion {
    private val NUMBERS = Regex("""^(\d+)\.(\d+)\.(\d+)""")

    /**
     * Why a server reporting [daemonVersion] cannot serve a client built against [apiVersion],
     * or null when it can. A version that is not `major.minor.patch` (a development or test
     * server) is not checked.
     */
    fun incompatibility(
        daemonVersion: String,
        apiVersion: String = TAP_API_VERSION,
    ): String? {
        val server = numbers(daemonVersion) ?: return null
        val client = numbers(apiVersion) ?: return null
        if (compareValues(server, client) >= 0) return null
        return "the tap server is version $daemonVersion, older than the engine $apiVersion this client was " +
            "built against; upgrade the server (`tap stop`, then `tap start` with the newer `tap`) or use a client " +
            "built for engine $daemonVersion"
    }

    private fun numbers(version: String): Triple<Int, Int, Int>? =
        NUMBERS.find(version)?.groupValues?.let { (_, major, minor, patch) ->
            Triple(major.toInt(), minor.toInt(), patch.toInt())
        }

    private fun compareValues(
        a: Triple<Int, Int, Int>,
        b: Triple<Int, Int, Int>,
    ): Int = compareBy<Triple<Int, Int, Int>>({ it.first }, { it.second }, { it.third }).compare(a, b)
}
