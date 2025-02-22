package info.plateaukao.einkbro.addons

import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

// Load configuration from path in the following order:
// - CONFIG_INSTALL_DIR
// - one of external files directories
class ConfigLoader<ConfigType: Any>(
    private val name: String,
    private val serializer: KSerializer<ConfigType>,
    private val context: Context,
    private val registry: ActivityResultRegistry,
    private val callback: (ConfigType, Boolean) -> Unit) {  // config, is_new_config
    companion object {
        // Suffix of the configuration file.
        private const val CONFIG_SUFFIX: String = ".json"

        // Directory in Android device where we detects new config to be installed.
        // If user wants to overwrite the existing config file with a new one, s/he
        // can put the new config file in this directory, and the app will detect it
        // during startup.
        private const val CONFIG_INSTALL_DIR: String = "/storage/emulated/0/Download"

        private val TAG = ConfigLoader::class.java.simpleName
    }

    private val configFileName = name + CONFIG_SUFFIX
    private var config: ConfigType? = null

    // For launching File Chooser to pick a new config file to install.
    private var configLauncher: ActivityResultLauncher<Array<String>>? = null

    init {
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
        val install_file = File(install_dir, configFileName)
        if (!install_file.exists()) {
            for (dir in context.getExternalFilesDirs(null)) {
                dir?.let {
                    val file = File(dir, configFileName)
                    if (initConfigFromFile(file)) {
                        callback(config!!, false)
                        return
                    }
                }
            }
        }
        pickConfigAndProceed()
    }

    // Initializes configuration from the specified file.
    private fun initConfigFromFile(file: File): Boolean {
        Log.i(TAG, "Checking path for config: ${file.path}")
        if (file.exists()) {
            try {
                val text = file.readText()
                config = Json.decodeFromString(serializer, text)
                //config = decodeJson(text)
                Log.i(TAG, "Loaded config from ${file.path}")
                return true
            } catch (e: IOException) {
                Log.i(TAG, "Failed to load config: ${e.toString()}")
            }
        }
        return false
    }

    // Launches File Chooser for user to pick a config file to be installed.
    private fun pickConfigAndProceed() {
        configLauncher = registry.register(name, OpenDocument()) { uri ->
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
                val file = File(dir, configFileName)
                try {
                    file.writeBytes(bytes)
                    Log.i(TAG, "Wrote config to $file")
                    if (initConfigFromFile(file)) {
                        callback(config!!, true)
                    }
                    return
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to install config to $file: ${e.toString()}")
                } catch (e: SerializationException) {
                    Log.e(TAG, "Failed to parse config file: ${e.toString()}")
                }
            }
        }
    }
}