package com.github.libretube.jc

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.base.BaseActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen asking for the access code before the app can be used.
 */
class LicenseActivity : BaseActivity() {
    private lateinit var codeInput: EditText
    private lateinit var submitButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        // outer container that keeps the content clear of the status and navigation bars
        val container = FrameLayout(this).apply { fitsSystemWindows = true }

        val title = TextView(this).apply {
            text = "Activation"
            textSize = 26f
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Entrez votre code d'accès pour utiliser l'application."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(24))
        }

        codeInput = EditText(this).apply {
            hint = "123456"
            textSize = 20f
            gravity = Gravity.CENTER
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        submitButton = Button(this).apply {
            text = "Valider"
            setOnClickListener { submit() }
        }

        statusText = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }

        val fullWidth = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        root.addView(title, fullWidth)
        root.addView(subtitle, fullWidth)
        root.addView(codeInput, fullWidth)
        root.addView(
            submitButton,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) }
        )
        root.addView(statusText, fullWidth)

        container.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(container)
    }

    private fun submit() {
        val code = codeInput.text.toString().trim()
        if (code.isEmpty()) {
            statusText.text = "Entrez votre code."
            return
        }

        submitButton.isEnabled = false
        statusText.text = "Vérification…"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                LicenseManager.check(this@LicenseActivity, code)
            }
            submitButton.isEnabled = true

            when (result) {
                is LicenseManager.Result.Valid -> {
                    LicenseManager.save(this@LicenseActivity, code, result.expiresAt)
                    startActivity(Intent(this@LicenseActivity, MainActivity::class.java))
                    finish()
                }

                is LicenseManager.Result.Invalid -> {
                    statusText.text = LicenseManager.messageFor(result.reason)
                }

                LicenseManager.Result.NetworkError -> {
                    statusText.text = "Connexion impossible. Vérifiez votre accès internet."
                }
            }
        }
    }
}
