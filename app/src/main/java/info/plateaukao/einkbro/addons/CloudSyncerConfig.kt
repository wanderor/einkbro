package info.plateaukao.einkbro.addons

import kotlinx.serialization.Serializable

// Represents URL configuration data.
@Serializable
data class UrlConfig(
    // Regular expression pattern for URLs that should apply this config.
    val pattern: String = "",
    // Whether to preload in reader mode.
    val reading: Boolean = false,
    // Whether to skip loading.
    val skipping: Boolean = false,
    // If not empty, only keep the specified query params.
    val params: List<String> = listOf(),
)

// Represents configuration data of CloudSyncer.
@Serializable
data class CloudSyncerConfig(
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
    // Time to wait before a forced sync in seconds.
    val forceSync: Int = 180,
    // Maximal number of recently closed URLs to be cached.
    val recents: Int = 200,
    // Maximal duration in seconds for a URL to stay in cache or queue.
    val lifetime: Int = 7 * 86400,  // 7 days
    // Interval between heartbeats in seconds.
    val heartbeat: Int = 30,
    // Duration to display a message in seconds.
    val display: Int = 15,
    // Size of vertical scroll bar, or 0 for auto.
    val scroller: Int = 16,
    // List of URL configurations.
    val urls: List<UrlConfig> = listOf(),
    // For debugging release version.
    // Whether to turn on logging to external file.
    val logging: Boolean = false
)
