package info.plateaukao.einkbro.addons

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.activity.result.ActivityResultRegistry
import info.plateaukao.einkbro.browser.AlbumController
import info.plateaukao.einkbro.browser.BrowserContainer
import info.plateaukao.einkbro.browser.BrowserController
import info.plateaukao.einkbro.view.EBWebView
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.timer

// Represents browser state data to be read from and written to the cloud.
// Note: we will compress/uncompress it using gzip by ourselves, not retrofit2.
@Serializable
data class BrowserState(
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

// Implements a mechanism to periodically sync local tabs with peers across the network
// via the relay of a cloud service.
//
// Note: the current sync algorithm is naive and is not well tuned.
// The basic assumption is: it is OK to open excessive web pages, but not OK to close
// web pages unintentionally.
class CloudSyncer(
    name: String,
    private val context: Context,
    registry: ActivityResultRegistry,
    private val agent: CloudSyncerAgent,
    private val helper: AddOnHelper,
    private val browserContainer: BrowserContainer,
    private val browserController: BrowserController,
    private val openUrlsFunc: (Set<String>) -> Unit,
) {
    companion object {
        // Latest data version supported in this syncer.
        private const val VERSION: String = "1.0.0"

        // Maximal number of recently worked URLs to temporarily keep.
        private const val MAX_RECENTLY_WORKED_URLS: Int = 1_000

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
    }

    @Serializable
    data class CachedUrlInfo(val path: String, val title: String, val timestamp: Long)

    // For loading and persisting state. Note backward compatibility.
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("baidu", Context.MODE_PRIVATE)
    // For loading URLs in background, e.g. for caching.
    private val headlessWebView: EBWebView = EBWebView(context, browserController)

    // Configuration.
    private var config: CloudSyncerConfig = CloudSyncerConfig()
    // Time of the next forced sync, or Long.MAX_VALUE if absent.
    private var forceSyncTime: AtomicLong = AtomicLong(Long.MAX_VALUE)
    // Timestamp of the last sync action.
    private var lastSyncTime: Long = 0
    // We check 2 timestamps when reading from the cloud:
    // - cloud file mtime.
    // - timestamp string written in the file.
    private var lastCloudMtime: Long = 0
    private var lastUpdateTime: String = ""
    // Previously seen open URLs in local device.
    private var prevUrls: Set<String> = setOf<String>()
    // URLs received from the cloud but not yet open (i.e. throttled).
    private var waitingUrls: List<String> = listOf()
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
    private var cachedUrls: MutableMap<String, CachedUrlInfo> = mutableMapOf()
    // ID of the next cached URL.
    private var nextCachedUrlId: Long = 0
    // URLs that should be, but are not yet, cached.
    private var urlsToCache: MutableList<String> = mutableListOf()
    // URLs that we recently worked on and should avoid duplicate work.
    private var recentlyCachedUrls: MutableSet<String> = mutableSetOf()
    private var recentlyAdjustedUrls: MutableSet<String> = mutableSetOf()
    private var offline: Boolean = false

    // For loading configuration file flexibly.
    private val configLoader: ConfigLoader<CloudSyncerConfig>

    // For formatting date strings in BrowserState.
    private val stateDateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss")

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
        if (webView.albumUrl.startsWith("http") &&
            prevUrls.isNotEmpty() && webView.albumUrl !in prevUrls) {
            scheduleForceSync()
        }

        helper.handler.postDelayed({
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
        }, 2_000)
    }

    fun onPageRemoved() = scheduleForceSync()

    fun onPageScrolled() {
        if (hasForceSync()) scheduleForceSync()  // postpone
    }

    fun handleUri(url: String): Boolean {
        if (skipperUrlRegex.matches(url)) {
            helper.log(Log.DEBUG, "Skipping URL: $url")
            return true
        }
        return false
    }

    init {
        helper.log("Initializing CloudSyncer")
        configLoader = ConfigLoader(name, CloudSyncerConfig.serializer(),
            context, registry) { config, _ ->
            applyConfig(config)
            start()
        }
    }

    private fun applyConfig(config: CloudSyncerConfig) {
        this.config = config

        helper.externalLogging = config.logging
        helper.displayMs = config.display * 1_000L

        agent.applyConfig(config)
    }

    // Starts periodic sync.
    // Precondition: configuration is ready.
    private fun start() {
        helper.log("Starting")
        readerUrlRegex = Regex(config.reader)
        skipperUrlRegex = Regex(config.skipper)
        loadState()
        updateCachePages()

        agent.start()
        startTimer()
    }

    // Starts timer for periodical sync.
    private fun startTimer() {
        timer(
            daemon = true, initialDelay = config.startup * 1_000L,
            period = config.heartbeat * 1_000L
        ) {
            try {
                heartbeat()
            } catch (e: Exception) {
                helper.log(Log.ERROR, "Heartbeat failed: ${e.stackTraceToString()}")
            }
        }
    }

    private fun heartbeat() {
        // Checks offline status.
        val transports = mutableListOf<String>()
        helper.isOffline(transports).let {
            if (it != offline) {
                offline = it
                val status = if (offline) "offline" else "online [${transports.joinToString()}]"
                helper.display("Network change: $status")
            }
        }

        // Decides actions to take.
        val now = Date().time
        var shortcut = true
        if (!offline) {
            val cur = forceSyncTime.get()
            if (now >= cur) {
                forceSyncTime.compareAndSet(cur, Long.MAX_VALUE)
                shortcut = false
            } else if (now >= lastSyncTime + config.interval * 1_000L) {
                shortcut = false
            }
        }

        var backfilling = true
        var caching = true
        if (shortcut) {  // shortcut path
            // Note: theoretically we shouldn't call size() here in timer thread
            backfilling = (browserContainer.size() < config.slots * .7 && waitingUrls.isNotEmpty())
            caching = (urlsToCache.isNotEmpty() && !offline && !hasForceSync())
        } else {  // full path
            lastSyncTime = now  // update even if action fails
            // Note: prevent infinite memory growth.
            if (recentlyCachedUrls.size > MAX_RECENTLY_WORKED_URLS) recentlyCachedUrls.clear()
            if (recentlyAdjustedUrls.size > MAX_RECENTLY_WORKED_URLS) recentlyAdjustedUrls.clear()
        }

        // Take actions.
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
        helper.log("Loaded: ${waitingUrls.size} waiting, ${recentUrls.size} recent, ${cachedUrls.size} cached")
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
        helper.log("Sync started: shortcut=$shortcut, sending=${config.sending}, receiving=${config.receiving}")

        // Gathers local information.
        val openUrls = listUrls()
        if (openUrls.isEmpty()) {
            helper.log("Skipping sync: no local URLs found")
            return
        }
        val closedUrls = prevUrls.subtract(openUrls)
        closedUrls.forEach { recentUrls[it] = now }  // will inspect when merging from cloud
        helper.log("Local URLs: open=${openUrls.size}, closed=${closedUrls.size}, waiting=${waitingUrls.size}")

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
    // - Prefer to open newest URLs.
    private fun applyMerge(openUrls: Set<String>, merger: Merger) {
        // Calculates URLs to open and close. Updates local state accordingly.
        val urlsToClose = openUrls intersect merger.urlsToClose
        waitingUrls = merger.urlsToOpen.toList() + waitingUrls.filterNot { it in merger.urlsToClose }
        prevUrls = openUrls subtract urlsToClose
        var urlsToOpenFirst: List<String> = listOf()
        var urlsToOpenLast: List<String> = listOf()
        val slots = (config.slots - openUrls.size + urlsToClose.size).coerceIn(0, waitingUrls.size)
        if (slots > 0) {
            val slotsFirst = maxOf(slots - urlsToClose.size, 1)
            val slotsLast = slots - slotsFirst
            urlsToOpenFirst = waitingUrls.take(slotsFirst)
            waitingUrls = waitingUrls.drop(slotsFirst)
            urlsToOpenLast = waitingUrls.take(slotsLast)
            waitingUrls = waitingUrls.drop(slotsLast)
            prevUrls = prevUrls union urlsToOpenFirst union urlsToOpenLast
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

        // Gives user an update.
        if (merger.cloudSource.isNotEmpty()) {
            helper.display("Synced with ${merger.cloudSource}: $slots opened, ${urlsToClose.size} closed, ${waitingUrls.size} waiting")
        } else if (waitingUrls.isNotEmpty()) {
            helper.display("${waitingUrls.size} waiting")
        }
    }

    // Cleans cache of recently closed URLs. Removes obsolete URLs and limit cache size.
    private fun cleanRecentUrls(now: Long) {
        recentUrls = recentUrls.filterValues { timestamp ->
            now - timestamp < config.lifetime * 1_000L
        }.toMutableMap()
        if (recentUrls.size <= config.recents) return
        val threshold = recentUrls.values.sortedDescending()[config.recents]
        recentUrls = recentUrls.filterValues { it > threshold }.toMutableMap()
    }

    // Reads information from cloud, merges with local information and write to `merger`.
    // Returns false if read fails or the cloud version is obsolete.
    private fun mergeFromCloud(merger: Merger): Boolean {
        val cloudMtime = agent.readCloudMtime()
        if (cloudMtime != null && cloudMtime <= lastCloudMtime) {
            helper.log("Ignore obsolete cloud state: now=$cloudMtime prev=$lastCloudMtime")
            return false
        }

        agent.readFromCloud()?.let { bytes ->
            val state: BrowserState = CloudSyncerAgent.decodeJson(bytes)
            helper.log("Received state from '${state.name}': open=${state.urls.size} closed=${state.closed.size} [time: '${state.timestamp}']")
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
                    helper.log("Ignore obsolete cloud state: now=${state.timestamp} prev=$lastUpdateTime")
                }
            } else {
                helper.log(Log.ERROR, "Ignore unrecognized version: [${state.version}]")
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
            helper.log("No update to write to cloud")
            return false
        }
        if (merger.urlsInCloud == merger.curUrls &&
            merger.closedUrlsInCloud.entries.containsAll(recent)) {
            // Our data is a subset of the cloud version. It doesn't make sense for us to overwrite
            // the cloud version.
            helper.log("Skip overwrite of the cloud version")
            return false
        }
        val timestamp = stateDateFormat.format(Date())
        val state = BrowserState(VERSION, config.name, timestamp, merger.curUrls, recentUrls)
        helper.log("Writing state to cloud: open=${state.urls.size} closed=${state.closed.size}")
        val bytes = CloudSyncerAgent.encodeJson(state)
        agent.writeToCloud(bytes)?.let { mtime ->
            writtenUrls = merger.curUrls
            writtenClosedUrls = recentUrls
            lastCloudMtime = mtime
            lastUpdateTime = timestamp
            return true
        }
        return false
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
            helper.display("Cache: ${urlsToCache.size} to be cached, now ${cachedUrls.size}")
        }
    }

    // Cache URLs in background.
    private fun cache(now: Long) {
        if (!config.caching || urlsToCache.isEmpty()) return

        // Note: each time we only attempt to cache a few URLs, so that the total time spent and
        // the risk of interruption is acceptable.
        val urls = urlsToCache.take(config.slots)
        val attempted = mutableSetOf<String>()
        helper.runAndWait(period = config.wait * 1_000L, max = urls.size,
                          skip = { hasForceSync() }) { index ->
            val url = urls[index]
            synchronized(attempted) {
                attempted.add(url)
            }
            cacheUrl(headlessWebView, url, now)
        }

        synchronized(attempted) {
            helper.log(Log.DEBUG, "Cache: ${attempted.size} / ${urls.size} attempted")
            urlsToCache.removeIf { it in attempted }
        }
    }

    // Caches the URL to a local file.
    private fun cacheUrl(webView: EBWebView, url: String, now: Long) {
        context.externalCacheDir?.let {
            // Note: filename pattern must be synced with cacheRegex.
            val path = "${it.path}/cache-$nextCachedUrlId.mht"
            nextCachedUrlId++
            helper.log(Log.DEBUG, "Caching $url to $path")
            val steps = listOf({
                webView.toggleReaderMode()  // note: toggle twice to load lazy images
            }, {
                webView.toggleReaderMode()
            }, {
                if (isLoaded(webView)) {
                    val title = webView.albumTitle  // note: needs delay to work
                    webView.saveWebArchive(path, false) {
                        synchronized(cachedUrls) {
                            cachedUrls[url] = CachedUrlInfo(path, title, now)
                        }
                    }
                }
            })
            webView.setOnPageFinishedAction {
                helper.chain(1_000L, steps)
            }
            webView.loadUrl(url)
        }
    }

    // Cleans cache of recently closed URLs. Removes obsolete URLs and limit cache size.
    private fun cleanCachedUrls(now: Long) {
        var count = 0
        cachedUrls = cachedUrls.filterValues {
            now - it.timestamp < config.lifetime * 1_000L
        }.toMutableMap()
        context.externalCacheDir?.let { dir ->
            val cachedPaths = cachedUrls.map { it.value.path }.toSet()
            dir.listFiles()?.filter { cacheRegex.matches(it.path) && !cachedPaths.contains(it.path) }
                ?.map {
                    helper.log(Log.DEBUG, "Deleting cache file: ${it.path}")
                    it.delete()
                    ++count
                }
        }

        if (count > 0) {
            helper.display("Cache: $count purged")
        }
    }

    // Preloads inactive tabs in background.
    private fun preload(offline: Boolean) {
        if (!config.preloading) return

        // Prepare candidates to preload.
        val candidates = mutableListOf<Pair<EBWebView, String>>()
        helper.runAndWait(period = 1_000L) {
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
        helper.runAndWait(period = config.wait * 1_000L, max = candidates.size,
                          skip = { !offline && hasForceSync() }) { index ->
            val (webView, url) = candidates[index]
            if (!isLoaded(webView) && webView.initAlbumUrl == url) {
                preloadUrl(webView, url, offline)
            }
        }
        helper.log("Preloaded ${candidates.size} inactive tabs")
    }

    // Preloads the URL in the specified WebView.
    private fun preloadUrl(webView: EBWebView, url: String, offline: Boolean) {
        helper.log(Log.DEBUG, "Preloading $url")
        if (offline) {
            loadUrlFromCache(webView, url)
        } else {
            webView.loadUrl(url)
        }
    }

    // Load URL from local cache if available.
    private fun loadUrlFromCache(webView: EBWebView, url: String) {
        val path = synchronized(cachedUrls) {
            cachedUrls[url]?.path ?: ""
        }
        if (path.isBlank() || !File(path).exists()) return
        helper.log(Log.DEBUG, "Loading $url from cache: $path")
        webView.initAlbumUrl = url
        webView.loadUrl("file://$path")
    }

    // Load page title from local cache if available.
    private fun loadTitleFromCache(webView: EBWebView, url: String) {
        val title = synchronized(cachedUrls) {
            cachedUrls[url]?.title ?: ""
        }
        if (title.isBlank()) return
        webView.albumTitle = title
    }

    // Enter or exit reader mode as appropriate.
    private fun adjustReaderMode(webView: EBWebView, url: String) {
        if (!recentlyAdjustedUrls.contains(url) &&
            readerUrlRegex.matches(url) != webView.isReaderModeOn) {
            helper.log(Log.DEBUG, "Toggling reader mode from ${webView.isReaderModeOn} for $url")
            recentlyAdjustedUrls.add(url)
            webView.toggleReaderMode()
        }
    }

    // Sets initial URL and title for each page loaded from cache at startup.
    private fun updateCachePages() {
        val lookup = mutableMapOf<String, Pair<String, String>>()  // path -> (url, title)
        synchronized(cachedUrls) {
            cachedUrls.forEach { url, info ->
                lookup["file://${info.path}"] = Pair(url, info.title)
            }
        }
        helper.handler.postDelayed({
            browserContainer.list().forEach { controller ->
                val webView = controller as EBWebView
                val url = webView.albumUrl.ifBlank { webView.initAlbumUrl }
                lookup[url]?.let { pair ->
                    webView.initAlbumUrl = pair.first
                    webView.albumTitle = pair.second
                    helper.log(Log.DEBUG, "Updated cache page: $url -> [${webView.albumTitle}] ${webView.initAlbumUrl}")
                }
            }
        }, config.startup * 1_000L)
    }

    // Lists normalized URLs of the currently open web pages in the local browser.
    private fun listUrls(): Set<String> {
        lateinit var result: Set<String>
        helper.runAndWait(period = 1_000L) {
            val controllers = browserContainer.list()
            val urls = controllers
                .filter { !it.isTranslatePage }
                .map { normalizeUrl(it) }
                .filter { it.startsWith("http") }
                .toSet()
            helper.log("Listed ${urls.size} URLs from ${controllers.size} tabs")
            result = urls
        }
        return result
    }

    // Opens the specified URLs in the local browser.
    private fun openUrls(urls: Iterable<String>) {
        urls.forEach { url ->
            helper.log(Log.DEBUG, "Opening $url")
            val subset: Set<String> = setOf(url)
            helper.handler.post {
                openUrlsFunc(subset)
            }
            Thread.sleep(config.wait * 1_000L)
        }
    }

    // Closes the specified URLs in the local browser.
    private fun closeUrls(urls: Iterable<String>) {
        helper.handler.post {
            val controllers: Set<AlbumController> = browserContainer.list()
                .filter { !it.isTranslatePage }
                .filter { urls.contains(normalizeUrl(it)) }
                .toSet()
            controllers.forEach {
                browserController.removeAlbum(it, false)
            }
        }
    }

    private fun hasForceSync() = forceSyncTime.get() < Long.MAX_VALUE

    private fun scheduleForceSync() {
        forceSyncTime.set(Date().time + config.forceSync * 1_000L)
    }
}