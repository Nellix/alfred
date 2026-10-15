package ai.alfred.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import ai.alfred.app.runtime.BuiltInSkillDefinition
import ai.alfred.app.runtime.BuiltInSkills
import ai.alfred.app.runtime.GitHubRegistryStatus
import ai.alfred.app.runtime.GitHubSkillSummary
import ai.alfred.app.runtime.LocalSemanticCache
import ai.alfred.app.runtime.PhoneSkillSummary
import ai.alfred.app.runtime.SkillsClient
import java.util.Date

class SkillsActivity : Activity() {
    private lateinit var registryStatus: TextView
    private lateinit var refreshButton: Button
    private lateinit var downloadedSkills: LinearLayout
    private lateinit var phoneSkills: LinearLayout
    private var automaticRefreshStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.Night
        window.navigationBarColor = Palette.Night
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun buildContent(): ScrollView {
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
            background = rounded(Palette.Surface, 18, this@SkillsActivity)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(
            label("Skill Library", 28f, Palette.Ink, true),
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(14)
                gravity = Gravity.CENTER_VERTICAL
            },
        )
        content.addView(header)
        content.addView(label(
            "Built-ins are shipped with Alfred. Shared skills come from a reviewed GitHub repository. Personal learned skills stay on this phone.",
            14f,
            Palette.Muted,
        ).apply { setPadding(0, dp(12), 0, 0) })

        content.addView(sectionTitle("BUILT-IN ASSISTANT SKILLS"))
        BuiltInSkills.all.forEachIndexed { index, skill ->
            content.addView(builtInSkillCard(index + 1, skill), spaced())
        }

        content.addView(sectionTitle("GITHUB REGISTRY"))
        registryStatus = label("Checking the local GitHub catalog…", 14f, Palette.Muted)
        refreshButton = actionButton("Pull latest reviewed skills") { forceRefresh() }
        content.addView(card().apply {
            addView(label("Pull requests are the control plane", 17f, Palette.Ink, true))
            addView(registryStatus.apply { setPadding(0, dp(8), 0, dp(12)) })
            addView(refreshButton)
            addView(actionButton("Open skills repository") {
                openUrl(SettingsActivity.GITHUB_SKILLS_REPOSITORY)
            }.apply { setPadding(0, dp(8), 0, 0) })
            addView(actionButton("Read contribution guide") {
                openUrl("${SettingsActivity.GITHUB_SKILLS_REPOSITORY}/blob/main/CONTRIBUTING.md")
            }.apply { setPadding(0, dp(8), 0, 0) })
            addView(label(
                "Alfred verifies catalog revision, file paths, SHA-256 digests, schemas, parameters, actions, and safety rules before replacing its local downloaded catalog.",
                12f,
                Palette.Muted,
            ).apply { setPadding(0, dp(9), 0, 0) })
        })

        content.addView(sectionTitle("DOWNLOADED REVIEWED SKILLS"))
        downloadedSkills = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(downloadedSkills)

        content.addView(sectionTitle("LEARNED ON THIS PHONE"))
        phoneSkills = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(phoneSkills)

        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
    }

    private fun reload() {
        renderPhoneSkills()
        val client = client()
        if (client == null) {
            registryStatus.text = "GitHub registry is not configured. Add its raw URL in Settings."
            refreshButton.isEnabled = false
            renderDownloaded(emptyList())
            return
        }
        refreshButton.isEnabled = true
        client.catalog { result ->
            runOnUiThread {
                result.fold(
                    onSuccess = { catalog ->
                        renderRegistryStatus(catalog.status)
                        renderDownloaded(catalog.skills)
                        val stale = catalog.status.lastSuccessAt == null ||
                            System.currentTimeMillis() - catalog.status.lastSuccessAt > AUTO_REFRESH_MS
                        if (stale && !automaticRefreshStarted) {
                            automaticRefreshStarted = true
                            refresh(client, automatic = true)
                        }
                    },
                    onFailure = { error ->
                        registryStatus.text = "Local catalog error: ${error.message}"
                        renderDownloaded(emptyList())
                    },
                )
            }
        }
    }

    private fun forceRefresh() {
        val client = client() ?: return
        refresh(client, automatic = false)
    }

    private fun refresh(client: SkillsClient, automatic: Boolean) {
        refreshButton.isEnabled = false
        refreshButton.text = if (automatic) "Checking GitHub…" else "Pulling reviewed skills…"
        client.refresh { result ->
            runOnUiThread {
                refreshButton.isEnabled = true
                refreshButton.text = "Pull latest reviewed skills"
                result.fold(
                    onSuccess = { catalog ->
                        renderRegistryStatus(catalog.status)
                        renderDownloaded(catalog.skills)
                        if (!automatic) {
                            Toast.makeText(this, "GitHub skills refreshed", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onFailure = { error ->
                        registryStatus.text = "Refresh failed; installed skills remain unchanged. ${error.message}"
                        if (!automatic) Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                    },
                )
            }
        }
    }

    private fun renderRegistryStatus(status: GitHubRegistryStatus) {
        val updated = status.lastSuccessAt?.let {
            DateFormat.getMediumDateFormat(this).format(Date(it)) + " " +
                DateFormat.getTimeFormat(this).format(Date(it))
        } ?: "never"
        registryStatus.text = buildString {
            append("Revision ${status.revision ?: "—"} · ${status.installed} installed")
            if (status.disabled > 0) append(" · ${status.disabled} disabled")
            append("\nLast successful pull: $updated")
            status.lastError?.let { append("\nLast error: $it") }
        }
    }

    private fun renderDownloaded(skills: List<GitHubSkillSummary>) {
        downloadedSkills.removeAllViews()
        if (skills.isEmpty()) {
            downloadedSkills.addView(emptyCard("No reviewed GitHub skills are installed yet."))
            return
        }
        skills.forEach { skill -> downloadedSkills.addView(downloadedSkillCard(skill), spaced()) }
    }

    private fun downloadedSkillCard(skill: GitHubSkillSummary): LinearLayout = card().apply {
        addView(label(skill.title, 17f, Palette.Ink, true))
        addView(label("${skill.canonicalIntent} · v${skill.version}", 12f, Palette.Mint, true))
        addView(label(skill.description, 13f, Palette.Muted).apply {
            setPadding(0, dp(7), 0, dp(8))
        })
        addView(label(
            if (skill.safety == "confirm") "Confirmation required" else "No extra confirmation",
            12f,
            if (skill.safety == "confirm") Palette.Coral else Palette.Mint,
            true,
        ))
        addView(Switch(this@SkillsActivity).apply {
            text = if (skill.enabled) "Enabled" else "Disabled"
            setTextColor(Palette.Ink)
            isChecked = skill.enabled
            thumbTintList = android.content.res.ColorStateList.valueOf(Palette.Mint)
            setOnCheckedChangeListener { button, enabled ->
                button.isEnabled = false
                client()?.setEnabled(skill.id, enabled) { result ->
                    runOnUiThread {
                        button.isEnabled = true
                        result.fold(
                            onSuccess = {
                                button.text = if (enabled) "Enabled" else "Disabled"
                            },
                            onFailure = { error ->
                                button.isChecked = !enabled
                                Toast.makeText(this@SkillsActivity, error.message, Toast.LENGTH_LONG)
                                    .show()
                            },
                        )
                    }
                }
            }
        })
    }

    private fun renderPhoneSkills() {
        phoneSkills.removeAllViews()
        val cache = LocalSemanticCache(this)
        val skills = cache.listSkills()
        if (skills.isEmpty()) {
            phoneSkills.addView(emptyCard("No learned phone skills yet. Verified LLM actions appear here."))
            return
        }
        skills.forEach { skill -> phoneSkills.addView(phoneSkillCard(cache, skill), spaced()) }
    }

    private fun phoneSkillCard(
        cache: LocalSemanticCache,
        skill: PhoneSkillSummary,
    ): LinearLayout = card().apply {
        addView(label(skill.canonicalIntent, 16f, Palette.Ink, true))
        addView(label(
            "confidence ${"%.2f".format(skill.confidence)} · ${skill.successes} verified · ${skill.failures} failed",
            12f,
            Palette.Muted,
        ).apply { setPadding(0, dp(6), 0, dp(8)) })
        addView(label(
            skill.goalTemplate ?: "Exact-match fingerprint · not eligible for public contribution",
            12f,
            Palette.Mint,
        ))
        addView(Switch(this@SkillsActivity).apply {
            text = if (skill.active) "Enabled on this phone" else "Disabled on this phone"
            setTextColor(Palette.Ink)
            isChecked = skill.active
            thumbTintList = android.content.res.ColorStateList.valueOf(Palette.Mint)
            setOnCheckedChangeListener { _, enabled ->
                cache.setActive(skill.id, enabled)
                text = if (enabled) "Enabled on this phone" else "Disabled on this phone"
            }
        })
        if (skill.goalTemplate != null) {
            addView(actionButton("Prepare GitHub pull request") {
                val proposal = cache.contributionProposal(skill.id)
                if (proposal == null) {
                    Toast.makeText(this@SkillsActivity, "This skill cannot be exported", Toast.LENGTH_LONG)
                        .show()
                } else {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Alfred skill proposal", proposal))
                    Toast.makeText(
                        this@SkillsActivity,
                        "Generalized skill JSON copied; opening the pull-request guide",
                        Toast.LENGTH_LONG,
                    ).show()
                    openUrl("${SettingsActivity.GITHUB_SKILLS_REPOSITORY}/blob/main/CONTRIBUTING.md")
                }
            }.apply { setPadding(0, dp(8), 0, 0) })
        }
        addView(actionButton("Delete from phone") {
            AlertDialog.Builder(this@SkillsActivity)
                .setTitle("Delete learned skill?")
                .setMessage("This removes only the personal copy stored on this phone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    cache.deleteFromPhone(skill.id)
                    renderPhoneSkills()
                }
                .show()
        }.apply {
            setTextColor(Palette.Coral)
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun builtInSkillCard(number: Int, skill: BuiltInSkillDefinition): LinearLayout =
        card().apply {
            addView(label("$number  ${skill.title}", 17f, Palette.Ink, true))
            addView(label(skill.id, 12f, Palette.Mint, true))
            addView(label("Say: ${skill.invocation}", 13f, Palette.Muted).apply {
                setPadding(0, dp(7), 0, dp(4))
            })
            addView(label("Reads: ${skill.reads}", 12f, Palette.Ink))
            addView(label(skill.doesNotRead, 12f, Palette.Coral).apply {
                setPadding(0, dp(4), 0, 0)
            })
            addView(label(skill.privacy, 12f, Palette.Muted).apply {
                setPadding(0, dp(6), 0, 0)
            })
        }

    private fun client(): SkillsClient? {
        val url = getSharedPreferences("alfred", MODE_PRIVATE)
            .getString("github_registry_url", SettingsActivity.DEFAULT_GITHUB_REGISTRY_URL)
            .orEmpty()
        return runCatching { SkillsClient(this, url) }.getOrNull()
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun sectionTitle(text: String): TextView = label(text, 11f, Palette.Muted, true).apply {
        letterSpacing = 0.12f
        setPadding(0, dp(26), 0, dp(9))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.Surface, 20, this@SkillsActivity, Palette.SurfaceBright)
        pad(17)
    }

    private fun emptyCard(text: String): LinearLayout = card().apply {
        addView(label(text, 13f, Palette.Muted))
    }

    private fun actionButton(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Palette.Ink)
        background = rounded(Palette.SurfaceBright, 16, this@SkillsActivity)
        setOnClickListener { action() }
    }

    private fun spaced(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(10) }

    companion object {
        private const val AUTO_REFRESH_MS = 6 * 60 * 60 * 1000L
    }
}
