package ai.alfred.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ai.alfred.app.runtime.InferenceMode
import ai.alfred.app.runtime.ClientPlanner
import ai.alfred.app.runtime.LocalSemanticCache
import ai.alfred.app.runtime.SecureModelCredentials
import ai.alfred.app.runtime.SemanticAccessibilityService
import ai.alfred.app.runtime.SkillsClient
import ai.alfred.app.runtime.WorkflowStore

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.Night
        window.navigationBarColor = Palette.Night
        val preferences = getSharedPreferences("alfred", MODE_PRIVATE)
        val credentials = SecureModelCredentials(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(36))
            setBackgroundColor(Palette.Night)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Button(this).apply {
            text = "‹"
            textSize = 28f
            setTextColor(Palette.Ink)
            background = rounded(Palette.Surface, 18, this@SettingsActivity)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(
            label("Settings", 28f, Palette.Ink, true),
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(14)
                gravity = Gravity.CENTER_VERTICAL
            },
        )
        content.addView(header)

        content.addView(sectionTitle("DEVICE CONTROL"))
        val accessibilityEnabled = SemanticAccessibilityService.isEnabled(this)
        content.addView(card().apply {
            addView(label(
                if (accessibilityEnabled) "● Device Actions enabled" else "● Device Actions disabled",
                16f,
                if (accessibilityEnabled) Palette.Mint else Palette.Coral,
                true,
            ))
            addView(label(
                "Alfred uses Android accessibility to understand semantic controls and perform only requested actions.",
                14f,
                Palette.Muted,
            ).apply { setPadding(0, dp(8), 0, dp(12)) })
            addView(actionButton("Open Accessibility settings") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        })

        content.addView(sectionTitle("GITHUB SKILLS"))
        val registryEndpoint = EditText(this).apply {
            setText(
                preferences.getString("github_registry_url", DEFAULT_GITHUB_REGISTRY_URL)
            )
            hint = "Raw GitHub registry URL"
            setTextColor(Palette.Ink)
            setHintTextColor(Palette.Muted)
            textSize = 13f
            isSingleLine = true
            background = rounded(Palette.SurfaceBright, 14, this@SettingsActivity)
            setPadding(dp(14), 0, dp(14), 0)
        }
        content.addView(card().apply {
            addView(label("Public, reviewed skill catalog", 16f, Palette.Ink, true))
            addView(label(
                "Alfred downloads a static catalog from GitHub. Every shared skill enters through a pull request; no database or registry API is required.",
                13f,
                Palette.Muted,
            ).apply { setPadding(0, dp(7), 0, dp(11)) })
            addView(registryEndpoint, LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
            addView(actionButton("Open Skill Library") {
                startActivity(Intent(this@SettingsActivity, SkillsActivity::class.java))
            }.apply { setPadding(0, dp(8), 0, 0) })
        })

        content.addView(sectionTitle("INFERENCE"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val selectedMode = preferences.getString("inference_mode", InferenceMode.AUTO.name)
        listOf(
            InferenceMode.AUTO to "Auto · workflows → skills → client planner",
            InferenceMode.LOCAL_ONLY to "Local only · never contact a model provider",
            InferenceMode.CLOUD_FIRST to "Client planner first · for testing",
        ).forEach { (mode, title) ->
            group.addView(RadioButton(this).apply {
                id = mode.ordinal + 100
                text = title
                textSize = 15f
                setTextColor(Palette.Ink)
                buttonTintList = android.content.res.ColorStateList.valueOf(Palette.Mint)
                isChecked = selectedMode == mode.name
                tag = mode.name
            })
        }
        val geminiKey = secretField(
            if (credentials.has(ClientPlanner.KEY_GEMINI)) {
                "Gemini key saved securely · enter to replace"
            } else {
                "Gemini API key"
            }
        )
        val openRouterKey = secretField(
            if (credentials.has(ClientPlanner.KEY_OPENROUTER)) {
                "OpenRouter key saved securely · enter to replace"
            } else {
                "OpenRouter API key"
            }
        )
        val geminiModel = EditText(this).apply {
            setText(
                preferences.getString(
                    ClientPlanner.KEY_GEMINI_MODEL,
                    ClientPlanner.DEFAULT_GEMINI_MODEL,
                )
            )
            hint = "Gemini model"
            setTextColor(Palette.Ink)
            setHintTextColor(Palette.Muted)
            textSize = 13f
            isSingleLine = true
            background = rounded(Palette.SurfaceBright, 14, this@SettingsActivity)
            setPadding(dp(14), 0, dp(14), 0)
        }
        val openRouterModel = EditText(this).apply {
            setText(
                preferences.getString(
                    ClientPlanner.KEY_OPENROUTER_MODEL,
                    ClientPlanner.DEFAULT_OPENROUTER_MODEL,
                )
            )
            hint = "OpenRouter model"
            setTextColor(Palette.Ink)
            setHintTextColor(Palette.Muted)
            textSize = 13f
            isSingleLine = true
            background = rounded(Palette.SurfaceBright, 14, this@SettingsActivity)
            setPadding(dp(14), 0, dp(14), 0)
        }
        content.addView(card().apply {
            addView(group)
            addView(label("Direct model fallback", 13f, Palette.Muted, true).apply {
                setPadding(0, dp(12), 0, dp(7))
            })
            addView(geminiKey, LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
            addView(
                geminiModel,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply {
                    topMargin = dp(7)
                },
            )
            addView(
                openRouterKey,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply {
                    topMargin = dp(12)
                },
            )
            addView(
                openRouterModel,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply {
                    topMargin = dp(7)
                },
            )
            addView(label(
                "Planning runs inside Alfred. Gemini is tried first; OpenRouter is the fallback. Keys are encrypted with Android Keystore and sent only to their provider.",
                12f,
                Palette.Muted,
            ).apply { setPadding(0, dp(8), 0, 0) })
            addView(actionButton("Remove saved model keys") {
                credentials.remove(ClientPlanner.KEY_GEMINI)
                credentials.remove(ClientPlanner.KEY_OPENROUTER)
                geminiKey.hint = "Gemini API key"
                openRouterKey.hint = "OpenRouter API key"
                Toast.makeText(
                    this@SettingsActivity,
                    "Saved model keys removed",
                    Toast.LENGTH_SHORT,
                ).show()
            }.apply { setPadding(0, dp(9), 0, 0) })
        })

        content.addView(sectionTitle("VOICE & PRIVACY"))
        val locale = EditText(this).apply {
            setText(preferences.getString("voice_locale", ""))
            hint = "System language (or e.g. it-IT)"
            setTextColor(Palette.Ink)
            setHintTextColor(Palette.Muted)
            isSingleLine = true
            background = rounded(Palette.SurfaceBright, 14, this@SettingsActivity)
            setPadding(dp(14), 0, dp(14), 0)
        }
        content.addView(card().apply {
            addView(label("Recognition language", 13f, Palette.Muted, true))
            addView(locale, LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
            addView(label(
                "Personal learned skills remain only on this phone unless you explicitly export a generalized proposal for a GitHub pull request.",
                12f,
                Palette.Muted,
            ).apply { setPadding(0, dp(9), 0, 0) })
            addView(actionButton("Read privacy details") {
                startActivity(Intent(this@SettingsActivity, PrivacyActivity::class.java))
            }.apply { setPadding(0, dp(10), 0, 0) })
            addView(actionButton("Forget my learned skills") {
                val skillCount = LocalSemanticCache(this@SettingsActivity).clearAll()
                val workflowCount = WorkflowStore(this@SettingsActivity).clearAll()
                Toast.makeText(
                    this@SettingsActivity,
                    "$skillCount phone skill${if (skillCount == 1) "" else "s"} and " +
                        "$workflowCount workflow${if (workflowCount == 1) "" else "s"} removed",
                    Toast.LENGTH_LONG,
                ).show()
            }.apply {
                setTextColor(Palette.Coral)
                setPadding(0, dp(8), 0, 0)
            })
        })

        content.addView(
            actionButton("Save settings") {
                val registryUrl = registryEndpoint.text.toString().trim()
                if (registryUrl.isNotEmpty() && runCatching {
                        SkillsClient(this, registryUrl)
                    }.isFailure
                ) {
                    Toast.makeText(this, "Enter a valid HTTPS GitHub registry URL", Toast.LENGTH_LONG)
                        .show()
                    return@actionButton
                }
                val checked = group.findViewById<RadioButton>(group.checkedRadioButtonId)
                val credentialsSaved = runCatching {
                    if (geminiKey.text.isNotBlank()) {
                        credentials.put(ClientPlanner.KEY_GEMINI, geminiKey.text.toString().trim())
                    }
                    if (openRouterKey.text.isNotBlank()) {
                        credentials.put(
                            ClientPlanner.KEY_OPENROUTER,
                            openRouterKey.text.toString().trim(),
                        )
                    }
                }
                if (credentialsSaved.isFailure) {
                    Toast.makeText(
                        this,
                        "Android could not encrypt the model keys",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@actionButton
                }
                preferences.edit()
                    .putString("inference_mode", checked?.tag?.toString() ?: InferenceMode.AUTO.name)
                    .putString(ClientPlanner.KEY_GEMINI_MODEL, geminiModel.text.toString().trim())
                    .putString(
                        ClientPlanner.KEY_OPENROUTER_MODEL,
                        openRouterModel.text.toString().trim(),
                    )
                    .putString("github_registry_url", registryUrl)
                    .putString("voice_locale", locale.text.toString().trim())
                    .apply()
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
                finish()
            }.apply {
                background = rounded(Palette.Mint, 24, this@SettingsActivity)
                setTextColor(Palette.Night)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply {
                topMargin = dp(24)
            },
        )

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        })
    }

    private fun sectionTitle(text: String): TextView = label(text, 11f, Palette.Muted, true).apply {
        letterSpacing = 0.12f
        setPadding(0, dp(26), 0, dp(9))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.Surface, 20, this@SettingsActivity, Palette.SurfaceBright)
        pad(17)
    }

    private fun actionButton(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Palette.Ink)
        background = rounded(Palette.SurfaceBright, 16, this@SettingsActivity)
        setOnClickListener { action() }
    }

    private fun secretField(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setTextColor(Palette.Ink)
        setHintTextColor(Palette.Muted)
        textSize = 13f
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        background = rounded(Palette.SurfaceBright, 14, this@SettingsActivity)
        setPadding(dp(14), 0, dp(14), 0)
    }

    companion object {
        const val DEFAULT_GITHUB_REGISTRY_URL =
            "https://raw.githubusercontent.com/Nellix/alfred-skills/main"
        const val GITHUB_SKILLS_REPOSITORY = "https://github.com/Nellix/alfred-skills"
    }
}
