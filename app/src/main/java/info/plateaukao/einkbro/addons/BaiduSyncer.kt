package info.plateaukao.einkbro.addons

import android.content.Context
import android.view.View
import androidx.activity.result.ActivityResultRegistry
import info.plateaukao.einkbro.browser.BrowserContainer
import info.plateaukao.einkbro.browser.BrowserController

// Cloud syncer for Baidu Pan.
class BaiduSyncer {
    companion object {
        fun create(context: Context,
                   view: View,
                   registry: ActivityResultRegistry,
                   browserContainer: BrowserContainer,
                   browserController: BrowserController,
                   openUrlsFunc: (Set<String>) -> Unit): CloudSyncer {
            val helper = AddOnHelper("baidu", context, view, TAG)
            val agent = BaiduSyncerAgent(helper, CLOUD_PATH)
            val syncer = CloudSyncer("baidu", context, registry, agent, helper,
                browserContainer, browserController, openUrlsFunc)
            return syncer
        }

        // Tag for logging.
        private const val TAG: String = "BaiduSyncer"

        // Path of the file of interest in the cloud.
        private const val CLOUD_PATH: String = "/apps/bypy/browser.json.gz"
    }
}