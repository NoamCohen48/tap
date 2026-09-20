package com.company.tap.host.validation

import com.company.tap.host.*

import com.company.tap.protocol.DumpHierarchy
import com.company.tap.protocol.Exists
import com.company.tap.protocol.Health
import com.company.tap.protocol.ScrollUntil
import com.company.tap.protocol.Selector
import com.company.tap.protocol.SetText
import com.company.tap.protocol.Tap
import com.company.tap.protocol.WaitVisible
import com.company.tap.protocol.errorCode
import java.io.StringReader
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource

private const val PROBE_DEVICE_PORT = 27183

internal suspend fun runProductProbe(arguments: List<String>) = withContext(Dispatchers.IO) {
    require(arguments.size >= 7) {
        "Product probe requires <serial> <driver.apk> <driver-test.apk> <aut.apk> <package> " +
            "<activity> <tap-text|ready-text|screen-name>..."
    }
    val serial = arguments[0]
    val driverApk = Path.of(arguments[1])
    val driverTestApk = Path.of(arguments[2])
    val autApk = Path.of(arguments[3])
    val autPackage = arguments[4]
    val activity = arguments[5]
    val screens = arguments.drop(6).map(::parseScreen)
    val adb = Adb()
    val store = SessionJournalStore(
        Path.of(System.getProperty("user.home"), ".tap", "sessions"),
        serial,
    )

    store.acquireLease().use {
        val bootId = adb.run(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id")
        val prior = recoverJournal(adb, serial, bootId, store)
        adb.install(serial, autApk)
        adb.install(serial, driverApk)
        adb.install(serial, driverTestApk)

        val generation = Math.addExact(prior?.generation ?: 0L, 1L)
        val sessionId = UUID.randomUUID().toString()
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
        var journal = SessionJournal(
            state = JournalState.CREATING,
            serial = serial,
            bootId = bootId,
            sessionId = sessionId,
            generation = generation,
            devicePort = PROBE_DEVICE_PORT,
        )
        store.write(journal)
        var running: RunningInstrumentation? = null
        var hostPort: Int? = null
        var successful = false
        var cleanupSuccessful = true
        try {
            running = startDriverWithRetry(
                adb = adb,
                serial = serial,
                sessionId = sessionId,
                generation = generation,
                encodedSecret = encodedSecret,
                autPackage = autPackage,
            ) { devicePort ->
                journal = journal.copy(devicePort = devicePort, updatedAtEpochMs = System.currentTimeMillis())
                store.write(journal)
            }
            hostPort = adb.forward(serial, running.devicePort)
            val driverPid = adb.processIds(serial, DRIVER_PACKAGE).single()
            journal = journal.copy(
                state = JournalState.ACTIVE,
                hostPort = hostPort,
                driverPid = driverPid,
                driverStartToken = processStartToken(adb, serial, driverPid),
                driverInstanceId = running.driverInstanceId,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            store.write(journal)

            DriverClient(hostPort, sessionId, generation, secret, serial = serial).use { client ->
                client.execute(Health)
                journal = journal.copy(state = JournalState.READY, updatedAtEpochMs = System.currentTimeMillis())
                store.write(journal)
                adb.run(serial, "shell", "am", "force-stop", autPackage)
                adb.run(
                    serial,
                    "shell", "am", "start", "-W", "-n", "$autPackage/$activity",
                    timeoutMs = 60_000,
                )

                screens.forEach { screen ->
                    if (screen.tapText != "-") {
                        val expectedError = screen.tapText
                            .takeIf { it.startsWith("!") }
                            ?.substringAfter('!')
                            ?.substringBefore(':')
                        val tapText = if (expectedError == null) {
                            screen.tapText
                        } else {
                            screen.tapText.substringAfter(':')
                        }
                        val tap = executeProbeAction(client, autPackage, tapText)
                        if (expectedError == null) {
                            check(tap.ok) { "Could not navigate to ${screen.name}: $tap" }
                        } else {
                            check(tap.errorCode?.name == expectedError) {
                                "Expected $expectedError while entering ${screen.name}, got $tap"
                            }
                        }
                    }
                    val selector = Selector.text(screen.readyText)
                    val ready = client.send(WaitVisible(selector), timeoutMs = 15_000)
                    check(ready.ok) { "Screen ${screen.name} did not become ready: $ready" }
                    printProbeResult(serial, autPackage, screen.name, selector, client)
                }
            }
            successful = true
        } finally {
            if (hostPort != null) {
                runCatching { adb.removeForward(serial, hostPort) }
                    .onFailure { cleanupSuccessful = false }
            }
            if (running != null) {
                runCatching { cleanupInstrumentation(adb, serial, running) }
                    .onFailure { cleanupSuccessful = false }
            }
            store.write(
                journal.copy(
                    state = if (cleanupSuccessful) JournalState.CLOSED else JournalState.QUARANTINED,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            )
        }
        check(successful) { "Product probe did not complete" }
        println("PRODUCT_PROBE_OK serial=$serial package=$autPackage generation=$generation")
    }
}

private data class ProbeScreen(val tapText: String, val readyText: String, val name: String)

private fun executeProbeAction(
    client: DriverClient,
    autPackage: String,
    action: String,
) = when {
    action.startsWith("SET_TEXT@") -> {
        val resource = action.substringAfter('@')
        client.send(SetText(Selector.androidResource(autPackage, resource), "must-not-write"), timeoutMs = 10_000)
    }
    action.startsWith("SCROLL_UNTIL@") -> {
        val resource = action.substringAfter('@')
        client.send(
            ScrollUntil(Selector.text("Missing target"), container = Selector.androidResource(autPackage, resource)),
            timeoutMs = 10_000,
        )
    }
    else -> client.send(Tap(Selector.text(action)), timeoutMs = 10_000)
}

private fun parseScreen(value: String): ProbeScreen {
    val fields = value.split('|', limit = 3)
    require(fields.size == 3 && fields.all(String::isNotBlank)) {
        "Invalid screen '$value'; expected tap-text|ready-text|screen-name"
    }
    return ProbeScreen(fields[0], fields[1], fields[2])
}

private fun printProbeResult(
    serial: String,
    autPackage: String,
    screen: String,
    selector: Selector,
    client: DriverClient,
) {
    val coldStarted = System.nanoTime()
    check(client.execute(Exists(selector)).value)
    val coldMs = elapsedMs(coldStarted)

    val direct = List(100) {
        val started = System.nanoTime()
        check(client.execute(Exists(selector)).value)
        elapsedMs(started)
    }
    val dumps = List(10) {
        val started = System.nanoTime()
        val hierarchy = client.execute(DumpHierarchy, timeoutMs = 15_000).text
        elapsedMs(started) to hierarchy
    }
    val hierarchy = dumps.last().second
    val parseAndQuery = List(100) {
        val started = System.nanoTime()
        val document = parseHierarchy(hierarchy)
        val nodes = document.getElementsByTagName("node")
        check((0 until nodes.length).any { nodes.item(it).attributes?.getNamedItem("text")?.nodeValue == selector.node.text?.value })
        elapsedMs(started)
    }
    val inventory = inventory(hierarchy, autPackage)

    println(
        "PRODUCT_PROBE_RESULT serial=$serial package=$autPackage screen=$screen " +
            "coldMs=${format(coldMs)} directP50Ms=${format(percentile(direct, 50))} " +
            "directP95Ms=${format(percentile(direct, 95))} " +
            "dumpP50Ms=${format(percentile(dumps.map { it.first }, 50))} " +
            "dumpP95Ms=${format(percentile(dumps.map { it.first }, 95))} " +
            "parseQueryP50Ms=${format(percentile(parseAndQuery, 50))} " +
            "parseQueryP95Ms=${format(percentile(parseAndQuery, 95))} " +
            "nodes=${inventory.nodes} text=${inventory.withText} descriptions=${inventory.withDescription} " +
            "resourceIds=${inventory.withResourceId} clickable=${inventory.clickable} " +
            "unlabeledClickable=${inventory.unlabeledClickable}"
    )
}

private data class AccessibilityInventory(
    val nodes: Int,
    val withText: Int,
    val withDescription: Int,
    val withResourceId: Int,
    val clickable: Int,
    val unlabeledClickable: Int,
)

private fun inventory(hierarchy: String, autPackage: String): AccessibilityInventory {
    val nodes = parseHierarchy(hierarchy).getElementsByTagName("node")
    var withText = 0
    var withDescription = 0
    var withResourceId = 0
    var clickable = 0
    var unlabeledClickable = 0
    repeat(nodes.length) { index ->
        val node = nodes.item(index) as Element
        val attributes = node.attributes
        if (attributes.getNamedItem("package")?.nodeValue != autPackage) return@repeat
        val text = attributes?.getNamedItem("text")?.nodeValue.orEmpty()
        val description = attributes?.getNamedItem("content-desc")?.nodeValue.orEmpty()
        if (text.isNotBlank()) withText++
        if (description.isNotBlank()) withDescription++
        if (attributes?.getNamedItem("resource-id")?.nodeValue.orEmpty().isNotBlank()) withResourceId++
        if (attributes?.getNamedItem("clickable")?.nodeValue == "true") {
            clickable++
            if (text.isBlank() && description.isBlank() && !hasDescendantLabel(node)) {
                unlabeledClickable++
            }
        }
    }
    return AccessibilityInventory(
        (0 until nodes.length).count {
            nodes.item(it).attributes?.getNamedItem("package")?.nodeValue == autPackage
        },
        withText,
        withDescription,
        withResourceId,
        clickable,
        unlabeledClickable,
    )
}

private fun hasDescendantLabel(element: Element): Boolean {
    val descendants = element.getElementsByTagName("node")
    return (0 until descendants.length).any { index ->
        val attributes = descendants.item(index).attributes
        attributes?.getNamedItem("text")?.nodeValue.orEmpty().isNotBlank() ||
            attributes?.getNamedItem("content-desc")?.nodeValue.orEmpty().isNotBlank()
    }
}

private fun parseHierarchy(hierarchy: String) = DocumentBuilderFactory.newInstance().apply {
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setFeature("http://xml.org/sax/features/external-general-entities", false)
    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
}.newDocumentBuilder().parse(InputSource(StringReader(hierarchy)))

private fun elapsedMs(startedNanos: Long): Double = (System.nanoTime() - startedNanos) / 1_000_000.0

private fun percentile(values: List<Double>, percentile: Int): Double {
    require(values.isNotEmpty() && percentile in 1..100)
    val sorted = values.sorted()
    val index = kotlin.math.ceil(percentile / 100.0 * sorted.size).toInt() - 1
    return sorted[index.coerceIn(sorted.indices)]
}

private fun format(value: Double): String = "%.3f".format(java.util.Locale.US, value)
