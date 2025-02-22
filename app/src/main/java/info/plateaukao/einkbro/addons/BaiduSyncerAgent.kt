package info.plateaukao.einkbro.addons

import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.annotations.Expose
import kotlinx.serialization.Serializable
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
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// Represents file stat in the cloud.
// The format is defined by Baidu Pan, and we only expose the subset of interest.
@Serializable
private data class BaiduFileStat(
    // File mtime.
    @Expose
    val mtime: Long = 0,
)

// Represents file metadata in the cloud.
// The format is defined by Baidu Pan, and we only expose the subset of interest.
@Serializable
private data class BaiduFileMeta(
    // List of file stats.
    @Expose
    val list: List<BaiduFileStat> = listOf(),
)

// Retrofit service that represents Baidu Pan cloud service.
private interface BaiduCloudService {
    // Reads data file metadata from the cloud.
    @GET("file?method=meta")
    fun readMeta(
        @Query("access_token") token: String,
        @Query("path") path: String
    ): Call<BaiduFileMeta>

    // Reads data from the cloud.
    @GET("file?method=download")
    fun read(
        @Query("access_token") token: String,
        @Query("path") path: String
    ): Call<ResponseBody>  // we will parse by ourselves

    // Writes data to the cloud.
    @Multipart
    @POST("file?method=upload&ondup=overwrite")
    fun write(
        @Query("access_token") token: String,
        @Query("path") path: String,
        @Part file: MultipartBody.Part
    ): Call<BaiduFileStat>
}

// Reads from and writes to Baidu Pan cloud service.
class BaiduSyncerAgent(
    private val helper: AddOnHelper,
    private val cloudPath: String,
) : CloudSyncerAgent {
    companion object {
        // Base URL of Baidu Pan cloud service.
        private const val CLOUD_BASE_URL: String = "https://pan.baidu.com/rest/2.0/xpan/"
    }

    private var token: String = ""
    private var retries: Int = 0

    // Retrofit.
    private lateinit var retrofit: Retrofit
    private lateinit var service: BaiduCloudService

    override fun applyConfig(config: CloudSyncerConfig) {
        token = config.token
        retries = config.retries
    }

    // Initializes Retrofit.
    override fun start() {
        val gson = GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()
        retrofit = Retrofit.Builder()
            .baseUrl(CLOUD_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        service = retrofit.create(BaiduCloudService::class.java)
    }

    // Reads file mtime of the cloud information.
    override fun readCloudMtime(): Long? {
        // Note: no need for retry
        helper.log("Getting cloud mtime")
        try {
            val call = service.readMeta(token, cloudPath)
            val response = call.execute()
            if (response.isSuccessful) {
                response.body()?.let { meta ->
                    // Cloud might return error. Also see explanation in writeToCloud().
                    @Suppress("SENSELESS_COMPARISON")
                    if (meta.list == null) {
                        helper.log(Log.ERROR, "Metadata incorrect or broken")
                    } else {
                        val mtime = meta.list.get(0).mtime
                        helper.log("Got cloud mtime: $mtime")
                        return mtime
                    }
                }
            }
        } catch (e: IOException) {
            helper.log(Log.ERROR, "Failed to check cloud mtime: ${e.toString()}")
        }
        return null
    }

    override fun readFromCloud(): ByteArray? {
        helper.log("Reading data from cloud")
        for (i in 0..retries) {
            try {
                val call = service.read(token, cloudPath)
                val response = call.execute()
                if (response.isSuccessful) {
                    response.body()?.let {
                        val bytes = GZIPInputStream(it.byteStream()).readBytes()
                        helper.log("Succeeded to read from cloud (attempt #$i)")
                        return bytes
                    }
                }
                helper.log(
                    Log.ERROR,
                    "Failed to read from cloud (attempt #$i): response - ${response.toString()}"
                )
            } catch (e: IOException) {
                helper.log(
                    Log.ERROR,
                    "Failed to read from cloud (attempt #$i): exception - ${e.toString()}"
                )
            }
        }
        return null
    }

    override fun writeToCloud(bytes: ByteArray): Long? {
        val stream = ByteArrayOutputStream()
        with(GZIPOutputStream(stream)) {
            write(bytes)
            close()
        }
        val body = stream.toByteArray().toRequestBody()
        val part =
            MultipartBody.Part.createFormData(name = "file", filename = cloudPath, body = body)

        for (i in 0..retries) {
            try {
                val call = service.write(token, cloudPath, part)
                val response = call.execute()
                if (response.isSuccessful) {
                    response.body()?.let { stat ->
                        // Note: the comparison below is necessary because we may incidentally
                        // configure `minifyEnabled` and `proguard` rules incorrectly, especially
                        // for release version, which corrupts the reflection mechanism of retrofit
                        // and causes retrofit to assign null to this and other fields.
                        @Suppress("SENSELESS_COMPARISON")
                        if (stat.mtime == null) {
                            helper.log(Log.ERROR, "Stat parsing is broken, likely buggy")
                        } else {
                            helper.log("Succeeded to write to cloud (attempt #$i)")
                            return stat.mtime
                        }
                    }
                } else {
                    helper.log(
                        Log.ERROR,
                        "Failed to write to cloud (attempt #$i): response - ${response.toString()}"
                    )
                }
            } catch (e: IOException) {
                helper.log(
                    Log.ERROR,
                    "Failed to write to cloud (attempt #$i): exception - ${e.toString()}"
                )
            }
        }
        return null
    }
}