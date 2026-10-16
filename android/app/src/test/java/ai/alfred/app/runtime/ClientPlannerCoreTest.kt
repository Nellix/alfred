package ai.alfred.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientPlannerCoreTest {
    @Test
    fun verifiedUnknownEdgeBecomesParameterizedLocalArtifact() {
        val result = ClientPlannerCore.validate(
            goal = "Search YouTube for jazz piano",
            snapshot = snapshot(editableNode()),
            planned = PlannedStep(
                canonicalIntent = "youtube.search",
                confidence = 0.92,
                parameters = listOf(
                    PlannerParameter("query", "string", "jazz piano"),
                    PlannerParameter("provider", "string", "google"),
                ),
                action = DeviceAction(
                    "set_text",
                    target = "node-search",
                    value = "jazz piano",
                    packageName = "irrelevant.provider.field",
                ),
            ),
            hasVerifiedHistory = false,
        )

        assertEquals("label:Search", result.action.target)
        assertNull(result.action.packageName)
        assertTrue(result.confirmationRequired)
        assertEquals("search youtube for \${query}", result.artifact?.goalTemplate)
        assertEquals("\${query}", result.artifact?.actions?.single()?.value)
        assertEquals(mapOf("query" to "string"), result.artifact?.parameterTypes)
    }

    @Test
    fun destructiveIntentIsBlockedBeforeExecution() {
        assertThrows(IllegalArgumentException::class.java) {
            ClientPlannerCore.validate(
                goal = "Send this message",
                snapshot = snapshot(clickableNode("node-send", "Send")),
                planned = PlannedStep(
                    canonicalIntent = "message.send",
                    confidence = 0.9,
                    parameters = emptyList(),
                    action = DeviceAction("click", target = "node-send"),
                ),
                hasVerifiedHistory = true,
            )
        }
    }

    @Test
    fun stopCannotCompleteAnEmptyWorkflow() {
        assertThrows(IllegalArgumentException::class.java) {
            ClientPlannerCore.validate(
                goal = "Open display settings",
                snapshot = snapshot(clickableNode("node-display", "Display")),
                planned = PlannedStep(
                    canonicalIntent = "settings.open_display",
                    confidence = 0.8,
                    parameters = emptyList(),
                    action = DeviceAction("stop"),
                ),
                hasVerifiedHistory = false,
            )
        }
    }

    @Test
    fun privateResidualPreventsGeneralizedCacheButNotSafeExecution() {
        val result = ClientPlannerCore.validate(
            goal = "Open profile user@example.com",
            snapshot = snapshot(clickableNode("node-profile", "Profile")),
            planned = PlannedStep(
                canonicalIntent = "profile.open",
                confidence = 0.9,
                parameters = listOf(PlannerParameter("section", "string", "profile")),
                action = DeviceAction("click", target = "node-profile"),
            ),
            hasVerifiedHistory = false,
        )

        assertEquals("label:Profile", result.action.target)
        assertNull(result.artifact)
    }

    @Test
    fun exactSafeActionCanStillBeRemembered() {
        val result = ClientPlannerCore.validate(
            goal = "Open calculator",
            snapshot = snapshot(),
            planned = PlannedStep(
                canonicalIntent = "app.open_calculator",
                confidence = 0.9,
                parameters = emptyList(),
                action = DeviceAction("launch_app", packageName = "com.android.calculator2"),
            ),
            hasVerifiedHistory = false,
        )

        assertNotNull(result.artifact?.goalFingerprint)
        assertNull(result.artifact?.goalTemplate)
    }

    @Test
    fun irrelevantProviderFieldsDoNotMakeCachedClicksAmbiguous() {
        val base = LocalCacheMatch(
            id = 1,
            canonicalIntent = "settings.open",
            safety = "confirm",
            confidence = 0.91,
            actions = listOf(DeviceAction("click", target = "label:Open settings")),
            terminal = false,
        )
        val providerVariant = base.copy(
            id = 2,
            actions = listOf(
                DeviceAction(
                    "click",
                    target = "label:Open settings",
                    packageName = "ai.alfred.app",
                )
            ),
        )

        assertEquals(semanticLocalMatchKey(base), semanticLocalMatchKey(providerVariant))
    }

    private fun snapshot(vararg nodes: SemanticNode) = SemanticSnapshot(
        packageName = "com.example",
        screenId = "com.example:main",
        windowId = "1",
        revision = 1,
        nodes = nodes.toList(),
        capturedAtSeconds = 1.0,
    )

    private fun editableNode() = node(
        handle = "node-search",
        label = "Search",
        actions = listOf("set_text"),
        editable = true,
    )

    private fun clickableNode(handle: String, label: String) = node(
        handle = handle,
        label = label,
        actions = listOf("click"),
        clickable = true,
    )

    private fun node(
        handle: String,
        label: String,
        actions: List<String>,
        clickable: Boolean = false,
        editable: Boolean = false,
    ) = SemanticNode(
        handle = handle,
        parentHandle = null,
        packageName = "com.example",
        windowId = "1",
        resourceId = null,
        role = if (editable) "text_field" else "button",
        className = "android.view.View",
        text = label,
        contentDescription = null,
        clickable = clickable,
        editable = editable,
        scrollable = false,
        enabled = true,
        visible = true,
        selected = false,
        checked = null,
        bounds = null,
        actions = actions,
        structuralSignature = handle,
        semanticSignature = "$handle:$label",
        sensitivity = "public",
    )
}
