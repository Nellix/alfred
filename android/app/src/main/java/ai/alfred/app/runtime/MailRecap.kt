package ai.alfred.app.runtime

enum class MailRecapScope { INBOX_ROWS, MESSAGE_BODIES }

data class MailRecapRequest(
    val requested: Int,
    val limit: Int,
    val scope: MailRecapScope,
) {
    val readsMessageBodies: Boolean get() = scope == MailRecapScope.MESSAGE_BODIES
}

data class BuiltInSkillDefinition(
    val id: String,
    val canonicalIntent: String,
    val title: String,
    val invocation: String,
    val parameter: String,
    val reads: String,
    val doesNotRead: String,
    val deviceEffect: String,
    val privacy: String,
)

object BuiltInSkills {
    val gmailInboxBriefing = BuiltInSkillDefinition(
        id = "gmail.inbox_briefing.v1",
        canonicalIntent = "email.inbox.recap",
        title = "Gmail inbox-row briefing",
        invocation = "Check the latest <N> emails in Gmail and recap the inbox list",
        parameter = "N · integer from 1 to 10",
        reads = "Only labels already displayed in each inbox row: typically sender, subject, date, status, and Gmail's short snippet.",
        doesNotRead = "Does NOT open any email. Does NOT read message body content or attachments.",
        deviceEffect = "Opens Gmail and may scroll the inbox list. It does not click a conversation or change its read state.",
        privacy = "On device · zero LLM calls · ephemeral row text · never logged, learned, or synchronized",
    )

    val gmailBodyBriefing = BuiltInSkillDefinition(
        id = "gmail.message_body_briefing.v1",
        canonicalIntent = "email.message_body.recap",
        title = "Gmail message-body summary",
        invocation = "Open the latest <N> emails in Gmail, read their body content, and summarize them",
        parameter = "N · integer from 1 to 5",
        reads = "Opens each requested message, reads up to 1,200 visible body characters, and creates a short on-device extractive summary with key sentences and action cues. If Gmail omits the body from accessibility, Alfred uses bundled on-device text recognition on the visible body region.",
        doesNotRead = "Does NOT display or retain screenshots or the raw body. Does NOT read attachments, hidden/remote content, or content outside the visible message body. No LLM receives the body.",
        deviceEffect = "Opening a message can cause Gmail to mark it as read. Alfred returns to the inbox after each message and never changes star, archive, delete, reply, or send controls.",
        privacy = "Explicit confirmation required · on-device accessibility/OCR · ephemeral pixels and body text · never sent to planner, logged, learned, or synchronized",
    )

    val all = listOf(gmailInboxBriefing, gmailBodyBriefing)
}

data class MailRecapResult(
    val requested: Int,
    val conversations: List<String>,
) {
    val displayText: String
        get() {
            val heading = if (conversations.size == requested) {
                "Here are your latest $requested inbox items:"
            } else {
                "I could read ${conversations.size} of the latest $requested inbox items currently exposed by Gmail:"
            }
            return buildString {
                append(heading)
                append(" No message was opened and no body content was read.")
                append("\n\n")
                conversations.forEachIndexed { index, conversation ->
                    append(index + 1)
                    append(". ")
                    append(conversation)
                    if (index != conversations.lastIndex) append("\n\n")
                }
            }
        }
}

data class MailMessageSummary(
    val inboxRow: String,
    val bodySummary: String?,
)

data class MailBodyRecapResult(
    val requested: Int,
    val attempted: Int,
    val messages: List<MailMessageSummary>,
) {
    val displayText: String
        get() {
            val summarized = messages.count { !it.bodySummary.isNullOrBlank() }
            return buildString {
                if (requested > attempted) {
                    append("You requested $requested messages. This privacy-bounded skill opens at most $attempted per run.\n\n")
                }
                append("I opened ${messages.size} message${if (messages.size == 1) "" else "s"} and created $summarized on-device body summar${if (summarized == 1) "y" else "ies"}. ")
                append("Raw body text is not displayed or retained.\n\n")
                messages.forEachIndexed { index, message ->
                    append(index + 1)
                    append(". Inbox row: ")
                    append(message.inboxRow)
                    append("\nBody summary:\n")
                    append(message.bodySummary ?: "[Gmail did not expose enough readable body text to summarize this message.]")
                    if (index != messages.lastIndex) append("\n\n")
                }
            }
        }
}

object MailBodySummarizer {
    fun summarize(body: String): String? {
        val candidates = body
            .lineSequence()
            .flatMap { line -> SENTENCE_BOUNDARY.split(line.trim()).asSequence() }
            .map(::cleanSentence)
            .filter { it.length >= MIN_SENTENCE_CHARS }
            .filterNot { QUOTED_OR_HEADER.matches(it) }
            .distinctBy { it.lowercase() }
            .take(MAX_CANDIDATES)
            .toList()
        if (candidates.isEmpty()) return fallback(body)

        val selected = candidates
            .mapIndexed { index, sentence -> ScoredSentence(index, sentence, score(sentence, index)) }
            .sortedWith(compareByDescending<ScoredSentence> { it.score }.thenBy { it.index })
            .take(MAX_SUMMARY_SENTENCES)
            .sortedBy { it.index }
            .map { shorten(it.text) }
        return selected.takeIf { it.isNotEmpty() }?.joinToString("\n") { "• $it" }
    }

    private fun cleanSentence(value: String): String = value
        .replace(URL, "")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '•', '>')

    private fun score(sentence: String, index: Int): Int {
        var value = (POSITION_WEIGHT - index).coerceAtLeast(0)
        if (ACTION_CUE.containsMatchIn(sentence)) value += ACTION_WEIGHT
        if (DATE_OR_TIME.containsMatchIn(sentence)) value += DATE_WEIGHT
        if (sentence.length in IDEAL_SENTENCE_LENGTH) value += LENGTH_WEIGHT
        if (LOW_VALUE.containsMatchIn(sentence)) value -= LOW_VALUE_PENALTY
        return value
    }

    private fun shorten(value: String): String {
        if (value.length <= MAX_SUMMARY_SENTENCE_CHARS) return value
        val prefix = value.take(MAX_SUMMARY_SENTENCE_CHARS)
        val boundary = prefix.lastIndexOf(' ').coerceAtLeast(MAX_SUMMARY_SENTENCE_CHARS - 40)
        return prefix.take(boundary).trimEnd() + "…"
    }

    private fun fallback(body: String): String? {
        val clean = cleanSentence(body)
        if (clean.length < MIN_SENTENCE_CHARS) return null
        return "• ${shorten(clean)}"
    }

    private data class ScoredSentence(val index: Int, val text: String, val score: Int)

    private val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+")
    private val URL = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val QUOTED_OR_HEADER = Regex(
        "^(?:on .+ wrote:|from:|sent:|to:|subject:|cc:|bcc:).*$",
        RegexOption.IGNORE_CASE,
    )
    private val ACTION_CUE = Regex(
        "\\b(?:action|required|please|need|must|due|deadline|confirm|approve|reply|respond|complete|submit|pay|meeting|appointment|schedule|important|urgent)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val DATE_OR_TIME = Regex(
        "\\b(?:today|tomorrow|tonight|monday|tuesday|wednesday|thursday|friday|saturday|sunday|\\d{1,2}[:/]\\d{1,2}|\\d{1,2}:\\d{2})\\b",
        RegexOption.IGNORE_CASE,
    )
    private val LOW_VALUE = Regex(
        "^(?:hi|hello|dear|thanks|thank you|regards|best|unsubscribe)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val IDEAL_SENTENCE_LENGTH = 35..220
    private const val MIN_SENTENCE_CHARS = 18
    private const val MAX_CANDIDATES = 24
    private const val MAX_SUMMARY_SENTENCES = 3
    private const val MAX_SUMMARY_SENTENCE_CHARS = 240
    private const val POSITION_WEIGHT = 24
    private const val ACTION_WEIGHT = 20
    private const val DATE_WEIGHT = 8
    private const val LENGTH_WEIGHT = 6
    private const val LOW_VALUE_PENALTY = 12
}

object MailRecapIntent {
    private val count = Regex(
        "\\b(?:last|latest|recent)\\s+(\\d{1,2})\\s+(?:e-?mails?|mails?|messages?)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val gmail = Regex("\\b(?:gmail|inbox)\\b", RegexOption.IGNORE_CASE)
    private val briefing = Regex(
        "\\b(?:check|read|review|recap|summari[sz]e|brief)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val bodyAccess = Regex(
        "\\b(?:body|bodies|body content|message content|email content|full content|full text)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val excludesBodyAccess = Regex(
        "\\b(?:without|do not|don't|never)\\b.{0,32}\\b(?:body|bodies|message content|email content)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun parse(goal: String): MailRecapRequest? {
        if (!gmail.containsMatchIn(goal) || !briefing.containsMatchIn(goal)) return null
        val requested = count.find(goal)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val scope = if (bodyAccess.containsMatchIn(goal) && !excludesBodyAccess.containsMatchIn(goal)) {
            MailRecapScope.MESSAGE_BODIES
        } else {
            MailRecapScope.INBOX_ROWS
        }
        val maximum = if (scope == MailRecapScope.MESSAGE_BODIES) MAX_BODY_ITEMS else MAX_ROW_ITEMS
        return MailRecapRequest(
            requested = requested.coerceAtLeast(1),
            limit = requested.coerceIn(1, maximum),
            scope = scope,
        )
    }

    private const val MAX_ROW_ITEMS = 10
    private const val MAX_BODY_ITEMS = 5
}
