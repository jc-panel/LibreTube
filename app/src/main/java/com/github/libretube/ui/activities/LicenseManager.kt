package com.github.libretube.jc

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Access code check against the licence panel.
 * One code is bound to one device and has an expiry date set by the panel.
 */
object LicenseManager {
    private const val CHECK_URL = "https://jc-panel.com/wp-json/jclic/v1/check"
    private const val PREFS = "jc_licence"
    private const val KEY_CODE = "code"
    private const val KEY_EXPIRES = "expires"
    private const val KEY_DEVICE = "device"

    sealed class Result {
        data class Valid(val expiresAt: Long, val daysLeft: Int) : Result()
        data class Invalid(val reason: String) : Result()
        object NetworkError : Result()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun storedCode(context: Context): String? = prefs(context).getString(KEY_CODE, null)

    fun storedExpiry(context: Context): Long = prefs(context).getLong(KEY_EXPIRES, 0L)

    /** True when a code is stored and its last known expiry date is still in the future. */
    fun isActivated(context: Context): Boolean {
        return !storedCode(context).isNullOrEmpty() &&
            storedExpiry(context) > System.currentTimeMillis()
    }

    fun save(context: Context, code: String, expiresAt: Long) {
        prefs(context).edit().putString(KEY_CODE, code).putLong(KEY_EXPIRES, expiresAt).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_CODE).remove(KEY_EXPIRES).apply()
    }

    @SuppressLint("HardwareIds")
    fun deviceId(context: Context): String {
        val prefs = prefs(context)
        prefs.getString(KEY_DEVICE, null)?.let { return it }

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty().filter { it.isLetterOrDigit() }

        val id = androidId.ifEmpty { UUID.randomUUID().toString().replace("-", "") }
        prefs.edit().putString(KEY_DEVICE, id).apply()
        return id
    }

    /** Blocking network call, never run it on the main thread. */
    fun check(context: Context, code: String): Result {
        var connection: HttpURLConnection? = null
        return try {
            val body = "code=" + URLEncoder.encode(code.trim().uppercase(Locale.ROOT), "UTF-8") +
                "&device=" + URLEncoder.encode(deviceId(context), "UTF-8")

            connection = (URL(CHECK_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(text)

            if (json.optBoolean("valid", false)) {
                val expiresAt = parseDate(json.optString("expires"))
                    ?: return Result.NetworkError
                Result.Valid(expiresAt, json.optInt("days_left", 0))
            } else {
                Result.Invalid(json.optString("reason", "code_inconnu"))
            }
        } catch (e: Exception) {
            Result.NetworkError
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseDate(value: String): Long? {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.parse(value)?.time
        } catch (e: Exception) {
            null
        }
    }

    fun messageFor(reason: String): String = when (reason) {
        "code_inconnu" -> "Code inconnu. Vérifiez la saisie."
        "code_bloque" -> "Ce code a été bloqué."
        "code_expire" -> "Ce code a expiré."
        "autre_appareil" -> "Ce code est déjà utilisé sur un autre appareil."
        "trop_de_tentatives" -> "Trop de tentatives. Réessayez dans quelques minutes."
        else -> "Code refusé."
    }

    private fun openLicenseScreen(activity: AppCompatActivity) {
        activity.startActivity(Intent(activity, LicenseActivity::class.java))
        activity.finish()
    }

    /**
     * Called at app start. Returns false when the calling activity has been closed
     * and replaced by the activation screen.
     */
    fun guard(activity: AppCompatActivity): Boolean {
        if (!isActivated(activity)) {
            openLicenseScreen(activity)
            return false
        }

        // re-check with the panel in the background (blocked, expired or extended code)
        val code = storedCode(activity).orEmpty()
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { check(activity, code) }
            when (result) {
                is Result.Valid -> save(activity, code, result.expiresAt)
                is Result.Invalid -> {
                    clear(activity)
                    if (!activity.isFinishing) openLicenseScreen(activity)
                }
                Result.NetworkError -> Unit // offline: keep the last known expiry date
            }
        }
        return true
    }
}
