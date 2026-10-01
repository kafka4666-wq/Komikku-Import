package exh.ui.batchadd

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.tachiyomi.data.BatchImportStatus
import exh.log.xLogE
import exh.source.ExhPreferences
import exh.ui.batchadd.BatchImportJob
import exh.ui.batchadd.BatchLinkParser
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class BatchAddScreenModel(
    private val exhPreferences: ExhPreferences = Injekt.get(),
) : StateScreenModel<BatchAddState>(BatchAddState()) {
    val isHentaiEnabled by Injekt.get<ExhPreferences>().isHentaiEnabled().asState(screenModelScope)
    private val batchImportStatus: BatchImportStatus = Injekt.get()

    init {
        screenModelScope.launch {
            batchImportStatus.state.collect { snapshot ->
                if (snapshot.total > 0) {
                    mutableState.update {
                        it.copy(
                            progressTotal = snapshot.total,
                            progress = snapshot.completed,
                            added = snapshot.added,
                            failed = snapshot.failed,
                            state = State.PROGRESS,
                            events = snapshot.events,
                        )
                    }
                }
            }
        }
    }

    fun addGalleries(context: Context) {
        val galleries = state.value.galleries
        if (galleries.isBlank()) {
            mutableState.update { it.copy(dialog = Dialog.NoGalleriesSpecified) }
            return
        }
        startImport(context, parseLines(galleries))
    }

    fun importFile(context: Context, uri: Uri) {
        screenModelScope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            xLogE("File import error", throwable)
            mutableState.update { it.copy(state = State.INPUT, dialog = Dialog.FileReadError) }
        }) {
            val urls = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                parseLines(reader.readText())
            }.orEmpty()
            if (urls.isEmpty()) {
                mutableState.update { it.copy(dialog = Dialog.NoGalleriesSpecified) }
            } else {
                startImport(context, urls)
            }
        }
    }

    private fun parseLines(text: String): List<String> =
        BatchLinkParser.parse(text, useExhentai = exhPreferences.enableExhentai().get())

    private fun startImport(context: Context, galleries: List<String>) {
        if (galleries.isEmpty()) {
            mutableState.update { it.copy(dialog = Dialog.NoGalleriesSpecified) }
            return
        }
        BatchImportJob.start(context.applicationContext, galleries)
        mutableState.update {
            it.copy(
                progress = 0,
                progressTotal = galleries.size,
                added = 0,
                failed = 0,
                state = State.PROGRESS,
                events = emptyList(),
            )
        }
    }

    fun pauseImport(context: Context) {
        BatchImportJob.Companion.pause(context.applicationContext)
    }

    fun resumeImport(context: Context) {
        BatchImportJob.Companion.resume(context.applicationContext)
    }

    fun cancelImport(context: Context) {
        BatchImportJob.cancel(context.applicationContext)
        mutableState.update { it.copy(state = State.INPUT) }
    }

    fun finish() {
        mutableState.update { it.copy(progressTotal = 0, progress = 0, galleries = "", state = State.INPUT, events = emptyList()) }
    }

    fun updateGalleries(galleries: String) = mutableState.update { it.copy(galleries = galleries) }
    fun dismissDialog() = mutableState.update { it.copy(dialog = null) }


    enum class State { INPUT, PROGRESS }

    sealed class Dialog {
        data object NoGalleriesSpecified : Dialog()
        data object FileReadError : Dialog()
    }

    companion object {
        val ehVisitedRegex = """[0-9]*?\.[a-z0-9]*?:""".toRegex()
    }
}

data class BatchAddState(
    val progressTotal: Int = 0,
    val progress: Int = 0,
    val galleries: String = "",
    val added: Int = 0,
    val failed: Int = 0,
    val state: BatchAddScreenModel.State = BatchAddScreenModel.State.INPUT,
    val events: List<String> = emptyList(),
    val dialog: BatchAddScreenModel.Dialog? = null,
)
