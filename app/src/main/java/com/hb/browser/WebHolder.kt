package com.hb.browser

import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

interface UiListener {
    fun onUrl(url: String)
    fun onProgress(p: Int)
    fun onShowCustom(v: View, cb: WebChromeClient.CustomViewCallback)
    fun onHideCustom()
}

/** Screen off / app background hole-o WebView ke "visible" dhore rakhe, jate video na thame. */
class HBWebView(ctx: Context) : WebView(ctx) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.GONE) super.onWindowVisibilityChanged(View.VISIBLE)
    }
}

object WebHolder {
    const val HOME = "https://m.youtube.com"

    var webView: WebView? = null
    var ui: UiListener? = null
    @Volatile var playing = false
    @Volatile var title = "HB_Browser"

    private val main = Handler(Looper.getMainLooper())

    private const val HOOK_JS = """
(function(){
 if(window.__hbHooked)return; window.__hbHooked=true;
 function def(o,k,v){try{Object.defineProperty(o,k,{get:function(){return v},configurable:true})}catch(e){}}
 def(document,'hidden',false); def(document,'webkitHidden',false);
 def(document,'visibilityState','visible'); def(document,'webkitVisibilityState','visible');
 document.hasFocus=function(){return true};
 ['visibilitychange','webkitvisibilitychange','blur'].forEach(function(n){
   document.addEventListener(n,function(e){e.stopImmediatePropagation()},true);
   window.addEventListener(n,function(e){e.stopImmediatePropagation()},true);
 });
 function report(){try{
   var v=document.querySelector('video');
   HBAndroid.onState(!!v&&!v.paused&&!v.ended,document.title||'');
 }catch(e){}}
 ['play','pause','ended','playing'].forEach(function(n){document.addEventListener(n,report,true)});
 setInterval(report,2000);
})();
"""

    fun get(activity: Activity): WebView {
        webView?.let {
            (it.context as MutableContextWrapper).baseContext = activity
            return it
        }
        val wv = HBWebView(MutableContextWrapper(activity))
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.addJavascriptInterface(Bridge(), "HBAndroid")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                return !(u.startsWith("http://") || u.startsWith("https://"))
            }
            override fun onPageStarted(v: WebView, url: String, f: Bitmap?) {
                ui?.onUrl(url); v.evaluateJavascript(HOOK_JS, null)
            }
            override fun onPageFinished(v: WebView, url: String) {
                v.evaluateJavascript(HOOK_JS, null)
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) { ui?.onProgress(p) }
            override fun onShowCustomView(view: View, cb: CustomViewCallback) { ui?.onShowCustom(view, cb) }
            override fun onHideCustomView() { ui?.onHideCustom() }
            override fun getDefaultVideoPoster(): Bitmap =
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        webView = wv
        return wv
    }

    fun detach(app: Context) {
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            (it.context as MutableContextWrapper).baseContext = app.applicationContext
        }
        ui = null
    }

    fun shutdown() {
        webView?.apply {
            loadUrl("about:blank")
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        playing = false
        title = "HB_Browser"
    }

    private fun js(code: String) = main.post { webView?.evaluateJavascript("(function(){$code})();", null) }

    fun setPlaying(play: Boolean) {
        js("var v=document.querySelector('video'); if(v){ " + (if (play) "v.play()" else "v.pause()") + " }")
        playing = play
        PlaybackService.instance?.refresh()
    }

    fun next() = js(
        """var b=document.querySelector('.ytp-next-button,button[aria-label="Next video"],button[aria-label="Next"]');
           if(b){b.click();return;}
           var v=document.querySelector('video'); if(v&&isFinite(v.duration)){v.currentTime=v.duration;}"""
    )

    fun prev() = js(
        """var b=document.querySelector('.ytp-prev-button,button[aria-label="Previous video"],button[aria-label="Previous"]');
           if(b&&getComputedStyle(b).display!=='none'){b.click();return;}
           var v=document.querySelector('video'); if(v&&v.currentTime>5){v.currentTime=0;}else{history.back();}"""
    )

    private fun onState(p: Boolean, t: String) {
        val clean = t.removeSuffix(" - YouTube").trim().ifBlank { "HB_Browser" }
        if (p != playing || clean != title) {
            playing = p
            title = clean
            PlaybackService.instance?.refresh()
        }
    }

    private class Bridge {
        @JavascriptInterface
        fun onState(playing: Boolean, title: String) {
            main.post { WebHolder.onState(playing, title) }
        }
    }
}
