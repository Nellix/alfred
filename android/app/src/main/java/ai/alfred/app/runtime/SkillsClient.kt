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
        store.replace(registryUrl, revision, indexDigest, downloaded)
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
        private const val MAX_SKILLS = 256
        private const val MAX_ACTIONS = 8
        private const val MAX_FILE_BYTES = 256 * 1024
        private val SKILL_ID = Regex("^[a-z][a-z0-9_.-]{2,127}$")
        private val SKILL_PATH = Regex("^[A-Za-z0-9_./-]+\\.json$")
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
    1,
) {
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
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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
            )
        }
        return GitHubSkillCatalog(state, skills)
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
        return writableDatabase.update("skills", values, "id=?", arrayOf(id)) == 1
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
