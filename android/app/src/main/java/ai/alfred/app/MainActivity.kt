package ai.alfred.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.alfred.app.runtime.AgentCoordinator
import ai.alfred.app.runtime.SemanticAccessibilityService
import ai.alfred.app.runtime.VoiceController
import ai.alfred.app.ui.Palette
import ai.alfred.app.ui.PetMood
import ai.alfred.app.ui.PetView
import ai.alfred.app.ui.dp
import ai.alfred.app.ui.label
import ai.alfred.app.ui.pad
import ai.alfred.app.ui.rounded
import ai.alfred.app.ui.SettingsActivity
import ai.alfred.app.ui.SkillsActivity

class MainActivity : Activity(), VoiceController.Listener, AgentCoordinator.Events {
    private lateinit var pet: PetView
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var resultTitle: TextView
    private lateinit var speakerBadge: TextView
    private lateinit var dialogueHint: TextView
    private lateinit var choiceRow: LinearLayout
    private lateinit var confirmChoice: Button
    private lateinit var cancelChoice: Button
    private lateinit var mic: Button
    private lateinit var accessCard: LinearLayout
    private lateinit var voice: VoiceController
    private lateinit var agent: AgentCoordinator
    private var listening = false
    private var pendingConfirmation: String? = null
    private val ui = Handler(Looper.getMainLooper())
    private var typewriter: Runnable? = null
    private var pendingDialogueText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.Night
        window.navigationBarColor = Palette.Night
        voice = VoiceController(this, this)
        agent = AgentCoordinator(this, this)
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        updateSetupState()
    }

    override fun onDestroy() {
        typewriter?.let(ui::removeCallbacks)
        voice.destroy()
        super.onDestroy()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(22), dp(22), dp(30))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(17, 16, 34), Color.rgb(25, 21, 48), Color.rgb(12, 23, 34)),
            )
        }

        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val brand = label("ALFRED", 22f, Palette.Ink, true).apply { letterSpacing = 0.16f }
        top.addView(brand, LinearLayout.LayoutParams(0, dp(48), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        val skills = Button(this).apply {
            text = "✦"
            textSize = 20f
            contentDescription = "Open skill library"
            setTextColor(Palette.Mint)
            background = rounded(Palette.Surface, 18, this@MainActivity, Palette.SurfaceBright)
            setOnClickListener { startActivity(Intent(this@MainActivity, SkillsActivity::class.java)) }
        }
        top.addView(skills, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginEnd = dp(8)
        })
        val settings = Button(this).apply {
            text = "⚙"
            textSize = 21f
            contentDescription = "Open settings"
            setTextColor(Palette.Ink)
            background = rounded(Palette.Surface, 18, this@MainActivity, Palette.SurfaceBright)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        top.addView(settings, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(top, LinearLayout.LayoutParams.MATCH_PARENT, dp(52))

        status = label("READY • LOCAL FIRST", 12f, Palette.Mint, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            background = rounded(Color.argb(40, 110, 231, 216), 18, this@MainActivity, Color.argb(100, 110, 231, 216))
            pad(10)
        }
        root.addView(status, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        pet = PetView(this)
        root.addView(pet, LinearLayout.LayoutParams.MATCH_PARENT, dp(285))

        root.addView(label("What shall we do?", 28f, Palette.Ink, true).apply {
            gravity = Gravity.CENTER
        })
        root.addView(label("Speak naturally. Familiar actions stay on your phone.", 15f, Palette.Muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, dp(18))
        })

        val dialogueCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.Surface, 22, this@MainActivity, Palette.Violet)
            pad(16)
            setOnClickListener { revealDialogue() }
        }
        val dialogueHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        speakerBadge = label("ALFRED", 11f, Palette.Night, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            background = rounded(Palette.Mint, 12, this@MainActivity)
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
        dialogueHeader.addView(
            speakerBadge,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        resultTitle = label("READY FOR A QUEST", 12f, Palette.Muted, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            letterSpacing = 0.08f
        }
        dialogueHeader.addView(
            resultTitle,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        dialogueCard.addView(dialogueHeader)

        transcript = label("Tap the mic and tell me what you need.", 16f, Palette.Ink).apply {
            gravity = Gravity.START
            minHeight = dp(82)
            setPadding(0, dp(14), 0, dp(8))
        }
        dialogueCard.addView(
            transcript,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        dialogueHint = label("", 11f, Palette.Muted, true).apply {
            gravity = Gravity.END
            visibility = View.GONE
        }
        dialogueCard.addView(dialogueHint)

        choiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        confirmChoice = dialogueChoice("✓  Confirm", Palette.Mint) { confirmPendingAction() }
        cancelChoice = dialogueChoice("Not now", Palette.Coral) { cancelPendingAction() }
        choiceRow.addView(
            confirmChoice,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) },
        )
        choiceRow.addView(
            cancelChoice,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) },
        )
        dialogueCard.addView(choiceRow)
        root.addView(
            dialogueCard,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        mic = Button(this).apply {
            text = "●  Talk to Alfred"
            textSize = 17f
            isAllCaps = false
            setTextColor(Palette.Night)
            background = rounded(Palette.Mint, 28, this@MainActivity)
            setOnClickListener { onMicPressed() }
        }
        root.addView(
            mic,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)).apply {
                topMargin = dp(18)
            },
        )

        root.addView(label("TRY SAYING", 11f, Palette.Muted, true).apply {
            letterSpacing = 0.12f
            setPadding(0, dp(24), 0, dp(10))
        })
        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "Open Settings" to "Open Settings",
            "Route to Rome" to "Navigate to Rome with Maps",
            "Inbox rows only" to "Check last 10 emails in Gmail and recap the inbox list without reading message bodies",
            "Summarize mail bodies" to "Open the latest 3 emails in Gmail, read their body content, and summarize them",
        ).forEach { (title, goal) ->
            quickRow.addView(quickButton(title, goal))
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(quickRow)
        }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        accessCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(54, 42, 66), 20, this@MainActivity, Palette.Coral)
            pad(16)
            addView(label("One setup step", 16f, Palette.Ink, true))
            addView(label("Enable Alfred Device Actions so I can read semantic controls and act for you.", 14f, Palette.Muted).apply {
                setPadding(0, dp(6), 0, dp(10))
            })
            addView(Button(this@MainActivity).apply {
                text = "Open Accessibility settings"
                isAllCaps = false
                setTextColor(Palette.Night)
                background = rounded(Palette.Coral, 18, this@MainActivity)
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            })
        }
        root.addView(
            accessCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(22)
            },
        )

        val privacy = label("◉  Semantic controls only • private values stay local", 12f, Palette.Muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(22), 0, 0)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ai.alfred.app.ui.PrivacyActivity::class.java))
            }
        }
        root.addView(privacy)

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    private fun quickButton(title: String, goal: String): Button = Button(this).apply {
        text = title
        isAllCaps = false
        setTextColor(Palette.Ink)
        background = rounded(Palette.Surface, 20, this@MainActivity, Palette.SurfaceBright)
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener {
            submitNewGoal(goal)
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46)).apply {
            marginEnd = dp(9)
        }
    }

    private fun dialogueChoice(text: String, color: Int, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Palette.Night)
            background = rounded(color, 16, this@MainActivity)
            setOnClickListener { action() }
        }

    private fun submitNewGoal(goal: String) {
        pendingConfirmation = null
        choiceRow.visibility = View.GONE
        mic.text = "●  Talk to Alfred"
        showDialogue("PLAYER REQUEST", "“$goal”", Palette.Violet)
        agent.submit(goal)
    }

    private fun confirmPendingAction() {
        val goal = pendingConfirmation ?: return
        pendingConfirmation = null
        choiceRow.visibility = View.GONE
        mic.text = "●  Talk to Alfred"
        showDialogue("CHOICE ACCEPTED", "Got it. I’ll continue with the confirmed action.", Palette.Mint)
        agent.submit(goal, confirmed = true)
    }

    private fun cancelPendingAction() {
        pendingConfirmation = null
        choiceRow.visibility = View.GONE
        pet.mood = PetMood.IDLE
        setStatus("QUEST PAUSED • YOUR CHOICE", Palette.Coral)
        showDialogue("CHOICE SAVED", "No problem. I won’t perform that action.", Palette.Coral, true)
        mic.text = "●  Ask another"
    }

    private fun onMicPressed() {
        if (pendingConfirmation != null) {
            confirmPendingAction()
            return
        }
        if (listening) {
            voice.stop()
            listening = false
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        startListening()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onError("Speech recognition is not available on this phone. You can still use quick actions.")
            return
        }
        listening = true
        pet.mood = PetMood.LISTENING
        setStatus("LISTENING • VOICE CHANNEL", Palette.Mint)
        showDialogue("VOICE LINK OPEN", "I’m listening…", Palette.Mint)
        mic.text = "■  Stop listening"
        val locale = getSharedPreferences("alfred", MODE_PRIVATE).getString("voice_locale", "")
        voice.start(locale)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else if (requestCode == REQUEST_MIC) {
            onError("Microphone permission was not granted.")
        }
    }

    override fun onListening() = Unit

    override fun onPartial(text: String) {
        showDialogue("HEARING YOU", "“$text…”", Palette.Mint)
    }

    override fun onResult(text: String) {
        listening = false
        submitNewGoal(text)
    }

    override fun onError(message: String) {
        listening = false
        pet.mood = PetMood.ERROR
        setStatus("QUEST INTERRUPTED", Palette.Coral)
        showDialogue("ALFRED NEEDS HELP", message, Palette.Coral, true)
        mic.text = "●  Try again"
    }

    override fun onThinking(message: String) {
        pet.mood = PetMood.THINKING
        setStatus("THINKING • SEARCHING MEMORY", Palette.Violet)
        showDialogue("CALCULATING NEXT MOVE", message, Palette.Violet)
    }

    override fun onActing(message: String) {
        pet.mood = PetMood.ACTING
        setStatus("IN ACTION • WATCH THE SCREEN", Palette.Mint)
        showDialogue("EXECUTING SKILL", message, Palette.Mint)
    }

    override fun onConfirmation(goal: String, message: String) {
        pendingConfirmation = goal
        pet.mood = PetMood.ASKING
        setStatus("ALFRED ASKS • YOUR CHOICE", Palette.Coral)
        showDialogue("DECISION POINT", message, Palette.Coral, true)
        choiceRow.visibility = View.VISIBLE
        mic.text = "✓  Confirm action"
    }

    override fun onSuccess(message: String, modelCalls: Int) {
        pet.mood = PetMood.SUCCESS
        setStatus(
            if (modelCalls == 0) "QUEST COMPLETE • LOCAL WIN" else "QUEST COMPLETE • NEW MEMORY",
            Palette.Mint,
        )
        showDialogue("QUEST COMPLETE", message, Palette.Mint, true)
        mic.text = "●  Ask another"
    }

    override fun onResult(title: String, message: String) {
        pet.mood = PetMood.ANSWERING
        setStatus("BRIEFING READY • ON DEVICE", Palette.Mint)
        showDialogue(title.uppercase(), message, Palette.Mint, true)
        mic.text = "●  Ask another"
    }

    private fun setStatus(text: String, accent: Int) {
        status.text = text
        status.setTextColor(accent)
        status.background = rounded(
            Color.argb(38, Color.red(accent), Color.green(accent), Color.blue(accent)),
            18,
            this,
            Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)),
        )
    }

    private fun showDialogue(
        title: String,
        message: String,
        accent: Int,
        animate: Boolean = false,
    ) {
        typewriter?.let(ui::removeCallbacks)
        typewriter = null
        pendingDialogueText = null
        dialogueHint.visibility = View.GONE
        choiceRow.visibility = View.GONE
        resultTitle.text = title
        resultTitle.setTextColor(accent)
        speakerBadge.background = rounded(accent, 12, this)
        transcript.gravity = Gravity.START
        transcript.minHeight = if (message.length > 240) dp(220) else dp(82)
        if (!animate) {
            transcript.text = message
            return
        }
        pendingDialogueText = message
        transcript.text = ""
        dialogueHint.text = "TAP CARD TO REVEAL  ▾"
        dialogueHint.visibility = View.VISIBLE
        val chunk = when {
            message.length > 900 -> 4
            message.length > 400 -> 2
            else -> 1
        }
        var position = 0
        val writer = object : Runnable {
            override fun run() {
                position = (position + chunk).coerceAtMost(message.length)
                transcript.text = message.substring(0, position)
                if (position < message.length) {
                    ui.postDelayed(this, TYPEWRITER_DELAY_MS)
                } else {
                    pendingDialogueText = null
                    dialogueHint.visibility = View.GONE
                    typewriter = null
                }
            }
        }
        typewriter = writer
        ui.post(writer)
    }

    private fun revealDialogue() {
        val complete = pendingDialogueText ?: return
        typewriter?.let(ui::removeCallbacks)
        typewriter = null
        pendingDialogueText = null
        transcript.text = complete
        dialogueHint.visibility = View.GONE
    }

    override fun onFailure(message: String) = onError(message)

    private fun updateSetupState() {
        val enabled = SemanticAccessibilityService.isEnabled(this)
        accessCard.visibility = if (enabled) View.GONE else View.VISIBLE
        if (enabled && pet.mood == PetMood.IDLE) status.text = "READY • LOCAL FIRST"
    }

    companion object {
        private const val REQUEST_MIC = 41
        private const val TYPEWRITER_DELAY_MS = 14L
    }
}
