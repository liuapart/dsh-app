# dsh-shell · dsh 原生壳应用（Android）

WebView 壳：加载 `https://<你的域名>/`，**自动应答 Basic 认证**（手机上零输入），
状态栏颜色**实时跟随页面主题色**。浏览器端行为不受任何影响。

## 构建

1. Android Studio（Koala/2024.1+，含 JDK 17）→ `Open` 选择本目录 → 等 Gradle sync 完成
2. `Build > Build Bundle(s)/APK(s) > Build APK(s)`，产物：
   `app/build/outputs/apk/debug/app-debug.apk`
3. 安装：数据线 `adb install app-debug.apk`，或把 APK 传到手机直接点开（允许未知来源）

命令行党：装好 SDK 后 `gradle assembleDebug`（Studio 可代生成 wrapper：`gradle wrapper`）。

## 首次使用

启动 → 弹一次原生登录框（用户名/密码）→ 之后 App 内**永远自动应答**，不再出现任何弹框。
凭证存在应用私有沙箱（`/data/data/cn.apanoo.dshshell/`），卸载即清除。

## 特性清单

| 特性 | 实现 |
|---|---|
| Basic 认证零输入 | `onReceivedHttpAuthRequest` → 存储凭证自动 `proceed()` |
| 状态栏跟随主题 | 读取页面 `meta[name=theme-color]`（dsh ThemePresenter 动态维护）+ MutationObserver，明暗切换实时变色；图标深浅自动反转 |
| 断网兜底 | 主帧失败 → 自动降级 `LOAD_CACHE_ELSE_NETWORK` 渲染缓存壳；再失败显示重试层 |
| 性能 | 渲染进程后台存活（`RENDERER_PRIORITY_IMPORTANT_ALWAYS`）、旋转/暗色切换不重建（manifest configChanges）、`onPause` 暂停页面定时器、进程回收后 `saveState/restoreState` 原地恢复 |
| 导航 | 返回键 = WebView 后退；下拉刷新已停用（聊天页内部滚动会被误判为刷新）；`target=_blank`/站外链接转交系统浏览器 |
| 兼容 | 文档启动前注入 AbortSignal.timeout/any polyfill（旧系统 WebView 发消息报错）|
| 应用内更新 | 启动时查 GitHub Release → 弹更新页 → 下载后自动拉起系统安装器（首次需允许"安装未知应用"）|
| 安全 | 非 HTTPS 内容禁止混合加载；`allowFileAccess/allowContentAccess` 关闭；凭证仅存应用私有目录 |

## 改站点/换域名

只动 `MainActivity.kt` 顶部 `BASE_HOST` / `BASE_URL` 两行常量。

## 已知边界

- 状态栏着色用的 `window.statusBarColor` 在 targetSdk 35+ 被 edge-to-edge 取代——
  本工程刻意 targetSdk 34 保留经典着色语义；未来升 35 需改为自绘 inset 背景或启用
  `isStatusBarColorEnabled` 新 API（工作量约半小时）。
- 页面若没输出 `meta[name=theme-color]`（如加载失败页），状态栏保持兜底色 `#151517`。
- 未处理文件下载（dsh 目前无下载能力）；需要时加 `DownloadListener` 即可。
