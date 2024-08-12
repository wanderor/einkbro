package info.plateaukao.einkbro.addons

import android.content.Context
import android.content.SharedPreferences
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
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.internal.wait
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
    // Maximum number of retries (besides initial attempt).
    val retries: Int = 0,
    // Maximum number of URLs to open locally.
    val slots: Int = 20,
    // Time to wait between opening URLs in seconds.
    val wait: Int = 15,
    // Maximum number of recently closed URLs to be cached.
    val recents: Int = 200,
    // Maximum duration in seconds for a closed URL to stay in cache.
    val lifetime: Int = 7 * 86400,  // 7 days
    // Interval between heartbeats in seconds.
    val heartbeat: Int = 30,
    // Duration to display a message in seconds.
    val display: Int = 15,
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
    _context: Context,
    _view: View,
    _browserContainer: BrowserContainer,
    _openUrls: (Set<String>) -> Unit,
    _closeAlbums: (Set<AlbumController>) -> Unit,
    _registry: ActivityResultRegistry
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

        // Normalizes a URL for dedup.
        private fun normalizeUrl(url: String): String {
            // Removes hash.
            val pos = url.indexOf('#')
            if (pos > 0) {
                return url.substring(0, pos)
            }
            return url
        }
    }

    // For GUI interactions.
    private val context: Context = _context
    private val view: View = _view
    private val browserContainer: BrowserContainer = _browserContainer
    private val openUrlsFunc: ((Set<String>) -> Unit) = _openUrls
    private val closeAlbumsFunc: (Set<AlbumController>) -> Unit = _closeAlbums
    private val registry: ActivityResultRegistry = _registry
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val shared_preferences: SharedPreferences =
        _context.getSharedPreferences("baidu", Context.MODE_PRIVATE)

    // Configuration.
    private var config: BaiduSyncerConfig = BaiduSyncerConfig()
    //
    private var lastSyncTime: Long = 0
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

    // For launching File Chooser to pick a new config file to install.
    private var configLauncher: ActivityResultLauncher<Array<String>>? = null
    // For logging to external file in local device.
    private var external_log_file: File? = null

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

    init {
        if (ALWAYS_EXTERNAL_LOGGING) {
            external_log_file = File(EXTERNAL_LOG_PATH)
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
        if (external_log_file == null && config.logging) {
            external_log_file = File(EXTERNAL_LOG_PATH)
        }

        log("Starting")

        // Loads certain pieces of data.
        shared_preferences.getString("waitingUrls", null)?.let {
            waitingUrls = Json.decodeFromString(it)
            log("Loaded ${waitingUrls.size} waiting URLs")
        }
        shared_preferences.getString("recentUrls", null)?.let {
            recentUrls = Json.decodeFromString(it)
            log("Loaded ${recentUrls.size} recent URLs")
        }

        // Initializes Retrofit.
        val gson = GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()
        retrofit = Retrofit.Builder()
            .baseUrl(STATE_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        service = retrofit.create(BaiduCloudService::class.java)

        // Starts timer for periodical sync.
        val delay = 1000L * config.startup
        val period = 1000L * config.heartbeat
        timer(daemon = true, initialDelay = delay, period = period, action = {
            try {
                heartbeat()
            } catch (e: Exception) {
                log(Log.ERROR, "Heartbeat failed: ${e.stackTraceToString()}")
            }
        })
    }

    private fun heartbeat() {
        val now = Date().time
        if (now >= lastSyncTime + config.interval * 1000L) {
            lastSyncTime = now  // update even if sync() fails
            sync(now)
        }
    }

    // Synchronizes between local and cloud.
    private fun sync(now: Long) {
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
        if (config.receiving) {
            if (mergeFromCloud(merger)) {
                merger.closedUrlsInCloud.forEach { (url, timestamp) ->
                    val old = recentUrls.getOrDefault(url, 0)
                    if (timestamp > old) recentUrls[url] = timestamp
                }
            }
        }

        // Cleans cache of recently closed URLs before sending them to cloud
        cleanRecentUrls(now)

        // Sync: local -> cloud
        if (config.sending) {
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
                val slotsFirst = max(slots - urlsToClose.size, 0)
                val slotsLast = slots - slotsFirst
                urlsToOpenFirst = urlsToOpen.take(slotsFirst)
                urlsToOpenLast = urlsToOpen.takeLast(slotsLast)
            }
        }

        // Open and close URLs.
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

        // Persists certain pieces of data.
        val editor = shared_preferences.edit()
        editor.putString("waitingUrls", Json.encodeToString(waitingUrls))
        editor.putString("recentUrls", Json.encodeToString(recentUrls))
        editor.apply()
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

    // List normalized URLs of the currently open web pages in the local browser.
    private fun listUrls(): Set<String> {
        var result: Set<String>? = null
        val lock = Object()
        handler.post {
            val controllers = browserContainer.list()
            val urls = controllers
                .filter { !it.isTranslatePage }
                .map { normalizeUrl(it.albumUrl) }
                .filter { !it.startsWith("data") }
                .filter { it.isNotBlank() && it != "about:blank" }
                .toSet()
            log("Listed ${urls.size} URLs from ${controllers.size} tabs")
            synchronized(lock) { result = urls }
        }
        var ongoing = true
        while (ongoing) {
            Thread.sleep(1000)
            synchronized(lock) { result?.let { ongoing = false } }
        }
        return result!!
    }

    // Opens the specified URLs in the local browser.
    private fun openUrls(urls: Iterable<String>) {
        urls.forEach { url ->
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
                .filter { urls.contains(normalizeUrl(it.albumUrl)) }
                .toSet()
            closeAlbumsFunc(controllers)
        }
    }

    // Displays a message shortly in GUI.
    private fun display(message: String) {
        log(message)
        val bar = Snackbar.make(context, view, message, config.display * 1000)
        bar.setAction("dismiss") { bar.dismiss() }
        bar.show()
    }

    // Logs at INFO priority.
    private fun log(message: String) {
        log(Log.INFO, message)
    }

    // Logs at the specified priority in both standard logging and, if turned on,
    // external logging file.
    private fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
        external_log_file?.let {
            val timestamp = logDateFormat.format(Date())
            val text = timestamp + "  " + message + "\n"
            try {
                external_log_file!!.appendText(text)
            } catch (e: IOException) {
                // ignored
            }
        }
    }
}
