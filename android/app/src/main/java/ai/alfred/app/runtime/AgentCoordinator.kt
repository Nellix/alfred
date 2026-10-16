package ai.alfred.app.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.alfred.app.ui.ConfirmationActivity
import org.json.JSONObject

enum class InferenceMode { AUTO, LOCAL_ONLY, CLOUD_FIRST }

class AgentCoordinator(
    context: Context,
    private val events: Events,
) {
    interface Events {
        fun assistantIsForeground(): Boolean
        fun onThinking(message: String)
        fun onActing(message: String)
        fun onConfirmation(goal: String, message: String)
        fun onSuccess(message: String, modelCalls: Int)
        fun onSkillLearned(skillIds: LongArray, names: List<String>)
        fun onResult(title: String, message: String)
        fun onFailure(message: String)
    }

    private val app = context.applicationContext
    private val native = NativeIntentRouter(app)
    private val localCache = LocalSemanticCache(app)
    private val githubSkills = GitHubSkillStore(app)
    private val workflows = WorkflowStore(app)
    private val main = Handler(Looper.getMainLooper())

    private data class LearningSession(
        val workflow: WorkflowSession,
        val publishableSkillIds: LinkedHashSet<Long> = linkedSetOf(),
        val names: LinkedHashSet<String> = linkedSetOf(),
        var goalTemplate: String? = null,
        var goalFingerprint: String? = null,
        var parameterTypes: Map<String, String> = emptyMap(),
    )

    private data class PendingRemoteConfirmation(
        val goal: String,
        val plan: RemotePlan,
        val client: BackendClient,
        val totalModelCalls: Int,
        val step: Int,
        val learning: LearningSession,
        val sourceScreen: String,
        val restorePreviousScreen: Boolean,
    )

    private data class PendingDeviceConfirmation(
        val goal: String,
        val actions: List<DeviceAction>,
        val totalModelCalls: Int,
        val localPatternId: Long?,
        val continueAfter: Boolean,
        val step: Int,
        val learning: LearningSession,
        val sourceScreen: String,
        val canonicalIntent: String?,
        val skillRef: String,
        val restorePreviousScreen: Boolean,
    )

    private var pendingRemoteConfirmation: PendingRemoteConfirmation? = null
    private var pendingDeviceConfirmation: PendingDeviceConfirmation? = null

    fun submit(goal: String, confirmed: Boolean = false) {
        if (confirmed) {
            val devicePending = pendingDeviceConfirmation
            if (devicePending != null && devicePending.goal == goal) {
                pendingDeviceConfirmation = null
                executeConfirmedDevicePlan(devicePending)
                return
            }
            val pending = pendingRemoteConfirmation
            if (pending != null && pending.goal == goal) {
                pendingRemoteConfirmation = null
                executeConfirmedRemotePlan(pending)
                return
            }
        } else {
            cancelPendingConfirmation()
        }
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
        route(
            goal,
            confirmed,
            step = 0,
            totalModelCalls = 0,
            allowNative = true,
            learning = LearningSession(workflows.begin(goal)),
        )
    }

    fun cancelPendingConfirmation() {
        pendingDeviceConfirmation?.let { pending ->
            pendingDeviceConfirmation = null
            finishWorkflow(pending.learning, pending.totalModelCalls, success = false)
        }
        val pending = pendingRemoteConfirmation ?: return
        pendingRemoteConfirmation = null
        finishWorkflow(pending.learning, pending.totalModelCalls, success = false)
        pending.plan.attemptId?.let { attemptId ->
            pending.client.report(
                attemptId,
                ActionOutcome(
                    success = false,
                    verified = false,
                    code = "USER_INTERRUPTED",
                    message = "User kept the proposed action private or cancelled it.",
                ),
                null,
            )
        }
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
        learning: LearningSession,
    ) {
        if (step >= 12) {
            finishWorkflow(learning, totalModelCalls, success = false)
            events.onFailure("I stopped after 12 actions to keep the task bounded and safe.")
            return
        }
        val service = SemanticAccessibilityService.current()
        if (service == null) {
            finishWorkflow(learning, totalModelCalls, success = false)
            events.onFailure("Enable Alfred Device Actions in Accessibility settings first.")
            return
        }
        val preferences = app.getSharedPreferences("alfred", Context.MODE_PRIVATE)
        val mode = runCatching {
            InferenceMode.valueOf(preferences.getString("inference_mode", InferenceMode.AUTO.name)!!)
        }.getOrDefault(InferenceMode.AUTO)
        val nativePlan = if (allowNative) native.resolve(goal) else null
        if (nativePlan != null && mode != InferenceMode.CLOUD_FIRST) {
            val sourceScreen = service.snapshot()?.screenId ?: "*"
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
                learning = learning,
                sourceScreen = sourceScreen,
                canonicalIntent = null,
                skillRef = nativeSkillRef(nativePlan.action),
            )
            return
        }
        val snapshot = service.snapshot()
        if (snapshot == null) {
            finishWorkflow(learning, totalModelCalls, success = false)
            events.onFailure("I cannot read the current screen yet. Open the target app and try again.")
            return
        }
        if (mode != InferenceMode.CLOUD_FIRST) {
            val workflowStep = workflows.nextPhoneStep(learning.workflow, snapshot.screenId)
            val workflowLocal = workflowStep?.let {
                localCache.resolveById(
                    it.patternId,
                    goal,
                    snapshot.screenId,
                    learning.workflow.parameters,
                )
            }
            if (workflowStep != null && workflowLocal != null) {
                if (workflowLocal.safety == "confirm" && !confirmed) {
                    pendingDeviceConfirmation = PendingDeviceConfirmation(
                        goal,
                        workflowLocal.actions,
                        totalModelCalls,
                        workflowLocal.id,
                        !workflowStep.terminal,
                        step,
                        learning,
                        snapshot.screenId,
                        workflowLocal.canonicalIntent,
                        "${WorkflowStore.PHONE_PREFIX}${workflowLocal.id}",
                        false,
                    )
                    showDeviceConfirmation(
                        goal,
                        "This learned workflow proposes to " +
                            "${describeAction(workflowLocal.actions.first())}. Continue?",
                        !events.assistantIsForeground(),
                    )
                    return
                }
                events.onActing("Following a learned workflow edge — zero network and model calls")
                executeActions(
                    service = service,
                    actions = workflowLocal.actions,
                    totalModelCalls = totalModelCalls,
                    attemptId = null,
                    client = null,
                    localArtifact = null,
                    localPatternId = workflowLocal.id,
                    goal = goal,
                    continueAfter = !workflowStep.terminal,
                    step = step,
                    learning = learning,
                    sourceScreen = snapshot.screenId,
                    canonicalIntent = workflowLocal.canonicalIntent,
                    skillRef = "${WorkflowStore.PHONE_PREFIX}${workflowLocal.id}",
                )
                return
            }
            val local = localCache.resolve(goal, snapshot.screenId)
            if (local != null) {
                if (local.safety == "confirm" && !confirmed) {
                    pendingDeviceConfirmation = PendingDeviceConfirmation(
                        goal,
                        local.actions,
                        totalModelCalls,
                        local.id,
                        !local.terminal,
                        step,
                        learning,
                        snapshot.screenId,
                        local.canonicalIntent,
                        "${WorkflowStore.PHONE_PREFIX}${local.id}",
                        false,
                    )
                    showDeviceConfirmation(
                        goal,
                        "Your remembered skill proposes to ${describeAction(local.actions.first())}. Continue?",
                        !events.assistantIsForeground(),
                    )
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
                    learning = learning,
                    sourceScreen = snapshot.screenId,
                    canonicalIntent = local.canonicalIntent,
                    skillRef = "${WorkflowStore.PHONE_PREFIX}${local.id}",
                )
                return
            }
            val downloaded = githubSkills.resolve(goal, snapshot.screenId)
            if (downloaded != null) {
                if (downloaded.safety == "confirm" && !confirmed) {
                    pendingDeviceConfirmation = PendingDeviceConfirmation(
                        goal,
                        downloaded.actions,
                        totalModelCalls,
                        null,
                        !downloaded.terminal,
                        step,
                        learning,
                        snapshot.screenId,
                        null,
                        "github:${downloaded.id}",
                        false,
                    )
                    showDeviceConfirmation(
                        goal,
                        "The reviewed GitHub skill “${downloaded.title}” proposes to " +
                            "${describeAction(downloaded.actions.first())}. Continue?",
                        !events.assistantIsForeground(),
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
                    learning = learning,
                    sourceScreen = snapshot.screenId,
                    canonicalIntent = null,
                    skillRef = "github:${downloaded.id}",
                )
                return
            }
        }
        if (mode == InferenceMode.LOCAL_ONLY) {
            finishWorkflow(learning, totalModelCalls, success = false)
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
            finishWorkflow(learning, totalModelCalls, success = false)
            events.onFailure(error.message ?: "Invalid backend URL")
            return
        }
        client.plan(goal, snapshot, confirmed, profileId) { result ->
            main.post {
                result.fold(
                    onSuccess = { plan ->
                        if (plan.confirmationRequired && !confirmed) {
                            pendingRemoteConfirmation = PendingRemoteConfirmation(
                                goal = goal,
                                plan = plan,
                                client = client,
                                totalModelCalls = totalModelCalls + plan.modelCalls,
                                step = step,
                                learning = learning,
                                sourceScreen = snapshot.screenId,
                                restorePreviousScreen = false,
                            )
                            showRemoteConfirmation(
                                goal,
                                plan.message ?: "This draft requires confirmation.",
                                !events.assistantIsForeground(),
                            )
                        } else if (plan.actions.isEmpty()) {
                            finishWorkflow(learning, totalModelCalls, success = false)
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
                                learning = learning,
                                sourceScreen = snapshot.screenId,
                                canonicalIntent = plan.canonicalIntent,
                                skillRef = "${plan.source}:${plan.canonicalIntent ?: "step"}",
                            )
                        }
                    },
                    onFailure = { error ->
                        finishWorkflow(learning, totalModelCalls, success = false)
                        events.onFailure("The planner is unavailable: ${error.message}")
                    },
                )
            }
        }
    }

    private fun showRemoteConfirmation(
        goal: String,
        message: String,
        bringAlfredForward: Boolean,
    ) {
        if (!bringAlfredForward) {
            events.onConfirmation(goal, message)
            return
        }
        if (!ConfirmationActivity.show(
                context = app,
                message = message,
                onConfirm = { main.post { submit(goal, confirmed = true) } },
                onCancel = { main.post { cancelPendingConfirmation() } },
            )
        ) {
            cancelPendingConfirmation()
            events.onFailure("I could not show Alfred's approval panel on the current screen.")
        }
    }

    private fun showDeviceConfirmation(
        goal: String,
        message: String,
        bringAlfredForward: Boolean,
    ) {
        if (!bringAlfredForward) {
            events.onConfirmation(goal, message)
            return
        }
        if (!ConfirmationActivity.show(
                context = app,
                message = message,
                onConfirm = { main.post { submit(goal, confirmed = true) } },
                onCancel = { main.post { cancelPendingConfirmation() } },
            )
        ) {
            pendingDeviceConfirmation?.let {
                finishWorkflow(it.learning, it.totalModelCalls, success = false)
            }
            pendingDeviceConfirmation = null
            events.onFailure("I could not show Alfred's approval panel on the current screen.")
        }
    }

    private fun executeConfirmedDevicePlan(pending: PendingDeviceConfirmation) {
        val service = SemanticAccessibilityService.current()
        if (service == null) {
            finishWorkflow(pending.learning, pending.totalModelCalls, success = false)
            events.onFailure("Enable Alfred Device Actions in Accessibility settings first.")
            return
        }
        val execute: () -> Unit = {
            events.onActing("Running the workflow action you approved")
            executeActions(
                service = service,
                actions = pending.actions,
                totalModelCalls = pending.totalModelCalls,
                attemptId = null,
                client = null,
                localArtifact = null,
                localPatternId = pending.localPatternId,
                goal = pending.goal,
                continueAfter = pending.continueAfter,
                step = pending.step,
                learning = pending.learning,
                sourceScreen = pending.sourceScreen,
                canonicalIntent = pending.canonicalIntent,
                skillRef = pending.skillRef,
            )
        }
        if (!pending.restorePreviousScreen) {
            execute()
            return
        }
        events.onActing("Returning to the exact screen for the action you approved…")
        service.execute(DeviceAction("back")) { outcome ->
            if (!outcome.success) {
                finishWorkflow(pending.learning, pending.totalModelCalls, success = false)
                events.onFailure("I could not restore the previous screen: ${outcome.message}")
                return@execute
            }
            main.postDelayed(execute, 250L)
        }
    }

    private fun executeConfirmedRemotePlan(pending: PendingRemoteConfirmation) {
        val service = SemanticAccessibilityService.current()
        if (service == null) {
            finishWorkflow(pending.learning, pending.totalModelCalls, success = false)
            pending.plan.attemptId?.let { attemptId ->
                pending.client.report(
                    attemptId,
                    ActionOutcome(false, false, "USER_INTERRUPTED", "Accessibility unavailable."),
                    null,
                )
            }
            events.onFailure("Enable Alfred Device Actions in Accessibility settings first.")
            return
        }
        val execute: () -> Unit = {
            events.onActing("Running the action you approved")
            executeActions(
                service = service,
                actions = pending.plan.actions,
                totalModelCalls = pending.totalModelCalls,
                attemptId = pending.plan.attemptId,
                client = pending.client,
                localArtifact = pending.plan.localArtifact,
                localPatternId = null,
                goal = pending.goal,
                continueAfter = !pending.plan.terminal,
                step = pending.step,
                learning = pending.learning,
                sourceScreen = pending.sourceScreen,
                canonicalIntent = pending.plan.canonicalIntent,
                skillRef = "${pending.plan.source}:${pending.plan.canonicalIntent ?: "step"}",
            )
        }
        if (!pending.restorePreviousScreen) {
            execute()
            return
        }
        events.onActing("Returning to the exact screen for the action you approved…")
        service.execute(DeviceAction("back")) { outcome ->
            if (!outcome.success) {
                finishWorkflow(pending.learning, pending.totalModelCalls, success = false)
                pending.plan.attemptId?.let { attemptId ->
                    pending.client.report(
                        attemptId,
                        ActionOutcome(false, false, "USER_INTERRUPTED", outcome.message),
                        null,
                    )
                }
                events.onFailure("I could not restore the previous screen: ${outcome.message}")
                return@execute
            }
            main.postDelayed(execute, 250L)
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
        learning: LearningSession,
        sourceScreen: String,
        canonicalIntent: String?,
        skillRef: String,
        index: Int = 0,
    ) {
        service.execute(actions[index]) { outcome ->
            if (!outcome.success) {
                localPatternId?.let { localCache.recordOutcome(it, false) }
                attemptId?.let {
                    client?.report(it, outcome, service.snapshot()?.screenId)
                }
                finishWorkflow(learning, totalModelCalls, success = false)
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
                    learning,
                    sourceScreen,
                    canonicalIntent,
                    skillRef,
                    index + 1,
                )
                return@execute
            }
            localPatternId?.let { localCache.recordOutcome(it, true) }
            var resolvedPatternId = localPatternId
            localArtifact?.let { resolvedPatternId = saveLearnedArtifact(it, learning) }
            val finish: (Boolean, Long?) -> Unit = { goalCompleted, learnedPatternId ->
                val finalPatternId = learnedPatternId ?: resolvedPatternId
                val destinationScreen = service.snapshot()?.screenId ?: sourceScreen
                workflows.recordSuccess(
                    session = learning.workflow,
                    sourceScreen = sourceScreen,
                    destinationScreen = destinationScreen,
                    canonicalIntent = canonicalIntent,
                    skillRef = finalPatternId?.let { "${WorkflowStore.PHONE_PREFIX}$it" } ?: skillRef,
                    actions = actions,
                    terminal = !continueAfter || goalCompleted,
                )
                if (continueAfter && !goalCompleted) {
                    route(
                        goal,
                        confirmed = false,
                        step = step + 1,
                        totalModelCalls = totalModelCalls,
                        allowNative = false,
                        learning = learning,
                    )
                } else {
                    finishWorkflow(learning, totalModelCalls, success = true)
                    val announceCompletion = {
                        events.onSuccess(
                            if (totalModelCalls == 0) {
                                "Done — no LLM call needed"
                            } else {
                                "Done — I'll reuse these verified steps next time"
                            },
                            totalModelCalls,
                        )
                        if (learning.publishableSkillIds.isNotEmpty()) {
                            events.onSkillLearned(
                                learning.publishableSkillIds.toLongArray(),
                                learning.names.toList(),
                            )
                        }
                    }
                    if (events.assistantIsForeground()) {
                        announceCompletion()
                    } else {
                        service.execute(DeviceAction("launch_app", packageName = app.packageName)) {
                            returned ->
                            main.post {
                                if (returned.success) {
                                    announceCompletion()
                                } else {
                                    events.onFailure(
                                        "The task finished, but Alfred could not return: ${returned.message}"
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (attemptId != null && client != null) {
                client.report(attemptId, outcome, service.snapshot()?.screenId) { result ->
                    val learned = result.getOrNull()
                    learned?.let {
                        resolvedPatternId = saveLearnedArtifact(it, learning)
                    }
                    main.post { finish(false, resolvedPatternId) }
                }
            } else {
                finish(false, resolvedPatternId)
            }
        }
    }

    private fun describeAction(action: DeviceAction): String = when (action.action) {
        "click" -> "click ${action.target?.removePrefix("label:")?.let { "“$it”" } ?: "a control"}"
        "set_text" -> "enter the requested value in " +
            (action.target?.removePrefix("label:")?.let { "“$it”" } ?: "a field")
        "launch_app" -> "open ${action.packageName ?: "the requested app"}"
        "open_deep_link" -> "open the requested link"
        "scroll" -> "scroll ${action.target?.removePrefix("label:") ?: "the current screen"}"
        "back" -> "go back"
        else -> "perform ${action.action.replace('_', ' ')}"
    }

    private fun saveLearnedArtifact(
        artifact: LocalCacheArtifact,
        learning: LearningSession,
    ): Long {
        val saved = localCache.save(artifact)
        if (artifact.goalTemplate != null && learning.goalTemplate == null) {
            learning.goalTemplate = artifact.goalTemplate
            learning.goalFingerprint = null
            learning.parameterTypes = artifact.parameterTypes
        } else if (learning.goalTemplate == null && learning.goalFingerprint == null) {
            learning.goalTemplate = artifact.goalTemplate
            learning.goalFingerprint = artifact.goalFingerprint
            learning.parameterTypes = artifact.parameterTypes
        }
        if (saved.created && localCache.contributionProposal(saved.id) != null) {
            learning.publishableSkillIds += saved.id
            learning.names += artifact.canonicalIntent
        }
        return saved.id
    }

    private fun finishWorkflow(
        learning: LearningSession,
        modelCalls: Int,
        success: Boolean,
    ) {
        workflows.finish(
            session = learning.workflow,
            goalTemplate = learning.goalTemplate,
            goalFingerprint = learning.goalFingerprint,
            parameterTypesJson = JSONObject(learning.parameterTypes.toSortedMap()).toString(),
            modelCalls = modelCalls,
            success = success,
        )
    }

    private fun nativeSkillRef(action: DeviceAction): String = listOfNotNull(
        "native",
        action.action,
        action.packageName,
        action.uri?.substringBefore(':'),
    ).joinToString(":")

    companion object {
        private const val GMAIL_PACKAGE = "com.google.android.gm"
    }
}
