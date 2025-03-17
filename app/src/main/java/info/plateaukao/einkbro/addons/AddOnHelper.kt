package info.plateaukao.einkbro.addons

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

// Base class for functionality unit.
class AddOnHelper(
    private val name: String,
    private val context: Context,
    private val view: View,
    private val tag: String,
) {
    companion object {
        // For debugging release version.
        // Whether to always turn on logging to external file.
        private const val ALWAYS_EXTERNAL_LOGGING: Boolean = false
    }

    var externalLogging: Boolean = false
    var displayMs: Long = 5_000L

    // For GUI interactions.
    val handler: Handler = Handler(Looper.getMainLooper())

    private var lastDisplayTime: Long = 0

    // For logging to external file in local device.
    private var externalLogFile: File? = null

    // For formatting date strings in logging.
    private val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    // Runs an action and waits for its completion.
    // `period`: period to wait in each round in milliseconds.
    // `max`: maximal number of iterations to run. Specifically, 1 to run once only.
    // `skip`: callback that returns true to skip remaining rounds, or false to continue.
    // `action`: function to run in each round.
    fun runAndWait(period: Long, max: Int = 1, skip: () -> Boolean = { false },
                   action: (Int) -> Unit) {
        for (index in 0 until max) {
            if (skip()) break
            handler.post {
                action(index)
            }
            Thread.sleep(period)
        }
    }

    fun chain(steps: Iterable<Pair<() -> Unit, Long>>) {
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

    fun chain(interval: Long, steps: Iterable<() -> Unit>) {
        val iterator = steps.iterator()
        val pairs = generateSequence {
            if (iterator.hasNext()) Pair(iterator.next(), interval) else null
        }
        chain(pairs.asIterable())
    }

    fun isOffline(transports: MutableList<String>? = null): Boolean {
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

    // Displays a message shortly in GUI.
    fun display(message: String) {
        fun helper() {
            val now = Date().time
            val delay = lastDisplayTime + displayMs - now
            if (delay > 0) {
                handler.postDelayed({ helper() }, delay)
            } else {
                lastDisplayTime = now
                val bar = Snackbar.make(context, view, message, displayMs.toInt())
                bar.setAction("dismiss") { bar.dismiss() }
                bar.show()
            }
        }

        log(message)
        handler.post { helper() }
    }

    // Logs at INFO priority.
    fun log(message: String) {
        log(Log.INFO, message)
    }

    // Logs at the specified priority in both standard logging and, if turned on,
    // external logging file.
    fun log(priority: Int, message: String) {
        Log.println(priority, tag, message)

        // For debugging release version.
        if (externalLogFile == null && (externalLogging || ALWAYS_EXTERNAL_LOGGING)) {
            context.getExternalFilesDir(null)?.let {
                val path = "${it.absolutePath}/$name.log"
                externalLogFile = File(path)
                Log.i(tag, "Opened external log file at $path")
            }
        }
        externalLogFile?.let {
            val timestamp = logDateFormat.format(Date())
            val text = "$timestamp  $message\n"
            try {
                it.appendText(text)
            } catch (e: IOException) {
                // ignored
            }
        }
    }
}