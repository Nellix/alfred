package ai.alfred.app.runtime

import android.content.Context
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class NativeIntentRouter(private val context: Context) {
    data class NativePlan(
        val action: DeviceAction,
        val message: String,
        val terminal: Boolean = true,
    )

    fun resolve(goal: String): NativePlan? {
        val text = goal.trim()
        if (Regex("^(open|launch|start)( the)?( android)? settings$", RegexOption.IGNORE_CASE).matches(text)) {
            return NativePlan(
                DeviceAction("launch_app", packageName = "com.android.settings"),
                "Opening Settings locally",
            )
        }
        Regex(
            "^(?:navigate|go|take me|give me directions) to (.+?)(?: (?:using|with|in) (?:google )?maps)?$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(text)?.let { match ->
            val destination = encode(match.groupValues[1])
            return NativePlan(
                DeviceAction(
                    "open_deep_link",
                    packageName = installed("com.google.android.apps.maps"),
                    uri = "https://www.google.com/maps/dir/?api=1&destination=$destination",
                ),
                "Opening a route preview locally",
            )
        }
        val youtube = listOf(
            Regex("^(?:open )?youtube(?: and)? play (.+)$", RegexOption.IGNORE_CASE),
            Regex("^play (.+) (?:on|in) youtube$", RegexOption.IGNORE_CASE),
        ).firstNotNullOfOrNull { it.matchEntire(text) }
        if (youtube != null) {
            return NativePlan(
                DeviceAction(
                    "open_deep_link",
                    packageName = installed("com.google.android.youtube"),
                    uri = "https://www.youtube.com/results?search_query=${encode(youtube.groupValues[1])}",
                ),
                "Opening YouTube results locally",
                terminal = false,
            )
        }
        if (Regex("^(check|open)( my)? (email|emails|mail|inbox)$", RegexOption.IGNORE_CASE).matches(text)) {
            installed("com.google.android.gm")?.let {
                return NativePlan(DeviceAction("launch_app", packageName = it), "Opening your inbox locally")
            }
        }
        MessagingDraftIntent.parse(text)?.let { request ->
            val packageName = request.packageName
            if (installed(packageName) == null) return null
            val uri = if (request.channel == "whatsapp") {
                "https://wa.me/?text=${encode(request.message)}"
            } else {
                val username = request.recipient.removePrefix("@").takeIf {
                    it.matches(Regex("[A-Za-z][A-Za-z0-9_]{4,31}"))
                }
                if (username != null) "https://t.me/$username?text=${encode(request.message)}"
                else "https://t.me/share/url?url=&text=${encode(request.message)}"
            }
            return NativePlan(
                DeviceAction("open_deep_link", packageName = packageName, uri = uri),
                "Preparing a draft locally — Alfred will never press Send",
                terminal = false,
            )
        }
        return null
    }

    private fun installed(packageName: String): String? = try {
        context.packageManager.getPackageInfo(packageName, 0)
        packageName
    } catch (_: Exception) {
        null
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
