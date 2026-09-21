package com.company.tap.samples

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Discovery guard for the device suite: JUnit 5 only executes `@Test` methods that return
 * `void`. Because `tapTest` is generic (`fun <T> tapTest(...): T`), an expression-bodied
 * `fun foo(device: Device) = tapTest { ... }` infers its JVM return type from the block's
 * last expression — `visible()`/`textEquals()` return `Element`, `.also {}` returns its
 * receiver — silently dropping the test from discovery (9 of 13 found). Every device test
 * must therefore be a block-bodied `Unit` method; this reflection check (no devices, no
 * service) fails loudly if one regresses.
 */
class TestDiscoveryTest {
    @Test
    fun allDeviceTestMethodsAreDiscoverableVoid() {
        val suites =
            listOf(
                MainScreenTest::class.java,
                MotionTest::class.java,
                LifecycleTest::class.java,
                MultiDeviceTest::class.java,
            )
        val found =
            suites
                .flatMap { suite ->
                    suite.declaredMethods
                        .filter { it.isAnnotationPresent(Test::class.java) }
                        .map { "${it.declaringClass.simpleName}.${it.name}" }
                }.toSet()
        assertEquals(expectedDeviceTests, found, "device suite @Test inventory changed")
        val nonVoid =
            suites
                .flatMap { suite ->
                    suite.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }
                }.filter { it.returnType != Void.TYPE }
        assertTrue(
            nonVoid.isEmpty(),
            "invisible to JUnit (non-void JVM return): ${nonVoid.map { "${it.declaringClass.simpleName}.${it.name}: ${it.returnType}" }}",
        )
    }

    companion object {
        private val expectedDeviceTests =
            setOf(
                "MainScreenTest.tapsViewAndComposeButtons",
                "MainScreenTest.typesAndClearsText",
                "MainScreenTest.scrollsComposeListUntilItemIsVisible",
                "MainScreenTest.ambiguousTapFailsBeforeAnyInput",
                "MainScreenTest.waitsForAppOwnedSynchronization",
                "MainScreenTest.waitTimeoutIsDiagnosable",
                "MainScreenTest.backAndHomeKeys",
                "MotionTest.waitsForAnimationToEnd",
                "MotionTest.settlesAfterTheHierarchyStopsMoving",
                "MotionTest.screenThatKeepsChangingTimesOut",
                "LifecycleTest.coldLaunchProducesANewProcessAndSurvivesClearData",
                "MultiDeviceTest.drivesTwoDevicesConcurrently",
                "MultiDeviceTest.siblingFailureCancelsWaitWithoutReplay",
            )
    }
}
