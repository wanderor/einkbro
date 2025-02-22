package info.plateaukao.einkbro.addons

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.text.Charsets.UTF_8

// Interface for agent implementations to be used with CloudSyncer.
interface CloudSyncerAgent {
    fun applyConfig(config: CloudSyncerConfig)

    fun start()

    fun readCloudMtime(): Long?

    fun readFromCloud(): ByteArray?

    fun writeToCloud(bytes: ByteArray): Long?

    companion object {
        inline fun <reified Data> decodeJson(bytes: ByteArray): Data {
            return Json.decodeFromString<Data>(bytes.toString(UTF_8))
        }

        inline fun <reified Data> encodeJson(data: Data): ByteArray {
            return Json.encodeToString(data).toByteArray(UTF_8)
        }
    }
}