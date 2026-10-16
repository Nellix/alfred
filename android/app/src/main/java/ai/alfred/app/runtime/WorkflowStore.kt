package ai.alfred.app.runtime

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/**
 * A privacy-preserving task graph. Recipes contain references to reusable skills, never the
 * concrete parameter values that were used while the task was learned.
 */
data class WorkflowSession(
    val runId: String,
    val goalFingerprint: String,
    val recipeId: Long?,
    val parameters: Map<String, Any> = emptyMap(),
    var currentNodeId: Long? = null,
    var diverged: Boolean = false,
    val trace: MutableList<WorkflowTraceStep> = mutableListOf(),
)

data class WorkflowTraceStep(
    val sourceScreen: String,
    val destinationScreen: String,
    val canonicalIntent: String?,
    val skillRef: String,
    val actionKinds: String,
    val terminal: Boolean,
)

data class WorkflowReplayStep(
    val nodeId: Long,
    val patternId: Long,
    val terminal: Boolean,
)

data class WorkflowRecipeSummary(
    val id: Long,
    val goalTemplate: String?,
    val nodes: Int,
    val branchPoints: Int,
    val successes: Int,
    val failures: Int,
    val active: Boolean,
    val updatedAt: Long,
)

class WorkflowStore(context: Context) : SQLiteOpenHelper(
    context,
    "alfred-workflows.db",
    null,
    1,
) {
    override fun onConfigure(database: SQLiteDatabase) {
        database.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE workflow_recipes(
                id INTEGER PRIMARY KEY,
                goal_template TEXT,
                goal_fingerprint TEXT,
                parameter_types_json TEXT NOT NULL,
                active INTEGER NOT NULL DEFAULT 1,
                successes INTEGER NOT NULL DEFAULT 0,
                failures INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                CHECK((goal_template IS NULL) <> (goal_fingerprint IS NULL))
            )""".trimIndent()
        )
        database.execSQL(
            """CREATE TABLE workflow_nodes(
                id INTEGER PRIMARY KEY,
                recipe_id INTEGER NOT NULL REFERENCES workflow_recipes(id) ON DELETE CASCADE,
                parent_node_id INTEGER REFERENCES workflow_nodes(id) ON DELETE CASCADE,
                source_screen TEXT NOT NULL,
                destination_screen TEXT NOT NULL,
                canonical_intent TEXT,
                skill_ref TEXT NOT NULL,
                action_kinds TEXT NOT NULL,
                terminal INTEGER NOT NULL,
                successes INTEGER NOT NULL DEFAULT 1,
                failures INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        database.execSQL(
            "CREATE INDEX workflow_root_lookup ON workflow_recipes(active, updated_at)"
        )
        database.execSQL(
            "CREATE INDEX workflow_child_lookup ON workflow_nodes(recipe_id, parent_node_id, source_screen)"
        )
        database.execSQL(
            """CREATE TABLE workflow_runs(
                run_id TEXT PRIMARY KEY,
                recipe_id INTEGER REFERENCES workflow_recipes(id) ON DELETE SET NULL,
                goal_fingerprint TEXT NOT NULL,
                status TEXT NOT NULL,
                model_calls INTEGER NOT NULL DEFAULT 0,
                started_at INTEGER NOT NULL,
                finished_at INTEGER
            )""".trimIndent()
        )
        database.execSQL(
            """CREATE TABLE workflow_run_steps(
                id INTEGER PRIMARY KEY,
                run_id TEXT NOT NULL REFERENCES workflow_runs(run_id) ON DELETE CASCADE,
                ordinal INTEGER NOT NULL,
                source_screen TEXT NOT NULL,
                destination_screen TEXT NOT NULL,
                canonical_intent TEXT,
                skill_ref TEXT NOT NULL,
                action_kinds TEXT NOT NULL,
                terminal INTEGER NOT NULL,
                UNIQUE(run_id, ordinal)
            )""".trimIndent()
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun listRecipes(): List<WorkflowRecipeSummary> = readableDatabase.rawQuery(
        """SELECT r.*,
            (SELECT COUNT(*) FROM workflow_nodes n WHERE n.recipe_id=r.id) AS node_count,
            (SELECT COUNT(*) FROM (
                SELECT parent_node_id FROM workflow_nodes b
                WHERE b.recipe_id=r.id
                GROUP BY parent_node_id HAVING COUNT(*) > 1
            )) AS branch_count
        FROM workflow_recipes r ORDER BY r.updated_at DESC, r.id DESC""".trimIndent(),
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val templateIndex = cursor.getColumnIndexOrThrow("goal_template")
                add(
                    WorkflowRecipeSummary(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        goalTemplate = if (cursor.isNull(templateIndex)) {
                            null
                        } else {
                            cursor.getString(templateIndex)
                        },
                        nodes = cursor.getInt(cursor.getColumnIndexOrThrow("node_count")),
                        branchPoints = cursor.getInt(cursor.getColumnIndexOrThrow("branch_count")),
                        successes = cursor.getInt(cursor.getColumnIndexOrThrow("successes")),
                        failures = cursor.getInt(cursor.getColumnIndexOrThrow("failures")),
                        active = cursor.getInt(cursor.getColumnIndexOrThrow("active")) == 1,
                        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                    )
                )
            }
        }
    }

    fun setActive(id: Long, active: Boolean): Boolean {
        val values = ContentValues().apply {
            put("active", if (active) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }
        return writableDatabase.update(
            "workflow_recipes",
            values,
            "id=?",
            arrayOf(id.toString()),
        ) == 1
    }

    fun delete(id: Long): Boolean = writableDatabase.delete(
        "workflow_recipes",
        "id=?",
        arrayOf(id.toString()),
    ) == 1

    /** Builds a privacy-safe public graph only when every referenced phone skill is exportable. */
    fun contributionProposal(id: Long, cache: LocalSemanticCache): String? {
        val recipe = readableDatabase.query(
            "workflow_recipes", null, "id=?", arrayOf(id.toString()), null, null, null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val templateIndex = cursor.getColumnIndexOrThrow("goal_template")
            if (cursor.isNull(templateIndex)) return null
            Triple(
                cursor.getString(templateIndex),
                cursor.getString(cursor.getColumnIndexOrThrow("parameter_types_json")),
                cursor.getInt(cursor.getColumnIndexOrThrow("successes")),
            )
        }
        if (recipe.third < 1) return null
        data class ExportNode(
            val id: Long,
            val parentId: Long?,
            val sourceScreen: String,
            val skillRef: String,
            val terminal: Boolean,
        )
        val storedNodes = readableDatabase.query(
            "workflow_nodes",
            arrayOf("id", "parent_node_id", "source_screen", "skill_ref", "terminal"),
            "recipe_id=?",
            arrayOf(id.toString()),
            null,
            null,
            "id",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ExportNode(
                            id = cursor.getLong(0),
                            parentId = if (cursor.isNull(1)) null else cursor.getLong(1),
                            sourceScreen = cursor.getString(2),
                            skillRef = cursor.getString(3),
                            terminal = cursor.getInt(4) == 1,
                        )
                    )
                }
            }
        }
        val roots = storedNodes.filter { it.parentId == null }
        if (roots.size != 1 || storedNodes.isEmpty()) return null
        val actionsByNode = storedNodes.associate { node ->
            val patternId = node.skillRef.removePrefix(PHONE_PREFIX)
                .takeIf { node.skillRef.startsWith(PHONE_PREFIX) }
                ?.toLongOrNull()
                ?: return null
            val skill = cache.contributionProposal(patternId)?.let(::JSONObject) ?: return null
            node.id to skill.getJSONArray("actions")
        }
        val nodeIds = storedNodes.map { it.id }.toSet()
        if (storedNodes.any { it.parentId != null && it.parentId !in nodeIds }) return null
        val publicSafety = if (actionsByNode.values.any { actions ->
                (0 until actions.length()).any { index ->
                    actions.getJSONObject(index).getString("action") in setOf("click", "set_text")
                }
            }) "confirm" else "safe"
        val canonicalIntent = storedNodes.firstNotNullOfOrNull { node ->
            val patternId = node.skillRef.removePrefix(PHONE_PREFIX).toLongOrNull()
            patternId?.let(cache::contributionProposal)?.let(::JSONObject)
                ?.optString("canonical_intent")?.takeIf(String::isNotBlank)
        } ?: "community.workflow"
        val proposalId = "community.workflow.${digest(recipe.first).take(12)}"
        val nodes = JSONArray()
        storedNodes.forEach { node ->
            val children = storedNodes.filter { it.parentId == node.id }
            if ((node.terminal && children.isNotEmpty()) || (!node.terminal && children.isEmpty())) {
                return null
            }
            nodes.put(
                JSONObject()
                    .put("id", "step-${node.id}")
                    .put("source_screen", generalizedScreen(node.sourceScreen))
                    .put("actions", actionsByNode.getValue(node.id))
                    .put("children", JSONArray(children.map { "step-${it.id}" }))
                    .put("terminal", node.terminal)
            )
        }
        val replayModelCalls = readableDatabase.rawQuery(
            "SELECT MIN(model_calls) FROM workflow_runs WHERE recipe_id=? AND status='success'",
            arrayOf(id.toString()),
        ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else 0 }
        return JSONObject()
            .put("schema_version", 1)
            .put("id", proposalId)
            .put("version", 1)
            .put("canonical_intent", canonicalIntent)
            .put("title", canonicalIntent.replace('.', ' ').replaceFirstChar(Char::uppercase))
            .put("description", "Generalized workflow proposed from verified on-device execution.")
            .put(
                "match",
                JSONObject()
                    .put("goal_template", recipe.first)
                    .put("parameter_types", JSONObject(recipe.second)),
            )
            .put("safety", publicSafety)
            .put("entry_node", "step-${roots.single().id}")
            .put("nodes", nodes)
            .put(
                "verification",
                JSONObject()
                    .put("status", "device_verified")
                    .put("verified_runs", recipe.third)
                    .put("replay_model_calls", replayModelCalls),
            )
            .toString(2)
    }

    fun clearAll(): Int {
        writableDatabase.beginTransaction()
        return try {
            writableDatabase.delete("workflow_run_steps", null, null)
            writableDatabase.delete("workflow_runs", null, null)
            val deleted = writableDatabase.delete("workflow_recipes", null, null)
            writableDatabase.setTransactionSuccessful()
            deleted
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun begin(goal: String): WorkflowSession {
        val fingerprint = digest(normalize(goal).lowercase(Locale.ROOT))
        val matches = mutableListOf<Pair<Long, Map<String, Any>>>()
        readableDatabase.query(
            "workflow_recipes",
            arrayOf("id", "goal_template", "goal_fingerprint", "parameter_types_json"),
            "active=1",
            null,
            null,
            null,
            "updated_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val templateIndex = cursor.getColumnIndexOrThrow("goal_template")
                val template = if (cursor.isNull(templateIndex)) null else cursor.getString(templateIndex)
                val fingerprintIndex = cursor.getColumnIndexOrThrow("goal_fingerprint")
                val storedFingerprint = if (cursor.isNull(fingerprintIndex)) {
                    null
                } else {
                    cursor.getString(fingerprintIndex)
                }
                val parameters = if (storedFingerprint == fingerprint) {
                    emptyMap()
                } else if (template != null) {
                    templateParameters(
                        template,
                        normalize(goal),
                        cursor.getString(cursor.getColumnIndexOrThrow("parameter_types_json")),
                    )
                } else {
                    null
                }
                if (parameters != null) {
                    matches += cursor.getLong(cursor.getColumnIndexOrThrow("id")) to parameters
                }
            }
        }
        return WorkflowSession(
            runId = UUID.randomUUID().toString(),
            goalFingerprint = fingerprint,
            recipeId = matches.singleOrNull()?.first,
            parameters = matches.singleOrNull()?.second.orEmpty(),
        )
    }

    /** Returns a known phone skill for the next edge. Other refs fall through to normal routing. */
    fun nextPhoneStep(session: WorkflowSession, sourceScreen: String): WorkflowReplayStep? {
        val recipeId = session.recipeId ?: return null
        if (session.diverged) return null
        val selection: String
        val arguments: Array<String>
        if (session.currentNodeId == null) {
            selection = "recipe_id=? AND parent_node_id IS NULL AND source_screen=?"
            arguments = arrayOf(recipeId.toString(), sourceScreen)
        } else {
            selection = "recipe_id=? AND parent_node_id=? AND source_screen=?"
            arguments = arrayOf(recipeId.toString(), session.currentNodeId.toString(), sourceScreen)
        }
        val candidates = readableDatabase.query(
            "workflow_nodes",
            arrayOf("id", "skill_ref", "terminal"),
            selection,
            arguments,
            null,
            null,
            "successes DESC, updated_at DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Triple(cursor.getLong(0), cursor.getString(1), cursor.getInt(2) == 1))
                }
            }
        }
        if (candidates.size != 1) return null
        val candidate = candidates.single()
        val patternId = candidate.second.removePrefix(PHONE_PREFIX)
            .takeIf { candidate.second.startsWith(PHONE_PREFIX) }
            ?.toLongOrNull()
            ?: return null
        return WorkflowReplayStep(candidate.first, patternId, candidate.third)
    }

    fun recordSuccess(
        session: WorkflowSession,
        sourceScreen: String,
        destinationScreen: String,
        canonicalIntent: String?,
        skillRef: String,
        actions: List<DeviceAction>,
        terminal: Boolean,
    ) {
        val step = WorkflowTraceStep(
            sourceScreen = sourceScreen,
            destinationScreen = destinationScreen,
            canonicalIntent = canonicalIntent,
            skillRef = skillRef,
            actionKinds = actions.joinToString(",") { it.action },
            terminal = terminal,
        )
        session.trace += step
        if (session.recipeId == null || session.diverged) return
        val child = findChild(
            session.recipeId,
            session.currentNodeId,
            sourceScreen,
            skillRef,
        )
        if (child == null) {
            session.diverged = true
        } else {
            session.currentNodeId = child
        }
    }

    fun finish(
        session: WorkflowSession,
        goalTemplate: String?,
        goalFingerprint: String?,
        parameterTypesJson: String,
        modelCalls: Int,
        success: Boolean,
    ): Long? {
        if (session.trace.isEmpty()) return null
        val now = System.currentTimeMillis()
        val recipeId = if (success) {
            session.recipeId ?: insertRecipe(
                goalTemplate = goalTemplate,
                goalFingerprint = goalFingerprint ?: session.goalFingerprint,
                parameterTypesJson = parameterTypesJson,
                now = now,
            )
        } else {
            session.recipeId
        }
        writableDatabase.beginTransaction()
        try {
            val run = ContentValues().apply {
                put("run_id", session.runId)
                put("recipe_id", recipeId)
                put("goal_fingerprint", session.goalFingerprint)
                put("status", if (success) "success" else "failed")
                put("model_calls", modelCalls)
                put("started_at", now)
                put("finished_at", now)
            }
            writableDatabase.insertWithOnConflict(
                "workflow_runs",
                null,
                run,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            session.trace.forEachIndexed { index, step ->
                writableDatabase.insertOrThrow(
                    "workflow_run_steps",
                    null,
                    step.values().apply {
                        put("run_id", session.runId)
                        put("ordinal", index)
                    },
                )
            }
            if (success && recipeId != null) {
                var parent: Long? = null
                session.trace.forEach { step ->
                    parent = findChild(recipeId, parent, step.sourceScreen, step.skillRef)
                        ?: insertNode(recipeId, parent, step, now)
                }
                writableDatabase.execSQL(
                    "UPDATE workflow_recipes SET successes=successes+1, updated_at=? WHERE id=?",
                    arrayOf(now, recipeId),
                )
            } else if (recipeId != null) {
                writableDatabase.execSQL(
                    "UPDATE workflow_recipes SET failures=failures+1, updated_at=? WHERE id=?",
                    arrayOf(now, recipeId),
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return recipeId
    }

    private fun insertRecipe(
        goalTemplate: String?,
        goalFingerprint: String,
        parameterTypesJson: String,
        now: Long,
    ): Long {
        val values = ContentValues().apply {
            put("goal_template", goalTemplate)
            put("goal_fingerprint", if (goalTemplate == null) goalFingerprint else null)
            put("parameter_types_json", parameterTypesJson)
            put("created_at", now)
            put("updated_at", now)
        }
        return writableDatabase.insertOrThrow("workflow_recipes", null, values)
    }

    private fun insertNode(
        recipeId: Long,
        parentId: Long?,
        step: WorkflowTraceStep,
        now: Long,
    ): Long = writableDatabase.insertOrThrow(
        "workflow_nodes",
        null,
        step.values().apply {
            put("recipe_id", recipeId)
            put("parent_node_id", parentId)
            put("updated_at", now)
        },
    )

    private fun findChild(
        recipeId: Long,
        parentId: Long?,
        sourceScreen: String,
        skillRef: String,
    ): Long? {
        val parentClause = if (parentId == null) "parent_node_id IS NULL" else "parent_node_id=?"
        val args = if (parentId == null) {
            arrayOf(recipeId.toString(), sourceScreen, skillRef)
        } else {
            arrayOf(recipeId.toString(), parentId.toString(), sourceScreen, skillRef)
        }
        return readableDatabase.query(
            "workflow_nodes",
            arrayOf("id"),
            "recipe_id=? AND $parentClause AND source_screen=? AND skill_ref=?",
            args,
            null,
            null,
            "successes DESC",
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    private fun WorkflowTraceStep.values(): ContentValues = ContentValues().apply {
        put("source_screen", sourceScreen)
        put("destination_screen", destinationScreen)
        put("canonical_intent", canonicalIntent)
        put("skill_ref", skillRef)
        put("action_kinds", actionKinds)
        put("terminal", if (terminal) 1 else 0)
    }

    private fun templateParameters(
        template: String,
        goal: String,
        parameterTypesJson: String,
    ): Map<String, Any>? {
        var cursor = 0
        val names = mutableListOf<String>()
        val pattern = buildString {
            append("^")
            PLACEHOLDER.findAll(template).forEach { placeholder ->
                append(Regex.escape(template.substring(cursor, placeholder.range.first)))
                append("(.+?)")
                names += placeholder.groupValues[1]
                cursor = placeholder.range.last + 1
            }
            append(Regex.escape(template.substring(cursor)))
            append("$")
        }
        val matched = Regex(pattern, RegexOption.IGNORE_CASE).matchEntire(goal) ?: return null
        val types = JSONObject(parameterTypesJson)
        return names.mapIndexed { index, name ->
            val raw = matched.groupValues[index + 1].trim()
            val value: Any = when (types.optString(name, "string")) {
                "integer" -> raw.toIntOrNull() ?: return null
                "boolean" -> raw.lowercase(Locale.ROOT).toBooleanStrictOrNull() ?: return null
                else -> raw
            }
            name to value
        }.toMap()
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun generalizedScreen(value: String): String {
        if (value == "*" || value.endsWith(":*")) return value
        val segments = value.split(':')
        return if (segments.size >= 3) segments.dropLast(1).joinToString(":") + ":*" else value
    }

    companion object {
        const val PHONE_PREFIX = "phone:"
        private val PLACEHOLDER = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}""")
    }
}
