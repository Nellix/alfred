package ai.alfred.app.runtime

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

data class GitHubRegistryStatus(
    val configured: Boolean,
    val sourceUrl: String?,
    val revision: Int?,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val installed: Int,
    val disabled: Int,
    val workflows: Int,
)

data class GitHubSkillSummary(
    val id: String,
    val version: Int,
    val canonicalIntent: String,
    val title: String,
    val description: String,
    val safety: String,
    val enabled: Boolean,
)

data class GitHubSkillCatalog(
    val status: GitHubRegistryStatus,
    val skills: List<GitHubSkillSummary>,
    val workflows: List<GitHubWorkflowSummary>,
)

data class GitHubWorkflowSummary(
    val id: String,
    val version: Int,
    val canonicalIntent: String,
    val title: String,
    val description: String,
    val nodes: Int,
    val enabled: Boolean,
)

data class PublicWorkflowCursor(
    val workflowId: String,
    val version: Int,
    val parameters: Map<String, Any>,
    val childIds: List<String>,
)

data class GitHubWorkflowStep(
    val cursor: PublicWorkflowCursor,
    val nodeId: String,
    val title: String,
    val safety: String,
    val actions: List<DeviceAction>,
    val terminal: Boolean,
)

data class GitHubSkillMatch(
    val id: String,
    val title: String,
    val safety: String,
    val actions: List<DeviceAction>,
    val terminal: Boolean,
)

internal data class DownloadedSkill(
    val id: String,
    val version: Int,
    val canonicalIntent: String,
    val title: String,
    val description: String,
    val goalTemplate: String,
    val parameterTypes: Map<String, String>,
    val sourceScreen: String,
    val safety: String,
    val actions: List<DeviceAction>,
    val terminal: Boolean,
    val digest: String,
    val path: String,
)

internal data class DownloadedWorkflowNode(
    val id: String,
    val sourceScreen: String,
    val actions: List<DeviceAction>,
    val childIds: List<String>,
    val terminal: Boolean,
)

internal data class DownloadedWorkflow(
    val id: String,
    val version: Int,
    val canonicalIntent: String,
    val title: String,
    val description: String,
    val goalTemplate: String,
    val parameterTypes: Map<String, String>,
    val safety: String,
    val entryNode: String,
    val nodes: List<DownloadedWorkflowNode>,
    val digest: String,
    val path: String,
)

class SkillsClient(
    context: Context,
    baseUrl: String,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val store = GitHubSkillStore(context.applicationContext)
    private val registryUrl = baseUrl.trim().trimEnd('/')

    init {
        require(registryUrl.isNotBlank()) { "GitHub skill registry URL is required" }
        val uri = URI(registryUrl)
        require(uri.scheme in setOf("http", "https")) {
            "Skill registry URL must use HTTP or HTTPS"
        }
        require(
            uri.scheme == "https" || uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        ) { "Plain HTTP is allowed only for a local test registry" }
    }

    fun catalog(callback: (Result<GitHubSkillCatalog>) -> Unit) = execute(callback) {
        store.catalog(registryUrl)
    }

    fun refresh(callback: (Result<GitHubSkillCatalog>) -> Unit) = execute(callback) {
        runCatching { downloadRegistry() }
            .onFailure { store.recordFailure(registryUrl, it.message ?: "Registry refresh failed") }
            .getOrThrow()
        store.catalog(registryUrl)
    }

    fun setEnabled(
        skillId: String,
        enabled: Boolean,
        callback: (Result<GitHubSkillSummary>) -> Unit,
    ) = execute(callback) {
        check(store.setEnabled(skillId, enabled)) { "Downloaded skill was not found" }
        store.skill(skillId) ?: error("Downloaded skill was not found")
    }

    fun setWorkflowEnabled(
        workflowId: String,
        enabled: Boolean,
        callback: (Result<GitHubWorkflowSummary>) -> Unit,
    ) = execute(callback) {
        check(store.setEnabled(workflowId, enabled)) { "Downloaded workflow was not found" }
        store.workflow(workflowId) ?: error("Downloaded workflow was not found")
    }

    private fun downloadRegistry() {
        val indexBytes = request("registry/index.json")
        val indexDigest = sha256(indexBytes)
        val index = JSONObject(indexBytes.toString(Charsets.UTF_8))
        require(index.getInt("schema_version") == REGISTRY_SCHEMA_VERSION) {
            "Unsupported registry schema"
        }
        val revision = index.getInt("revision")
        require(revision >= 1) { "Registry revision must be positive" }
        store.requireFreshRevision(revision, indexDigest)
        val entries = index.getJSONArray("skills")
        require(entries.length() <= MAX_SKILLS) { "Registry contains too many skills" }
        val seen = mutableSetOf<String>()
        val downloaded = buildList {
            for (indexPosition in 0 until entries.length()) {
                val entry = entries.getJSONObject(indexPosition)
                val id = entry.getString("id")
                val version = entry.getInt("version")
                val path = entry.getString("path")
                val digest = entry.getString("sha256").lowercase(Locale.ROOT)
                require(SKILL_ID.matches(id)) { "Invalid skill id in registry" }
                require(seen.add(id)) { "Duplicate skill id in registry: $id" }
                require(version >= 1) { "Skill version must be positive" }
                require(SKILL_PATH.matches(path) && ".." !in path) { "Unsafe skill path" }
                require(SHA256.matches(digest)) { "Invalid skill digest" }
                val bytes = request(path)
                require(sha256(bytes) == digest) { "Digest mismatch for $id" }
                add(parseSkill(JSONObject(bytes.toString(Charsets.UTF_8)), id, version, path, digest))
            }
        }
        val workflowEntries = index.optJSONArray("workflows") ?: JSONArray()
        require(workflowEntries.length() <= MAX_WORKFLOWS) { "Registry contains too many workflows" }
        val seenWorkflows = mutableSetOf<String>()
        val downloadedWorkflows = buildList {
            for (indexPosition in 0 until workflowEntries.length()) {
                val entry = workflowEntries.getJSONObject(indexPosition)
                val id = entry.getString("id")
                val version = entry.getInt("version")
                val path = entry.getString("path")
                val digest = entry.getString("sha256").lowercase(Locale.ROOT)
                require(SKILL_ID.matches(id)) { "Invalid workflow id in registry" }
                require(seenWorkflows.add(id)) { "Duplicate workflow id in registry: $id" }
                require(version >= 1) { "Workflow version must be positive" }
                require(WORKFLOW_PATH.matches(path) && ".." !in path) { "Unsafe workflow path" }
                require(SHA256.matches(digest)) { "Invalid workflow digest" }
                val bytes = request(path)
                require(sha256(bytes) == digest) { "Digest mismatch for $id" }
                add(parseWorkflow(JSONObject(bytes.toString(Charsets.UTF_8)), id, version, path, digest))
            }
        }
        store.replace(registryUrl, revision, indexDigest, downloaded, downloadedWorkflows)
    }

    private fun parseWorkflow(
        json: JSONObject,
        expectedId: String,
        expectedVersion: Int,
        path: String,
        digest: String,
    ): DownloadedWorkflow {
        require(json.getInt("schema_version") == WORKFLOW_SCHEMA_VERSION) {
            "Unsupported workflow schema for $expectedId"
        }
        val id = json.getString("id")
        val version = json.getInt("version")
        require(id == expectedId && version == expectedVersion) { "Workflow index mismatch for $id" }
        val canonicalIntent = json.getString("canonical_intent")
        require(SKILL_ID.matches(canonicalIntent)) { "Invalid canonical intent for $id" }
        val title = json.getString("title").trim()
        val description = json.getString("description").trim()
        require(title.length in 3..80 && description.length in 10..300) {
            "Invalid title or description for $id"
        }
        val match = json.getJSONObject("match")
        val goalTemplate = match.getString("goal_template").trim()
        require(goalTemplate.length in 3..240) { "Invalid goal template for $id" }
        val typesJson = match.optJSONObject("parameter_types") ?: JSONObject()
        val parameterTypes = typesJson.keys().asSequence().associateWith { name ->
            require(PARAMETER_NAME.matches(name)) { "Invalid parameter name in $id" }
            typesJson.getString(name).also { type ->
                require(type in PARAMETER_TYPES) { "Invalid parameter type in $id" }
            }
        }
        require(PLACEHOLDER.findAll(goalTemplate).map { it.groupValues[1] }.toSet() == parameterTypes.keys) {
            "Template parameters do not match for $id"
        }
        val safety = json.getString("safety")
        require(safety in setOf("safe", "confirm")) { "Invalid safety level for $id" }
        val entryNode = json.getString("entry_node")
        require(SKILL_ID.matches(entryNode)) { "Invalid entry node for $id" }
        val nodesJson = json.getJSONArray("nodes")
        require(nodesJson.length() in 1..MAX_WORKFLOW_NODES) { "Invalid node count for $id" }
        val nodes = buildList {
            for (position in 0 until nodesJson.length()) {
                val node = nodesJson.getJSONObject(position)
                val nodeId = node.getString("id")
                require(SKILL_ID.matches(nodeId)) { "Invalid node id in $id" }
                val sourceScreen = node.getString("source_screen")
                require(sourceScreen.length in 1..240 && '*' !in sourceScreen.dropLast(1)) {
                    "Invalid source screen in $id"
                }
                val actionsJson = node.getJSONArray("actions")
                require(actionsJson.length() in 1..MAX_ACTIONS) { "Invalid action count in $id" }
                val actions = buildList {
                    for (actionPosition in 0 until actionsJson.length()) {
                        add(DeviceAction.fromJson(actionsJson.getJSONObject(actionPosition)).also {
                            validateAction(it, id, parameterTypes.keys)
                        })
                    }
                }
                val childrenJson = node.getJSONArray("children")
                val children = buildList {
                    for (childPosition in 0 until childrenJson.length()) {
                        add(childrenJson.getString(childPosition))
                    }
                }
                val terminal = node.getBoolean("terminal")
                require((terminal && children.isEmpty()) || (!terminal && children.isNotEmpty())) {
                    "Terminal/children mismatch in $id"
                }
                add(DownloadedWorkflowNode(nodeId, sourceScreen, actions, children, terminal))
            }
        }
        val nodeIds = nodes.map { it.id }
        require(nodeIds.size == nodeIds.toSet().size && entryNode in nodeIds) { "Invalid workflow graph for $id" }
        require(nodes.flatMap { it.childIds }.all { it in nodeIds }) { "Unknown child node in $id" }
        require(nodes.all { it.childIds.size == it.childIds.toSet().size }) { "Duplicate child node in $id" }
        val nodeById = nodes.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(nodeId: String) {
            require(nodeId !in visiting) { "Workflow graph contains a cycle in $id" }
            if (nodeId in visited) return
            visiting += nodeId
            nodeById.getValue(nodeId).childIds.forEach(::visit)
            visiting -= nodeId
            visited += nodeId
        }
        visit(entryNode)
        require(visited == nodeIds.toSet()) { "Workflow contains unreachable nodes in $id" }
        require(nodes.any { node -> node.actions.any { it.action in INTERACTIVE_ACTIONS } }.not() || safety == "confirm") {
            "Interactive workflow requires confirmation for $id"
        }
        val verification = json.getJSONObject("verification")
        require(verification.getString("status") == "device_verified") { "Unverified workflow $id" }
        require(verification.getInt("verified_runs") >= 1) { "Workflow has no verified run: $id" }
        require(verification.getInt("replay_model_calls") >= 0) { "Invalid replay evidence for $id" }
        return DownloadedWorkflow(
            id, version, canonicalIntent, title, description, goalTemplate, parameterTypes,
            safety, entryNode, nodes, digest, path,
        )
    }

    private fun parseSkill(
        json: JSONObject,
        expectedId: String,
        expectedVersion: Int,
        path: String,
        digest: String,
    ): DownloadedSkill {
        require(json.getInt("schema_version") == SKILL_SCHEMA_VERSION) {
            "Unsupported skill schema for $expectedId"
        }
        val id = json.getString("id")
        val version = json.getInt("version")
        require(id == expectedId && version == expectedVersion) { "Skill index mismatch for $id" }
        val canonicalIntent = json.getString("canonical_intent")
        require(SKILL_ID.matches(canonicalIntent)) { "Invalid canonical intent for $id" }
        val title = json.getString("title").trim()
        val description = json.getString("description").trim()
        require(title.length in 3..80 && description.length in 10..300) {
            "Invalid title or description for $id"
        }
        val match = json.getJSONObject("match")
        val goalTemplate = match.getString("goal_template").trim()
        require(goalTemplate.length in 3..240) { "Invalid goal template for $id" }
        val sourceScreen = match.optString("source_screen", "*").trim()
        require(sourceScreen == "*" || sourceScreen.length in 3..240) {
            "Invalid source screen for $id"
        }
        val typesJson = match.optJSONObject("parameter_types") ?: JSONObject()
        val parameterTypes = typesJson.keys().asSequence().associateWith { name ->
            require(PARAMETER_NAME.matches(name)) { "Invalid parameter name in $id" }
            typesJson.getString(name).also { type ->
                require(type in PARAMETER_TYPES) { "Invalid parameter type in $id" }
            }
        }
        val placeholders = PLACEHOLDER.findAll(goalTemplate).map { it.groupValues[1] }.toSet()
        require(placeholders == parameterTypes.keys) { "Template parameters do not match for $id" }
        val declaredSafety = json.getString("safety")
        require(declaredSafety in setOf("safe", "confirm")) { "Invalid safety level for $id" }
        val actionsJson = json.getJSONArray("actions")
        require(actionsJson.length() in 1..MAX_ACTIONS) { "Invalid action count for $id" }
        val actions = buildList {
            for (position in 0 until actionsJson.length()) {
                add(
                    DeviceAction.fromJson(actionsJson.getJSONObject(position)).also {
                        validateAction(it, id, parameterTypes.keys)
                    }
                )
            }
        }
        val safety = if (actions.any { it.action in INTERACTIVE_ACTIONS }) "confirm" else declaredSafety
        return DownloadedSkill(
            id = id,
            version = version,
            canonicalIntent = canonicalIntent,
            title = title,
            description = description,
            goalTemplate = goalTemplate,
            parameterTypes = parameterTypes,
            sourceScreen = sourceScreen,
            safety = safety,
            actions = actions,
            terminal = json.optBoolean("terminal", true),
            digest = digest,
            path = path,
        )
    }

    private fun validateAction(
        action: DeviceAction,
        skillId: String,
        parameterNames: Set<String>,
    ) {
        require(action.action in ALLOWED_ACTIONS) { "Unsupported action in $skillId" }
        action.packageName?.let {
            require(PACKAGE_NAME.matches(it)) { "Invalid package in $skillId" }
        }
        action.uri?.let {
            val scheme = it.substringBefore(':').lowercase(Locale.ROOT)
            require(scheme in setOf("https", "geo", "mailto")) { "Unsafe URI in $skillId" }
        }
        if (action.action in setOf("click", "set_text", "scroll")) {
            require(action.target?.startsWith("label:") == true) {
                "Interactive targets must use a semantic label in $skillId"
            }
            require(!DANGEROUS_TARGET.containsMatchIn(action.target)) {
                "Destructive target is not allowed in $skillId"
            }
        }
        if (action.action == "set_text") {
            require(action.value?.contains("\${") == true) {
                "Downloaded text-entry actions must use a parameter in $skillId"
            }
        }
        listOf(action.target, action.value, action.packageName, action.uri).forEach { value ->
            if (value == null) return@forEach
            val placeholders = ACTION_PLACEHOLDER.findAll(value).toList()
            require(placeholders.all { it.groupValues[1] in parameterNames }) {
                "Action references an undeclared parameter in $skillId"
            }
            require("\${" !in ACTION_PLACEHOLDER.replace(value, "")) {
                "Action contains an invalid parameter expression in $skillId"
            }
        }
    }

    private fun request(path: String): ByteArray {
        val connection = URL("$registryUrl/$path").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 8_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Alfred-Android/0.1")
        val code = connection.responseCode
        if (code !in 200..299) throw IllegalStateException("GitHub registry returned HTTP $code")
        val declaredLength = connection.contentLengthLong
        require(declaredLength < 0 || declaredLength <= MAX_FILE_BYTES) { "Registry file is too large" }
        return connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var remaining = MAX_FILE_BYTES + 1
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
            val bytes = output.toByteArray()
            require(bytes.size <= MAX_FILE_BYTES) { "Registry file is too large" }
            bytes
        }
    }

    private fun <T> execute(callback: (Result<T>) -> Unit, action: () -> T) {
        executor.execute { callback(runCatching(action)) }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val REGISTRY_SCHEMA_VERSION = 1
        private const val SKILL_SCHEMA_VERSION = 1
        private const val WORKFLOW_SCHEMA_VERSION = 1
        private const val MAX_SKILLS = 256
        private const val MAX_WORKFLOWS = 128
        private const val MAX_WORKFLOW_NODES = 64
        private const val MAX_ACTIONS = 8
        private const val MAX_FILE_BYTES = 256 * 1024
        private val SKILL_ID = Regex("^[a-z][a-z0-9_.-]{2,127}$")
        private val SKILL_PATH = Regex("^[A-Za-z0-9_./-]+\\.json$")
        private val WORKFLOW_PATH = Regex("^workflows/[A-Za-z0-9_./-]+\\.json$")
        private val SHA256 = Regex("^[a-f0-9]{64}$")
        private val PARAMETER_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")
        private val PLACEHOLDER = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}""")
        private val ACTION_PLACEHOLDER =
            Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(?:\|(url))?\}""")
        private val PARAMETER_TYPES = setOf("string", "integer", "boolean")
        private val ALLOWED_ACTIONS = setOf(
            "launch_app", "open_deep_link", "back", "wait", "stop",
            "click", "set_text", "scroll",
        )
        private val INTERACTIVE_ACTIONS = setOf("click", "set_text")
        private val DANGEROUS_TARGET = Regex(
            "\\b(?:send|delete|remove|purchase|buy|pay|transfer|install|uninstall|factory reset)\\b",
            RegexOption.IGNORE_CASE,
        )
    }
}

class GitHubSkillStore(context: Context) : SQLiteOpenHelper(
    context,
    "github-skills.db",
    null,
    2,
) {
    override fun onConfigure(database: SQLiteDatabase) {
        database.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE skills(
                id TEXT PRIMARY KEY,
                version INTEGER NOT NULL,
                canonical_intent TEXT NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL,
                goal_template TEXT NOT NULL,
                parameter_types_json TEXT NOT NULL,
                source_screen TEXT NOT NULL,
                safety TEXT NOT NULL,
                actions_json TEXT NOT NULL,
                terminal INTEGER NOT NULL,
                digest TEXT NOT NULL,
                path TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        database.execSQL(
            """CREATE TABLE registry_state(
                id INTEGER PRIMARY KEY CHECK(id=1),
                source_url TEXT,
                revision INTEGER,
                index_digest TEXT,
                last_success_at INTEGER,
                last_error TEXT
            )""".trimIndent()
        )
        database.execSQL("INSERT INTO registry_state(id) VALUES (1)")
        createWorkflowTables(database)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createWorkflowTables(database)
    }

    private fun createWorkflowTables(database: SQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS workflows(
                id TEXT PRIMARY KEY,
                version INTEGER NOT NULL,
                canonical_intent TEXT NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL,
                goal_template TEXT NOT NULL,
                parameter_types_json TEXT NOT NULL,
                safety TEXT NOT NULL,
                entry_node TEXT NOT NULL,
                digest TEXT NOT NULL,
                path TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS workflow_nodes(
                workflow_id TEXT NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
                node_id TEXT NOT NULL,
                source_screen TEXT NOT NULL,
                actions_json TEXT NOT NULL,
                children_json TEXT NOT NULL,
                terminal INTEGER NOT NULL,
                PRIMARY KEY(workflow_id, node_id)
            )""".trimIndent()
        )
    }

    fun requireFreshRevision(revision: Int, digest: String) {
        readableDatabase.rawQuery(
            "SELECT revision, index_digest FROM registry_state WHERE id=1",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return
            val installedRevision = cursor.getInt(0)
            val installedDigest = cursor.getString(1)
            require(revision >= installedRevision) { "Registry rollback was rejected" }
            require(revision != installedRevision || digest == installedDigest) {
                "Registry revision changed without a revision bump"
            }
        }
    }

    internal fun replace(
        sourceUrl: String,
        revision: Int,
        indexDigest: String,
        skills: List<DownloadedSkill>,
        workflows: List<DownloadedWorkflow>,
    ) {
        writableDatabase.beginTransaction()
        try {
            val ids = skills.map { it.id }.toSet()
            skills.forEach { skill ->
                val previousEnabled = readableDatabase.query(
                    "skills",
                    arrayOf("enabled"),
                    "id=?",
                    arrayOf(skill.id),
                    null,
                    null,
                    null,
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 1 }
                val values = ContentValues().apply {
                    put("id", skill.id)
                    put("version", skill.version)
                    put("canonical_intent", skill.canonicalIntent)
                    put("title", skill.title)
                    put("description", skill.description)
                    put("goal_template", skill.goalTemplate)
                    put("parameter_types_json", JSONObject(skill.parameterTypes).toString())
                    put("source_screen", skill.sourceScreen)
                    put("safety", skill.safety)
                    put("actions_json", JSONArray(skill.actions.map(::actionJson)).toString())
                    put("terminal", if (skill.terminal) 1 else 0)
                    put("digest", skill.digest)
                    put("path", skill.path)
                    put("enabled", previousEnabled)
                    put("updated_at", System.currentTimeMillis())
                }
                writableDatabase.insertWithOnConflict(
                    "skills",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            val stale = readableDatabase.query(
                "skills",
                arrayOf("id"),
                null,
                null,
                null,
                null,
                null,
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }.filterNot(ids::contains)
            stale.forEach { writableDatabase.delete("skills", "id=?", arrayOf(it)) }
            val workflowIds = workflows.map { it.id }.toSet()
            workflows.forEach { workflow ->
                val previousEnabled = readableDatabase.query(
                    "workflows", arrayOf("enabled"), "id=?", arrayOf(workflow.id),
                    null, null, null,
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 1 }
                val values = ContentValues().apply {
                    put("id", workflow.id)
                    put("version", workflow.version)
                    put("canonical_intent", workflow.canonicalIntent)
                    put("title", workflow.title)
                    put("description", workflow.description)
                    put("goal_template", workflow.goalTemplate)
                    put("parameter_types_json", JSONObject(workflow.parameterTypes).toString())
                    put("safety", workflow.safety)
                    put("entry_node", workflow.entryNode)
                    put("digest", workflow.digest)
                    put("path", workflow.path)
                    put("enabled", previousEnabled)
                    put("updated_at", System.currentTimeMillis())
                }
                writableDatabase.insertWithOnConflict(
                    "workflows", null, values, SQLiteDatabase.CONFLICT_REPLACE,
                )
                writableDatabase.delete("workflow_nodes", "workflow_id=?", arrayOf(workflow.id))
                workflow.nodes.forEach { node ->
                    writableDatabase.insertOrThrow(
                        "workflow_nodes",
                        null,
                        ContentValues().apply {
                            put("workflow_id", workflow.id)
                            put("node_id", node.id)
                            put("source_screen", node.sourceScreen)
                            put("actions_json", JSONArray(node.actions.map(::actionJson)).toString())
                            put("children_json", JSONArray(node.childIds).toString())
                            put("terminal", if (node.terminal) 1 else 0)
                        },
                    )
                }
            }
            readableDatabase.query(
                "workflows", arrayOf("id"), null, null, null, null, null,
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }.filterNot(workflowIds::contains).forEach {
                writableDatabase.delete("workflows", "id=?", arrayOf(it))
            }
            writableDatabase.execSQL(
                """UPDATE registry_state SET source_url=?, revision=?, index_digest=?,
                last_success_at=?, last_error=NULL WHERE id=1""",
                arrayOf<Any>(sourceUrl, revision, indexDigest, System.currentTimeMillis()),
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun recordFailure(sourceUrl: String, message: String) {
        writableDatabase.execSQL(
            "UPDATE registry_state SET source_url=?, last_error=? WHERE id=1",
            arrayOf(sourceUrl, message.take(300)),
        )
    }

    fun catalog(sourceUrl: String): GitHubSkillCatalog {
        val skills = readableDatabase.query(
            "skills",
            null,
            null,
            null,
            null,
            null,
            "canonical_intent, id",
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.summary()) }
        }
        val state = readableDatabase.rawQuery(
            "SELECT revision, last_success_at, last_error FROM registry_state WHERE id=1",
            null,
        ).use { cursor ->
            check(cursor.moveToFirst())
            GitHubRegistryStatus(
                configured = sourceUrl.isNotBlank(),
                sourceUrl = sourceUrl,
                revision = if (cursor.isNull(0)) null else cursor.getInt(0),
                lastSuccessAt = if (cursor.isNull(1)) null else cursor.getLong(1),
                lastError = if (cursor.isNull(2)) null else cursor.getString(2),
                installed = skills.size,
                disabled = skills.count { !it.enabled },
                workflows = workflowSummaries().size,
            )
        }
        return GitHubSkillCatalog(state, skills, workflowSummaries())
    }

    private fun workflowSummaries(): List<GitHubWorkflowSummary> = readableDatabase.rawQuery(
        """SELECT w.*, (SELECT COUNT(*) FROM workflow_nodes n WHERE n.workflow_id=w.id) AS node_count
        FROM workflows w ORDER BY canonical_intent, id""".trimIndent(),
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    GitHubWorkflowSummary(
                        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                        version = cursor.getInt(cursor.getColumnIndexOrThrow("version")),
                        canonicalIntent = cursor.getString(cursor.getColumnIndexOrThrow("canonical_intent")),
                        title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                        description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
                        nodes = cursor.getInt(cursor.getColumnIndexOrThrow("node_count")),
                        enabled = cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) == 1,
                    )
                )
            }
        }
    }

    fun skill(id: String): GitHubSkillSummary? = readableDatabase.query(
        "skills",
        null,
        "id=?",
        arrayOf(id),
        null,
        null,
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.summary() else null }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val values = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
        val skillUpdated = writableDatabase.update("skills", values, "id=?", arrayOf(id)) == 1
        val workflowUpdated = writableDatabase.update("workflows", values, "id=?", arrayOf(id)) == 1
        return skillUpdated || workflowUpdated
    }

    fun workflow(id: String): GitHubWorkflowSummary? = workflowSummaries().singleOrNull { it.id == id }

    fun resolveWorkflow(goal: String, sourceScreen: String): GitHubWorkflowStep? {
        val normalized = normalize(goal)
        val matches = mutableListOf<GitHubWorkflowStep>()
        readableDatabase.query(
            "workflows", null, "enabled=1", null, null, null, "version DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val typesJson = JSONObject(cursor.getString(cursor.getColumnIndexOrThrow("parameter_types_json")))
                val types = typesJson.keys().asSequence().associateWith(typesJson::getString)
                val parameters = match(
                    normalized,
                    cursor.getString(cursor.getColumnIndexOrThrow("goal_template")),
                    types,
                ) ?: continue
                val workflowId = cursor.getString(cursor.getColumnIndexOrThrow("id"))
                val node = workflowNode(
                    workflowId,
                    cursor.getString(cursor.getColumnIndexOrThrow("entry_node")),
                    sourceScreen,
                    parameters,
                    cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    cursor.getString(cursor.getColumnIndexOrThrow("safety")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("version")),
                ) ?: continue
                matches += node
            }
        }
        return matches.singleOrNull()
    }

    fun nextWorkflowStep(cursor: PublicWorkflowCursor, sourceScreen: String): GitHubWorkflowStep? {
        val summary = workflow(cursor.workflowId) ?: return null
        if (!summary.enabled || summary.version != cursor.version) return null
        val safety = readableDatabase.query(
            "workflows", arrayOf("safety"), "id=?", arrayOf(cursor.workflowId),
            null, null, null,
        ).use { result -> if (result.moveToFirst()) result.getString(0) else return null }
        val matches = cursor.childIds.mapNotNull { nodeId ->
            workflowNode(
                cursor.workflowId, nodeId, sourceScreen, cursor.parameters,
                summary.title, safety, summary.version,
            )
        }
        return matches.singleOrNull()
    }

    private fun workflowNode(
        workflowId: String,
        nodeId: String,
        sourceScreen: String,
        parameters: Map<String, Any>,
        title: String,
        workflowSafety: String,
        version: Int,
    ): GitHubWorkflowStep? = readableDatabase.query(
        "workflow_nodes", null, "workflow_id=? AND node_id=?", arrayOf(workflowId, nodeId),
        null, null, null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        if (!screenMatches(cursor.getString(cursor.getColumnIndexOrThrow("source_screen")), sourceScreen)) {
            return@use null
        }
        val actionsJson = JSONArray(cursor.getString(cursor.getColumnIndexOrThrow("actions_json")))
        val actions = buildList {
            for (position in 0 until actionsJson.length()) {
                add(substitute(DeviceAction.fromJson(actionsJson.getJSONObject(position)), parameters))
            }
        }
        val childrenJson = JSONArray(cursor.getString(cursor.getColumnIndexOrThrow("children_json")))
        val children = buildList {
            for (position in 0 until childrenJson.length()) add(childrenJson.getString(position))
        }
        GitHubWorkflowStep(
            cursor = PublicWorkflowCursor(workflowId, version, parameters, children),
            nodeId = nodeId,
            title = title,
            safety = if (actions.any { it.action in setOf("click", "set_text") }) workflowSafety else "safe",
            actions = actions,
            terminal = cursor.getInt(cursor.getColumnIndexOrThrow("terminal")) == 1,
        )
    }

    private fun screenMatches(pattern: String, actual: String): Boolean = when {
        pattern == "*" -> true
        pattern.endsWith("*") -> actual.startsWith(pattern.dropLast(1))
        else -> pattern == actual
    }

    fun resolve(goal: String, sourceScreen: String): GitHubSkillMatch? {
        val normalized = normalize(goal)
        val matches = mutableListOf<GitHubSkillMatch>()
        readableDatabase.query(
            "skills",
            null,
            "enabled=1 AND (source_screen=? OR source_screen='*')",
            arrayOf(sourceScreen),
            null,
            null,
            "version DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val template = cursor.getString(cursor.getColumnIndexOrThrow("goal_template"))
                val typesJson = JSONObject(
                    cursor.getString(cursor.getColumnIndexOrThrow("parameter_types_json"))
                )
                val types = typesJson.keys().asSequence().associateWith(typesJson::getString)
                val parameters = match(normalized, template, types) ?: continue
                val actionsJson = JSONArray(
                    cursor.getString(cursor.getColumnIndexOrThrow("actions_json"))
                )
                val actions = buildList {
                    for (position in 0 until actionsJson.length()) {
                        add(substitute(DeviceAction.fromJson(actionsJson.getJSONObject(position)), parameters))
                    }
                }
                matches += GitHubSkillMatch(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    safety = cursor.getString(cursor.getColumnIndexOrThrow("safety")),
                    actions = actions,
                    terminal = cursor.getInt(cursor.getColumnIndexOrThrow("terminal")) == 1,
                )
            }
        }
        return matches.singleOrNull()
    }

    /** Returns only a few relevant public skill descriptions, never the full registry. */
    fun plannerHints(goal: String, sourceScreen: String, limit: Int = 3): List<String> {
        val goalTokens = normalize(goal).lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .toSet()
        return readableDatabase.query(
            "skills",
            arrayOf(
                "canonical_intent", "title", "description", "goal_template", "source_screen",
            ),
            "enabled=1",
            null,
            null,
            null,
            "version DESC",
            "80",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val intent = cursor.getString(0)
                    val title = cursor.getString(1)
                    val description = cursor.getString(2)
                    val template = cursor.getString(3)
                    val screen = cursor.getString(4)
                    val searchable = "$intent $title $description $template".lowercase(Locale.ROOT)
                    val score = goalTokens.count(searchable::contains) +
                        if (screen == "*" || screen == sourceScreen) 1 else 0
                    if (score > 0) add(score to "public:$intent · $title · $template")
                }
            }.sortedByDescending { it.first }.take(limit).map { it.second }
        }
    }

    private fun android.database.Cursor.summary(): GitHubSkillSummary = GitHubSkillSummary(
        id = getString(getColumnIndexOrThrow("id")),
        version = getInt(getColumnIndexOrThrow("version")),
        canonicalIntent = getString(getColumnIndexOrThrow("canonical_intent")),
        title = getString(getColumnIndexOrThrow("title")),
        description = getString(getColumnIndexOrThrow("description")),
        safety = getString(getColumnIndexOrThrow("safety")),
        enabled = getInt(getColumnIndexOrThrow("enabled")) == 1,
    )

    private fun actionJson(action: DeviceAction): JSONObject = JSONObject()
        .put("action", action.action)
        .put("target", action.target)
        .put("value", action.value)
        .put("package", action.packageName)
        .put("uri", action.uri)

    private fun match(
        normalized: String,
        template: String,
        types: Map<String, String>,
    ): Map<String, Any>? {
        val placeholders = PLACEHOLDER.findAll(template).toList()
        val names = mutableListOf<String>()
        var cursor = 0
        val expression = buildString {
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
        val result = Regex(expression, RegexOption.IGNORE_CASE).matchEntire(normalized) ?: return null
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
                else -> raw
            }
        }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("\\s+"), " ")

    companion object {
        private val PLACEHOLDER = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}""")
        private val VALUE_PLACEHOLDER =
            Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(?:\|(url))?\}""")
    }
}
