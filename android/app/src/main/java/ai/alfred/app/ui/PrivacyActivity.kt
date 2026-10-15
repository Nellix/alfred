package ai.alfred.app.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView

class PrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.Night
        window.navigationBarColor = Palette.Night
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(36))
            setBackgroundColor(Palette.Night)
        }
        root.addView(Button(this).apply {
            text = "‹  Back"
            isAllCaps = false
            setTextColor(Palette.Ink)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            background = rounded(Palette.Surface, 18, this@PrivacyActivity)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(110), dp(48)))
        root.addView(label("Private by design", 30f, Palette.Ink, true).apply {
            setPadding(0, dp(28), 0, dp(8))
        })
        root.addView(label(
            "Alfred turns your request into a semantic intent, such as navigation.route(destination), before checking memory.",
            16f,
            Palette.Muted,
        ))
        listOf(
            "1" to ("Local first" to "Native actions and verified patterns run without an LLM call."),
            "2" to ("No raw-value memory" to "Learned mappings store an intent skeleton and typed placeholders—not your message, contact, destination, or original prompt."),
            "3" to ("Cloud only when needed" to "The configured planner receives a redacted semantic screen. Screenshots are not sent by this app. Android's speech service handles voice recognition; offline recognition is requested when available."),
            "4" to ("Verified before reuse" to "A model result is saved only after Android reports a successful semantic transition. Failures lower confidence and disable stale patterns."),
            "5" to ("GitHub skills are public" to "Alfred downloads reviewed, generalized skills from a public GitHub repository. Your personal learned skills remain only on this phone unless you explicitly export a proposal and create a pull request."),
            "6" to ("You stay in control" to "Message actions can prepare a draft but never press Send. Sensitive and destructive controls remain blocked."),
            "7" to ("Inbox-row briefing: no body access" to "Alfred reads at most ten labels already displayed in Gmail's inbox list. It does not open messages, read body content, or inspect attachments. Row text is not sent to the planner, logged, learned, or synchronized."),
            "8" to ("Message-body summary: explicit opt-in" to "Only after confirmation, Alfred opens at most five messages, temporarily reads up to 1,200 visible body characters, and creates a short extractive summary on-device. If Gmail omits the body from accessibility, bundled on-device text recognition reads only the visible message-body region. Screenshot pixels and raw bodies are not displayed or retained. Opening can mark a message as read. Attachments and hidden content are not inspected, and no pixels or body text are sent to the planner, logged, learned, or synchronized."),
        ).forEach { (number, copy) ->
            root.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    background = rounded(Palette.Surface, 20, this@PrivacyActivity, Palette.SurfaceBright)
                    pad(17)
                    addView(label(number, 18f, Palette.Mint, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(38), dp(38)))
                    addView(LinearLayout(this@PrivacyActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label(copy.first, 17f, Palette.Ink, true))
                        addView(label(copy.second, 14f, Palette.Muted).apply { setPadding(0, dp(6), 0, 0) })
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(14) },
            )
        }
        root.addView(label(
            "There is no central skill database or automatic contribution upload. Raw utterances are kept only for the active request. GitHub contributions are explicit pull requests containing generalized templates, never contacts, messages, destinations, or original prompts.",
            13f,
            Palette.Muted,
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
