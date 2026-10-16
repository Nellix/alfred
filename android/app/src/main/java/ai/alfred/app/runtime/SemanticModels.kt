package ai.alfred.app.runtime

import org.json.JSONArray
import org.json.JSONObject

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun toJson(): JSONObject = JSONObject()
        .put("left", left)
        .put("top", top)
        .put("right", right)
        .put("bottom", bottom)
}

data class SemanticNode(
    val handle: String,
    val parentHandle: String?,
    val packageName: String,
    val windowId: String,
    val resourceId: String?,
    val role: String,
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val selected: Boolean,
    val checked: Boolean?,
    val bounds: Bounds?,
    val actions: List<String>,
    val structuralSignature: String,
    val semanticSignature: String,
    val sensitivity: String,
) {
    val label: String get() = contentDescription ?: text ?: resourceId ?: role

    fun toJson(): JSONObject = JSONObject()
        .put("handle", handle)
        .put("parent_handle", parentHandle)
        .put("package", packageName)
        .put("window_id", windowId)
        .put("resource_id", resourceId)
        .put("accessibility_id", JSONObject.NULL)
        .put("role", role)
        .put("class_name", className)
        .put("text", text)
        .put("content_description", contentDescription)
        .put("clickable", clickable)
        .put("editable", editable)
        .put("scrollable", scrollable)
        .put("enabled", enabled)
        .put("visible", visible)
        .put("selected", selected)
        .put("checked", checked)
        .put("bounds", bounds?.toJson())
        .put("actions", JSONArray(actions))
        .put("structural_signature", structuralSignature)
        .put("semantic_signature", semanticSignature)
        .put("sensitivity", sensitivity)
        .put("confidence", 1.0)
}

data class SemanticSnapshot(
    val packageName: String,
    val screenId: String,
    val windowId: String,
    val revision: Int,
    val nodes: List<SemanticNode>,
    val capturedAtSeconds: Double,
) {
    val digest: String = nodes.joinToString("|") { it.semanticSignature }.hashCode().toString()
    val viewportDigest: String = nodes.joinToString("|") {
        "${it.handle}:${it.visible}:${it.bounds}"
    }.hashCode().toString()

    fun toJson(): JSONObject = JSONObject()
        .put("package", packageName)
        .put("screen_id", screenId)
        .put("window_id", windowId)
        .put("epoch", 1)
        .put("revision", revision.coerceAtLeast(1))
        .put("rotation", 0)
        .put("nodes", JSONArray(nodes.map { it.toJson() }))
        .put("captured_at", capturedAtSeconds)
        .put("source", "accessibility")
}

data class DeviceAction(
    val action: String,
    val target: String? = null,
    val value: String? = null,
    val packageName: String? = null,
    val uri: String? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): DeviceAction = DeviceAction(
            action = json.getString("action"),
            target = json.optString("target").takeIf { it.isNotBlank() && it != "null" },
            value = json.optString("value").takeIf { it.isNotBlank() && it != "null" },
            packageName = json.optString("package").takeIf { it.isNotBlank() && it != "null" },
            uri = json.optString("uri").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}

data class ActionOutcome(
    val success: Boolean,
    val verified: Boolean,
    val code: String,
    val message: String,
)
