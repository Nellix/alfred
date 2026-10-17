package ai.alfred.app.runtime

/**
 * A private, ephemeral request to prepare (but never send) a messaging draft.
 * Recipient and message values must not be persisted as learned-skill material.
 */
data class MessagingDraftIntent(
    val recipient: String,
    val message: String,
    val channel: String,
) {
    val packageName: String
        get() = if (channel == "whatsapp") "com.whatsapp" else "org.telegram.messenger"

    fun plannerGoal(): String =
        "In ${channel.replaceFirstChar { it.uppercase() }}, select the contact whose visible " +
            "label exactly matches \"$recipient\" for an already prepared draft. Never press Send. " +
            "Stop once that contact's conversation is open and the existing draft is visible."

    companion object {
        private val QUOTED_FIRST = Regex(
            "^(?:send(?: a)? message|write) to (.+?)\\s+[\\\"“](.+)[\\\"”]\\s+(?:via|on)\\s+(whatsapp|telegram)$",
            RegexOption.IGNORE_CASE,
        )
        private val CHANNEL_FIRST = Regex(
            "^(?:send|write) (?:a )?message to (.+?) (?:via|on) (whatsapp|telegram):\\s*(.+)$",
            RegexOption.IGNORE_CASE,
        )

        fun parse(goal: String): MessagingDraftIntent? {
            val text = goal.trim()
            QUOTED_FIRST.matchEntire(text)?.let { match ->
                return create(
                    recipient = match.groupValues[1],
                    message = match.groupValues[2],
                    channel = match.groupValues[3],
                )
            }
            CHANNEL_FIRST.matchEntire(text)?.let { match ->
                return create(
                    recipient = match.groupValues[1],
                    message = match.groupValues[3],
                    channel = match.groupValues[2],
                )
            }
            return null
        }

        private fun create(
            recipient: String,
            message: String,
            channel: String,
        ): MessagingDraftIntent? {
            val cleanRecipient = recipient.trim().trim('"', '“', '”')
            val cleanMessage = message.trim()
            if (cleanRecipient.isBlank() || cleanMessage.isBlank()) return null
            return MessagingDraftIntent(
                recipient = cleanRecipient,
                message = cleanMessage,
                channel = channel.lowercase(),
            )
        }
    }
}
