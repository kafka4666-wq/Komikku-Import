package eu.kanade.tachiyomi.crash

import android.content.Context
import android.content.Intent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class GlobalExceptionHandler private constructor(
    private val applicationContext: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler,
    private val activityToBeLaunched: Class<*>,
) : Thread.UncaughtExceptionHandler {

    object ThrowableSerializer : KSerializer<Throwable> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("Throwable", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): Throwable =
            Throwable(message = decoder.decodeString())

        override fun serialize(encoder: Encoder, value: Throwable) =
            encoder.encodeString(formatThrowable(value))
    }

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        // Crash reporting must never become a second crash, especially when the
        // original failure is an OutOfMemoryError during synchronization.
        try {
            if (exception is OutOfMemoryError) {
                logcat(LogPriority.ERROR) { "Uncaught OutOfMemoryError; crash details were bounded." }
            } else {
                logcat(priority = LogPriority.ERROR, throwable = exception)
            }
        } catch (_: Throwable) {
            // Ignore logging failures while the process is already failing.
        }
        try {
            launchActivity(applicationContext, activityToBeLaunched, exception)
        } catch (_: Throwable) {
            // Fall through to Android's default crash handling.
        }
        defaultHandler.uncaughtException(thread, exception)
    }

    private fun launchActivity(
        applicationContext: Context,
        activity: Class<*>,
        exception: Throwable,
    ) {
        val intent = Intent(applicationContext, activity).apply {
            putExtra(INTENT_EXTRA, Json.encodeToString(ThrowableSerializer, exception))
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        applicationContext.startActivity(intent)
    }

    companion object {
        private const val INTENT_EXTRA = "Throwable"
        private const val MAX_CRASH_TEXT_LENGTH = 32_768
        private const val MAX_MESSAGE_LENGTH = 2_048
        private const val MAX_CAUSE_DEPTH = 8

        private fun formatThrowable(throwable: Throwable): String {
            if (throwable is OutOfMemoryError) {
                return "${throwable::class.java.name}: ${throwable.message.orEmpty().take(MAX_MESSAGE_LENGTH)}"
            }
            val output = StringBuilder(MAX_CRASH_TEXT_LENGTH)
            val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            appendThrowable(output, throwable, visited, 0)
            return output.toString()
        }

        private fun appendThrowable(
            output: StringBuilder,
            throwable: Throwable,
            visited: MutableSet<Throwable>,
            depth: Int,
        ) {
            if (output.length >= MAX_CRASH_TEXT_LENGTH || depth > MAX_CAUSE_DEPTH || !visited.add(throwable)) return
            output.append(throwable::class.java.name)
            throwable.message?.let { output.append(": ").append(it.take(MAX_MESSAGE_LENGTH)) }
            output.append('\n')
            throwable.stackTrace.forEach { frame ->
                if (output.length < MAX_CRASH_TEXT_LENGTH) output.append("\tat ").append(frame).append('\n')
            }
            throwable.cause?.let {
                if (output.length < MAX_CRASH_TEXT_LENGTH) {
                    output.append("Caused by: ")
                    appendThrowable(output, it, visited, depth + 1)
                }
            }
        }

        fun initialize(
            applicationContext: Context,
            activityToBeLaunched: Class<*>,
        ) {
            val handler = GlobalExceptionHandler(
                applicationContext,
                Thread.getDefaultUncaughtExceptionHandler() as Thread.UncaughtExceptionHandler,
                activityToBeLaunched,
            )
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }

        fun getThrowableFromIntent(intent: Intent): Throwable? {
            return try {
                Json.decodeFromString(ThrowableSerializer, intent.getStringExtra(INTENT_EXTRA)!!)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Wasn't able to retrieve throwable from intent" }
                null
            }
        }
    }
}
