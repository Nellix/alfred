package ai.alfred.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SemanticSnapshotTest {
    @Test
    fun `viewport digest changes when scrolling changes bounds and visibility`() {
        val node = SemanticNode(
            handle = "a1",
            parentHandle = null,
            packageName = "ai.alfred.app",
            windowId = "1",
            resourceId = null,
            role = "button",
            className = "android.widget.Button",
            text = "Read privacy details",
            contentDescription = null,
            clickable = true,
            editable = false,
            scrollable = false,
            enabled = true,
            visible = false,
            selected = false,
            checked = null,
            bounds = Bounds(10, 2600, 500, 2700),
            actions = listOf("click"),
            structuralSignature = "structure",
            semanticSignature = "semantic",
            sensitivity = "public",
        )
        val before = snapshot(node)
        val after = snapshot(node.copy(visible = true, bounds = Bounds(10, 400, 500, 500)))

        assertEquals(before.digest, after.digest)
        assertNotEquals(before.viewportDigest, after.viewportDigest)
    }

    private fun snapshot(node: SemanticNode) = SemanticSnapshot(
        packageName = "ai.alfred.app",
        screenId = "ai.alfred.app:SettingsActivity",
        windowId = "1",
        revision = 1,
        nodes = listOf(node),
        capturedAtSeconds = 0.0,
    )
}
