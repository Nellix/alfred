package ai.alfred.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailBodySummarizerTest {
    @Test
    fun `creates a short summary and prioritizes an action with deadline`() {
        val body = """
            Hello team.
            The weekly project review covered the completed design and implementation work.
            Please submit the final accessibility report by Friday at 15:00.
            The remaining background notes are available in the internal project folder.
            Thank you for your continued help with the release.
        """.trimIndent()

        val summary = requireNotNull(MailBodySummarizer.summarize(body))
        val bullets = summary.lines().filter { it.startsWith("• ") }

        assertTrue(summary.contains("submit the final accessibility report", ignoreCase = true))
        assertTrue(summary.contains("Friday at 15:00", ignoreCase = true))
        assertTrue(bullets.size in 1..3)
        assertTrue(summary.length < body.length)
    }

    @Test
    fun `removes URLs and quoted mail headers`() {
        val body = """
            The release candidate is ready for validation. Review the checklist at https://example.invalid/private-token.
            Please confirm approval tomorrow.
            From: Previous Sender
            Subject: Earlier thread
        """.trimIndent()

        val summary = requireNotNull(MailBodySummarizer.summarize(body))

        assertFalse(summary.contains("https://"))
        assertFalse(summary.contains("Previous Sender"))
        assertTrue(summary.contains("confirm approval", ignoreCase = true))
    }

    @Test
    fun `returns null when Gmail exposes too little meaningful body text`() {
        assertNull(MailBodySummarizer.summarize("Hi"))
        assertNull(MailBodySummarizer.summarize("   "))
    }

    @Test
    fun `result renders summaries and never a raw body section`() {
        val result = MailBodyRecapResult(
            requested = 1,
            attempted = 1,
            messages = listOf(
                MailMessageSummary(
                    inboxRow = "Example Sender — Release update",
                    bodySummary = "• Release is ready.\n• Approval is needed by Friday.",
                )
            ),
        ).displayText

        assertTrue(result.contains("Body summary:"))
        assertTrue(result.contains("Raw body text is not displayed or retained."))
        assertFalse(result.contains("Body text exposed by Gmail:"))
        assertEquals(1, Regex("Body summary:").findAll(result).count())
    }
}
