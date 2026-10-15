package ai.alfred.app.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper

enum class InferenceMode { AUTO, LOCAL_ONLY, CLOUD_FIRST }

class AgentCoordinator(
    context: Context,
    private val events: Events,
) {
    interface Events {
        fun onThinking(message: String)
        fun onActing(message: String)
        fun onConfirmation(goal: String, message: String)
        fun onSuccess(message: String, modelCalls: Int)
        fun onResult(title: String, message: String)
        fun onFailure(message: String)
    }

    private val app = context.applicationContext
    private val native = NativeIntentRouter(app)
    private val localCache = LocalSemanticCache(app)
    private val githubSkills = GitHubSkillStore(app)
    private val main = Handler(Looper.getMainLooper())

    fun submit(goal: String, confirmed: Boolean = false) {
        val mailRequest = MailRecapIntent.parse(goal)
        if (mailRequest != null) {
            if (mailRequest.readsMessageBodies && !confirmed) {
                events.onConfirmation(
                    goal,
                    "This skill will open up to ${mailRequest.limit} Gmail messages and temporarily read visible body text to create short on-device summaries. " +
                        "If Gmail does not expose the body to accessibility, Alfred uses on-device text recognition only on the visible message-body region. " +
                        "Screenshots and raw body text will not be displayed or retained. " +
                        "Gmail may mark opened messages as read. " +
                        "The text stays on this phone and is not logged, learned, synchronized, or sent to the planner. Continue?",
                )
                return
            }
            runMailRecap(mailRequest)
            return
        }
        route(goal, confirmed, step = 0, totalModelCalls = 0, allowNative = true)
    }

    private fun runMailRecap(request: MailRecapRequest) {
        val service = SemanticAccessibilityService.current()
        if (service == null) {
            events.onFailure("Enable Alfred Device Actions in Accessibility settings first.")
            return
        }
        if (runCatching { app.packageManager.getPackageInfo(GMAIL_PACKAGE, 0) }.isFailure) {
            events.onFailure("Gmail is not installed on this phone.")
            return
        }
        events.onActing(
            if (request.readsMessageBodies) {
                "Opening Gmail and summarizing visible body text from up to ${request.limit} messages locally…"
            } else {
                "Opening Gmail and reading ${request.limit} inbox rows only — no messages or body content will be opened…"
            }
        )
        service.execute(DeviceAction("launch_app", packageName = GMAIL_PACKAGE)) { launched ->
            if (!launched.success) {
                events.onFailure(launched.message)
                return@execute
            }
            if (request.readsMessageBodies) {
                events.onThinking("Opening each requested message and creating a private on-device summary…")
                service.collectGmailBodyRecap(request.requested, request.limit) { result ->
                    result.fold(
                        onSuccess = { recap ->
                            returnMailResult(service, "Message-body summary", recap.displayText)
                        },
                        onFailure = { error ->
                            returnMailFailure(
                                service,
                                error.message ?: "I could not read Gmail message bodies.",
                            )
                        },
                    )
                }
            } else {
                events.onThinking("Preparing a private inbox-row briefing without opening any message…")
                service.collectGmailRecap(request.limit) { result ->
                    result.fold(
                        onSuccess = { recap ->
                            returnMailResult(service, "Inbox-row briefing", recap.displayText)
                        },
                        onFailure = { error ->
                            returnMailFailure(
                                service,
                                error.message ?: "I could not read the Gmail inbox rows.",
                            )
                        },
                    )
                }
            }
        }
    }

    private fun returnMailResult(
        service: SemanticAccessibilityService,
        title: String,
        displayText: String,
    ) {
        events.onActing("Briefing ready — returning to Alfred…")
        service.execute(DeviceAction("launch_app", packageName = app.packageName)) { returned ->
            if (returned.success) {
                events.onResult(title, displayText)
            } else {
                events.onFailure(returned.message)
            }
        }
    }

    private fun returnMailFailure(
        service: SemanticAccessibilityService,
        message: String,
    ) {
        events.onActing("I hit a Gmail issue — returning to Alfred…")
        service.execute(DeviceAction("launch_app", packageName = app.packageName)) { returned ->
            if (returned.success) {
                events.onFailure(message)
            } else {
                events.onFailure("$message Alfred could not return automatically: ${returned.message}")
            }
        }
    }

    private fun route(
        goal: String,
        confirmed: Boolean,
        step: Int,
        totalModelCalls: Int,
        allowNative: Boolean,
    ) {
        if (step >= 12) {
            events.onFailure("I stopped after 12 actions to keep the task bounded and safe.")
            return
        }
        val service = SemanticAccessibilityService.current()
        if (service == null) {
            events.onFailure("Enable Alfred Device Actions in Accessibility settings first.")
            return
        }
        val preferences = app.getSharedPreferences("alfred", Context.MODE_PRIVATE)
        val mode = runCatching {
            InferenceMode.valueOf(preferences.getString("inference_mode", InferenceMode.AUTO.name)!!)
        }.getOrDefault(InferenceMode.AUTO)
        val nativePlan = if (allowNative) native.resolve(goal) else null
        if (nativePlan != null && mode != InferenceMode.CLOUD_FIRST) {
            events.onActing(nativePlan.message)
            executeActions(
                service = service,
                actions = listOf(nativePlan.action),
                totalModelCalls = totalModelCalls,
                attemptId = null,
                client = null,
                localArtifact = null,
                localPatternId = null,
                goal = goal,
                continueAfter = !nativePlan.terminal,
                step = step,
            )
            return
        }
        val snapshot = service.snapshot()
        if (snapshot == null) {
            events.onFailure("I cannot read the current screen yet. Open the target app and try again.")
            return
        }
        if (mode != InferenceMode.CLOUD_FIRST) {
            val local = localCache.resolve(goal, snapshot.screenId)
            if (local != null) {
                if (local.safety == "confirm" && !confirmed) {
                    events.onConfirmation(goal, "Confirm before Alfred prepares this remembered draft.")
                    return
                }
                events.onActing("I remember this on your phone — zero network and model calls")
                executeActions(
                    service = service,
                    actions = local.actions,
                    totalModelCalls = totalModelCalls,
                    attemptId = null,
                    client = null,
                    localArtifact = null,
                    localPatternId = local.id,
                    goal = goal,
                    continueAfter = !local.terminal,
                    step = step,
                )
                return
            }
            val downloaded = githubSkills.resolve(goal, snapshot.screenId)
            if (downloaded != null) {
                if (downloaded.safety == "confirm" && !confirmed) {
                    events.onConfirmation(
                        goal,
                        "The reviewed GitHub skill “${downloaded.title}” needs confirmation before it interacts with this screen.",
                    )
                    return
                }
                events.onActing("Using reviewed GitHub skill · ${downloaded.title}")
                executeActions(
                    service = service,
                    actions = downloaded.actions,
                    totalModelCalls = totalModelCalls,
                    attemptId = null,
                    client = null,
                    localArtifact = null,
                    localPatternId = null,
                    goal = goal,
                    continueAfter = !downloaded.terminal,
                    step = step,
                )
                return
            }
        }
        if (mode == InferenceMode.LOCAL_ONLY) {
            events.onFailure("This request is not in the local catalog or phone cache. Change inference mode or teach a pattern first.")
            return
        }
        events.onThinking("Phone cache missed — checking your planner…")
        val endpoint = preferences.getString("backend_url", "http://127.0.0.1:8765")!!
        val profileId = preferences.getString("profile_id", null) ?: java.util.UUID.randomUUID()
            .toString()
            .also { preferences.edit().putString("profile_id", it).apply() }
        val client = try {
            BackendClient(endpoint)
        } catch (error: IllegalArgumentException) {
            events.onFailure(error.message ?: "Invalid backend URL")
            return
        }
        client.plan(goal, snapshot, confirmed, profileId) { result ->
            main.post {
                result.fold(
                    onSuccess = { plan ->
                        if (plan.confirmationRequired && !confirmed) {
                            events.onConfirmation(goal, plan.message ?: "This draft requires confirmation.")
                        } else if (plan.actions.isEmpty()) {
                            events.onFailure(plan.message ?: "No safe action was returned.")
                        } else {
                            events.onActing(
                                when (plan.source) {
                                    "cache" -> "I remember this — running locally with zero model calls"
                                    "native" -> "Using a local device action"
                                    else -> "I found a safe next action"
                                }
                            )
                            executeActions(
                                service = service,
                                actions = plan.actions,
                                totalModelCalls = totalModelCalls + plan.modelCalls,
                                attemptId = plan.attemptId,
                                client = client,
                                localArtifact = plan.localArtifact,
                                localPatternId = null,
                                goal = goal,
                                continueAfter = !plan.terminal,
                                step = step,
                            )
                        }
                    },
                    onFailure = { error ->
                        events.onFailure("The planner is unavailable: ${error.message}")
                    },
                )
            }
        }
    }

    private fun executeActions(
        service: SemanticAccessibilityService,
        actions: List<DeviceAction>,
        totalModelCalls: Int,
        attemptId: String?,
        client: BackendClient?,
        localArtifact: LocalCacheArtifact?,
        localPatternId: Long?,
        goal: String,
        continueAfter: Boolean,
        step: Int,
        index: Int = 0,
    ) {
        service.execute(actions[index]) { outcome ->
            if (!outcome.success) {
                localPatternId?.let { localCache.recordOutcome(it, false) }
                attemptId?.let {
                    client?.report(it, outcome, service.snapshot()?.screenId)
                }
                events.onFailure(outcome.message)
                return@execute
            }
            if (index + 1 < actions.size) {
                executeActions(
                    service,
                    actions,
                    totalModelCalls,
                    attemptId,
                    client,
                    localArtifact,
                    localPatternId,
                    goal,
                    continueAfter,
                    step,
                    index + 1,
                )
                return@execute
            }
            localPatternId?.let { localCache.recordOutcome(it, true) }
            localArtifact?.let(::saveLearnedArtifact)
            val finish: () -> Unit = {
                if (continueAfter) {
                    route(
                        goal,
                        confirmed = false,
                        step = step + 1,
                        totalModelCalls = totalModelCalls,
                        allowNative = false,
                    )
                } else {
                    events.onSuccess(
                        if (totalModelCalls == 0) "Done — no LLM call needed" else "Done — I'll reuse these verified steps next time",
                        totalModelCalls,
                    )
                }
            }
            if (attemptId != null && client != null) {
                client.report(attemptId, outcome, service.snapshot()?.screenId) { result ->
                    result.getOrNull()?.let(::saveLearnedArtifact)
                    main.post(finish)
                }
            } else {
                finish()
            }
        }
    }

    private fun saveLearnedArtifact(artifact: LocalCacheArtifact) {
        localCache.save(artifact)
    }

    companion object {
        private const val GMAIL_PACKAGE = "com.google.android.gm"
    }
}
