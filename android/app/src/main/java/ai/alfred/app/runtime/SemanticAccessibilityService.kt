package ai.alfred.app.runtime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SemanticAccessibilityService : AccessibilityService() {
    private var lastWindowClass = "unknown"
    private val revision = AtomicInteger(1)
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val textRecognizerDelegate = lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val textRecognizer by textRecognizerDelegate

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (textRecognizerDelegate.isInitialized()) textRecognizer.close()
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.className?.toString()?.let { lastWindowClass = it }
        revision.incrementAndGet()
    }

    override fun onInterrupt() = Unit

    fun snapshot(): SemanticSnapshot? {
        val root = rootInActiveWindow ?: return null
        val packageName = root.packageName?.toString().orEmpty()
        val windowId = root.windowId.toString()
        val nodes = mutableListOf<SemanticNode>()
        walk(root, null, "0", packageName, windowId, nodes)
        return SemanticSnapshot(
            packageName = packageName,
            screenId = "$packageName:$lastWindowClass",
            windowId = windowId,
            revision = revision.get(),
            nodes = nodes,
            capturedAtSeconds = System.currentTimeMillis() / 1000.0,
        )
    }

    fun execute(action: DeviceAction, callback: (ActionOutcome) -> Unit) {
        val before = snapshot()
        val accepted = try {
            perform(action)
        } catch (error: SecurityException) {
            callback(ActionOutcome(false, false, "PERMISSION_DENIED", error.message ?: "Permission denied"))
            return
        } catch (error: IllegalArgumentException) {
            callback(ActionOutcome(false, false, "ACTION_NOT_SUPPORTED", error.message ?: "Invalid action"))
            return
        } catch (error: Exception) {
            callback(ActionOutcome(false, false, "ACTION_NOT_SUPPORTED", error.message ?: "Action failed"))
            return
        }
        if (!accepted) {
            callback(ActionOutcome(false, false, "TARGET_NOT_FOUND", "The current screen does not expose that semantic control."))
            return
        }
        val delay = if (action.action in setOf("launch_app", "open_deep_link")) 800L else 400L
        Handler(Looper.getMainLooper()).postDelayed({
            val after = snapshot()
            val changed = action.action in setOf("wait", "stop") || (
                before != null && after != null &&
                    (before.packageName != after.packageName || before.screenId != after.screenId || before.digest != after.digest)
                )
            callback(
                ActionOutcome(
                    success = changed,
                    verified = changed,
                    code = if (changed) "success" else "VERIFICATION_FAILED",
                    message = if (changed) "Action verified on device" else "No semantic screen change was observed",
                )
            )
        }, delay)
    }

    fun collectGmailRecap(limit: Int, callback: (Result<MailRecapResult>) -> Unit) {
        val requested = limit.coerceIn(1, 10)
        val conversations = linkedSetOf<String>()
        val handler = Handler(Looper.getMainLooper())

        fun collectPage(page: Int, recoveryAttempt: Int = 0) {
            val root = rootInActiveWindow
            if (root?.packageName?.toString() != GMAIL_PACKAGE) {
                callback(Result.failure(IllegalStateException("Gmail inbox is not visible.")))
                return
            }
            val list = findConversationList(root)
            if (list == null) {
                if (recoveryAttempt < MAX_INBOX_RECOVERY_ATTEMPTS) {
                    val canRetry = !isGmailConversationView(root) ||
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    if (canRetry) {
                        handler.postDelayed(
                            { collectPage(page, recoveryAttempt + 1) },
                            MAIL_RETURN_DELAY_MS,
                        )
                        return
                    }
                }
                callback(
                    Result.failure(
                        IllegalStateException(
                            "Gmail did not expose its inbox rows. Make sure the Primary inbox is visible."
                        )
                    )
                )
                return
            }
            visibleConversationRows(list).forEach { conversations += it }
            if (conversations.size >= requested || page >= MAX_MAIL_PAGES) {
                val result = conversations.take(requested)
                if (result.isEmpty()) {
                    callback(Result.failure(IllegalStateException("No readable inbox rows were found.")))
                } else {
                    callback(Result.success(MailRecapResult(requested, result)))
                }
                return
            }
            if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                callback(Result.success(MailRecapResult(requested, conversations.toList())))
                return
            }
            handler.postDelayed({ collectPage(page + 1) }, MAIL_SCROLL_DELAY_MS)
        }

        handler.postDelayed({ collectPage(0) }, MAIL_LOAD_DELAY_MS)
    }

    fun collectGmailBodyRecap(
        requested: Int,
        limit: Int,
        callback: (Result<MailBodyRecapResult>) -> Unit,
    ) {
        val target = limit.coerceIn(1, MAX_BODY_MAIL_ITEMS)
        val visitedRows = linkedSetOf<String>()
        val messages = mutableListOf<MailMessageSummary>()
        val handler = Handler(Looper.getMainLooper())
        var scrollCount = 0
        var inboxRecoveryAttempts = 0

        fun finish() {
            if (messages.isEmpty()) {
                callback(Result.failure(IllegalStateException("No Gmail message body could be opened.")))
            } else {
                callback(Result.success(MailBodyRecapResult(requested, target, messages.toList())))
            }
        }

        fun collectNext() {
            if (messages.size >= target) {
                finish()
                return
            }
            val root = rootInActiveWindow
            if (root?.packageName?.toString() != GMAIL_PACKAGE) {
                callback(Result.failure(IllegalStateException("Gmail inbox is not visible.")))
                return
            }
            val list = findConversationList(root)
            if (list == null) {
                if (inboxRecoveryAttempts < MAX_INBOX_RECOVERY_ATTEMPTS) {
                    val canRetry = !isGmailConversationView(root) ||
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    if (canRetry) {
                        inboxRecoveryAttempts += 1
                        handler.postDelayed(::collectNext, MAIL_RETURN_DELAY_MS)
                        return
                    }
                }
                callback(
                    Result.failure(
                        IllegalStateException(
                            "Gmail did not return to its inbox after reading a message."
                        )
                    )
                )
                return
            }
            inboxRecoveryAttempts = 0
            val next = visibleConversationEntries(list).firstOrNull { it.summary !in visitedRows }
            if (next == null) {
                if (scrollCount >= MAX_MAIL_PAGES || !list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    finish()
                    return
                }
                scrollCount += 1
                handler.postDelayed(::collectNext, MAIL_SCROLL_DELAY_MS)
                return
            }
            visitedRows += next.summary
            if (!next.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                messages += MailMessageSummary(next.summary, null)
                handler.postDelayed(::collectNext, MAIL_SCROLL_DELAY_MS)
                return
            }
            handler.postDelayed({
                val messageRoot = rootInActiveWindow
                summarizeVisibleGmailBody(messageRoot) { bodySummary ->
                    messages += MailMessageSummary(
                        inboxRow = next.summary,
                        bodySummary = bodySummary,
                    )
                    if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
                        callback(Result.failure(IllegalStateException("Gmail did not allow Alfred to return to the inbox.")))
                        return@summarizeVisibleGmailBody
                    }
                    handler.postDelayed(::collectNext, MAIL_RETURN_DELAY_MS)
                }
            }, MAIL_MESSAGE_LOAD_DELAY_MS)
        }

        handler.postDelayed(::collectNext, MAIL_LOAD_DELAY_MS)
    }

    private fun summarizeVisibleGmailBody(
        root: AccessibilityNodeInfo?,
        callback: (String?) -> Unit,
    ) {
        if (root?.packageName?.toString() != GMAIL_PACKAGE) {
            callback(null)
            return
        }
        val accessibilitySummary = extractGmailMessageBody(root)?.let(MailBodySummarizer::summarize)
        if (accessibilitySummary != null) {
            callback(accessibilitySummary)
            return
        }
        recognizeVisibleGmailBody(root) { recognizedText ->
            callback(recognizedText?.let(MailBodySummarizer::summarize))
        }
    }

    private fun recognizeVisibleGmailBody(
        root: AccessibilityNodeInfo,
        callback: (String?) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }
        val bodyBounds = gmailVisibleBodyBounds(root)
        if (bodyBounds == null) {
            callback(null)
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer,
                        screenshot.colorSpace,
                    )
                    screenshot.hardwareBuffer.close()
                    val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBitmap?.recycle()
                    if (softwareBitmap == null) {
                        postRecognizedText(callback, null)
                        return
                    }
                    val left = bodyBounds.left.coerceIn(0, softwareBitmap.width - 1)
                    val top = bodyBounds.top.coerceIn(0, softwareBitmap.height - 1)
                    val right = bodyBounds.right.coerceIn(left + 1, softwareBitmap.width)
                    val bottom = bodyBounds.bottom.coerceIn(top + 1, softwareBitmap.height)
                    val bodyBitmap = Bitmap.createBitmap(
                        softwareBitmap,
                        left,
                        top,
                        right - left,
                        bottom - top,
                    )
                    softwareBitmap.recycle()
                    textRecognizer.process(InputImage.fromBitmap(bodyBitmap, 0))
                        .addOnSuccessListener(screenshotExecutor) { result ->
                            postRecognizedText(callback, normalizeRecognizedBody(result.text))
                        }
                        .addOnFailureListener(screenshotExecutor) {
                            postRecognizedText(callback, null)
                        }
                        .addOnCompleteListener(screenshotExecutor) {
                            bodyBitmap.recycle()
                        }
                }

                override fun onFailure(errorCode: Int) {
                    postRecognizedText(callback, null)
                }
            },
        )
    }

    private fun gmailVisibleBodyBounds(root: AccessibilityNodeInfo): Rect? {
        var conversation: Rect? = null
        var header: Rect? = null
        var footer: Rect? = null

        fun visit(node: AccessibilityNodeInfo) {
            val id = node.viewIdResourceName.orEmpty().substringAfterLast('/').lowercase()
            val bounds = Rect().also(node::getBoundsInScreen)
            when (id) {
                "conversation_container" -> conversation = bounds
                "upper_header" -> header = bounds
                "conversation_footer" -> footer = bounds
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        val container = conversation ?: return null
        val top = header?.bottom ?: return null
        val bottom = footer?.top ?: container.bottom
        if (bottom - top < MIN_VISUAL_BODY_HEIGHT_PX) return null
        return Rect(container.left, top, container.right, bottom)
    }

    private fun normalizeRecognizedBody(value: String): String? = value
        .lineSequence()
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .take(MAX_MAIL_BODY_CHARS)
        .trim()
        .ifEmpty { null }

    private fun postRecognizedText(callback: (String?) -> Unit, value: String?) {
        Handler(Looper.getMainLooper()).post { callback(value) }
    }

    private fun perform(action: DeviceAction): Boolean = when (action.action) {
        "launch_app" -> {
            val packageName = requireNotNull(action.packageName) { "Missing package" }
            val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return false
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            true
        }
        "open_deep_link" -> {
            val uri = requireNotNull(action.uri) { "Missing URI" }
            val scheme = Uri.parse(uri).scheme?.lowercase()
            require(scheme == "https" || scheme == "geo" || scheme == "mailto") {
                "Unsafe deep-link scheme"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            action.packageName?.let(intent::setPackage)
            startActivity(intent)
            true
        }
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "wait" -> true
        "stop" -> true
        "click", "set_text", "scroll" -> {
            val target = requireNotNull(action.target) { "Missing semantic target" }
            val node = findNode(target) ?: return false
            when (action.action) {
                "click" -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                "set_text" -> node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            action.value.orEmpty(),
                        )
                    },
                )
                else -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
        }
        else -> false
    }

    private fun findNode(target: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, path: String) {
            val label = node.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
                ?: node.contentDescription?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
                ?: node.viewIdResourceName?.substringAfterLast('/')
            val handle = stableHandle(node, path)
            val matches = if (target.startsWith("label:")) {
                label?.equals(target.removePrefix("label:").trim(), ignoreCase = true) == true
            } else {
                handle == target
            }
            if (matches) {
                found += node
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, "$path.$index") }
        }
        visit(root, "0")
        return found.singleOrNull()
    }

    private fun findConversationList(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        fun visit(node: AccessibilityNodeInfo) {
            val id = node.viewIdResourceName.orEmpty().lowercase()
            val className = node.className?.toString().orEmpty().lowercase()
            val score =
                (if (node.isScrollable) 5 else 0) +
                    (if ("conversation" in id || "thread" in id) 8 else 0) +
                    (if ("recycler" in id || "recyclerview" in className) 3 else 0) +
                    node.childCount.coerceAtMost(6)
            if (node.isVisibleToUser && node.childCount > 1 && score > bestScore) {
                best = node
                bestScore = score
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        return best?.takeIf { bestScore >= 8 }
    }

    private fun isGmailConversationView(root: AccessibilityNodeInfo): Boolean {
        fun containsConversation(node: AccessibilityNodeInfo): Boolean {
            val id = node.viewIdResourceName.orEmpty().substringAfterLast('/').lowercase()
            if (id == "conversation_view_container" && node.childCount > 0) return true
            for (index in 0 until node.childCount) {
                if (node.getChild(index)?.let(::containsConversation) == true) return true
            }
            return false
        }
        return containsConversation(root)
    }

    private data class GmailConversationRow(
        val summary: String,
        val node: AccessibilityNodeInfo,
    )

    private fun visibleConversationRows(list: AccessibilityNodeInfo): List<String> =
        visibleConversationEntries(list).map { it.summary }

    private fun visibleConversationEntries(list: AccessibilityNodeInfo): List<GmailConversationRow> {
        val rows = mutableListOf<GmailConversationRow>()
        for (index in 0 until list.childCount) {
            val child = list.getChild(index) ?: continue
            if (!child.isVisibleToUser) continue
            val labels = linkedSetOf<String>()
            collectMailLabels(child, labels)
            val summary = labels
                .filterNot(::isMailChromeLabel)
                .joinToString(" — ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_MAIL_ROW_CHARS)
            if (summary.length >= MIN_MAIL_ROW_CHARS && child.isClickable) {
                rows += GmailConversationRow(summary, child)
            }
        }
        return rows.distinctBy { it.summary }
    }

    private fun extractGmailMessageBody(root: AccessibilityNodeInfo): String? {
        val labels = linkedSetOf<String>()
        val nativeBodyLabels = linkedSetOf<String>()

        fun collectContent(node: AccessibilityNodeInfo) {
            sequenceOf(node.text, node.contentDescription)
                .mapNotNull { it?.toString()?.replace(Regex("\\s+"), " ")?.trim() }
                .filter { it.length >= MIN_MAIL_BODY_LABEL_CHARS }
                .filterNot(::isMailBodyChromeLabel)
                .forEach(labels::add)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::collectContent)
            }
        }

        fun visit(node: AccessibilityNodeInfo) {
            val id = node.viewIdResourceName.orEmpty().substringAfterLast('/').lowercase()
            if (isGmailMessageContentRoot(id)) collectContent(node)
            if (GMAIL_NATIVE_BODY_IDS.any(id::contains)) {
                sequenceOf(node.text, node.contentDescription)
                    .mapNotNull { it?.toString()?.replace(Regex("\\s+"), " ")?.trim() }
                    .filter { it.length >= MIN_MAIL_BODY_LABEL_CHARS }
                    .filterNot(::isMailBodyChromeLabel)
                    .forEach(nativeBodyLabels::add)
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }

        visit(root)
        if (labels.isEmpty()) labels += nativeBodyLabels
        val body = labels.joinToString("\n").trim()
        if (body.isEmpty()) return null
        return if (body.length <= MAX_MAIL_BODY_CHARS) {
            body
        } else {
            body.take(MAX_MAIL_BODY_CHARS).trimEnd() + "…"
        }
    }

    private fun isGmailMessageContentRoot(id: String): Boolean =
        (id.endsWith("-content") && ("msg" in id || "message" in id)) ||
            id.endsWith("message_content") || id.endsWith("mail_message_body")

    private fun isMailBodyChromeLabel(value: String): Boolean {
        val normalized = value.trim().lowercase()
        return isMailChromeLabel(value) || normalized in MAIL_BODY_CHROME_LABELS
    }

    private fun collectMailLabels(node: AccessibilityNodeInfo, labels: MutableSet<String>) {
        sequenceOf(node.text, node.contentDescription)
            .mapNotNull { it?.toString()?.replace(Regex("\\s+"), " ")?.trim() }
            .filter { it.isNotBlank() }
            .forEach(labels::add)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectMailLabels(it, labels) }
        }
    }

    private fun isMailChromeLabel(value: String): Boolean {
        val normalized = value.trim().lowercase()
        return normalized in setOf(
            "starred",
            "not starred",
            "important",
            "not important",
            "select",
            "attachment",
        )
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        parentHandle: String?,
        path: String,
        packageName: String,
        windowId: String,
        output: MutableList<SemanticNode>,
    ) {
        val handle = stableHandle(node, path)
        val resourceId = node.viewIdResourceName
        val secret = node.isPassword
        val privateContentPackage = packageName in PRIVATE_CONTENT_PACKAGES
        val personal = !secret && (
            privateContentPackage || node.isEditable || resourceId?.contains(
                Regex(
                    "message|body|email|phone|contact|recipient|token|otp|address",
                    RegexOption.IGNORE_CASE,
                )
            ) == true
        )
        val rawText = node.text?.toString()?.takeIf { it.isNotBlank() }
        val rawDescription = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val text = if (secret || personal) null else rawText
        val description = if (secret || personal) null else rawDescription
        val bounds = Rect().also(node::getBoundsInScreen)
        val actions = buildList {
            if (node.isClickable) add("click")
            if (node.isEditable) add("set_text")
            if (node.isScrollable) add("scroll")
        }
        val className = node.className?.toString().orEmpty()
        val role = className.substringAfterLast('.').ifBlank { "view" }.lowercase()
        val structural = hash("$packageName|${node.viewIdResourceName}|$className|$path")
        val semantic = hash("$structural|$text|$description|${node.isChecked}|${node.isSelected}")
        output += SemanticNode(
            handle = handle,
            parentHandle = parentHandle,
            packageName = packageName,
            windowId = windowId,
            resourceId = resourceId,
            role = role,
            className = className,
            text = text,
            contentDescription = description,
            clickable = node.isClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            visible = node.isVisibleToUser,
            selected = node.isSelected,
            checked = if (node.isCheckable) node.isChecked else null,
            bounds = if (bounds.isEmpty) null else Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            actions = actions,
            structuralSignature = structural,
            semanticSignature = semantic,
            sensitivity = when {
                secret -> "secret"
                personal -> "personal"
                else -> "public"
            },
        )
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                walk(child, handle, "$path.$index", packageName, windowId, output)
            }
        }
    }

    private fun stableHandle(node: AccessibilityNodeInfo, path: String): String = "a" + hash(
        "${node.packageName}|${node.windowId}|${node.viewIdResourceName}|${node.className}|$path"
    ).take(10)

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val GMAIL_PACKAGE = "com.google.android.gm"
        private const val MAX_MAIL_PAGES = 4
        private const val MAIL_LOAD_DELAY_MS = 1_500L
        private const val MAIL_SCROLL_DELAY_MS = 650L
        private const val MAIL_MESSAGE_LOAD_DELAY_MS = 2_500L
        private const val MAIL_RETURN_DELAY_MS = 900L
        private const val MAX_INBOX_RECOVERY_ATTEMPTS = 6
        private const val MAX_MAIL_ROW_CHARS = 280
        private const val MIN_MAIL_ROW_CHARS = 12
        private const val MAX_BODY_MAIL_ITEMS = 5
        private const val MAX_MAIL_BODY_CHARS = 1_200
        private const val MIN_MAIL_BODY_LABEL_CHARS = 2
        private const val MIN_VISUAL_BODY_HEIGHT_PX = 160
        private val GMAIL_NATIVE_BODY_IDS = setOf(
            "gmail_card_title",
            "gmail_card_subtitle",
            "purchase_text",
            "gmail_card_data_cell_attribute",
            "gmail_card_data_cell_value",
        )
        private val MAIL_BODY_CHROME_LABELS = setOf(
            "archive",
            "delete",
            "mark as unread",
            "more options",
            "navigate up",
            "reply",
            "reply all",
            "forward",
            "add reaction",
            "show details",
            "hide details",
            "related emails",
        )
        private val PRIVATE_CONTENT_PACKAGES = setOf(
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "com.microsoft.outlooklite",
        )
        @Volatile private var instance: SemanticAccessibilityService? = null

        fun current(): SemanticAccessibilityService? = instance

        fun isEnabled(context: android.content.Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return enabled.contains(context.packageName, ignoreCase = true)
        }
    }
}
