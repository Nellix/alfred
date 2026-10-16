package ai.alfred.app.runtime

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

data class LocalCacheArtifact(
    val canonicalIntent: String,
    val goalTemplate: String?,
    val goalFingerprint: String?,
    val parameterTypes: Map<String, String>,
    val sourceScreen: String,
    val safety: String,
    val confidence: Double,
    val actions: List<DeviceAction>,
    val terminal: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("canonical_intent", canonicalIntent)
        .put("goal_template", goalTemplate)
        .put("goal_fingerprint", goalFingerprint)
        .put("parameter_types", JSONObject(parameterTypes.toSortedMap()))
        .put("source_screen", sourceScreen)
        .put("safety", safety)
        .put("confidence", confidence)
        .put("actions", JSONArray(actions.map { action ->
            JSONObject()
                .put("action", action.action)
                .put("target", action.target)
                .put("value", action.value)
                .put("package", action.packageName)
                .put("uri", action.uri)
        }))
        .put("terminal", terminal)

    companion object {
        fun fromJson(json: JSONObject): LocalCacheArtifact {
            val typesJson = json.getJSONObject("parameter_types")
            val types = typesJson.keys().asSequence().associateWith { typesJson.getString(it) }
            val actionsJson = json.getJSONArray("actions")
            return LocalCacheArtifact(
                canonicalIntent = json.getString("canonical_intent"),
                goalTemplate = json.optString("goal_template").takeIf { it.isNotBlank() && it != "null" },
                goalFingerprint = json.optString("goal_fingerprint").takeIf { it.isNotBlank() && it != "null" },
                parameterTypes = types,
                sourceScreen = json.getString("source_screen"),
                safety = json.getString("safety"),
                confidence = json.getDouble("confidence"),
                actions = buildList {
                    for (index in 0 until actionsJson.length()) {
                        add(DeviceAction.fromJson(actionsJson.getJSONObject(index)))
                    }
                },
                terminal = json.optBoolean("terminal", false),
            )
        }
    }
}

data class LocalCacheMatch(
    val id: Long,
    val canonicalIntent: String,
    val safety: String,
    val confidence: Double,
    val actions: List<DeviceAction>,
    val terminal: Boolean,
)

internal fun semanticLocalMatchKey(match: LocalCacheMatch): String = buildString {
    append(match.canonicalIntent)
    append('|')
    append(match.safety)
    append('|')
    append(match.terminal)
    match.actions.forEach { action ->
        append('|')
        append(action.action)
        when (action.action) {
            "launch_app" -> append(":${action.packageName}")
            "open_deep_link" -> append(":${action.packageName}:${action.uri}")
            "click", "scroll" -> append(":${action.target}")
            "set_text" -> append(":${action.target}:${action.value}")
        }
    }
}

data class PhoneSkillSummary(
    val id: Long,
    val canonicalIntent: String,
    val goalTemplate: String?,
    val sourceScreen: String,
    val safety: String,
    val confidence: Double,
    val successes: Int,
    val failures: Int,
    val active: Boolean,
    val updatedAt: Long,
)

data class LocalCacheSaveResult(
    val id: Long,
    val created: Boolean,
)

class LocalSemanticCache(context: Context) : SQLiteOpenHelper(context, "semantic-intents.db", null, 3) {
    override fun onCreate(database: SQLiteDatabase) {
        createPatterns(database)
    }

    private fun createPatterns(database: SQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE patterns(
                id INTEGER PRIMARY KEY,
                artifact_key TEXT NOT NULL UNIQUE,
                canonical_intent TEXT NOT NULL,
                goal_template TEXT,
                goal_fingerprint TEXT,
                parameter_types_json TEXT NOT NULL,
                source_screen TEXT NOT NULL,
                safety TEXT NOT NULL,
                confidence REAL NOT NULL,
                actions_json TEXT NOT NULL,
                terminal INTEGER NOT NULL,
                successes INTEGER NOT NULL DEFAULT 0,
                failures INTEGER NOT NULL DEFAULT 0,
                consecutive_failures INTEGER NOT NULL DEFAULT 0,
                active INTEGER NOT NULL DEFAULT 1,
                updated_at INTEGER NOT NULL,
                CHECK((goal_template IS NULL) <> (goal_fingerprint IS NULL))
            )""".trimIndent()
        )
        database.execSQL(
            "CREATE INDEX pattern_lookup ON patterns(source_screen, active, confidence)"
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            database.execSQL("ALTER TABLE patterns RENAME TO patterns_legacy")
            database.execSQL("DROP INDEX IF EXISTS pattern_lookup")
            database.execSQL("DROP INDEX IF EXISTS pattern_sync_id")
            createPatterns(database)
            database.execSQL(
                """INSERT INTO patterns(
                    id, artifact_key, canonical_intent, goal_template, goal_fingerprint,
                    parameter_types_json, source_screen, safety, confidence, actions_json,
                    terminal, successes, failures, consecutive_failures, active, updated_at
                ) SELECT
                    id, artifact_key, canonical_intent, goal_template, goal_fingerprint,
                    parameter_types_json, source_screen, safety, confidence, actions_json,
                    terminal, successes, failures, consecutive_failures, active, updated_at
                FROM patterns_legacy""".trimIndent()
            )
            database.execSQL("DROP TABLE patterns_legacy")
            database.execSQL("DROP TABLE IF EXISTS account_sync_state")
        }
    }

    fun save(artifact: LocalCacheArtifact): LocalCacheSaveResult {
        require(artifact.goalTemplate != null || artifact.goalFingerprint != null)
        val key = artifactKey(artifact)
        val reusableId = reusablePatternId(artifact, key)
        if (reusableId != null) {
            writableDatabase.execSQL(
                "UPDATE patterns SET confidence=MAX(confidence, ?), updated_at=? WHERE id=?",
                arrayOf<Any>(artifact.confidence, System.currentTimeMillis(), reusableId),
            )
            return LocalCacheSaveResult(id = reusableId, created = false)
        }
        val values = valuesFor(artifact).apply {
            put("artifact_key", key)
            put("updated_at", System.currentTimeMillis())
        }
        val updated = writableDatabase.update("patterns", values, "artifact_key=?", arrayOf(key))
        if (updated == 0) {
            writableDatabase.insertOrThrow("patterns", null, values)
        }
        val id = readableDatabase.query(
            "patterns",
            arrayOf("id"),
            "artifact_key=?",
            arrayOf(key),
            null,
            null,
            null,
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        return LocalCacheSaveResult(id = id, created = updated == 0)
    }

    fun listSkills(): List<PhoneSkillSummary> = readableDatabase.query(
        "patterns",
        arrayOf(
            "id",
            "canonical_intent",
            "goal_template",
            "source_screen",
            "safety",
            "confidence",
            "successes",
            "failures",
            "active",
            "updated_at",
        ),
        null,
        null,
        null,
        null,
        "updated_at DESC, id DESC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    PhoneSkillSummary(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        canonicalIntent = cursor.getString(
                            cursor.getColumnIndexOrThrow("canonical_intent")
                        ),
                        goalTemplate = cursor.stringOrNull("goal_template"),
                        sourceScreen = cursor.getString(
                            cursor.getColumnIndexOrThrow("source_screen")
                        ),
                        safety = cursor.getString(cursor.getColumnIndexOrThrow("safety")),
                        confidence = cursor.getDouble(
                            cursor.getColumnIndexOrThrow("confidence")
                        ),
                        successes = cursor.getInt(cursor.getColumnIndexOrThrow("successes")),
                        failures = cursor.getInt(cursor.getColumnIndexOrThrow("failures")),
                        active = cursor.getInt(cursor.getColumnIndexOrThrow("active")) == 1,
                        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                    )
                )
            }
        }
    }

    /** Compact, privacy-safe metadata for an LLM call after exact local matching has failed. */
    fun plannerHints(goal: String, sourceScreen: String, limit: Int = 3): List<String> {
        val goalTokens = normalize(goal).lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .toSet()
        return readableDatabase.query(
            "patterns",
            arrayOf("canonical_intent", "goal_template", "source_screen", "confidence"),
            "active=1 AND confidence>=0.90",
            null,
            null,
            null,
            "confidence DESC, updated_at DESC",
            "40",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val intent = cursor.getString(0)
                    val template = cursor.stringOrNull("goal_template")
                    val screen = cursor.getString(2)
                    val searchable = "$intent ${template.orEmpty()}".lowercase(Locale.ROOT)
                    val score = goalTokens.count(searchable::contains) +
                        if (screen == sourceScreen) 2 else 0
                    if (score > 0) add(score to "private:$intent · ${template ?: "exact request"}")
                }
            }.sortedByDescending { it.first }.take(limit).map { it.second }
        }
    }

    fun contributionProposal(id: Long): String? = readableDatabase.query(
        "patterns",
        null,
        "id=?",
        arrayOf(id.toString()),
        null,
        null,
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        val template = cursor.stringOrNull("goal_template") ?: return null
        val canonicalIntent = cursor.getString(
            cursor.getColumnIndexOrThrow("canonical_intent")
        )
        val artifactKey = cursor.getString(cursor.getColumnIndexOrThrow("artifact_key"))
        val actions = JSONArray(
            cursor.getString(cursor.getColumnIndexOrThrow("actions_json"))
        )
        val hasInteractiveAction = (0 until actions.length()).any { index ->
            actions.getJSONObject(index).getString("action") in setOf("click", "set_text")
        }
        val storedSafety = cursor.getString(cursor.getColumnIndexOrThrow("safety"))
        val publicSafety = if (hasInteractiveAction || storedSafety == "confirm") {
            "confirm"
        } else {
            "safe"
        }
        val proposalId = "$canonicalIntent.${artifactKey.take(10)}"
        JSONObject()
            .put("schema_version", 1)
            .put("id", proposalId)
            .put("version", 1)
            .put("canonical_intent", canonicalIntent)
            .put("title", canonicalIntent.replace('.', ' ').replaceFirstChar(Char::uppercase))
            .put("description", "Generalized skill proposed from verified on-device execution.")
            .put(
                "match",
                JSONObject()
                    .put("goal_template", template)
                    .put(
                        "parameter_types",
                        JSONObject(
                            cursor.getString(
                                cursor.getColumnIndexOrThrow("parameter_types_json")
                            )
                        ),
                    )
                    .put(
                        "source_screen",
                        cursor.getString(cursor.getColumnIndexOrThrow("source_screen")),
                    ),
            )
            .put("safety", publicSafety)
            .put("terminal", cursor.getInt(cursor.getColumnIndexOrThrow("terminal")) == 1)
            .put("actions", actions)
            .toString(2)
    }

    fun setActive(id: Long, active: Boolean): Boolean {
        val values = ContentValues().apply {
            put("active", if (active) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }
        return writableDatabase.update("patterns", values, "id=?", arrayOf(id.toString())) == 1
    }

    fun deleteFromPhone(id: Long): Boolean =
        writableDatabase.delete("patterns", "id=?", arrayOf(id.toString())) == 1

    fun resolve(goal: String, sourceScreen: String): LocalCacheMatch? {
        val normalized = normalize(goal)
        if (normalized.isBlank()) return null
        val found = mutableListOf<LocalCacheMatch>()
        readableDatabase.query(
            "patterns",
            null,
            "source_screen=? AND active=1 AND confidence>=0.90",
            arrayOf(sourceScreen),
            null,
            null,
            "confidence DESC, updated_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val template = cursor.stringOrNull("goal_template")
                val fingerprint = cursor.stringOrNull("goal_fingerprint")
                val typesJson = JSONObject(cursor.getString(cursor.getColumnIndexOrThrow("parameter_types_json")))
                val types = typesJson.keys().asSequence().associateWith { typesJson.getString(it) }
                val parameters = match(normalized, template, fingerprint, types) ?: continue
                val actionsJson = JSONArray(cursor.getString(cursor.getColumnIndexOrThrow("actions_json")))
                val actions = buildList {
                    for (index in 0 until actionsJson.length()) {
                        add(substitute(DeviceAction.fromJson(actionsJson.getJSONObject(index)), parameters))
                    }
                }
                found += LocalCacheMatch(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    canonicalIntent = cursor.getString(cursor.getColumnIndexOrThrow("canonical_intent")),
                    safety = cursor.getString(cursor.getColumnIndexOrThrow("safety")),
                    confidence = cursor.getDouble(cursor.getColumnIndexOrThrow("confidence")),
                    actions = actions,
                    terminal = cursor.getInt(cursor.getColumnIndexOrThrow("terminal")) == 1,
                )
            }
        }
        val equivalentGroups = found.groupBy(::semanticLocalMatchKey)
        return equivalentGroups.values.singleOrNull()?.maxByOrNull { it.confidence }
    }

    fun resolveById(
        id: Long,
        goal: String,
        sourceScreen: String,
        workflowParameters: Map<String, Any> = emptyMap(),
    ): LocalCacheMatch? {
        val normalized = normalize(goal)
        if (normalized.isBlank()) return null
        return readableDatabase.query(
            "patterns",
            null,
            "id=? AND source_screen=? AND active=1 AND confidence>=0.90",
            arrayOf(id.toString(), sourceScreen),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val template = cursor.stringOrNull("goal_template")
            val fingerprint = cursor.stringOrNull("goal_fingerprint")
            val typesJson = JSONObject(
                cursor.getString(cursor.getColumnIndexOrThrow("parameter_types_json"))
            )
            val types = typesJson.keys().asSequence().associateWith { typesJson.getString(it) }
            val parameters = if (workflowParameters.keys.containsAll(types.keys)) {
                workflowParameters
            } else {
                match(normalized, template, fingerprint, types) ?: return null
            }
            val actionsJson = JSONArray(
                cursor.getString(cursor.getColumnIndexOrThrow("actions_json"))
            )
            val actions = buildList {
                for (index in 0 until actionsJson.length()) {
                    add(
                        substitute(
                            DeviceAction.fromJson(actionsJson.getJSONObject(index)),
                            parameters,
                        )
                    )
                }
            }
            if (actions.any { action ->
                    listOf(action.target, action.value, action.packageName, action.uri)
                        .filterNotNull()
                        .any { "\${" in it }
                }
            ) {
                return null
            }
            LocalCacheMatch(
                id = id,
                canonicalIntent = cursor.getString(
                    cursor.getColumnIndexOrThrow("canonical_intent")
                ),
                safety = cursor.getString(cursor.getColumnIndexOrThrow("safety")),
                confidence = cursor.getDouble(cursor.getColumnIndexOrThrow("confidence")),
                actions = actions,
                terminal = cursor.getInt(cursor.getColumnIndexOrThrow("terminal")) == 1,
            )
        }
    }

    fun recordOutcome(id: Long, success: Boolean) {
        val row = readableDatabase.query(
            "patterns",
            arrayOf("confidence", "consecutive_failures"),
            "id=?",
            arrayOf(id.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            cursor.getDouble(0) to cursor.getInt(1)
        }
        val confidence = if (success) {
            minOf(0.999, row.first + 0.03 * (1.0 - row.first))
        } else {
            row.first * 0.65
        }
        val failures = if (success) 0 else row.second + 1
        writableDatabase.execSQL(
            """UPDATE patterns SET
                ${if (success) "successes=successes+1" else "failures=failures+1"},
                confidence=?, consecutive_failures=?, active=?, updated_at=? WHERE id=?""",
            arrayOf<Any>(
                confidence,
                failures,
                if (failures >= 2) 0 else 1,
                System.currentTimeMillis(),
                id,
            ),
        )
    }

    fun clearAll(): Int = writableDatabase.delete("patterns", null, null)

    private fun valuesFor(artifact: LocalCacheArtifact): ContentValues = ContentValues().apply {
        put("canonical_intent", artifact.canonicalIntent)
        put("goal_template", artifact.goalTemplate)
        put("goal_fingerprint", artifact.goalFingerprint)
        put("parameter_types_json", JSONObject(artifact.parameterTypes.toSortedMap()).toString())
        put("source_screen", artifact.sourceScreen)
        put("safety", artifact.safety)
        put("confidence", artifact.confidence)
        put("actions_json", artifact.toJson().getJSONArray("actions").toString())
        put("terminal", if (artifact.terminal) 1 else 0)
    }

    private fun reusablePatternId(artifact: LocalCacheArtifact, artifactKey: String): Long? {
        val actions = artifact.toJson().getJSONArray("actions").toString()
        return readableDatabase.query(
            "patterns",
            arrayOf("id"),
            "artifact_key<>? AND canonical_intent=? AND source_screen=? AND actions_json=?",
            arrayOf(artifactKey, artifact.canonicalIntent, artifact.sourceScreen, actions),
            null,
            null,
            "confidence DESC, updated_at DESC",
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    private fun artifactKey(artifact: LocalCacheArtifact): String = digest(
        listOf(
            artifact.canonicalIntent,
            artifact.goalTemplate,
            artifact.goalFingerprint,
            artifact.sourceScreen,
            artifact.actions.joinToString("|") { it.toString() },
        ).joinToString("|")
    )

    private fun match(
        normalized: String,
        template: String?,
        fingerprint: String?,
        types: Map<String, String>,
    ): Map<String, Any>? {
        if (template == null) return if (fingerprint == digest(normalized.lowercase(Locale.ROOT))) emptyMap() else null
        val placeholders = PLACEHOLDER.findAll(template).toList()
        val names = mutableListOf<String>()
        var cursor = 0
        val pattern = buildString {
            append("^")
            placeholders.forEach { placeholder ->
                append(Regex.escape(template.substring(cursor, placeholder.range.first)))
                val name = placeholder.groupValues[1]
                if (name in names) return null
                names += name
                append("(.+?)")
                cursor = placeholder.range.last + 1
            }
            append(Regex.escape(template.substring(cursor)))
            append("$")
        }
        val result = Regex(pattern, RegexOption.IGNORE_CASE).matchEntire(normalized) ?: return null
        if (types.keys.any { it !in names }) return null
        val values = mutableMapOf<String, Any>()
        names.forEachIndexed { index, name ->
            val raw = result.groupValues[index + 1].trim()
            values[name] = when (types[name]) {
                "integer" -> raw.toIntOrNull() ?: return null
                "boolean" -> raw.lowercase(Locale.ROOT).toBooleanStrictOrNull() ?: return null
                else -> raw
            }
        }
        return values
    }

    private fun substitute(action: DeviceAction, parameters: Map<String, Any>): DeviceAction =
        action.copy(
            target = substitute(action.target, parameters),
            value = substitute(action.value, parameters),
            packageName = substitute(action.packageName, parameters),
            uri = substitute(action.uri, parameters),
        )

    private fun substitute(value: String?, parameters: Map<String, Any>): String? {
        if (value == null) return null
        return VALUE_PLACEHOLDER.replace(value) { match ->
            val raw = parameters[match.groupValues[1]]?.toString() ?: return@replace match.value
            when (match.groupValues[2]) {
                "url" -> Uri.encode(raw)
                "query" -> URLEncoder.encode(raw, StandardCharsets.UTF_8.name())
                else -> raw
            }
        }
    }

    private fun android.database.Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private val PLACEHOLDER = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}""")
        private val VALUE_PLACEHOLDER =
            Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(?:\|(url|query))?\}""")
    }
}
