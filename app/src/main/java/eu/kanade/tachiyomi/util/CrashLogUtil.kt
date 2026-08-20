package eu.kanade.tachiyomi.util

import android.content.Context
import android.os.Build
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.lang.withUIContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.OffsetDateTime
import java.time.ZoneId

class CrashLogUtil(
    private val context: Context,
    private val extensionManager: ExtensionManager = Injekt.get(),
) {

    suspend fun dumpLogs(exception: Throwable? = null) = withNonCancellableContext {
        try {
            val file = context.createFileInCacheDir("komikku_crash_logs.txt")

            file.appendText(getDebugInfo() + "\n\n")
            getExtensionsInfo()?.let { file.appendText("$it\n\n") }
            exception?.let { file.appendText(formatException(it) + "\n\n") }

            Runtime.getRuntime().exec("logcat *:E -d -v year -v zone -f ${file.absolutePath}").waitFor()

            val uri = file.getUriCompat(context)
            context.startActivity(uri.toShareIntent(context, "text/plain"))
        } catch (_: OutOfMemoryError) {
            // Do not allocate UI work while reporting an out-of-memory crash.
        } catch (_: Throwable) {
            withUIContext { context.toast("Failed to get logs") }
        }
    }

    fun getDebugInfo(): String {
        return """
            App ID: ${BuildConfig.APPLICATION_ID}
            App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.COMMIT_SHA}, ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TIME})
            Build version: ${BuildConfig.COMMIT_COUNT}
            Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}; build ${Build.DISPLAY})
            Device brand: ${Build.BRAND}
            Device manufacturer: ${Build.MANUFACTURER}
            Device name: ${Build.DEVICE} (${Build.PRODUCT})
            Device model: ${Build.MODEL}
            WebView: ${WebViewUtil.getVersion(context)}
            Current time: ${OffsetDateTime.now(ZoneId.systemDefault())}
        """.trimIndent()
    }

    private fun getExtensionsInfo(): String? {
        val availableExtensions = extensionManager.availableExtensionsFlow.value.associateBy { it.pkgName }

        val extensionInfoList = extensionManager.installedExtensionsFlow.value
            .asSequence()
            .sortedBy { it.name }
            .mapNotNull {
                val availableExtension = availableExtensions[it.pkgName]
                val hasUpdate = (availableExtension?.versionCode ?: 0) > it.versionCode

                if (!hasUpdate && !it.isObsolete) return@mapNotNull null

                """
                    - ${it.name}
                      Installed: ${it.versionName} / Available: ${availableExtension?.versionName ?: "?"}
                      Orphaned: ${it.isObsolete}
                """.trimIndent()
            }
            .take(MAX_EXTENSION_LINES)
            .toList()

        return if (extensionInfoList.isNotEmpty()) {
            (listOf("Problematic extensions:") + extensionInfoList)
                .joinToString("\n")
        } else {
            null
        }
    }

    private fun formatException(exception: Throwable): String {
        if (exception is OutOfMemoryError) {
            return "${exception::class.java.name}: ${exception.message.orEmpty().take(MAX_EXCEPTION_TEXT_LENGTH)}"
        }
        val output = StringBuilder(MAX_EXCEPTION_TEXT_LENGTH)
        output.append(exception::class.java.name)
        exception.message?.let { output.append(": ").append(it.take(MAX_MESSAGE_LENGTH)) }
        output.append('\n')
        exception.stackTrace.take(MAX_STACK_FRAMES).forEach { frame ->
            if (output.length < MAX_EXCEPTION_TEXT_LENGTH) output.append("\tat ").append(frame).append('\n')
        }
        return output.toString().take(MAX_EXCEPTION_TEXT_LENGTH)
    }

    private companion object {
        const val MAX_EXCEPTION_TEXT_LENGTH = 32_768
        const val MAX_MESSAGE_LENGTH = 2_048
        const val MAX_STACK_FRAMES = 128
        const val MAX_EXTENSION_LINES = 200
    }
}
