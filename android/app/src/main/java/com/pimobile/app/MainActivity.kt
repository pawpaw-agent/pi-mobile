package com.pimobile.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var connectView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("pi-mobile", Context.MODE_PRIVATE)

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // WebView fills entire screen
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                userAgentString = settings.userAgentString.replace(
                    "Android", "PiMobile/1.0 Android"
                )
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    applyFullscreen()
                }
            }
        }
        root.addView(webView)

        // Connect screen (initially hidden if URL saved)
        connectView = createConnectView(prefs)
        root.addView(connectView)

        setContentView(root)
        applyFullscreen()

        val savedUrl = prefs.getString("url", null)
        if (savedUrl != null) {
            connectView?.visibility = View.GONE
            webView?.loadUrl(savedUrl)
        }
    }

    private fun createConnectView(prefs: android.content.SharedPreferences): View {
        val bg = 0xFF1A1A2E.toInt()
        val accent = 0xFFE94560.toInt()

        val wrapper = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(bg)
        }

        val column = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = android.view.Gravity.CENTER }
            orientation = LinearLayout.VERTICAL
        }
        wrapper.addView(column)

        val title = TextView(this).apply {
            text = "π Pi Mobile"
            textSize = 28f
            setTextColor(0xFF0F3460.toInt())
        }
        column.addView(title)

        val subtitle = TextView(this).apply {
            text = "Connect to pi-web on your laptop"
            textSize = 14f
            setTextColor(0xB3FFFFFF.toInt())
        }
        column.addView(subtitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { topMargin = dp(8) })

        // Spacer
        column.addView(createSpacer(dp(32)))

        val hostInput = EditText(this).apply {
            hint = "100.x.x.x or hostname.ts.net"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x80FFFFFF.toInt())
            setBackgroundColor(0x33FFFFFF.toInt())
        }
        column.addView(hostInput, LinearLayout.LayoutParams(
            dp(300), ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { topMargin = dp(12) })

        val portInput = EditText(this).apply {
            hint = "30142"
            setText("30142")
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x80FFFFFF.toInt())
            setBackgroundColor(0x33FFFFFF.toInt())
        }
        column.addView(portInput, LinearLayout.LayoutParams(
            dp(300), ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { topMargin = dp(12) })

        column.addView(createSpacer(dp(24)))

        val connectBtn = Button(this).apply {
            text = "Connect"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(accent)
            setOnClickListener {
                val host = hostInput.text.toString().trim()
                if (host.isBlank()) return@setOnClickListener
                val port = portInput.text.toString().trim().ifEmpty { "30142" }
                val url = "http://$host:$port"
                connectView?.visibility = View.GONE
                prefs.edit().putString("url", url).apply()
                webView?.loadUrl(url)
                applyFullscreen()
            }
        }
        column.addView(connectBtn, LinearLayout.LayoutParams(
            dp(300), dp(48)
        ).also { topMargin = dp(12) })

        val footer = TextView(this).apply {
            text = "Make sure pi-web is running on your laptop:\ncd pi-mobile && node server/index.js"
            textSize = 12f
            setTextColor(0x80FFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
        }
        column.addView(footer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { topMargin = dp(24) })

        return wrapper
    }

    private fun createSpacer(h: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, h)
        }
    }

    private fun dp(n: Int): Int {
        val scale = resources.displayMetrics.density
        return (n * scale + 0.5f).toInt()
    }

    private fun applyFullscreen() {
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
}
