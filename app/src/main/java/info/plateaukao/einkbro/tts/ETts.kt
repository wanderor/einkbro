package info.plateaukao.einkbro.tts

import icu.xmc.edgettslib.entity.VoiceItem
import info.plateaukao.einkbro.EinkBroApplication
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// ported from https://github.com/9ikj/Edge-TTS-Lib/
class ETts {
    private var headers: HashMap<String, String> = HashMap<String, String>().apply {
        put("Origin", EDGE_ORIGIN)
        put("Pragma", "no-cache")
        put("Cache-Control", "no-cache")
        put("User-Agent", EDGE_UA)
    }
    private val format = "audio-24khz-48kbitrate-mono-mp3"
    private val storage = EinkBroApplication.instance.cacheDir.absolutePath

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun tts(voice: VoiceItem, speed: Int, content: String): File? =
        suspendCoroutine { continuation ->
            val processedContent = removeIncompatibleCharacters(content)
            if (processedContent.isNullOrBlank()) {
                continuation.resume(null)
            }
            val storageFolder = File(storage)
            if (!storageFolder.exists()) {
                storageFolder.mkdirs()
            }

            val dateStr = dateToString(Date())
            val reqId = uuid()
            var fileName = "$reqId.mp3"
            val audioFormat = mkAudioFormat(dateStr, format)
            val ssml = mkssml(
                voice.locale,
                voice.name,
                processedContent,
                "+0Hz",
                "+${speed - 100}%",
                "+0%"
            )
            val ssmlHeadersPlusData = ssmlHeadersPlusData(reqId, dateStr, ssml)

            val storageFile = File(storage)
            if (!storageFile.exists()) {
                storageFile.mkdirs()
            }

            val request = Request.Builder()
                .url(EDGE_URL)
                .headers(headers.toHeaders())
                .build()

            try {
                val client = okHttpClient.newWebSocket(
                    request,
                    object : TTSWebSocketListener(storage, fileName) {
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            val file = File(storage, fileName)
                            if (file.exists()) {
                                continuation.resume(file)
                            } else {
                                continuation.resume(null)
                            }
                        }
                    })
                client.send(audioFormat)
                client.send(ssmlHeadersPlusData)
            } catch (e: Throwable) {
                e.printStackTrace()
                continuation.resume(null)
            }
        }

    private fun dateToString(date: Date): String {
        val sdf = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT'Z (zzzz)", Locale.getDefault()
        )
        return sdf.format(date)
    }

    private fun uuid(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }

    private fun removeIncompatibleCharacters(input: String): String {
        if (input.isBlank()) {
            return ""
        }
        val output = StringBuilder()
        for (element in input) {
            val code = element.code
            if (code in 0..8 || code in 11..12 || code in 14..31) {
                output.append(" ")
            } else {
                output.append(element)
            }
        }
        return output.toString()
    }

    private fun mkAudioFormat(dateStr: String, format: String): String =
        "X-Timestamp:" + dateStr + "\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"},\"outputFormat\":\"" + format + "\"}}}}\n"


    private fun mkssml(
        locate: String,
        voiceName: String,
        content: String,
        voicePitch: String,
        voiceRate: String,
        voiceVolume: String,
    ): String =
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='" + locate + "'>" +
                "<voice name='" + voiceName + "'><prosody pitch='" + voicePitch + "' rate='" + voiceRate + "' volume='" + voiceVolume + "'>" +
                content + "</prosody></voice></speak>"


    private fun ssmlHeadersPlusData(requestId: String, timestamp: String, ssml: String): String =
        "X-RequestId:" + requestId + "\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:" + timestamp + "Z\r\n" +
                "Path:ssml\r\n\r\n" + ssml

    companion object {
        const val EDGE_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        const val EDGE_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/99.0.4844.74 Safari/537.36 Edg/99.0.1150.55"
        const val EDGE_ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        const val VOICES_LIST_URL =
            "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list?trustedclienttoken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    }
}