# Komikku Nyaa Torrent Streaming Repair Report

**Repository:** [kafka4666-wq/Komikku-Import](https://github.com/kafka4666-wq/Komikku-Import)  
**Final commit:** `ef1ee5a`  
**Build run:** [GitHub Actions run 33398354471](https://github.com/kafka4666-wq/Komikku-Import/actions/runs/33398354471)

## Result

The existing implementation was repaired in place without resetting the repository or removing the existing Doujin, library, reader, import, nhentai, settings, or normal-download functionality. The authoritative `komikku-final-overlay.zip` was updated so the CI overlay contains the repaired source and verifier.

The remote Android build completed successfully. It produced and validated the universal APK `app-universal-debug.apk` with SHA-256 `300c0d9a2cb3e6a17ea55e4b8ebfd419d4521bc88e95f45412625027d90e0d5f` and size approximately 184 MiB.

## Root causes found

The reported five-to-ten-minute behavior was not a simple timeout problem. The old request loop treated every five seconds as a failed attempt, reannounced, and restarted its wait. That could abandon a healthy transfer before the requested pieces completed and could repeatedly reset progress.

The code also promoted the complete selected archive with `filePriority(..., TOP_PRIORITY)`. In libtorrent, changing a file priority applies that priority to the file’s pieces and resets piece priorities; this defeats strict piece-only selection. The old cleanup paused the handle but left the disk-backed torrent workspace in place, so transient pieces could accumulate until the manual cache-clear action was used.

## Changes made

| File | Change |
|---|---|
| `app/src/main/java/eu/kanade/tachiyomi/torrent/TorrentStreamManager.kt` | Changed selection to an ignored-file baseline plus explicit required-piece priorities; retained the narrow sequential picker range; submitted all required pieces before waiting; replaced the two-by-five-second retry behavior with separate initial-peer and stalled-transfer deadlines; added detailed peer, seed, speed, availability, byte, timing, and request-state diagnostics; removed the native handle and deletes the transient workspace after each active request; recreates only the cache directory for the next request. |
| `scripts/verify-torrent-streaming.sh` | Updated static checks for piece-only selection, progressive deadlines, diagnostics, and transient cleanup. The fixture still validates selective ZIP entry behavior and no complete-archive fallback. |
| `komikku-final-overlay.zip` | Synchronized both the repaired manager and verifier into the final CI overlay, which is the authoritative build input. |

## Exact torrent API behavior used

The implementation uses libtorrent4j `2.1.0-39`, as declared in `app/build.gradle.kts`. `piecePriority(piece, TOP_PRIORITY)` promotes only explicitly selected pieces. `setSequentialRange(firstPiece, lastPiece)` narrows the native piece-picker range and does not replace the explicit piece priorities. `havePiece(piece)` reports completion only after a piece has been downloaded and written to disk. `peerInfo()`, `status()`, and `pieceAvailability()` are used for diagnostics. `SessionManager.remove(handle)` ends the short-lived native torrent handle before the transient workspace is deleted.

The upstream libtorrent reference documents that file-priority changes reset piece priorities and that sequential ranges configure the piece picker rather than acting as a file-content reader [1]. It also documents that piece availability is the number of connected peers advertising each piece and that `have_piece` represents a piece written to disk [1].

## Selection and request strategy

For each ZIP operation, the manager calculates the exact byte range, maps its first and last byte to global torrent piece indices, promotes every required piece before waiting, and waits for the set as a group. That means the native engine can request multiple required pieces concurrently from different peers rather than receiving one piece, waiting, and only then submitting the next piece.

The ZIP central directory is requested only when the active book’s in-memory archive index is absent. The central directory and entry metadata remain in a bounded LRU cache containing names, offsets, compressed sizes, uncompressed sizes, flags, and compression methods. A page request then fetches only the local header range and compressed image payload range for the selected entry. The image is decompressed into a bounded byte array and returned to the existing reader decoder. Covers use the same selected-entry path and return an in-memory decoded image without writing a generated torrent cover file or Coil disk-cache source.

The implementation supports random page access because it uses the requested entry’s offsets directly; it does not enumerate or download intermediate pages. The current code does not add a large page prefetch queue, so it avoids accidental dozens-of-pages prefetching. Existing reader behavior remains responsible for any normal page navigation preloading.

## Deadlines and diagnostics

The request now has a 30-second initial peer-discovery guard, a 30-second stalled-transfer guard, and a 180-second total guard. A healthy transfer is not cancelled merely because five seconds elapsed. Progress is recognized when the count of completed required pieces increases. A single reannounce is attempted after the initial peer deadline; thereafter a request fails with a useful `no-peers` or `data-stalled` state instead of repeatedly restarting from zero.

Diagnostic records include the torrent book key, file index and size, piece size, requested offset and length, first and last required pieces, required-piece count, connected peers, seeds, payload download and upload rates, required-piece availability, completed and missing required pieces, received-byte estimate, request state, elapsed time, and stalled time.

## Storage behavior and limitations

This repair does **not** claim zero storage. libtorrent4j’s normal storage path is disk-backed. The implementation therefore uses `context.cacheDir/torrent_stream/<hash>` as a transient workspace and enforces a strict 32 MiB maximum for accumulated requested-piece space per active stream. It never uses Komikku’s normal download directory or the user’s Downloads directory. Catalog metadata remains separately in `context.filesDir/torrent_catalog/<hash>` and contains torrent reconstruction metadata, not book bytes.

When a request ends, the native handle is removed and the transient workspace is recursively deleted. The complete CBZ/ZIP is not intentionally materialized, and extracted page images are not saved as normal files. However, an actual Android storage measurement using a known-good large torrent was not possible in this sandbox, so peak physical storage, sparse-file allocation, and post-cleanup storage numbers remain to be measured on a real device.

## Validation status

| Acceptance area | Result |
|---|---|
| Existing repository and history preserved | Passed; changes are limited to the manager, verifier, and final overlay archive. |
| Static torrent verifier | Passed locally after the repair. |
| Final overlay contains repaired implementation | Passed locally. |
| Official overlay/Android Gradle build | Passed in GitHub Actions run `33398354471`. |
| Universal APK signing/package validation | Passed in the same workflow; artifact downloaded locally. |
| Actual Nyaa import and torrent-backed cover/page display | Not run in this environment. |
| Android device storage measurements | Not run in this environment. |
| Full Android regression suite for normal Komikku, reader, downloads, library, updates, and Doujin behavior | Not run; the repository’s static verifier and the successful CI build are not substitutes for device-level regression testing. |

Accordingly, the code and APK build are complete, but the attachment’s requirement to claim end-to-end runtime success is **not marked complete** until a real Android device is used with a seeded controlled torrent. The build artifact is provided for that test.

## References

[1]: https://libtorrent.org/reference-Torrent_Handle.html "libtorrent torrent_handle reference"
