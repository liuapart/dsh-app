package cn.apanoo.dshshell

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新：启动时查 GitHub Release 最新版 → 弹窗 → DownloadManager 下载 → 自动拉起安装器。
 * （Android 不允许完全静默安装，最后一步由系统安装器完成，一键确认即"自动重装"）
 */
object Updater {

    private const val API = "https://api.github.com/repos/liuapart/dsh-app/releases/latest"
    private var downloadId = -1L

    /** 在途防抖：冷启动与下拉强刷可能接连触发，避免重复检查/重复弹窗 */
    private val checking = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 检查更新：静默进行，有新版本才打扰（冷启动 & 下拉强刷时调用） */
    fun check(activity: MainActivity) {
        if (!checking.compareAndSet(false, true)) return
        Thread {
            try {
                val conn = URL(API).openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode != 200) return@Thread
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val tag = json.getString("tag_name").removePrefix("v")
                val body = json.optString("body", "")
                val apkUrl = (0 until json.getJSONArray("assets").length())
                    .map { json.getJSONArray("assets").getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") }
                    ?.getString("browser_download_url") ?: return@Thread
                if (!isNewer(tag, BuildConfig.VERSION_NAME)) return@Thread
                Handler(Looper.getMainLooper()).post {
                    AlertDialog.Builder(activity)
                        .setTitle("发现新版本 v$tag")
                        .setMessage("当前 v${BuildConfig.VERSION_NAME}\n\n${body.take(400)}")
                        .setPositiveButton("立即更新") { _, _ -> download(activity, apkUrl) }
                        .setNegativeButton("下次再说", null)
                        .show()
                }
            } catch (e: Exception) {
                // 更新检查失败静默忽略，不影响正常使用
            } finally {
                checking.set(false)
            }
        }.start()
    }

    /** 语义化版本比较：remote > local 才算新 */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun download(activity: MainActivity, url: String) {
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(android.net.Uri.parse(url)).apply {
            setTitle("dsh 新版本")
            setDescription("下载完成后将自动打开安装")
            setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "dsh-update.apk")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        }
        downloadId = dm.enqueue(req)
        Toast.makeText(activity, "开始下载更新…", Toast.LENGTH_SHORT).show()
    }

    /** 在 Activity.onCreate 注册一次：下载完成 → 自动拉起系统安装器 */
    fun registerReceiver(activity: MainActivity) {
        ContextCompat.registerReceiver(
            activity,
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id != downloadId) return
                    val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val uri = dm.getUriForDownloadedFile(downloadId) ?: return
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            },
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
}
