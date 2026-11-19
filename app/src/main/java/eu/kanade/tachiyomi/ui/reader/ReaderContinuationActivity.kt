package eu.kanade.tachiyomi.ui.reader

/**
 * A standard-launch-mode instance used only for end-of-book continuation. Keeping it separate
 * from ReaderActivity's singleTask entry point guarantees that the next title receives a fresh
 * ReaderViewModel and chapter loader.
 */
class ReaderContinuationActivity : ReaderActivity()
