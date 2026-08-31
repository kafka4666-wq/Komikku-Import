#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANAGER="$ROOT/app/src/main/java/eu/kanade/tachiyomi/torrent/TorrentStreamManager.kt"
WORKER="$ROOT/app/src/main/java/exh/ui/torrent/TorrentImportWorker.kt"
FETCHER="$ROOT/app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverFetcher.kt"
SOURCE="$ROOT/app/src/main/java/eu/kanade/tachiyomi/source/online/TorrentSource.kt"
MORE_SCREEN="$ROOT/app/src/main/java/eu/kanade/presentation/more/MoreScreen.kt"
IMPORT_SCREEN="$ROOT/app/src/main/java/exh/ui/torrent/TorrentImportScreen.kt"
NOTIFICATION_RECEIVER="$ROOT/app/src/main/java/eu/kanade/tachiyomi/data/notification/NotificationReceiver.kt"
for file in "$MANAGER" "$WORKER" "$FETCHER" "$SOURCE" "$MORE_SCREEN" "$IMPORT_SCREEN" "$NOTIFICATION_RECEIVER"; do test -s "$file"; done
python3 - "$MANAGER" "$WORKER" "$FETCHER" "$SOURCE" "$MORE_SCREEN" "$IMPORT_SCREEN" "$NOTIFICATION_RECEIVER" <<'PY'
from pathlib import Path
import sys, tempfile, zipfile
manager, worker, fetcher, source, more, screen, receiver = [Path(p).read_text() for p in sys.argv[1:]]
required = (
    'private val catalogRoot = File(context.filesDir, "torrent_catalog")',
    'atomicWriteBytes(File(directory, METADATA_FILE), bytes)',
    'private fun restoreCatalog(hash: String): TorrentCatalog?',
    'private suspend fun ensureArchiveIndex(book: TorrentBook): ArchiveIndex',
    'private suspend fun requestRange(',
    'active.handle.filePriority(book.fileIndex, Priority.DEFAULT)',
    'active.handle.filePriority(book.fileIndex, Priority.TOP_PRIORITY)',
    'active.handle.setPieceDeadline(piece, PIECE_DEADLINE_MILLIS)',
    'setSequentialRange(firstPiece, lastPiece)',
    'TorrentImportControl.awaitResume(context)',
    'TorrentImportControl.isCancelled(context)',
    'registerLibraryManga(book.key, manga.id)',
    'const val INITIAL_PEER_TIMEOUT_MILLIS = 30_000L',
    'const val STALLED_TRANSFER_TIMEOUT_MILLIS = 30_000L',
    'const val PIECE_DEADLINE_MILLIS = 15_000',
    'setPieceDeadline(piece, PIECE_DEADLINE_MILLIS)',
    'resetPieceDeadline(piece)',
    'const val REQUEST_TOTAL_TIMEOUT_MILLIS = 180_000L',
    'range-reannounce purpose=',
    'availableRequiredPieces=',
    'temporary=true diskCacheDeleted=true',
)
for marker in required:
    assert marker in manager + worker, f'missing durable/readable marker: {marker}'
assert 'prefetchCovers(' not in worker, 'fast import must not race asynchronous cover warming'
assert 'active.catalog.saveDirectory.deleteRecursively()' in manager, 'transient piece storage must be deleted during page cleanup'
assert 'catalogDirectory(hash)' in manager, 'catalog metadata must remain separate from transient piece storage'
assert 'readSelectedEntry(active, book, entry)' in manager, 'reader must use the selected ZIP entry path'
assert 'waitForCompleteArchive(active, book)' not in manager, 'streaming must never fall back to a complete archive download'
assert 'ensureArchive(book: TorrentBook)' not in manager, 'streaming must not materialize a complete archive'
assert 'directory-range-failed book=' in manager, 'directory failures must be surfaced as finite request failures'
assert 'persistentFile' not in fetcher, 'Torrent cover path must not bypass the stream with a stale persistent file'
assert 'val pageSize = 40' in source and 'val slice = books.drop(start).take(pageSize)' in source
assert 'onClickTorrentImport' in more
for control in ('TorrentImportControl.pause(context)', 'TorrentImportControl.resume(context)', 'TorrentImportWorker.cancel(context)'):
    assert control in screen and control in receiver, f'missing import control: {control}'
with tempfile.TemporaryDirectory() as d:
    p = Path(d) / 'fixture.cbz'
    with zipfile.ZipFile(p, 'w', compression=zipfile.ZIP_DEFLATED) as z:
        z.writestr('cover.jpg', b'cover')
        z.writestr('001.png', b'page')
    with zipfile.ZipFile(p) as z:
        assert set(z.namelist()) == {'cover.jpg', '001.png'}
print('PASS: selective piece-only ZIP-range Torrent streaming, durable catalog, bounded transient cleanup, import timing, cover path, and finite failure handling')
PY
