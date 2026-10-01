package io.github.noamcohen48.tap.sdk

/**
 * Marks API that is not covered by the compatibility promise: it may change or go away in any
 * release, including a patch. Today that is app synchronization (`App.awaitIdle` and the
 * `tap-sync-sdk` contract behind it), which only works
 * with apps the driver can see (see the app-lifecycle guide). Opt in with
 * `@OptIn(ExperimentalTapApi::class)`.
 */
@RequiresOptIn(message = "Experimental Tap API: it may change in any release.", level = RequiresOptIn.Level.WARNING)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
annotation class ExperimentalTapApi
