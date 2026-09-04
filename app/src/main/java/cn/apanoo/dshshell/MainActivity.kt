package cn.apanoo.dshshell

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Message
import android.view.MotionEvent
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
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat

class MainActivity : AppCompatActivity() {

    companion object {
        /** 目标站点：构建时注入（BuildConfig），源码不含任何域名信息。
         *  本地构建：local.properties 的 BASE_HOST/BASE_URL；
         *  CI 构建：Actions 变量/Secret 同名注入。 */
        val BASE_HOST: String = BuildConfig.BASE_HOST
        val BASE_URL: String = BuildConfig.BASE_URL

        /** 渲染进程崩溃时间戳（companion 级：recreate() 重建 Activity 后仍保留，用于防循环重建） */
        @Volatile
        var lastRenderGoneAt = 0L

        /** 页面上报主题色之前的兜底值（与 PWA manifest 的 theme_color 一致） */
        const val FALLBACK_THEME = "#151517"

        /** 亮色主题的兜底背景（与 dsh 亮色表面色一致，首帧/过滚动区域不再黑一块） */
        const val LIGHT_THEME = "#f6f8fa"

        /**
         * 壳端主题偏好持久化（v1.10.0；v1.10.1 系统信号改用 Android 真相）。机制：
         *  - 偏好存壳端 SharedPreferences（theme_pref: system/light/dark），
         *    不写服务端 settings.yaml——手机经 Caddy 属远程浏览器，dsh 本就不接受其写入；
         *  - JS 每次加载经同步桥 getPreference() 现读（下拉刷新 reload 不会拿到旧字面量）；
         *  - matchMedia shim：WebView 自报的 prefers-color-scheme 会撒谎（v1.10.0 实测
         *    系统亮色时报 dark，boot 脚本/ThemeRuntime/壳 JS 三方全被带偏且互相一致、
         *    无从纠偏），故以 Kotlin 侧 resources.configuration.uiMode（__DARK__ 字面量）
         *    劫持 matchMedia——页面里所有 prefers-color-scheme 查询统一按 Android 真相应答；
         *  - 文档启动即按壳端偏好纠正 html colorScheme 与 body[data-ds-dark-theme]，
         *    抢在服务端内嵌 boot 脚本（恒 light）与 ThemeRuntime 采纳之前；
         *  - 用户在页面里点主题切换（点击后 3s 内 body 明暗变化即视为用户意图）→
         *    推导 system/light/dark 上报 setPreference 持久化，传输层无关；
         *  - 其余不匹配（运行时采纳旧值等）一律纠正回壳端偏好；
         *  - 系统明暗切换（偏好=system）→ uiMode 变更 Activity 重建 → 整页带新字面量重载。
         */
        const val SHELL_THEME_JS =
            "(function(){var TRUE_DARK=__DARK__;" +
            "try{var _mm=window.matchMedia?window.matchMedia.bind(window):null;" +
            "window.matchMedia=function(q){" +
            "var r=_mm?_mm(q):{matches:false,addEventListener:function(){},removeEventListener:function(){},addListener:function(){},removeListener:function(){}};" +
            "if(q&&q.indexOf&&q.indexOf('prefers-color-scheme')>=0){" +
            "var wantDark=q.indexOf('dark')>=0;" +
            "var d={matches:wantDark?TRUE_DARK:!TRUE_DARK};" +
            "for(var k in r){if(!(k in d)){d[k]=(typeof r[k]==='function')?r[k].bind(r):r[k];}}" +
            "return d;}" +
            "return r;};}catch(e){}" +
            "var PREF='system';" +
            "try{PREF=DshTheme.getPreference()||'system';}catch(e){}" +
            "function sysd(){try{return !!matchMedia('(prefers-color-scheme: dark)').matches;}catch(e){return false;}}" +
            "function want(){return PREF==='dark'?true:PREF==='light'?false:sysd();}" +
            "var selfWrote=false,lastTap=0;" +
            "document.addEventListener('pointerdown',function(){lastTap=Date.now();},true);" +
            "function postBar(){try{DshTheme.post(getComputedStyle(document.body).backgroundColor);}catch(e){}}" +
            "function apply(){try{" +
            "var w=want();" +
            "document.documentElement.style.colorScheme=w?'dark':'light';" +
            "if(document.body){" +
            "if(document.body.hasAttribute('data-ds-dark-theme')!==w){selfWrote=true;document.body.toggleAttribute('data-ds-dark-theme',w);postBar();}" +
            "}" +
            "}catch(e){}}" +
            "apply();" +
            "function hook(){" +
            "if(!document.body)return;" +
            "apply();" +
            "new MutationObserver(function(){try{" +
            "if(selfWrote){selfWrote=false;return;}" +
            "var cur=document.body.hasAttribute('data-ds-dark-theme');" +
            "if(cur===want())return;" +
            "if(Date.now()-lastTap<3000){" +
            "var sd=sysd();" +
            "PREF=cur?(cur===sd?'system':'dark'):'light';" +
            "try{DshTheme.setPreference(PREF);}catch(e2){}" +
            "return;}" +
            "apply();" +
            "}catch(e){}}).observe(document.body,{attributes:true,attributeFilter:['data-ds-dark-theme']});}" +
            "if(document.readyState==='loading'){" +
            "var iv=setInterval(function(){if(document.body){clearInterval(iv);hook();}},4);" +
            "}else{hook();}" +
            "try{matchMedia('(prefers-color-scheme: dark)').addEventListener('change',function(){apply();postBar();});}catch(e){}" +
            "})();"

        /** 旧 WebView 的 AbortSignal 新静态方法 polyfill（文档启动前注入） */
        const val POLYFILL_JS =
            "if(window.AbortSignal){" +
            "if(!AbortSignal.timeout){AbortSignal.timeout=function(ms){var c=new AbortController();" +
            "setTimeout(function(){c.abort();},ms);return c.signal;};}" +
            "if(!AbortSignal.any){AbortSignal.any=function(sigs){var c=new AbortController();" +
            "sigs.forEach(function(s){if(s.aborted)c.abort();else s.addEventListener('abort',function(){c.abort();});});" +
            "return c.signal;};}}"

        // Session log 按钮紧凑化已迁入 dsh-web-kit 插件 v2.2.0（Web/壳双端一致，
        // 媒体查询驱动），壳内不再重复注入。

        /** 导出面板"从不弹出"方案（v1.6.0，jsdom 四场景验证通过）：
         *  初始加载后的新 body 子节点（React 传送门面板）在绘制前隐藏；
         *  内容翻转为错误时恢复显示。不碰 App 主容器（聊天文本含同关键词） */
        const val DIALOG_JS =
            "(function(){var initKids=null,hiddenEl=null,lastCount=0;" +
            "var SUCC=['已开始下载','正在下载 Session ZIP','download started','downloading the Session ZIP','正在导出 Session','Exporting Session'];" +
            "var ERR=['导出失败','export failed'];" +
            "function has(s,a){for(var i=0;i<a.length;i++)if(s.indexOf(a[i])>=0)return true;return false;}" +
            "function sweep(){try{" +
            "if(!document.body||!initKids)return;" +
            "var kids=document.body.children;" +
            "if(hiddenEl&&!hiddenEl.isConnected)hiddenEl=null;" +
            "for(var i=0;i<kids.length;i++){var el=kids[i];" +
            "if(initKids.indexOf(el)>=0)continue;" +
            "var tx=el.textContent||'';" +
            "if(has(tx,ERR)){if(el.style.display==='none'){el.style.display='';}if(hiddenEl===el)hiddenEl=null;continue;}" +
            "if(has(tx,SUCC)){if(el.style.display!=='none'){el.style.display='none';hiddenEl=el;}}}" +
            "}catch(e){}}" +
            "function init(){try{" +
            "initKids=Array.prototype.slice.call(document.body.children);" +
            "lastCount=initKids.length;" +
            "new MutationObserver(function(){var c=document.body.children.length;if(c!==lastCount||hiddenEl){lastCount=c;sweep();}}).observe(document.body,{childList:true,subtree:true});" +
            "}catch(e){}setInterval(sweep,1200);}" +
            "if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',init);}else{init();}})();"

        /** 版本角标（__VER__ 由 BuildConfig.VERSION_NAME 注入替换）。
         *  v1.7.1：锚点改为侧边栏底部"设置/Settings"行（⚙ 图标 + 文本定位，
         *  原 HARNESS wordmark 是 SVG 无文本导致失配）。绝对定位不占宽度，2s 自愈 */
        const val VERSION_JS =
            "(function(){var V='__VER__';" +
            "function f(){try{" +
            "if(document.getElementById('dsh-shell-ver'))return;" +
            "var leafs=document.querySelectorAll('span,div,p,a,button');" +
            "for(var i=0;i<leafs.length;i++){var el=leafs[i];" +
            "if(el.children.length>0)continue;" +
            "var tx=(el.textContent||'').trim();" +
            "if(tx!=='设置'&&tx!=='Settings')continue;" +
            "var row=el.parentElement,found=null;" +
            "for(var k=0;k<6&&row;k++){" +
            "var r=row.getBoundingClientRect?row.getBoundingClientRect():null;" +
            "if(row.querySelector&&row.querySelector('svg')&&r&&r.height>=28&&r.height<=80){found=row;break;}" +
            "row=row.parentElement;}" +
            "if(!found)continue;" +
            "var st=getComputedStyle(found);" +
            "if(st.position==='static')found.style.position='relative';" +
            "var b=document.createElement('span');b.id='dsh-shell-ver';b.textContent='v'+V;" +
            "b.style.cssText='position:absolute;right:16px;top:50%;transform:translateY(-50%);" +
            "font-size:10px;line-height:1;letter-spacing:.4px;opacity:.6;pointer-events:none;color:inherit;';" +
            "found.appendChild(b);return;}}catch(e){}}" +
            "if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',f);}else{f();}" +
            "setInterval(f,2000);})();"
    }

    private lateinit var auth: AuthStore
    private lateinit var webView: WebView
    private lateinit var swipe: TopZoneSwipeLayout
    private lateinit var errorOverlay: View

    /** 壳端持久化：主题偏好（theme_pref）等 */
    private val prefs by lazy { getSharedPreferences("dsh_shell", MODE_PRIVATE) }

    /** 壳端偏好解析为"此刻应否暗色"：dark→是，light→否，system→跟系统 uiMode */
    private fun shellPrefDark(): Boolean = when (prefs.getString("theme_pref", null)) {
        "dark" -> true
        "light" -> false
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    /** 断网降级只做一次，避免死循环 */
    private var cacheFallbackUsed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)   // 窗口背景已是主题色（themes.xml），无白闪

        applyStatusBar(if (shellPrefDark()) FALLBACK_THEME else LIGHT_THEME)  // 首帧即按壳端偏好着色
        window.decorView.setBackgroundColor(Color.parseColor(if (shellPrefDark()) FALLBACK_THEME else LIGHT_THEME))

        auth = AuthStore(this)
        swipe = findViewById(R.id.swipe)
        webView = findViewById(R.id.web)
        errorOverlay = findViewById(R.id.error_overlay)
        findViewById<Button>(R.id.retry).setOnClickListener { retry() }

        configureWebView()
        configureSwipe()
        setupBackGesture()

        // 应用内更新：注册下载完成接收器；延迟检查，先让 WebView 开始加载
        Updater.registerReceiver(this)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) Updater.check(this)
        }, 5_000L)

        if (savedInstanceState != null) webView.restoreState(savedInstanceState)  // 进程回收后原地恢复
        else if (BASE_URL.isNotEmpty()) webView.loadUrl(BASE_URL)
        else showError()   // 未配置 BASE_URL（构建时未注入）
    }

    // ---------- WebView 配置（性能相关都集中在这里） ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.setBackgroundColor(Color.parseColor(if (shellPrefDark()) FALLBACK_THEME else LIGHT_THEME))  // 加载/过滚动不闪异色
        // v1.10.0：显式关闭算法加暗。页面自带完整明暗主题，WebView 层再涂一遍只会
        // 得到反色式的假暗色，且加暗环境会把 prefers-color-scheme 求值为 dark，
        // 「跟随系统」就永远落在暗色（v1.9.x 用户实测）。targetSdk 34 下此开关
        // 是 WebView 加暗行为的唯一真源，显式 false 连 OEM 魔改也一并关掉。
        try {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        } catch (_: Exception) { }
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
        // origin 规则同时给出带/不带端口两种形态（站点是 https://host:8443，规则若不匹配则不会注入）
        val originRules = try {
            setOf("https://$BASE_HOST", "https://$BASE_HOST:8443")
        } catch (e: Exception) {
            setOf("https://$BASE_HOST")
        }
        // 版本角标：把壳版本号写入注入脚本（编译期常量替换，不经页面接口）
        val versionJs = VERSION_JS.replace("__VER__", BuildConfig.VERSION_NAME)
        // 主题：把 Android 真实 uiMode 解析结果烧进注入脚本（v1.10.1）——
        // WebView 自报的 prefers-color-scheme 会撒谎，页面侧统一以壳端真值为准
        val themeJs = SHELL_THEME_JS.replace("__DARK__", if (shellPrefDark()) "true" else "false")
        WebViewCompat.addDocumentStartJavaScript(webView, POLYFILL_JS + DIALOG_JS + versionJs + themeJs, originRules)

        // 页面触发的下载（Session log 等，多为 blob: 链接且需认证态）：
        // DownloadManager 无法携带 WebView 的认证/内存 blob，改用页面上下文 fetch → 桥接落盘
        webView.addJavascriptInterface(DownloadBridge(), "DshDownload")
        webView.setDownloadListener { url, _, contentDisposition, _, _ ->
            val name = Regex("filename\\*?=?\\s*\"?([^\";]+)\"?").find(contentDisposition ?: "")
                ?.groupValues?.get(1)?.trim()
                ?: url.substringAfterLast('/').substringBefore('?').ifEmpty { "dsh-download" }
            // 统一壳内下载（v1.5.0）：外部浏览器没有页面会话凭证会被 403。
            // 流程借鉴成熟 WebView 方案：页面上下文 fetch（自动带同源 Cookie/Basic 认证态）
            // → base64 过桥 → 原生 MediaStore 落盘；120s 超时防挂起。
            toast("开始下载：$name")
            val js = "(async function(u,n){try{" +
                "var ctrl=new AbortController();var to=setTimeout(function(){ctrl.abort();},120000);" +
                "var r=await fetch(u,{credentials:'include',signal:ctrl.signal});clearTimeout(to);" +
                "if(!r.ok)throw new Error('HTTP '+r.status);" +
                "var b=await r.arrayBuffer();var u8=new Uint8Array(b);var bin='';var CH=0x8000;" +
                "for(var i=0;i<u8.length;i+=CH){bin+=String.fromCharCode.apply(null,u8.subarray(i,i+CH));}" +
                "DshDownload.save(n,btoa(bin));}catch(e){try{DshDownload.fail(String((e&&e.name)||e));}catch(x){}}})(" +
                org.json.JSONObject.quote(url) + "," + org.json.JSONObject.quote(name) + ");"
            webView.evaluateJavascript(js, null)
        }

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
                // polyfill 兜底二次注入：即使文档启动注入的 origin 规则未匹配，
                // 这里也赶在用户点击"发送"之前补上（v1.2.0）
                view.evaluateJavascript(POLYFILL_JS, null)
            }

            /**
             * 渲染进程崩溃接管（v1.9.2）：截图/大图粘贴等场景会让 Chromium 渲染进程
             * 崩溃或 OOM。若不处理 onRenderProcessGone，系统会直接杀掉宿主 app（表现为
             * "崩溃退出"）。这里接管后重建界面恢复；返回 true 表示已处理。
             */
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                val now = android.os.SystemClock.elapsedRealtime()
                val reason = if (detail.didCrash()) "渲染进程崩溃" else "渲染进程被系统回收"
                if (now - lastRenderGoneAt < 10_000) {
                    // 10 秒内再次崩溃：不再自动重建（避免死循环），落错误页让用户手动重试
                    toast("$reason，界面恢复失败，请点下方重试")
                    runOnUiThread { showError() }
                    return true
                }
                lastRenderGoneAt = now
                toast("$reason，正在恢复界面…")
                // renderer 死亡后同一 WebView 实例不可复用，整体重建最稳
                runOnUiThread { recreate() }
                return true
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
                toast("外链：转交系统浏览器")   // v1.3.2 诊断③：区分下载 vs 新窗口路径
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
        // Chrome 式下拉刷新：仅当手势【起点】落在屏幕靠上区域时触发（topZonePx），
        // 其余区域的手势完全交给页面滚动（v1.1.0 曾全局关闭，v1.2.0 按触点位置放行）
        swipe.topZonePx = (120 * resources.displayMetrics.density).toInt()
        swipe.setOnChildScrollUpCallback { _, _ -> false }   // 不再按滚动位置拦截 = 顶部区域随时可强制刷新
        swipe.setColorSchemeColors(Color.parseColor(FALLBACK_THEME))
        swipe.setOnRefreshListener {
            // 强制刷新只刷新页面，不再访问 GitHub，避免刷新手势干扰页面加载
            webView.reload()
        }
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

        /** 壳端主题偏好（JS 文档启动时同步读取；SharedPreferences 读线程安全） */
        @JavascriptInterface
        fun getPreference(): String = prefs.getString("theme_pref", null) ?: "system"

        /** 用户在页面里改主题 → JS 上报意图 → 壳端持久化（不写服务端 settings.yaml） */
        @JavascriptInterface
        fun setPreference(pref: String?) {
            when (pref) {
                "system", "light", "dark" ->
                    prefs.edit().putString("theme_pref", pref).apply()
            }
        }
    }

    /** 页面下载落盘：接收 JS fetch 转 base64 的文件数据，写入系统"下载"目录并自动打开 */
    inner class DownloadBridge {
        @JavascriptInterface
        fun save(name: String?, base64: String?) {
            val data = try {
                android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            } catch (e: Exception) { null }
            if (data == null) { toast("下载失败：数据解码错误"); return }
            val safe = (name ?: "dsh-download").replace(Regex("[\\\\/:*?\"<>|]"), "_")
            var openUri: android.net.Uri? = null
            val saved = try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val cv = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safe)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip")
                        put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = contentResolver
                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                    resolver.openOutputStream(uri)!!.use { it.write(data) }
                    cv.clear(); cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, cv, null, null)
                    openUri = uri
                    "系统下载目录/$safe"
                } else {
                    val f = java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), safe)
                    f.writeBytes(data)
                    f.absolutePath
                }
            } catch (e: Exception) { null }
            if (saved == null) { toast("保存失败"); return }
            toast("已保存：$saved")
            // 自动打开（文件管理器/解压工具），省去翻目录
            openUri?.let { u ->
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(u, "application/zip")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                } catch (e: Exception) { /* 没有可打开的应用则停留在 Toast 提示 */ }
            }
        }

        @JavascriptInterface
        fun fail(msg: String?) {
            val m = when (msg) {
                "AbortError" -> "下载超时（120s）"
                "TypeError" -> "网络错误"
                else -> msg ?: "未知错误"
            }
            toast("下载失败：$m")
        }
    }

    private fun toast(msg: String) = runOnUiThread {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun applyStatusBar(colorHex: String) {
        val c = parsePageColor(colorHex) ?: parsePageColor(FALLBACK_THEME)!!
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

    /**
     * 解析页面上报的颜色。ThemePresenter 写入的是 getComputedStyle 的
     * backgroundColor（rgb/rgba 格式，如 "rgb(246, 248, 250)"），
     * Color.parseColor 不认识它（v1.2.1 修复：亮色主题状态栏恒黑）。
     */
    private fun parsePageColor(value: String): Int? {
        return try {
            Color.parseColor(value)
        } catch (e: Exception) {
            val m = Regex("rgba?\\(\\s*(\\d+)[,\\s]+(\\d+)[,\\s]+(\\d+)(?:[,\\s]+([\\d.]+))?\\s*\\)").find(value)
            m?.let {
                val alpha = (it.groupValues.getOrNull(4)?.toFloatOrNull() ?: 1f).coerceIn(0f, 1f)
                Color.argb(
                    (alpha * 255).toInt(),
                    it.groupValues[1].toInt(),
                    it.groupValues[2].toInt(),
                    it.groupValues[3].toInt()
                )
            }
        }
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

/**
 * 顶部区域下拉刷新布局（Chrome 式）：
 * 仅当手势起点落在屏幕顶部 topZonePx 范围内才启用下拉刷新；
 * 其他位置的手势不拦截，完全交给 WebView 页面滚动。
 */
class TopZoneSwipeLayout @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {

    var topZonePx = 0

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN && topZonePx > 0 && ev.y > topZonePx) {
            return false   // 起点在顶部区域之外：本次手势不参与刷新判定
        }
        return super.onInterceptTouchEvent(ev)
    }
}
