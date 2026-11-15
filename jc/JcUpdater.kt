package com.github.libretube.jc

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object JcUpdater {
    const val BUILD = __BUILD__
    private const val VERSION_URL = "https://github.com/jc-panel/LibreTube/releases/latest/download/version.txt"
    private const val APK_URL = "https://github.com/jc-panel/LibreTube/releases/latest/download/tubeplus.apk"

    fun check(activity: Activity) {
        thread {
            val latest = try {
                val c = URL(VERSION_URL).openConnection() as HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 6000
                val line = c.inputStream.bufferedReader().use { it.readLine() }
                c.disconnect()
                line?.trim()?.toIntOrNull() ?: -1
            } catch (e: Exception) {
                -1
            }
            if (latest > BUILD) {
                activity.runOnUiThread { if (!activity.isFinishing) prompt(activity) }
            }
        }
    }

    private fun prompt(activity: Activity) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Nouvelle version disponible")
            .setMessage("Une mise a jour de DarkTube est prete. L'installer maintenant ?")
            .setPositiveButton("Installer") { _, _ -> download(activity) }
            .setNegativeButton("Plus tard", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ?.setTextColor(android.graphics.Color.parseColor("#EB1419"))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            ?.setTextColor(android.graphics.Color.WHITE)
    }

    private fun download(activity: Activity) {
        Toast.makeText(activity, "Telechargement...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val dir = activity.externalCacheDir ?: activity.cacheDir
                val out = File(dir, "update.apk")
                var urlStr = APK_URL
                var redirects = 0
                var c = URL(urlStr).openConnection() as HttpURLConnection
                while (true) {
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 15000
                    c.readTimeout = 60000
                    val code = c.responseCode
                    if (code in 300..399) {
                        val loc = c.getHeaderField("Location")
                        c.disconnect()
                        if (loc == null || redirects++ > 5) throw Exception("redirect")
                        urlStr = loc
                        c = URL(urlStr).openConnection() as HttpURLConnection
                        continue
                    }
                    break
                }
                c.inputStream.use { inp -> out.outputStream().use { inp.copyTo(it) } }
                c.disconnect()
                if (out.length() < 100000L) throw Exception("empty")
                val uri = FileProvider.getUriForFile(activity, activity.packageName + ".jcfileprovider", out)
                val i = Intent(Intent.ACTION_VIEW)
                i.setDataAndType(uri, "application/vnd.android.package-archive")
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(i)
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Mise a jour impossible, reessayez.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
