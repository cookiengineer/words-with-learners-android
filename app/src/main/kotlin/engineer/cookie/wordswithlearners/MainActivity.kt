package engineer.cookie.wordswithlearners

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var overlay: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var retryButton: Button
    private lateinit var startButton: Button
    private lateinit var copyLogsButton: Button
    private lateinit var webView: WebView

    private var service: ServerService? = null
    private var bound = false
    private var stateJob: Job? = null
    private var loadedPort: Int? = null
    private var currentPort: Int? = null
    private var advertisedHost: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val prefs by lazy { getSharedPreferences("wwl", Context.MODE_PRIVATE) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? ServerService.LocalBinder ?: return
            service = local.service()
            bound = true
            stateJob?.cancel()
            stateJob = lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    service?.state?.collect { renderState(it) }
                }
            }
            service?.startServer()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        overlay = findViewById(R.id.overlay)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.status_text)
        detailText = findViewById(R.id.detail_text)
        retryButton = findViewById(R.id.retry_button)
        startButton = findViewById(R.id.start_button)
        copyLogsButton = findViewById(R.id.copy_logs_button)

        detailText.movementMethod = ScrollingMovementMethod.getInstance()

        applyInsets()
        webView = createWebView()
        root.addView(
            webView,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val version = prefs.getInt(PREF_WEBVIEW_VERSION, -1)
        if (version != BuildConfig.VERSION_CODE) {
            webView.clearCache(true)
            prefs.edit().putInt(PREF_WEBVIEW_VERSION, BuildConfig.VERSION_CODE).apply()
        }

        retryButton.setOnClickListener { startServerService() }
        startButton.setOnClickListener { startServerService() }
        copyLogsButton.setOnClickListener { copyLogs() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                    return
                }
                moveTaskToBack(true)
            }
        })

        requestNotificationPermission()
        renderState(ServerState.Idle)
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, ServerService::class.java)
        ContextCompat.startForegroundService(this, intent)
        if (bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            bound = true
        }
        registerNetworkCallback()
    }

    override fun onStop() {
        unregisterNetworkCallback()
        stateJob?.cancel()
        stateJob = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
        service = null
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        if (::webView.isInitialized) {
            root.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(): WebView {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val view = WebView(this)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = false
            builtInZoomControls = false
            displayZoomControls = false
        }
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.addJavascriptInterface(ClipboardBridge(), "WWLAndroid")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)

        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url ?: return false
                val host = url.host
                if (host == "127.0.0.1" || host == "localhost") {
                    return false
                }
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, url)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(HIDE_FULLSCREEN_JS, null)
                injectAdvertisedHost()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) {
                    return
                }
                val detail = listOfNotNull(
                    getString(R.string.server_failed_detail),
                    error?.description?.toString(),
                ).joinToString("\n\n")
                showOverlay(
                    getString(R.string.server_failed_title),
                    detail,
                    retry = true,
                    copyLogs = true,
                )
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                if (view !== webView) {
                    return false
                }
                root.removeView(view)
                view.destroy()
                webView = createWebView()
                root.addView(
                    webView,
                    0,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                loadedPort = null
                renderState(service?.state?.value ?: ServerState.Idle)
                return true
            }
        }
        return view
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val system = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(system.left, system.top, system.right, system.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) {
            return
        }
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateAdvertisedHost()
            }

            override fun onLost(network: Network) {
                updateAdvertisedHost()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                updateAdvertisedHost()
            }
        }
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(callback)
        }
    }

    private fun updateAdvertisedHost() {
        runOnUiThread {
            val port = currentPort
            advertisedHost = if (port == null) {
                null
            } else {
                NetworkAddresses.lanIPv4(this).firstOrNull()?.let { "$it:$port" }
            }
            injectAdvertisedHost()
        }
    }

    private fun injectAdvertisedHost() {
        if (!::webView.isInitialized) {
            return
        }
        val value = advertisedHost?.let { JSONObject.quote(it) } ?: "null"
        webView.evaluateJavascript("window.WWL_ADVERTISE = $value;", null)
    }

    private fun renderState(state: ServerState) {
        currentPort = (state as? ServerState.Running)?.port

        when (state) {
            is ServerState.Idle, is ServerState.Starting -> {
                showOverlay(getString(R.string.server_starting), progress = true)
            }

            is ServerState.Running -> {
                hideOverlay()
                updateAdvertisedHost()
                if (loadedPort != state.port) {
                    loadedPort = state.port
                    webView.loadUrl("http://127.0.0.1:${state.port}/")
                }
            }

            is ServerState.Failed -> {
                loadedPort = null
                val detail = listOf(state.message, state.logs)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                showOverlay(
                    getString(R.string.server_failed_title),
                    detail,
                    retry = true,
                    copyLogs = true,
                )
            }

            is ServerState.Stopping -> {
                showOverlay(getString(R.string.server_stopping), progress = true)
            }

            is ServerState.Stopped -> {
                loadedPort = null
                showOverlay(
                    getString(R.string.server_stopped_title),
                    getString(R.string.server_stopped_detail),
                    start = true,
                )
            }
        }
    }

    private fun showOverlay(
        title: String,
        detail: String? = null,
        progress: Boolean = false,
        retry: Boolean = false,
        start: Boolean = false,
        copyLogs: Boolean = false,
    ) {
        overlay.visibility = View.VISIBLE
        this.progress.visibility = if (progress) View.VISIBLE else View.GONE
        statusText.text = title
        detailText.text = detail.orEmpty()
        detailText.visibility = if (detail.isNullOrBlank()) View.GONE else View.VISIBLE
        retryButton.visibility = if (retry) View.VISIBLE else View.GONE
        startButton.visibility = if (start) View.VISIBLE else View.GONE
        copyLogsButton.visibility = if (copyLogs) View.VISIBLE else View.GONE
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
        detailText.text = ""
    }

    private fun startServerService() {
        val current = service
        if (current != null) {
            current.startServer()
            val state = current.state.value
            if (state is ServerState.Running) {
                loadedPort = null
                renderState(state)
            } else {
                renderState(state)
            }
            return
        }
        val intent = Intent(this, ServerService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun copyLogs() {
        copyText("Words with Learners server logs", detailText.text.toString())
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
    }

    private fun copyText(label: String, text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private inner class ClipboardBridge {
        @JavascriptInterface
        fun copyText(text: String) {
            copyText("Invite", text)
        }
    }

    companion object {
        private const val PREF_WEBVIEW_VERSION = "webview_version"
        private const val HIDE_FULLSCREEN_JS =
            "(function(){var b=document.getElementById('toggle-fullscreen');if(b){b.style.display='none';}})();"
    }
}
