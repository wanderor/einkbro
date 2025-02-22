package info.plateaukao.einkbro.addons

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import com.google.gson.GsonBuilder
import com.google.gson.annotations.Expose
import com.google.android.material.snackbar.Snackbar
import info.plateaukao.einkbro.browser.AlbumController
import info.plateaukao.einkbro.browser.BrowserContainer
import info.plateaukao.einkbro.browser.BrowserController
import info.plateaukao.einkbro.view.EBWebView
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.timer
import kotlin.text.Charsets.UTF_8


// Represents browser state data to be read from and written to the cloud.
// Note: we will compress/uncompress it using gzip by ourselves, not retrofit2.
@Serializable
private data class BrowserState(
    val version: String = "",
    // Identifies the instance providing this data.
    val name: String = "",
    // Update time of this data in YYYY/MM/DD HH:MM:SS format.
    val timestamp: String = "",
    // URLs of currently open web pages.
    val urls: Set<String> = setOf(),
    // URLs of recently closed web pages.
    val closed: Map<String, Long> = mapOf()
)

// Represents file stat of browser state data in the cloud.
// The format is defined by Baidu Pan, and we only expose the subset of interest.
@Serializable
private data class BrowserFileStat(
    // File mtime.
    @Expose
    val mtime: Long = 0,
)

// Represents file metadata of browser state data in the cloud.
// The format is defined by Baidu Pan, and we only expose the subset of interest.
@Serializable
private data class BrowserFileMeta(
    // List of file stats.
    @Expose
    val list: List<BrowserFileStat> = listOf(),
)

// Represents configuration data of this add-on.
@Serializable
private data class BaiduSyncerConfig(
    val version: String = "",
    // Identifies the instance providing BrowserState data.
    val name: String = "",
    // Access token from Baidu Pan.
    val token: String = "",
    // Startup delay of sync in seconds.
    val startup: Int = 60,
    // Interval between syncs in seconds.
    val interval: Int = 86400,
    // Whether to send BrowserState data to the cloud.
    val sending: Boolean = false,
    // Whether to receive BrowserState data from the cloud.
    val receiving: Boolean = false,
    // Whether to preload inactive tabs in background.
    val preloading: Boolean = false,
    // Whether to cache waiting URLs in background.
    val caching: Boolean = false,
    // Maximal number of retries (besides initial attempt).
    val retries: Int = 0,
    // Maximal number of URLs to open locally.
    val slots: Int = 20,
    // Time to wait between loading URLs in seconds.
    val wait: Int = 15,
    // Maximal number of recently closed URLs to be cached.
    val recents: Int = 200,
    // Maximal duration in seconds for a URL to stay in cache.
    val lifetime: Int = 7 * 86400,  // 7 days
    // Interval between heartbeats in seconds.
    val heartbeat: Int = 30,
    // Duration to display a message in seconds.
    val display: Int = 15,
    // Regular expression pattern for URLs to preload in reader mode.
    val reader: String = "",
    // Regular expression pattern for URLs to skip loading.
    val skipper: String = "",
    // For debugging release version.
    // Whether to turn on logging to external file.
    val logging: Boolean = false
)

// Retrofit service that represents Baidu Pan cloud service.
private interface BaiduCloudService {
    // Reads BrowserState file metadata from the cloud.
    @GET("file?method=meta")
    fun checkState(
        @Query("access_token") token: String,
        @Query("path") path: String
    ): Call<BrowserFileMeta>

    // Reads BrowserState from the cloud.
    @GET("file?method=download")
    fun readState(
        @Query("access_token") token: String,
        @Query("path") path: String
    ): Call<ResponseBody>  // we will parse by ourselves

    // Writes BrowserState to the cloud.
    @Multipart
    @POST("file?method=upload&ondup=overwrite")
    fun writeState(
        @Query("access_token") token: String,
        @Query("path") path: String,
        @Part file: MultipartBody.Part
    ): Call<BrowserFileStat>
}

// Implements a mechanism to periodically sync local tabs with peers across the network
// via the relay of Baidu Pan cloud service.
//
// Note: the current sync algorithm is naive and is not well tuned.
// The basic assumption is: it is OK to open excessive web pages, but not OK to close
// web pages unintentionally.
class BaiduSyncer(
    private val context: Context,
    private val view: View,
    private val browserContainer: BrowserContainer,
    private val browserController: BrowserController,
    private val openUrlsFunc: (Set<String>) -> Unit,
    private val registry: ActivityResultRegistry
) {
    companion object {
        // Tag for logging.
        private const val TAG: String = "BaiduSyncer"

        // Base URL of Baidu Pan cloud service.
        private const val STATE_BASE_URL: String = "https://pan.baidu.com/rest/2.0/xpan/"

        // Path of the BrowserState file in the cloud.
        private const val STATE_PATH: String = "/apps/bypy/browser.json.gz"

        // Name of the configuration file.
        private const val CONFIG_FILENAME: String = "baidu.json"

        // Directory in Android device where we detects new config to be installed.
        // If user wants to overwrite the existing config file with a new one, s/he
        // can put the new config file in this directory, and the app will detect it
        // during startup.
        private const val CONFIG_INSTALL_DIR: String = "/storage/emulated/0/Download"

        // Latest data version supported in this syncer.
        private const val VERSION: String = "1.0.0"

        // For debugging release version.
        // Whether to always turn on logging to external file.
        private const val ALWAYS_EXTERNAL_LOGGING: Boolean = false

        // For debugging release version.
        // Path to the external logging file.
        private const val EXTERNAL_LOG_PATH: String =
            "/storage/emulated/0/Android/data/info.plateaukao.einkbro/files/baidu.log"

        // Maximal number of recently worked URLs to temporarily keep.
        private const val MAX_RECENTLY_WORKED_URLS: Int = 1000

        // Normalizes the URL of an album controller for dedup.
        fun normalizeUrl(controller: AlbumController): String {
            var url = controller.albumUrl
            if (!url.startsWith("http")) {
                url = controller.initAlbumUrl
                if (!url.startsWith("http")) return ""
            }
            return normalizeUrl(url)
        }

        // Normalizes a URL for dedup.
        fun normalizeUrl(url: String): String {
            // Removes hash.
            val pos = url.indexOf('#')
            if (pos > 0) {
                return url.substring(0, pos)
            }
            return url
        }

        // Checks whether a WebView instance is indeed loaded.
        private fun isLoaded(webView: EBWebView): Boolean {
            // TODO: add i18n support
            return webView.album.isLoaded && webView.albumTitle.isNotBlank() &&
                   webView.albumTitle != "..." &&
                   webView.albumTitle != "Webpage not available" &&
                   webView.albumTitle != "网页无法打开"
        }

        fun isOffline(context: Context, transports: MutableList<String>? = null): Boolean {
            var result = true
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.getNetworkCapabilities(manager.activeNetwork)?.let { capabilities ->
                mapOf("mobile" to NetworkCapabilities.TRANSPORT_CELLULAR,
                      "wifi" to NetworkCapabilities.TRANSPORT_WIFI,
                      "ethernet" to NetworkCapabilities.TRANSPORT_ETHERNET).forEach { name, type ->
                    if (capabilities.hasTransport(type)) {
                        transports?.add(name)
                        result = false
                    }
                }
            }
            return result
        }

        fun chain(handler: Handler, steps: Iterable<Pair<() -> Unit, Long>>) {
            fun proceed(iterator: Iterator<Pair<() -> Unit, Long>>) {
                if (!iterator.hasNext()) return
                val pair = iterator.next()
                handler.postDelayed({
                    (pair.first)()
                    proceed(iterator)
                }, pair.second)
            }
            proceed(steps.iterator())
        }

        fun chain(handler: Handler, interval: Long, steps: Iterable<() -> Unit>) {
            val iterator = steps.iterator()
            val pairs = generateSequence {
                if (iterator.hasNext()) Pair(iterator.next(), interval) else null
            }
            chain(handler, pairs.asIterable())
        }

    }

    // For GUI interactions.
    private val handler: Handler = Handler(Looper.getMainLooper())

    // For loading and persisting state.
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("baidu", Context.MODE_PRIVATE)
    // For loading URLs in background, e.g. for caching.
    private val headlessWebView: EBWebView = EBWebView(context, browserController)

    // Configuration.
    private var config: BaiduSyncerConfig = BaiduSyncerConfig()
    // Timestamp of the last actions.
    private var lastSyncTime: Long = 0
    private var lastDisplayTime: Long = 0
    // We check 2 timestamps when reading from the cloud:
    // - cloud file mtime.
    // - timestamp string written in the file.
    private var lastCloudMtime: Long = 0
    private var lastUpdateTime: String = ""
    // Previously seen open URLs in local device.
    private var prevUrls: Set<String> = setOf<String>()
    // URLs received from the cloud but not yet open (i.e. throttled).
    private var waitingUrls: Set<String> = setOf()
    // Open and closed URLs previously written to the cloud.
    private var writtenUrls: Set<String> = setOf<String>()
    private var writtenClosedUrls: Map<String, Long> = mapOf()
    // Cache of recently closed URLs in local device and seen in the cloud.
    private var recentUrls: MutableMap<String, Long> = mutableMapOf()
    // Regular expression pattern of URLs to preload in reader mode
    private var readerUrlRegex: Regex = Regex("")
    // Regular expression pattern of URLs to skip loading
    private var skipperUrlRegex: Regex = Regex("")
    // Regular expression pattern of URLs/paths for local cache.
    // Note: must be synced with cacheUrl().
    private val cacheRegex = Regex(""".*/cache-\d+\.mht""")
    // (Multi-threaded) Mapping of URL to (path, title, timestamp) for locally cached URLs.
    private var cachedUrls: MutableMap<String, Triple<String, String, Long>> = mutableMapOf()
    // ID of the next cached URL.
    private var nextCachedUrlId: Long = 0
    // URLs that should be, but are not yet, cached.
    private var urlsToCache: MutableList<String> = mutableListOf()
    // URLs that we recently worked on and should avoid duplicate work.
    private var recentlyCachedUrls: MutableSet<String> = mutableSetOf()
    private var recentlyAdjustedUrls: MutableSet<String> = mutableSetOf()
    private var offline: Boolean = false
    // For logging to external file in local device.
    private var externalLogFile: File? = null

    // For launching File Chooser to pick a new config file to install.
    private var configLauncher: ActivityResultLauncher<Array<String>>? = null

    // Retrofit.
    private lateinit var retrofit: Retrofit
    private lateinit var service: BaiduCloudService

    // For formatting date strings in BrowserState and in logging.
    private val stateDateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss")
    private val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    // Internal data structure used when merging state from/to cloud.
    private data class Merger (
        var cloudSource: String = "",
        var curUrls: Set<String> = setOf(),
        var urlsInCloud: Set<String> = setOf(),
        var closedUrlsInCloud: Map<String, Long> = mapOf(),
        var urlsToOpen: Set<String> = setOf(),
        var urlsToClose: Set<String> = setOf()
    )

    fun onPageFinished(webView: EBWebView) {
        handler.postDelayed({
            if (webView.albumUrl.startsWith("http")) {
                if (isLoaded(webView)) {
                    adjustReaderMode(webView, webView.albumUrl)
                } else {
                    loadUrlFromCache(webView, webView.albumUrl)
                }
            } else if (webView.albumUrl.startsWith("file")) {
                if (cacheRegex.matches(webView.albumUrl)) {
                    loadTitleFromCache(webView, webView.initAlbumUrl)
                    adjustReaderMode(webView, webView.initAlbumUrl)
                }
            }
        }, 2000)
    }

    fun handleUri(url: String): Boolean {
        if (skipperUrlRegex.matches(url)) {
            log(Log.DEBUG, "Skipping URL: $url")
            return true
        }
        return false
    }

    init {
        if (ALWAYS_EXTERNAL_LOGGING) {
            externalLogFile = File(EXTERNAL_LOG_PATH)
        }

        log("Initializing BaiduSyncer")
        configureAndProceed()
    }

    private fun configureAndProceed() {
        // Steps:
        // - If a file exists at the predefined install path, we assume it as an indication that
        //   the user wants to install a new config file. (Note that it will be the user's manual
        //   responsibility to delete that file after successful installation.)
        // - Otherwise, we will attempt to locate and load the config from app-specific storage.
        //   If it doesn't work, we need the user to install a new config file.
        val install_dir = File(CONFIG_INSTALL_DIR)
        val install_file = File(install_dir, CONFIG_FILENAME)
        if (!install_file.exists()) {
            for (dir in context.getExternalFilesDirs(null)) {
                dir?.let {
                    val file = File(dir, CONFIG_FILENAME)
                    if (initConfigFromFile(file)) {
                        start()
                        return
                    }
                }
            }
        }
        pickConfigAndProceed()
    }

    // Initializes configuration from the specified file.
    private fun initConfigFromFile(file: File): Boolean {
        log("Checking path for config: ${file.path}")
        if (file.exists()) {
            try {
                val text = file.readText()
                config = Json.decodeFromString<BaiduSyncerConfig>(text)
                log("Loaded config from ${file.path}")
                return true
            } catch (e: IOException) {
                log("Failed to load config: ${e.toString()}")
            }
        }
        return false
    }

    // Launches File Chooser for user to pick a config file to be installed.
    private fun pickConfigAndProceed() {
        configLauncher = registry.register(
            "BaiduSyncer", OpenDocument()
        ) { uri ->
            uri?.let {
                val inputStream = context.contentResolver.openInputStream(uri)
                inputStream?.let {
                    var bytes = inputStream.readBytes()
                    configLauncher!!.unregister()
                    configLauncher = null

                    installConfigAndProceed(bytes)
                }
            }
        }
        configLauncher!!.launch(arrayOf("application/json", "text/plain"))
    }

    // Installs configuration file by writing the specified bytes.
    private fun installConfigAndProceed(bytes: ByteArray) {
        for (dir in context.getExternalFilesDirs(null)) {
            dir?.let {
                val file = File(dir, CONFIG_FILENAME)
                try {
                    file.writeBytes(bytes)
                    log("Wrote config to $file")
                    if (initConfigFromFile(file)) {
                        display("Installed new config")
                        start()
                    }
                    return
                } catch (e: IOException) {
                    log(Log.ERROR, "Failed to install config to $file: ${e.toString()}")
                }
            }
        }
    }

    // Starts periodic sync.
    // Precondition: configuration is ready.
    private fun start() {
        if (externalLogFile == null && config.logging) {
            externalLogFile = File(EXTERNAL_LOG_PATH)
        }

        log("Starting")
        readerUrlRegex = Regex(config.reader)
        skipperUrlRegex = Regex(config.skipper)
        loadState()
        updateCachePages()

        // Initializes Retrofit.
        val gson = GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()
        retrofit = Retrofit.Builder()
            .baseUrl(STATE_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        service = retrofit.create(BaiduCloudService::class.java)

        // Starts timer for periodical sync.
        timer(daemon = true, initialDelay = config.startup * 1000L, period = config.heartbeat * 1000L) {
            try {
                heartbeat()
            } catch (e: Exception) {
                log(Log.ERROR, "Heartbeat failed: ${e.stackTraceToString()}")
            }
        }
    }

    private fun heartbeat() {
        val prevOffline = offline
        val transports = mutableListOf<String>()
        offline = isOffline(context, transports)
        if (offline != prevOffline) {
            val status = if (offline) "offline" else "online [${transports.joinToString()}]"
            display("Network change: $status")
        }

        val now = Date().time
        var shortcut = false
        var backfilling = true
        var caching = true
        if (now >= lastSyncTime + config.interval * 1000L && !offline) {  // full path
            lastSyncTime = now  // update even if action fails
            // Note: prevent infinite memory growth.
            if (recentlyCachedUrls.size > MAX_RECENTLY_WORKED_URLS) recentlyCachedUrls.clear()
            if (recentlyAdjustedUrls.size > MAX_RECENTLY_WORKED_URLS) recentlyAdjustedUrls.clear()
        } else {  // shortcut path
            shortcut = true
            // Note: theoretically we shouldn't call size() here in timer thread
            backfilling = (browserContainer.size() < config.slots * .7 && waitingUrls.isNotEmpty())
            caching = (urlsToCache.isNotEmpty() && !offline)
        }

        if (backfilling) {
            sync(now, shortcut)
            prepareUrlsToCache(now)
            cleanCachedUrls(now)
        }
        if (caching) cache(now)
        if (backfilling) preload(offline)
        if (backfilling || caching) saveState()
    }

    // Loads state.
    private fun loadState() {
        sharedPreferences.getString("waitingUrls", null)?.let {
            waitingUrls = Json.decodeFromString(it)
        }
        sharedPreferences.getString("recentUrls", null)?.let {
            recentUrls = Json.decodeFromString(it)
        }
        sharedPreferences.getString("cachedUrls", null)?.let {
            cachedUrls = Json.decodeFromString(it)
        }
        nextCachedUrlId = sharedPreferences.getLong("nextCachedUrlId", 0)
        log("Loaded: ${waitingUrls.size} waiting, ${recentUrls.size} recent, ${cachedUrls.size} cached")
    }

    // Persists state.
    private fun saveState() {
        val editor = sharedPreferences.edit()
        editor.putString("waitingUrls", Json.encodeToString(waitingUrls))
        editor.putString("recentUrls", Json.encodeToString(recentUrls))
        editor.putString("cachedUrls", Json.encodeToString(cachedUrls))
        editor.putLong("nextCachedUrlId", nextCachedUrlId)
        editor.apply()
    }

    // Synchronizes between local and cloud.
    private fun sync(now: Long, shortcut: Boolean) {
        log("Sync started: sending=${config.sending}, receiving=${config.receiving}")

        // Gathers local information.
        val openUrls = listUrls()
        if (openUrls.isEmpty()) {
            log("Skipping sync: no local URLs found")
            return
        }
        val closedUrls = prevUrls.subtract(openUrls)
        closedUrls.forEach { recentUrls[it] = now }  // will inspect when merging from cloud
        log("Local URLs: open=${openUrls.size}, closed=${closedUrls.size}, waiting=${waitingUrls.size}")

        // Note: keep the logics between read and write of cloud data minimal and fast, to
        // reduce the race condition of peers.

        // Sync: cloud -> local
        val merger = Merger(curUrls = openUrls union waitingUrls)
        if (config.receiving && !shortcut) {
            if (mergeFromCloud(merger)) {
                merger.closedUrlsInCloud.forEach { (url, timestamp) ->
                    val old = recentUrls.getOrDefault(url, 0)
                    if (timestamp > old) recentUrls[url] = timestamp
                }
            }
        }

        // Cleans cache of recently closed URLs before sending them to cloud
        if (!shortcut) cleanRecentUrls(now)

        // Sync: local -> cloud
        if (config.sending && !shortcut) {
            mergeToCloud(merger)
        }

        applyMerge(openUrls, merger)
    }

    // Opens and closes URLs in accordance with cloud information.
    // Note:
    // - When opening URLs, don't forget those in local waiting buffer.
    // - We cannot simply close all URLs before opening all URLs, as the closing step might close
    //   the last active URL and thus cause the app to quit. On the other hand, we cannot simply
    //   open all URLs before closing all URLs, as it might cause a memory surge and kill the app.
    private fun applyMerge(openUrls: Set<String>, merger: Merger) {
        // Calculates URLs to open and close.
        val urlsToClose = openUrls intersect merger.urlsToClose
        var newOpenUrls = openUrls subtract urlsToClose
        var urlsToOpenFirst: List<String> = listOf()
        var urlsToOpenLast: List<String> = listOf()
        var slots = max(config.slots - openUrls.size + urlsToClose.size, 0)
        if (slots > 0) {
            val allUrlsToOpen =
                merger.urlsToOpen union (waitingUrls subtract merger.urlsToClose)
            slots = min(slots, allUrlsToOpen.size)
            if (slots > 0) {
                val urlsToOpen = allUrlsToOpen.take(slots)
                newOpenUrls = newOpenUrls union urlsToOpen
                val slotsFirst = max(slots - urlsToClose.size, 1)
                val slotsLast = slots - slotsFirst
                urlsToOpenFirst = urlsToOpen.take(slotsFirst)
                urlsToOpenLast = urlsToOpen.takeLast(slotsLast)
            }
        }

        // Opens and closes URLs.
        if (urlsToOpenFirst.isNotEmpty()) {
            openUrls(urlsToOpenFirst)
        }
        if (urlsToClose.isNotEmpty()) {
            closeUrls(urlsToClose)
        }
        if (urlsToOpenLast.isNotEmpty()) {
            openUrls(urlsToOpenLast)
        }

        // Updates local state.
        prevUrls = newOpenUrls
        waitingUrls = merger.curUrls subtract newOpenUrls

        // Gives user an update.
        if (merger.cloudSource.isNotEmpty()) {
            display("Synced with ${merger.cloudSource}: $slots opened, ${urlsToClose.size} closed, ${waitingUrls.size} waiting")
        } else if (waitingUrls.isNotEmpty()) {
            display("${waitingUrls.size} waiting")
        }
    }

    // Cleans cache of recently closed URLs. Removes obsolete URLs and limit cache size.
    private fun cleanRecentUrls(now: Long) {
        recentUrls = recentUrls.filterValues { timestamp ->
            now - timestamp < config.lifetime * 1000L
        }.toMutableMap()
        if (recentUrls.size <= config.recents) return
        val threshold = recentUrls.values.sortedDescending()[config.recents]
        recentUrls = recentUrls.filterValues { it > threshold }.toMutableMap()
    }

    // Reads information from cloud, merges with local information and write to `merger`.
    // Returns false if read fails or the cloud version is obsolete.
    private fun mergeFromCloud(merger: Merger): Boolean {
        val cloudMtime = readCloudMtime()
        if (cloudMtime != null && cloudMtime <= lastCloudMtime) {
            log("Ignore obsolete cloud state: now=$cloudMtime prev=$lastCloudMtime")
            return false
        }

        readFromCloud()?.let { state ->
            log("Received state from '${state.name}': open=${state.urls.size} closed=${state.closed.size} [time: '${state.timestamp}']")
            if (state.version == "1.0.0") {
                if (state.timestamp > lastUpdateTime) {
                    merger.cloudSource = state.name
                    merger.urlsInCloud = state.urls
                    merger.closedUrlsInCloud = state.closed
                    merger.urlsToOpen = (state.urls subtract merger.curUrls).filter { url ->
                        !recentUrls.contains(url)
                    }.toSet()
                    merger.urlsToClose = state.closed.keys intersect merger.curUrls
                    merger.curUrls =
                        merger.curUrls union merger.urlsToOpen subtract merger.urlsToClose
                    cloudMtime?.let { lastCloudMtime = it }
                    lastUpdateTime = state.timestamp
                    return true
                } else {
                    log("Ignore obsolete cloud state: now=${state.timestamp} prev=$lastUpdateTime")
                }
            } else {
                log(Log.ERROR, "Ignore unrecognized version: [${state.version}]")
            }
        }
        return false
    }

    // Writes local information to cloud.
    // Returns false if write fails or there is no update to write.
    private fun mergeToCloud(merger: Merger): Boolean {
        val recent = recentUrls.entries
        if (writtenUrls == merger.curUrls &&
            writtenClosedUrls.entries.containsAll(recent)) {
            // We wrote the same data to the cloud last time. That data might have been overwritten
            // by someone else, but it doesn't make sense for us to send the same data again.
            log("No update to write to cloud")
            return false
        }
        if (merger.urlsInCloud == merger.curUrls &&
            merger.closedUrlsInCloud.entries.containsAll(recent)) {
            // Our data is a subset of the cloud version. It doesn't make sense for us to overwrite
            // the cloud version.
            log("Skip overwrite of the cloud version")
            return false
        }
        val timestamp = stateDateFormat.format(Date())
        val state = BrowserState(VERSION, config.name, timestamp, merger.curUrls, recentUrls)
        writeToCloud(state)?.let { stat ->
            writtenUrls = merger.curUrls
            writtenClosedUrls = recentUrls
            lastCloudMtime = stat.mtime
            lastUpdateTime = timestamp
            return true
        }
        return false
    }

    // Reads file mtime of the cloud information.
    private fun readCloudMtime(): Long? {
        // Note: no need for retry
        log("Getting cloud mtime")
        try {
            val call = service.checkState(config.token, STATE_PATH)
            val response = call.execute()
            if (response.isSuccessful) {
                response.body()?.let { meta ->
                    // Cloud might return error. Also see explanation in writeToCloud().
                    @Suppress("SENSELESS_COMPARISON")
                    if (meta.list == null) {
                        log(Log.ERROR, "Metadata incorrect or broken")
                    } else {
                        val mtime = meta.list.get(0).mtime
                        log("Got cloud mtime: $mtime")
                        return mtime
                    }
                }
            }
        } catch (e: IOException) {
            log(Log.ERROR, "Failed to check cloud mtime: ${e.toString()}")
        }
        return null
    }

    private fun readFromCloud(): BrowserState? {
        log("Reading state from cloud")
        for (i in 0..config.retries) {
            try {
                val call = service.readState(config.token, STATE_PATH)
                val response = call.execute()
                if (response.isSuccessful) {
                    response.body()?.let {
                        val bytes = GZIPInputStream(it.byteStream()).readBytes()
                        val state =
                            Json.decodeFromString<BrowserState>(bytes.toString(UTF_8))
                        log("Succeeded to read from cloud (attempt #$i)")
                        return state
                    }
                }
                log(Log.ERROR, "Failed to read from cloud (attempt #$i): response - ${response.toString()}")
            } catch (e: IOException) {
                log(Log.ERROR, "Failed to read from cloud (attempt #$i): exception - ${e.toString()}")
            }
        }
        return null
    }

    private fun writeToCloud(state: BrowserState): BrowserFileStat? {
        log("Writing state to cloud: open=${state.urls.size} closed=${state.closed.size}")
        val stream = ByteArrayOutputStream()
        with(GZIPOutputStream(stream)) {
            val bytes = Json.encodeToString(state).toByteArray(UTF_8)
            write(bytes)
            close()
        }
        val body = stream.toByteArray().toRequestBody()
        val part =
            MultipartBody.Part.createFormData(name = "file", filename = STATE_PATH, body = body)

        for (i in 0..config.retries) {
            try {
                val call = service.writeState(config.token, STATE_PATH, part)
                val response = call.execute()
                if (response.isSuccessful) {
                    response.body()?.let { stat ->
                        // Note: the comparison below is necessary because we may incidentally
                        // configure `minifyEnabled` and `proguard` rules incorrectly, especially
                        // for release version, which corrupts the reflection mechanism of retrofit
                        // and causes retrofit to assign null to this and other fields.
                        @Suppress("SENSELESS_COMPARISON")
                        if (stat.mtime == null) {
                            log(Log.ERROR, "Stat parsing is broken, likely buggy")
                        } else {
                            log("Succeeded to write to cloud (attempt #$i)")
                            return stat
                        }
                    }
                } else {
                    log(Log.ERROR, "Failed to write to cloud (attempt #$i): response - ${response.toString()}")
                }
            } catch (e: IOException) {
                log(Log.ERROR, "Failed to write to cloud (attempt #$i): exception - ${e.toString()}")
            }
        }
        return null
    }

    // Recalculate URLs that should be, but not yet, cached.
    private fun prepareUrlsToCache(now: Long) {
        urlsToCache.clear()
        // Note: Shuffle waiting URLs to avoid being blocked on the same URLs.
        val urls = prevUrls.toList() + waitingUrls.shuffled()
        synchronized(cachedUrls) {
            urls.forEach { url ->
                if (url !in cachedUrls && url !in recentlyCachedUrls) {
                    urlsToCache.add(url)
                    recentlyCachedUrls.add(url)
                }
            }
        }
        if (urlsToCache.isNotEmpty()) {
            display("Cache: ${urlsToCache.size} to be cached, now ${cachedUrls.size}")
        }
    }

    // Cache URLs in background.
    private fun cache(now: Long) {
        if (!config.caching || urlsToCache.isEmpty()) return

        // Note: each time we only attempt to cache a few URLs, so that the total time spent and
        // the risk of interruption is acceptable.
        val urls = urlsToCache.take(config.slots)
        urlsToCache = urlsToCache.subList(urls.size, urlsToCache.size)
        runAndWait(period = config.wait * 1000L, max = urls.size) { index ->
            cacheUrl(headlessWebView, urls[index], now)
        }
        log("Cache: ${urls.size} attempted")
    }

    // Caches the URL to a local file.
    private fun cacheUrl(webView: EBWebView, url: String, now: Long) {
        context.externalCacheDir?.let {
            // Note: filename pattern must be synced with cacheRegex.
            val path = it.path + "/cache-" + nextCachedUrlId++ + ".mht"
            log(Log.DEBUG, "Caching $url to $path")
            val steps = listOf({
                webView.toggleReaderMode()  // note: toggle twice to load lazy images
            }, {
                webView.toggleReaderMode()
            }, {
                if (isLoaded(webView)) {
                    val title = webView.albumTitle  // note: needs delay to work
                    webView.saveWebArchive(path, false) {
                        synchronized(cachedUrls) {
                            cachedUrls[url] = Triple(path, title, now)
                        }
                    }
                }
            })
            webView.setOnPageFinishedAction {
                chain(handler, 1000L, steps)
            }
            webView.loadUrl(url)
        }
    }

    // Cleans cache of recently closed URLs. Removes obsolete URLs and limit cache size.
    private fun cleanCachedUrls(now: Long) {
        var count = 0
        cachedUrls = cachedUrls.filterValues {
            now - it.third < config.lifetime * 1000L
        }.toMutableMap()
        context.externalCacheDir?.let { dir ->
            val cachedPaths = cachedUrls.map { it.value.first }.toSet()
            dir.listFiles()?.filter { cacheRegex.matches(it.path) && !cachedPaths.contains(it.path) }
                ?.map {
                    log(Log.DEBUG, "Deleting cache file: ${it.path}")
                    it.delete()
                    ++count
                }
        }

        if (count > 0) {
            display("Cache: $count purged")
        }
    }

    // Preloads inactive tabs in background.
    private fun preload(offline: Boolean) {
        if (!config.preloading) return

        // Prepare candidates to preload.
        val candidates = mutableListOf<Pair<EBWebView, String>>()
        runAndWait(period = 1000) {
            browserContainer.list().take(config.slots).forEach {
                val webView = it as EBWebView
                val url = webView.initAlbumUrl
                if (!isLoaded(webView) && url.isNotEmpty()) {
                    candidates.add(Pair(webView, url))
                }
            }
        }
        if (candidates.isEmpty()) return

        // Preload prepared candidates.
        runAndWait(period = config.wait * 1000L, max = candidates.size) { index ->
            val (webView, url) = candidates[index]
            if (!isLoaded(webView) && webView.initAlbumUrl == url) {
                preloadUrl(webView, url, offline)
            }
        }
        log("Preloaded ${candidates.size} inactive tabs")
    }

    // Preloads the URL in the specified WebView.
    private fun preloadUrl(webView: EBWebView, url: String, offline: Boolean) {
        log(Log.DEBUG, "Preloading $url")
        if (offline) {
            loadUrlFromCache(webView, url)
        } else {
            webView.loadUrl(url)
        }
    }

    // Load URL from local cache if available.
    private fun loadUrlFromCache(webView: EBWebView, url: String) {
        val path = synchronized(cachedUrls) {
            cachedUrls[url]?.first ?: ""
        }
        if (path.isBlank() || !File(path).exists()) return
        log(Log.DEBUG, "Loading $url from cache: $path")
        webView.initAlbumUrl = url
        webView.loadUrl("file://$path")
    }

    // Load page title from local cache if available.
    private fun loadTitleFromCache(webView: EBWebView, url: String) {
        val title = synchronized(cachedUrls) {
            log("DBG 60: ${cachedUrls[url]}")
            cachedUrls[url]?.second ?: ""
        }
        if (title.isBlank()) return
        webView.albumTitle = title
    }

    // Enter or exit reader mode as appropriate.
    private fun adjustReaderMode(webView: EBWebView, url: String) {
        if (!recentlyAdjustedUrls.contains(url) &&
            readerUrlRegex.matches(url) != webView.isReaderModeOn) {
            log(Log.DEBUG, "Toggling reader mode from ${webView.isReaderModeOn} for $url")
            recentlyAdjustedUrls.add(url)
            webView.toggleReaderMode()
        }
    }

    // Sets initial URL and title for each page loaded from cache at startup.
    private fun updateCachePages() {
        val lookup = mutableMapOf<String, Pair<String, String>>()  // path -> (url, title)
        synchronized(cachedUrls) {
            cachedUrls.forEach { url, triple ->
                lookup["file://${triple.first}"] = Pair(url, triple.second)
            }
        }
        handler.postDelayed({
            browserContainer.list().forEach { controller ->
                val webView = controller as EBWebView
                val url = webView.albumUrl.ifBlank { webView.initAlbumUrl }
                lookup[url]?.let { pair ->
                    webView.initAlbumUrl = pair.first
                    webView.albumTitle = pair.second
                    log(Log.DEBUG, "Updated cache page: $url -> [${webView.albumTitle}] ${webView.initAlbumUrl}")
                }
            }
        }, config.startup * 1000L)
    }

    // Lists normalized URLs of the currently open web pages in the local browser.
    private fun listUrls(): Set<String> {
        lateinit var result: Set<String>
        runAndWait(period = 1000) {
            val controllers = browserContainer.list()
            val urls = controllers
                .filter { !it.isTranslatePage }
                .map { normalizeUrl(it) }
                .filter { it.startsWith("http") }
                .toSet()
            log("Listed ${urls.size} URLs from ${controllers.size} tabs")
            result = urls
        }
        return result
    }

    // Runs an action and waits for its completion.
    // `period`: period to wait in each round in milliseconds.
    // `max`: maximal number of iterations to run. Specifically, 1 to run once only.
    // `action`: function to run in each round.
    private fun runAndWait(period: Long, max: Int = 1,
                           action: (Int) -> Unit) {
        for (index in 0..<max) {
            handler.post {
                action(index)
            }
            Thread.sleep(period)
        }
    }

    // Opens the specified URLs in the local browser.
    private fun openUrls(urls: Iterable<String>) {
        urls.forEach { url ->
            log(Log.DEBUG, "Opening $url")
            val subset: Set<String> = setOf(url)
            handler.post {
                openUrlsFunc(subset)
            }
            Thread.sleep(config.wait * 1000L)
        }
    }

    // Closes the specified URLs in the local browser.
    private fun closeUrls(urls: Iterable<String>) {
        handler.post {
            val controllers: Set<AlbumController> = browserContainer.list()
                .filter { !it.isTranslatePage }
                .filter { urls.contains(normalizeUrl(it)) }
                .toSet()
            controllers.forEach {
                browserController.removeAlbum(it, false)
            }
        }
    }

    // Displays a message shortly in GUI.
    private fun display(message: String) {
        fun helper() {
            val now = Date().time
            val delay = lastDisplayTime + config.display * 1000L - now
            if (delay > 0) {
                handler.postDelayed({ helper() }, delay)
            } else {
                lastDisplayTime = now
                val bar = Snackbar.make(context, view, message, config.display * 1000)
                bar.setAction("dismiss") { bar.dismiss() }
                bar.show()
            }
        }

        log(message)
        handler.post { helper() }
    }

    // Logs at INFO priority.
    private fun log(message: String) {
        log(Log.INFO, message)
    }

    // Logs at the specified priority in both standard logging and, if turned on,
    // external logging file.
    private fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
        externalLogFile?.let {
            val timestamp = logDateFormat.format(Date())
            val text = timestamp + "  " + message + "\n"
            try {
                it.appendText(text)
            } catch (e: IOException) {
                // ignored
            }
        }
    }
}
