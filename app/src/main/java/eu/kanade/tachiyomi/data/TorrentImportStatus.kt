package eu.kanade.tachiyomi.data

import kotlinx.coroutines.flow.MutableStateFlow

/** Progress shared by the Torrent screen, the app-wide header, and the notification. */
data class TorrentImportSnapshot(
    val total: Int = 0,
    val completed: Int = 0,
    val added: Int = 0,
    val failed: Int = 0,
    val torrentName: String = "",
    val currentTitle: String = "",
    val message: String = "",
    val running: Boolean = false,
)

class TorrentImportStatus : BannerProgressStatus() {
    val state = MutableStateFlow(TorrentImportSnapshot())

    fun begin(torrentName: String, total: Int = 0, message: String = "Resolving torrent metadata…") {
        state.value = TorrentImportSnapshot(
            total = total,
            torrentName = torrentName,
            message = message,
            running = true,
        )
        start()
        updateProgress(0f)
    }

    fun setTotal(total: Int, message: String? = null) {
        val old = state.value
        state.value = old.copy(
            total = total,
            message = message ?: old.message,
            running = true,
        )
        updateProgress(if (total == 0) 0f else old.completed.toFloat() / total)
    }

    fun updateMessage(message: String) {
        state.value = state.value.copy(message = message, running = true)
    }

    fun record(title: String, wasAdded: Boolean, detail: String? = null) {
        val old = state.value
        val completed = old.completed + 1
        val failed = old.failed + if (wasAdded) 0 else 1
        state.value = old.copy(
            completed = completed,
            added = old.added + if (wasAdded) 1 else 0,
            failed = failed,
            currentTitle = title,
            message = detail ?: if (wasAdded) "Added $title" else "Failed $title",
            running = true,
        )
        updateProgress(if (old.total == 0) 0f else completed.toFloat() / old.total)
    }

    fun finish(message: String) {
        state.value = state.value.copy(message = message, running = false)
        updateProgress(if (state.value.total == 0) 1f else state.value.completed.toFloat() / state.value.total)
        stop()
    }

    fun fail(message: String) {
        state.value = state.value.copy(message = message, running = false)
        stop()
    }
}
