package ai.alfred.app.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

data class RemotePlan(
    val source: String,
    val attemptId: String?,
    val canonicalIntent: String?,
    val actions: List<DeviceAction>,
    val confirmationRequired: Boolean,
    val modelCalls: Int,
    val message: String?,
    val localArtifact: LocalCacheArtifact?,
    val terminal: Boolean,
)

interface PlanningClient {
    fun plan(
        goal: String,
        snapshot: SemanticSnapshot,
        confirmed: Boolean,
        profileId: String,
        callback: (Result<RemotePlan>) -> Unit,
    )

    fun report(
        attemptId: String,
        outcome: ActionOutcome,
        screenId: String?,
        callback: (Result<LocalCacheArtifact?>) -> Unit = {},
    )
}

class BackendClient(private val baseUrl: String) : PlanningClient {
    private val executor = Executors.newSingleThreadExecutor()

    init {
        val uri = URI(baseUrl)
        require(uri.scheme in setOf("http", "https")) { "Backend URL must use HTTP or HTTPS" }
        require(
            uri.scheme == "https" || uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        ) { "Plain HTTP is allowed only for a local USB/emulator endpoint" }
    }

    override fun plan(
        goal: String,
        snapshot: SemanticSnapshot,
        confirmed: Boolean,
        profileId: String,
        callback: (Result<RemotePlan>) -> Unit,
    ) {
        executor.execute {
            callback(runCatching {
                val response = post(
                    "/v1/agent/plan",
                    JSONObject()
                        .put("goal", goal)
                        .put("state", snapshot.toJson())
                        .put("confirmed", confirmed)
                        .put("profile_id", profileId)
                        .put("android_api", android.os.Build.VERSION.SDK_INT)
                        .put("locale", java.util.Locale.getDefault().toLanguageTag()),
                )
                val actionArray = response.optJSONArray("actions") ?: JSONArray()
                RemotePlan(
                    source = response.getString("source"),
                    attemptId = response.optString("attempt_id").takeIf { it.isNotBlank() },
                    canonicalIntent = response.optString("canonical_intent").takeIf { it.isNotBlank() },
                    actions = buildList {
                        for (index in 0 until actionArray.length()) {
                            add(DeviceAction.fromJson(actionArray.getJSONObject(index)))
                        }
                    },
                    confirmationRequired = response.optBoolean("confirmation_required", false),
                    modelCalls = response.optInt("model_calls", 0),
                    message = response.optString("message").takeIf { it.isNotBlank() },
                    localArtifact = response.optJSONObject("local_artifact")
                        ?.let(LocalCacheArtifact::fromJson),
                    terminal = response.optBoolean("terminal", false),
                )
            })
        }
    }

    override fun report(
        attemptId: String,
        outcome: ActionOutcome,
        screenId: String?,
        callback: (Result<LocalCacheArtifact?>) -> Unit,
    ) {
        executor.execute {
            callback(runCatching {
                val response = post(
                    "/v1/agent/outcome",
                    JSONObject()
                        .put("attempt_id", attemptId)
                        .put("status", outcome.code)
                        .put("verified", outcome.verified)
                        .put("message", outcome.message)
                        .put("final_screen", screenId),
                )
                response.optJSONObject("local_artifact")?.let(LocalCacheArtifact::fromJson)
            })
        }
    }

    fun clearMemory(profileId: String, callback: (Result<Int>) -> Unit) {
        executor.execute {
            callback(runCatching {
                post("/v1/agent/memory/clear", JSONObject().put("profile_id", profileId))
                    .getInt("templates_deleted")
            })
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 8_000
        connection.readTimeout = 30_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("Backend returned $code: $text")
        return JSONObject(text)
    }
}
