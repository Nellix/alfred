package ai.alfred.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessagingDraftIntentTest {
    @Test
    fun parsesSendMessageWithQuotedBody() {
        val request = MessagingDraftIntent.parse(
            "Send message to mamma \"Buona notte, ci sentiamo domani\" via whatsapp"
        )

        assertEquals("mamma", request?.recipient)
        assertEquals("Buona notte, ci sentiamo domani", request?.message)
        assertEquals("whatsapp", request?.channel)
    }

    @Test
    fun parsesWriteWithSmartQuotes() {
        val request = MessagingDraftIntent.parse(
            "Write to mamma “buona notte, ci sentiamo domani” via WhatsApp"
        )

        assertEquals("mamma", request?.recipient)
        assertEquals("buona notte, ci sentiamo domani", request?.message)
        assertEquals("whatsapp", request?.channel)
    }

    @Test
    fun keepsLegacyColonSyntax() {
        val request = MessagingDraftIntent.parse(
            "Send a message to mamma via WhatsApp: buona notte"
        )

        assertEquals("mamma", request?.recipient)
        assertEquals("buona notte", request?.message)
    }

    @Test
    fun rejectsRequestsWithoutDraftText() {
        assertNull(MessagingDraftIntent.parse("Send message to mamma via WhatsApp"))
    }
}
