package io.github.noamcohen48.tap.junit5

import io.github.noamcohen48.tap.sdk.DeviceEntry
import org.junit.platform.commons.support.AnnotationSupport
import java.lang.reflect.Method

/** [RequireDevice] without its role. Every part that is set must hold. */
internal data class DeviceCriteria(
    val minApi: Int = 0,
    val maxApi: Int = Int.MAX_VALUE,
    val kind: DeviceKind = DeviceKind.ANY,
    val manufacturer: String = "",
    val modelContains: String = "",
) {
    /** Whether the device [entry] describes may play the role. A device whose properties the server could not read fits only empty criteria. */
    fun matches(entry: DeviceEntry?): Boolean {
        if (this == DeviceCriteria()) return true
        val properties = entry?.properties ?: return false
        return properties.apiLevel in minApi..maxApi &&
            when (kind) {
                DeviceKind.ANY -> true
                DeviceKind.EMULATOR -> properties.emulator
                DeviceKind.PHYSICAL -> !properties.emulator
            } &&
            (manufacturer.isEmpty() || properties.manufacturer.equals(manufacturer, ignoreCase = true)) &&
            (modelContains.isEmpty() || properties.model.contains(modelContains, ignoreCase = true))
    }

    override fun toString(): String =
        listOfNotNull(
            "minApi=$minApi".takeIf { minApi != 0 },
            "maxApi=$maxApi".takeIf { maxApi != Int.MAX_VALUE },
            "kind=$kind".takeIf { kind != DeviceKind.ANY },
            "manufacturer=$manufacturer".takeIf { manufacturer.isNotEmpty() },
            "modelContains=$modelContains".takeIf { modelContains.isNotEmpty() },
        ).joinToString(", ")
}

/**
 * The criteria of each of [roles]: the class's [RequireDevice] annotations, then the method's,
 * which replace the class's for the same role. A class may constrain roles a given test does
 * not use; a method naming a role it does not declare is a usage error (a typo would otherwise
 * silently lift the constraint).
 */
internal fun declaredCriteria(
    testClass: Class<*>,
    method: Method,
    roles: List<String>,
): Map<String, DeviceCriteria> {
    val onMethod = AnnotationSupport.findRepeatableAnnotations(method, RequireDevice::class.java)
    val unknown = onMethod.map { it.role }.filter { it !in roles }
    require(unknown.isEmpty()) { "${method.name} has @RequireDevice for $unknown but declares the roles $roles" }
    return (AnnotationSupport.findRepeatableAnnotations(testClass, RequireDevice::class.java) + onMethod)
        .filter { it.role in roles }
        .associate { it.role to DeviceCriteria(it.minApi, it.maxApi, it.kind, it.manufacturer, it.modelContains) }
}

/**
 * One distinct serial per role from [available] (in its order, so already rotated) that the
 * role's criteria accept, or null when there is no such assignment. A pinned role can only get
 * its pin, and only if the pin fits. The roles with the fewest candidates are placed first, and
 * a placement is undone when a later role is left without a device.
 */
internal fun chooseSerials(
    roles: List<String>,
    criteria: Map<String, DeviceCriteria>,
    available: List<String>,
    entries: Map<String, DeviceEntry>,
    pinned: Map<String, String> = emptyMap(),
): Map<String, String>? {
    val pins = pinned.filterKeys { it in roles }
    val candidates =
        roles.associateWith { role ->
            val allowed = pins[role]?.let(::listOf) ?: available.filter { it !in pins.values }
            allowed.filter { (criteria[role] ?: DeviceCriteria()).matches(entries[it]) }
        }
    val order = roles.sortedBy { candidates.getValue(it).size }
    val chosen = linkedMapOf<String, String>()

    fun place(index: Int): Boolean {
        if (index == order.size) return true
        val role = order[index]
        for (serial in candidates.getValue(role)) {
            if (serial in chosen.values) continue
            chosen[role] = serial
            if (place(index + 1)) return true
            chosen.remove(role)
        }
        return false
    }
    return if (place(0)) roles.associateWith { chosen.getValue(it) } else null
}

/** A device for a skip message: what it is, so the reader sees why it did not fit. */
internal fun describeDevice(
    serial: String,
    entry: DeviceEntry?,
): String {
    val properties = entry?.properties ?: return "$serial (properties unknown)"
    val kind = if (properties.emulator) "emulator" else "${properties.manufacturer} ${properties.model}"
    return "$serial (API ${properties.apiLevel}, $kind)"
}
