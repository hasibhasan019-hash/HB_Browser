package com.hb.browser

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar

class MainActivity : Activity(), UiListener {

    companion object { const val EXTRA_EXIT = "exit" }

    private lateinit var container: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var web: WebView
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_EXIT, false)) { exitApp(); return }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        buildUi()
        web = WebHolder.get(this)
        WebHolder.ui = this
        (web.parent as? ViewGroup)?.removeView(web)
        container.addView(web, FrameLayout.LayoutParams(-1, -1))

        startForegroundService(Intent(this, PlaybackService::class.java))

        val target = intent.data?.toString()
        when {
            target != null -> web.loadUrl(target)
            web.url == null -> web.loadUrl(WebHolder.HOME)
            else -> urlBar.setText(web.url)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val home = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_compass)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener { web.loadUrl(WebHolder.HOME) }
        }
        urlBar = EditText(this).apply {
            setSingleLine()
            hint = "Search or type URL"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { v, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO) {
                    load(v.text.toString())
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                    true
                } else false
            }
        }
        bar.addView(home, LinearLayout.LayoutParams(dp(40), dp(40)))
        bar.addView(urlBar, LinearLayout.LayoutParams(0, -2, 1f))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
        }
        container = FrameLayout(this)

        root.addView(bar, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        root.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun load(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(" ") && t.contains(".") -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        web.loadUrl(url)
    }

    override fun onUrl(url: String) { if (!urlBar.hasFocus()) urlBar.setText(url) }

    override fun onProgress(p: Int) {
        progress.progress = p
        progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
    }

    @Suppress("DEPRECATION")
    override fun onShowCustom(v: View, cb: WebChromeClient.CustomViewCallback) {
        customView = v; customCb = cb
        (window.decorView as FrameLayout).addView(v, FrameLayout.LayoutParams(-1, -1))
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    @Suppress("DEPRECATION")
    override fun onHideCustom() {
        customView?.let { (window.decorView as FrameLayout).removeView(it) }
        customView = null
        customCb?.onCustomViewHidden(); customCb = null
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_EXIT, false)) { exitApp(); return }
        intent.data?.let { web.loadUrl(it.toString()) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> onHideCustom()
            ::web.isInitialized && web.canGoBack() -> web.goBack()
            else -> moveTaskToBack(true)
        }
    }

    private fun exitApp() {
        WebHolder.shutdown()
        stopService(Intent(this, PlaybackService::class.java))
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        if (::container.isInitialized && ::web.isInitialized) {
            container.removeView(web)
            WebHolder.detach(this)
        }
        super.onDestroy()
    }
}
