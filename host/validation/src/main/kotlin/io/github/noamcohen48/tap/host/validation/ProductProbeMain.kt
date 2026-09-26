package io.github.noamcohen48.tap.host.validation

import kotlinx.coroutines.runBlocking

/**
 * `tap-product-probe <serial> <driver.apk> <driver-test.apk> <aut.apk> <package> <activity>
 * <tap-text|ready-text|screen-name>...`: drives a third-party AUT through the listed screens
 * read-only and prints selector-vs-dump latency and an accessibility inventory per screen.
 */
fun main(arguments: Array<String>) {
    runBlocking { runProductProbe(arguments.toList()) }
}
