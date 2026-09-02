package cn.apanoo.dshshell

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃诊断（v1.9.3）：截图粘贴崩溃排查专用。
 * - Java 未捕获异常 → 写 filesDir/crash.log + SharedPreferences 标记；
 * - 环形事件日志（最近 40 条）：onResume/onPause/剪贴板元数据/渲染进程崩溃详情；
 * - MainActivity 恢复后读取摘要弹窗展示（用户截图即可反馈），不依赖 adb/电脑。
 */
object CrashDiag {
    private const val PREFS = "dsh_crash"
    private const val KEY_LAST_CRASH = "last_crash"
    private const val KEY_EVENTS = "events"
    private const val MAX_EVENTS = 40

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var initialized = false

    /** 安装默认未捕获异常处理器（保留原处理器链）。native 崩溃无法在此捕获——见 README 诊断说明。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val ctx = context.applicationContext
                val sb = StringBuilder()
                sb.append("=== crash ").append(ts()).append(" thread=").append(thread.name).append(" ===\n")
                sb.append(Log.getStackTraceString(throwable))
                if (sb.length > 6000) sb.setLength(6000)
                File(ctx.filesDir, "crash.log").writeText(sb.toString())
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LAST_CRASH, sb.toString()).apply()
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(thread, throwable)
        }
    }

    /** 追加一条环形事件日志（自动带时间戳）。 */
    fun log(context: Context, msg: String) {
        try {
            val ctx = context.applicationContext
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cur = sp.getString(KEY_EVENTS, null)
            val list = (cur?.split("\u0001") ?: emptyList()).toMutableList()
            list.add(ts() + " " + msg)
            while (list.size > MAX_EVENTS) list.removeAt(0)
            sp.edit().putString(KEY_EVENTS, list.joinToString("\u0001")).apply()
        } catch (_: Throwable) {
        }
    }

    fun lastCrash(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_CRASH, null)

    fun events(context: Context): String {
        val ctx = context.applicationContext
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = sp.getString(KEY_EVENTS, null) ?: return "(无事件记录)"
        return cur.split("\u0001").joinToString("\n")
    }

    fun consumeCrash(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST_CRASH).apply()
    }

    /** 剪贴板元数据摘要（只读描述，不读图片内容，安全）。 */
    fun clipboardSummary(context: Context): String {
        return try {
            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
            val clip = cm.primaryClip ?: return "clipboard: (空)"
            val d = clip.description
            val sb = StringBuilder("clipboard: ")
            sb.append("mime=").append(if (d.mimeTypeCount > 0) d.getMimeType(0) else "?")
            sb.append(" items=").append(clip.itemCount)
            sb.append(" hasImage=").append(d.hasMimeType("image/*"))
            val first = clip.getItemAt(0)
            sb.append(" uri=").append(first.uri?.scheme ?: "-").append(":")
                .append(first.uri?.authority?.take(24) ?: "-")
            val text = try {
                first.coerceToText(context)?.toString()?.take(30)?.replace('\n', ' ')
            } catch (e: Exception) { "ERR:" + e.javaClass.simpleName }
            sb.append(" text=").append(text ?: "-")
            sb.toString()
        } catch (e: Exception) {
            "clipboard: 读取异常 " + e.javaClass.simpleName
        }
    }

    private fun ts(): String = fmt.format(Date())
}
