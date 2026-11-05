package io.github.noamcohen48.tap.junit5

import org.junit.platform.commons.support.AnnotationSupport
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writes tap-run/1 records for one launcher execution containing Tap tests. Loaded by the
 * JUnit Platform service loader; does no device work and never changes a test outcome.
 */
class TapRunListener private constructor(private val configuration: () -> TapConfig) : TestExecutionListener {
    constructor() : this({ TapConfig.current })
    internal constructor(config: TapConfig) : this({ config })
    private var records: RunRecords? = null
    private var plan: TestPlan? = null
    private val failed = AtomicBoolean(false)
    private val warned = AtomicBoolean(false)

    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        records = null
        failed.set(false)
        warned.set(false)
        plan = testPlan
        val tap = testPlan.roots.any { root -> (testPlan.getDescendants(root) + root).any(::isTap) }
        if (tap) {
            safe {
                val config = configuration()
                if (config.report) records = RunRecords(config.artifactsDir)
            }
        }
    }

    override fun executionStarted(testIdentifier: TestIdentifier) {
        val run = records ?: return
        if (testIdentifier.isTest && isTap(testIdentifier)) {
            safe {
                val key = run.startTest(testIdentifier.uniqueId)
                RunReports.bind(Recording(run, testIdentifier.uniqueId, key))
            }
        }
    }

    override fun executionSkipped(
        testIdentifier: TestIdentifier,
        reason: String,
    ) {
        val run = records ?: return
        // Reasons can contain application data; keep the outcome, not exception/reason text.
        val skipped = if (testIdentifier.isTest) setOf(testIdentifier) else plan?.getDescendants(testIdentifier).orEmpty().filter { it.isTest }.toSet()
        skipped.filter(::isTap).forEach { test ->
            safe { run.finishTest(run.startTest(test.uniqueId), "skipped") }
        }
    }

    override fun executionFinished(
        testIdentifier: TestIdentifier,
        testExecutionResult: TestExecutionResult,
    ) {
        val run = records ?: return
        if (testExecutionResult.status == TestExecutionResult.Status.FAILED) failed.set(true)
        if (!testIdentifier.isTest) {
            if (testExecutionResult.status != TestExecutionResult.Status.SUCCESSFUL) {
                safe {
                    run.event(
                        "container_finished",
                        mapOf(
                            "identity" to testIdentifier.uniqueId,
                            "status" to testExecutionResult.status.name.lowercase(),
                            "error_type" to
                                testExecutionResult.throwable
                                    .orElse(null)
                                    ?.javaClass
                                    ?.simpleName,
                        ),
                    )
                }
            }
            return
        }
        val recording = RunReports.current(testIdentifier.uniqueId) ?: return
        try {
            safe {
                val status =
                    when (testExecutionResult.status) {
                        TestExecutionResult.Status.SUCCESSFUL -> "passed"
                        TestExecutionResult.Status.ABORTED -> "skipped"
                        else -> "failed"
                    }
                run.finishTest(recording.key, status, testExecutionResult.throwable.orElse(null)?.javaClass?.simpleName)
            }
        } finally {
            RunReports.unbind(recording)
        }
    }

    override fun testPlanExecutionFinished(testPlan: TestPlan) {
        safe { records?.finish(if (failed.get()) "failed" else "passed") }
        records = null
        plan = null
    }

    private fun isTap(identifier: TestIdentifier): Boolean =
        generateSequence(identifier) { plan?.getParent(it)?.orElse(null) }.any { node ->
            val source = node.source.orElse(null)
            val type =
                when (source) {
                    is ClassSource -> source.javaClass
                    is MethodSource -> source.javaClass
                    else -> null
                }
            type != null && AnnotationSupport.isAnnotated(type, TapTest::class.java)
        }

    private fun safe(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            records?.markIncomplete()
            if (warned.compareAndSet(false, true)) System.err.println("Tap run records incomplete: ${error.javaClass.simpleName}")
        }
    }
}

internal data class Recording(
    val run: RunRecords,
    val identity: String,
    val key: String,
)

/** A stack permits nested launcher invocations without borrowing an outer test's run. */
internal object RunReports {
    private val active = ThreadLocal.withInitial { ArrayDeque<Recording>() }

    fun bind(recording: Recording) {
        active.get().addLast(recording)
    }

    fun current(identity: String): Recording? = active.get().lastOrNull()?.takeIf { it.identity == identity }

    fun unbind(recording: Recording) {
        val stack = active.get()
        if (stack.lastOrNull() == recording) stack.removeLast()
        if (stack.isEmpty()) active.remove()
    }
}
