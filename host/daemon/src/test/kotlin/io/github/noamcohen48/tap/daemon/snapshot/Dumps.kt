package io.github.noamcohen48.tap.daemon.snapshot

/**
 * Hierarchy dumps recorded from the fixture app (`io.github.noamcohen48.tap.fixture`) on the
 * local matrix: emulator-5554 (API 34) and 85e49002 (Samsung SM-J810G, API 29).
 */
internal object Dumps {
    const val AUT = "io.github.noamcohen48.tap.fixture"
    const val SYSTEM_UI = "com.android.systemui"

    val names: List<String> =
        listOf("emulator-5554", "85e49002").flatMap { serial ->
            listOf("MainActivity", "ViewListActivity", "AmbiguityActivity").map { "$serial-$it" }
        }

    fun xml(name: String): String = checkNotNull(Dumps::class.java.getResource("/snapshot/$name.xml")) { "no dump $name" }.readText()

    fun hierarchy(name: String): Hierarchy = HierarchyParser.parse(xml(name))

    /** A dump of one window of [AUT] around [body] (raw `<node>` XML). */
    fun wrap(body: String): String =
        """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="1">$body</hierarchy>"""

    /** One `<node>` with the usual attributes; [children] is nested raw XML. */
    fun node(
        className: String = "android.widget.TextView",
        text: String = "",
        resourceId: String = "",
        desc: String = "",
        hint: String = "",
        packageName: String = AUT,
        clickable: Boolean = false,
        visible: Boolean = true,
        bounds: String = "[0,0][100,100]",
        children: String = "",
    ): String {
        val attributes =
            """index="0" text="$text" resource-id="$resourceId" class="$className" package="$packageName" """ +
                """content-desc="$desc" checkable="false" checked="false" clickable="$clickable" enabled="true" """ +
                """focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" """ +
                """selected="false" visible-to-user="$visible" bounds="$bounds" drawing-order="0" hint="$hint" display-id="0""""
        return if (children.isEmpty()) "<node $attributes />" else "<node $attributes>$children</node>"
    }
}
