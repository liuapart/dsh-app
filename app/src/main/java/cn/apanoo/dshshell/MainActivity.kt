package cn.apanoo.dshshell

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Message
import android.view.View
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat

class MainActivity : AppCompatActivity() {

    companion object {
        /** 目标站点：构建时注入（BuildConfig），源码不含任何域名信息。
         *  本地构建：local.properties 的 BASE_HOST/BASE_URL；
         *  CI 构建：Actions 变量/Secret 同名注入。 */
        val BASE_HOST: String = BuildConfig.BASE_HOST
        val BASE_URL: String = BuildConfig.BASE_URL

        /** 页面上报主题色之前的兜底值（与 PWA manifest 的 theme_color 一致） */
        const val FALLBACK_THEME = "#151517"

        /** 旧 WebView 的 AbortSignal 新静态方法 polyfill（文档启动前注入） */
        const val POLYFILL_JS =
            "if(window.AbortSignal){" +
            "if(!AbortSignal.timeout){AbortSignal.timeout=function(ms){var c=new AbortController();" +
            "setTimeout(function(){c.abort();},ms);return c.signal;};}" +
            "if(!AbortSignal.any){AbortSignal.any=function(sigs){var c=new AbortController();" +
            "sigs.forEach(function(s){if(s.aborted)c.abort();else s.addEventListener('abort',function(){c.abort();});});" +
            "return c.signal;};}}"
    }

    private lateinit var auth: AuthStore
    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var errorOverlay: View

    /** 断网降级只做一次，避免死循环 */
    private var cacheFallbackUsed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)   // 窗口背景已是主题色（themes.xml），无白闪

        applyStatusBar(FALLBACK_THEME)           // 首帧即着色，不等页面上报

        auth = AuthStore(this)
        swipe = findViewById(R.id.swipe)
        webView = findViewById(R.id.web)
        errorOverlay = findViewById(R.id.error_overlay)
        findViewById<Button>(R.id.retry).setOnClickListener { retry() }

        configureWebView()
        configureSwipe()
        setupBackGesture()

        // 应用内更新：注册下载完成接收器 + 启动检查
        Updater.registerReceiver(this)
        Updater.check(this)

        if (savedInstanceState != null) webView.restoreState(savedInstanceState)  // 进程回收后原地恢复
        else if (BASE_URL.isNotEmpty()) webView.loadUrl(BASE_URL)
        else showError()   // 未配置 BASE_URL（构建时未注入）
    }

    // ---------- WebView 配置（性能相关都集中在这里） ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.setBackgroundColor(Color.parseColor(FALLBACK_THEME))   // 避免暗色主题下加载白闪
        // 短暂切后台不杀渲染进程：IMPORTANT 且不在不可见时降级（waivedWhenNotVisible=false）
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true                     // localStorage：web-kit 位置记忆依赖
            cacheMode = WebSettings.LOAD_DEFAULT         // HTTP 缓存按响应头走（同 Chrome 语义）
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false                      // 壳只需要 https 站点，收窄攻击面
            allowContentAccess = false
            setSupportMultipleWindows(true)              // target=_blank 走 onCreateWindow
            javaScriptCanOpenWindowsAutomatically = true
        }
        webView.addJavascriptInterface(ThemeBridge(), "DshTheme")

        // 文档启动前注入 polyfill：旧系统 WebView 缺新 API（AbortSignal.timeout/any），
        // 页面发消息会报 "AbortSignal.timeout is not a function"（v1.1.0 用户反馈）。
        // addDocumentStartJavaScript 保证先于页面任何脚本执行。
        WebViewCompat.addDocumentStartJavaScript(webView, POLYFILL_JS, setOf("https://$BASE_HOST"))

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                // 站内留在壳内；站外链接（聊天外链等）交给系统浏览器
                if (host == BASE_HOST || host.endsWith(".$BASE_HOST")) return false
                return openExternally(request.url)
            }

            // 核心：Basic 认证自动应答——浏览器弹框的环节在这里被原生接管
            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                answerAuth(handler)
            }

            override fun onPageFinished(view: WebView, url: String) {
                swipe.isRefreshing = false
                injectThemeObserver()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                if (!cacheFallbackUsed) {
                    // 断网兜底：降级用 HTTP 缓存渲染壳（静态骨架秒开），数据区由页面自行提示
                    cacheFallbackUsed = true
                    webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    webView.reload()
                } else {
                    showError()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // target=_blank：借临时 WebView 捕获目标 URL，交系统浏览器（不把壳带离 dsh）
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val temp = WebView(view.context)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = openExternally(r.url)
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = temp
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    private fun configureSwipe() {
        // 关闭下拉刷新：聊天页在内部容器滚动，WebView 的 scrollY 恒为 0，
        // SwipeRefreshLayout 会把"查看历史"的下滑手势误判为刷新（v1.1.0 用户反馈）
        swipe.isEnabled = false
        swipe.setColorSchemeColors(Color.parseColor(FALLBACK_THEME))
        swipe.setOnRefreshListener { webView.reload() }
    }

    private fun setupBackGesture() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    // ---------- Basic 认证 ----------

    private fun answerAuth(handler: HttpAuthHandler) {
        val saved = auth.load()
        if (saved != null) {
            handler.proceed(saved.first, saved.second)
            return
        }
        // 首次：原生对话框采集一次，存入应用私有沙箱；此后全部自动应答
        val v = layoutInflater.inflate(R.layout.dialog_auth, null)
        val userEt = v.findViewById<EditText>(R.id.auth_user)
        val passEt = v.findViewById<EditText>(R.id.auth_pass)
        AlertDialog.Builder(this)
            .setTitle("登录 dsh")
            .setView(v)
            .setPositiveButton("登录") { _, _ ->
                val u = userEt.text.toString()
                val p = passEt.text.toString()
                auth.save(u, p)
                handler.proceed(u, p)
            }
            .setNegativeButton("取消") { _, _ -> handler.cancel() }
            .setCancelable(false)
            .show()
    }

    // ---------- 主题色同步：状态栏跟随页面真实主题 ----------

    /**
     * 页面侧注入：dsh 官方 ThemePresenter 会动态维护 meta[name=theme-color]（明/暗主题）。
     * 这里读取它 + 挂 MutationObserver，主题一变就经 JS 桥上报，状态栏实时跟随。
     */
    private fun injectThemeObserver() {
        val js = """
        (function(){
          function post(){
            var m=document.querySelector('meta[name="theme-color"]');
            DshTheme.post(m&&m.getAttribute('content')||'$FALLBACK_THEME');
          }
          post();
          if(!document.head) return;
          new MutationObserver(post).observe(document.head,
            {childList:true,subtree:true,attributes:true,attributeFilter:['content']});
        })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class ThemeBridge {
        @JavascriptInterface
        fun post(color: String?) {
            runOnUiThread { if (!color.isNullOrEmpty()) applyStatusBar(color) }
        }
    }

    private fun applyStatusBar(colorHex: String) {
        val c = try { Color.parseColor(colorHex) } catch (e: Exception) { Color.parseColor(FALLBACK_THEME) }
        @Suppress("DEPRECATION")
        window.statusBarColor = c
        @Suppress("DEPRECATION")
        window.navigationBarColor = c
        // 亮色背景配深色图标，暗色背景配浅色图标（跟随页面主题明暗）
        val light = ColorUtils.calculateLuminance(c) > 0.5
        val ctr = WindowInsetsControllerCompat(window, window.decorView)
        ctr.isAppearanceLightStatusBars = light
        ctr.isAppearanceLightNavigationBars = light
    }

    // ---------- 错误兜底 ----------

    private fun showError() { errorOverlay.visibility = View.VISIBLE }

    private fun retry() {
        errorOverlay.visibility = View.GONE
        cacheFallbackUsed = false
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.reload()
    }

    private fun openExternally(url: android.net.Uri): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, url))
            true
        } catch (e: Exception) { false }
    }

    // ---------- 生命周期 ----------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() { webView.onPause(); super.onPause() }   // 暂停页面定时器，省电
    override fun onResume() { super.onResume(); webView.onResume() }
}
