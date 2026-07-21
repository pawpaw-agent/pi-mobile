package com.pimobile.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class MainActivity : Activity() {
    private var webView: WebView? = null
    private var connectView: View? = null
    private var errorView: View? = null
    private var lastUrl: String? = null

    private companion object {
        const val COL_BG = 0xFF1A1A2E.toInt()
        const val COL_ACCENT = 0xFFE94560.toInt()
        const val COL_TITLE = 0xFF0F3460.toInt()
        const val COL_TEXT = 0xFFFFFFFF.toInt()
        const val COL_HINT = 0x80FFFFFF.toInt()
        const val COL_INPUT_BG = 0x33FFFFFF.toInt()
        const val COL_MUTED = 0xB3FFFFFF.toInt()
        const val COL_DIM = 0x80FFFFFF.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("pi-mobile", Context.MODE_PRIVATE)
        val app = application as PiApp

        val root = FrameLayout(this).apply {
            setBackgroundColor(COL_BG)
        }

        // ── 复用 Application 级单例 WebView ──────────────────────────────
        // 进程存活期间 WebView 跨 Activity 存活（转屏 configChanges 不重建 + 最近任务返回复用）。
        // 关键：只在 currentUrl 为空时才 loadUrl，避免覆盖上次的页面状态。
        val reused = app.retainedWebView != null
        webView = (app.retainedWebView ?: WebView(this).also { app.retainedWebView = it }).apply {
            (parent as? ViewGroup)?.removeView(this)
            if (!reused) {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    builtInZoomControls = true
                    displayZoomControls = false
                    setSupportZoom(true)
                    userAgentString = settings.userAgentString.replace("Android", "PiMobile/1.0 Android")
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }
                webViewClient = object : WebViewClient() {
                    // 加载成功隐藏错误页
                    override fun onPageFinished(view: WebView?, url: String?) {
                        errorView?.visibility = View.GONE
                    }
                    // 网络/资源错误（DNS、连接拒绝、超时）
                    override fun onReceivedError(
                        view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                    ) {
                        // 只对主帧错误显示错误页，避免子资源 404 也弹出
                        if (request?.isForMainFrame == true) showErrorPage()
                    }
                    // HTTP 错误码（4xx/5xx）
                    override fun onReceivedHttpError(
                        view: WebView?, request: WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?
                    ) {
                        if (request?.isForMainFrame == true) showErrorPage()
                    }
                }
            }
        }
        root.addView(webView)

        connectView = createConnectView(prefs)
        root.addView(connectView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        setContentView(root)

        // Android 13+ 需运行时申请通知权限（后台任务完成通知依赖）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val savedUrl = prefs.getString("url", null)
        val currentUrl = webView?.url
        val needLoad = currentUrl.isNullOrBlank() && savedUrl != null
        Log.d("PiMobile", "onCreate: reused=$reused currentUrl=$currentUrl needLoad=$needLoad")
        if (needLoad && savedUrl != null) {
            connectView?.visibility = View.GONE
            lastUrl = savedUrl
            webView?.loadUrl(savedUrl)
        } else if (reused && !currentUrl.isNullOrBlank()) {
            connectView?.visibility = View.GONE
        }

        // 前后台检测：退到后台时启动 SSE 服务，回到前台时停止
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                Log.d("PiMobile", "app→background: start SSE service")
                PiSseService.start(this@MainActivity)
            }
            override fun onStart(owner: LifecycleOwner) {
                Log.d("PiMobile", "app→foreground: stop SSE service")
                PiSseService.stop(this@MainActivity)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.resumeTimers()
    }

    // 处理通知点击的 deep link：pi-mobile://session/<id> → baseUrl/?session=<id>
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val sid = intent.data?.lastPathSegment
        if (sid != null) {
            val base = getSharedPreferences("pi-mobile", Context.MODE_PRIVATE).getString("url", null)
            if (!base.isNullOrBlank()) {
                val url = base.trimEnd('/') + "/?session=" + sid
                lastUrl = url
                webView?.loadUrl(url)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 暂停定时器省电；SSE 连接本身不断（由后台通知方案的 FGS 接管）
        webView?.onPause()
        webView?.pauseTimers()
    }

    private fun createConnectView(prefs: android.content.SharedPreferences): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        val wrapper = FrameLayout(this).apply {
            setBackgroundColor(COL_BG)
            addView(column)
        }

        column.addView(TextView(this).apply {
            text = "π Pi Mobile"; textSize = 28f; setTextColor(COL_TITLE)
        })
        column.addView(TextView(this).apply {
            text = "Connect to pi-web on your laptop"; textSize = 14f; setTextColor(COL_MUTED)
        }, rowParams(top = dp(8)))

        column.addView(spacer(dp(32)))

        // 协议选择（http / https），支持 HTTPS 隧道场景
        val httpBtn = RadioButton(this).apply { id = View.generateViewId(); text = "http"; setTextColor(COL_TEXT) }
        val httpsBtn = RadioButton(this).apply { id = View.generateViewId(); text = "https"; setTextColor(COL_TEXT) }
        val protocolGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(httpBtn); addView(httpsBtn)
            check(httpBtn.id)
        }
        column.addView(protocolGroup, rowParams(top = dp(8), width = dp(300)))

        val hostInput = column.addEditText("100.x.x.x or hostname.ts.net")
        val portInput = column.addEditText("7777", "7777")
        column.addView(spacer(dp(24)))

        column.addView(Button(this).apply {
            text = "Connect"; setTextColor(COL_TEXT); setBackgroundColor(COL_ACCENT)
            setOnClickListener {
                val host = hostInput.text.toString().trim()
                if (host.isBlank()) return@setOnClickListener
                val port = portInput.text.toString().trim().ifEmpty { "7777" }
                val proto = if (protocolGroup.checkedRadioButtonId == httpsBtn.id) "https" else "http"
                val url = "$proto://$host:$port"
                connectView?.visibility = View.GONE
                prefs.edit().putString("url", url).apply()
                lastUrl = url
                webView?.loadUrl(url)
            }
        }, rowParams(top = dp(12), height = dp(48), width = dp(300)))

        column.addView(TextView(this).apply {
            text = "Enter your pi-web server address to connect"
            textSize = 12f; setTextColor(COL_DIM); gravity = Gravity.CENTER
        }, rowParams(top = dp(24)))
        return wrapper
    }

    // ── 小工具 ──────────────────────────────────────────────────────
    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, h) }

    private fun LinearLayout.addEditText(hint: String, default: String? = null): EditText =
        EditText(this.context).apply {
            this.hint = hint
            setTextColor(COL_TEXT); setHintTextColor(COL_HINT); setBackgroundColor(COL_INPUT_BG)
            default?.let { setText(it) }
        }.also { addView(it, rowParams(top = dp(12), width = dp(300))) }

    private fun rowParams(top: Int = 0, width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                          height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(width, height).apply { topMargin = top }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()

    private fun showErrorPage() {
        if (errorView == null) {
            errorView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(COL_BG)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(TextView(this@MainActivity).apply {
                    text = "连不上 pi-web"; textSize = 20f; setTextColor(COL_TEXT)
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@MainActivity).apply {
                    text = "检查地址、端口和网络后重试"; textSize = 14f; setTextColor(COL_MUTED)
                    gravity = Gravity.CENTER
                }, rowParams(top = dp(8)))
                addView(Button(this@MainActivity).apply {
                    text = "重试"; setTextColor(COL_TEXT); setBackgroundColor(COL_ACCENT)
                    setOnClickListener {
                        errorView?.visibility = View.GONE
                        lastUrl?.let { webView?.loadUrl(it) }
                    }
                }, rowParams(top = dp(24), height = dp(48), width = dp(160)))
            }
            (webView?.parent as? ViewGroup)?.addView(errorView)
        }
        errorView?.visibility = View.VISIBLE
    }

    private fun applyFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    /**
     * 返回键：连接屏可见→退后台；WebView 有历史→goBack；否则退后台保活。
     * 用平台 onBackPressed（无 androidx.activity 依赖，契合纯 WebView 壳）。
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            connectView?.visibility == View.VISIBLE -> moveTaskToBack(true)
            webView?.canGoBack() == true -> webView?.goBack()
            else -> moveTaskToBack(true)
        }
    }
}
