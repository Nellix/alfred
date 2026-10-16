package ai.alfred.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout

class ConfirmationActivity : Activity() {
    private var resolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.48f }
        setFinishOnTouchOutside(false)

        val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
        val root = FrameLayout(this).apply {
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = rounded(Palette.Surface, 24, this@ConfirmationActivity, Palette.Violet)
            elevation = dp(12).toFloat()
        }
        panel.addView(label("ALFRED • APPROVAL NEEDED", 13f, Palette.Mint, true).apply {
            letterSpacing = 0.08f
        })
        panel.addView(label(message, 16f, Palette.Ink).apply {
            setPadding(0, dp(12), 0, dp(14))
        })
        val choices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        choices.addView(choice("✓  Confirm", Palette.Mint, "Confirm Alfred action") {
            resolve(confirm = true)
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(8) })
        choices.addView(choice("Not now", Palette.SurfaceBright, "Cancel Alfred action") {
            resolve(confirm = false)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        panel.addView(choices)
        root.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        setContentView(root)
    }

    override fun onBackPressed() {
        resolve(confirm = false)
    }

    override fun onDestroy() {
        if (!resolved && !isChangingConfigurations) takeCallback(confirm = false)?.invoke()
        super.onDestroy()
    }

    private fun choice(
        text: String,
        color: Int,
        description: String,
        action: () -> Unit,
    ): View = Button(this).apply {
        this.text = text
        isAllCaps = false
        contentDescription = description
        setTextColor(if (color == Palette.Mint) Palette.Night else Palette.Ink)
        background = rounded(color, 16, this@ConfirmationActivity)
        setOnClickListener { action() }
    }

    private fun resolve(confirm: Boolean) {
        if (resolved) return
        resolved = true
        val callback = takeCallback(confirm)
        finish()
        overridePendingTransition(0, 0)
        Handler(Looper.getMainLooper()).postDelayed({ callback?.invoke() }, 120L)
    }

    companion object {
        private const val EXTRA_MESSAGE = "message"
        private var confirmCallback: (() -> Unit)? = null
        private var cancelCallback: (() -> Unit)? = null

        fun show(
            context: Context,
            message: String,
            onConfirm: () -> Unit,
            onCancel: () -> Unit,
        ): Boolean = runCatching {
            confirmCallback = onConfirm
            cancelCallback = onCancel
            context.startActivity(
                Intent(context, ConfirmationActivity::class.java)
                    .putExtra(EXTRA_MESSAGE, message)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }.onFailure {
            confirmCallback = null
            cancelCallback = null
        }.isSuccess

        private fun takeCallback(confirm: Boolean): (() -> Unit)? {
            val callback = if (confirm) confirmCallback else cancelCallback
            confirmCallback = null
            cancelCallback = null
            return callback
        }
    }
}
