package org.zotweb.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ZOTERO_URL = "https://www.zotero.org/mylibrary"
        private const val ZOTERO_HOST = "zotero.org"
        private const val STORAGE_PERMISSION_REQUEST = 1002
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: PendingDownload? = null

    private val fileChooserLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { arrayOf(it) }
            } else null
            fileUploadCallback?.onReceiveValue(data)
            fileUploadCallback = null
        }

    private data class PendingDownload(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val mimeType: String
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipeRefresh = findViewById(R.id.swipe_refresh)
        webView = findViewById(R.id.webview)

        setupCookies()
        setupWebView()
        setupDownloads()
        setupSwipeRefresh()

        setupBackNavigation()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(ZOTERO_URL)
        }
    }

    private fun setupCookies() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        // Flush cookies to persistent storage frequently
        cookieManager.flush()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true

            // Cache settings for offline support and performance
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)

            // Allow file access
            allowFileAccess = true
            allowContentAccess = true

            // Modern web features
            javaScriptCanOpenWindowsAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            // Use a desktop user agent so Zotero serves the full web library
            // instead of the mobile/touch-friendly version
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 ZotWeb/1.0"

            // Mixed content (some Zotero resources may need this)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Spoof as non-touch device before page scripts run, so Zotero
                // serves the full desktop web library instead of the touch UI
                injectDesktopMode(view)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                val host = request.url.host ?: ""

                // Keep Zotero URLs inside the app
                if (host.contains(ZOTERO_HOST)) {
                    return false
                }

                // Open external links in the system browser
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    // No browser available, load in WebView
                    return false
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Persist cookies after each page load
                CookieManager.getInstance().flush()
                swipeRefresh.isRefreshing = false
                // Intercept blob creation so we can download even after URL.revokeObjectURL
                injectBlobInterceptor(view)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Handle file uploads (e.g., attaching files in Zotero)
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = fileChooserParams?.createIntent()
                if (intent != null) {
                    try {
                        fileChooserLauncher.launch(intent)
                    } catch (_: Exception) {
                        fileUploadCallback?.onReceiveValue(null)
                        fileUploadCallback = null
                        return false
                    }
                }
                return true
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupDownloads() {
        webView.addJavascriptInterface(BlobDownloadInterface(), "ZotWebBlobDownload")

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:")) {
                handleBlobDownload(url, contentDisposition, mimeType)
            } else {
                if (needsStoragePermission()) {
                    pendingDownload = PendingDownload(url, userAgent, contentDisposition, mimeType)
                    requestStoragePermission()
                } else {
                    startHttpDownload(url, userAgent, contentDisposition, mimeType)
                }
            }
        }
    }

    private fun injectDesktopMode(view: WebView?) {
        // Hide touch capabilities so Zotero's web library shows the desktop UI.
        // Must run before page scripts execute (called from onPageStarted).
        val js = """
            (function() {
                // Remove touch event support detection
                delete window.ontouchstart;
                Object.defineProperty(window, 'ontouchstart', {
                    get: function() { return undefined; },
                    set: function() {},
                    configurable: true
                });
                // Report zero touch points
                Object.defineProperty(navigator, 'maxTouchPoints', {
                    get: function() { return 0; },
                    configurable: true
                });
                // Override matchMedia for touch queries
                var origMatchMedia = window.matchMedia.bind(window);
                window.matchMedia = function(query) {
                    if (query.includes('pointer: coarse') || query.includes('hover: none')) {
                        return { matches: false, media: query, addListener: function(){}, removeListener: function(){}, addEventListener: function(){}, removeEventListener: function(){}, dispatchEvent: function(){} };
                    }
                    return origMatchMedia(query);
                };
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun injectBlobInterceptor(view: WebView?) {
        // Override URL.createObjectURL to store blobs before they can be revoked.
        // Zotero revokes blob URLs after triggering the download, which causes
        // fetch() to fail with "failed to fetch" for annotated PDF exports.
        val js = """
            (function() {
                if (window._zotWebBlobStore) return;
                window._zotWebBlobStore = {};
                var origCreate = URL.createObjectURL.bind(URL);
                var origRevoke = URL.revokeObjectURL.bind(URL);
                URL.createObjectURL = function(blob) {
                    var url = origCreate(blob);
                    if (blob instanceof Blob) {
                        window._zotWebBlobStore[url] = blob;
                    }
                    return url;
                };
                URL.revokeObjectURL = function(url) {
                    origRevoke(url);
                };
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun handleBlobDownload(blobUrl: String, contentDisposition: String, mimeType: String) {
        val fileName = URLUtil.guessFileName(blobUrl, contentDisposition, mimeType)
        // Use the stored blob from our interceptor if available, otherwise fall back to fetch
        val js = """
            (async function() {
                try {
                    var blob;
                    if (window._zotWebBlobStore && window._zotWebBlobStore['$blobUrl']) {
                        blob = window._zotWebBlobStore['$blobUrl'];
                        delete window._zotWebBlobStore['$blobUrl'];
                    } else {
                        var response = await fetch('$blobUrl');
                        blob = await response.blob();
                    }
                    var reader = new FileReader();
                    reader.onloadend = function() {
                        var base64 = reader.result.split(',')[1];
                        var mimeType = blob.type || '$mimeType';
                        ZotWebBlobDownload.saveFile(base64, '$fileName', mimeType);
                    };
                    reader.readAsDataURL(blob);
                } catch(e) {
                    ZotWebBlobDownload.onError(e.message);
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        Toast.makeText(this, "Preparing download: $fileName", Toast.LENGTH_SHORT).show()
    }

    inner class BlobDownloadInterface {
        @JavascriptInterface
        fun saveFile(base64Data: String, fileName: String, mimeType: String) {
            runOnUiThread {
                try {
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    saveBytesToDownloads(bytes, fileName, mimeType)
                    Toast.makeText(this@MainActivity, "Downloaded: $fileName", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Download failed: $message", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveBytesToDownloads(bytes: ByteArray, fileName: String, mimeType: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use MediaStore for Android 10+
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    os.write(bytes)
                }
            }
        } else {
            // Direct file write for older versions
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(bytes) }
        }
    }

    private fun needsStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) != PackageManager.PERMISSION_GRANTED
    }

    private fun requestStoragePermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            STORAGE_PERMISSION_REQUEST
        )
    }

    private fun startHttpDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val cookies = CookieManager.getInstance().getCookie(url)

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("Cookie", cookies)
                addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setDescription("Downloading $fileName")
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Downloading: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {}
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }
        swipeRefresh.setColorSchemeResources(
            R.color.zotero_red
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingDownload?.let {
                    startHttpDownload(it.url, it.userAgent, it.contentDisposition, it.mimeType)
                }
            } else {
                Toast.makeText(this, "Storage permission needed for downloads", Toast.LENGTH_LONG).show()
            }
            pendingDownload = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        CookieManager.getInstance().flush()
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }
}
