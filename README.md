# Komikku-Import

## Torrent / Magnet import

Komikku includes a **More → Torrent** import option where you can paste:

- a `magnet:` URI (with `xt=urn:btih:...`)
- a direct `.torrent` URL
- a supported Nyaa/Sukebei detail page URL

### Import flow

1. Open **More → Torrent**.
2. Paste a link and tap **Fetch file list**.
3. Wait for metadata to resolve.
4. Select the books you want.
5. Tap **Add selected books**.

The importer resolves torrent metadata first, lists supported archives, and adds selected entries to library records backed by torrent metadata (hash + file index/path key), not permanent full-book files.

### Streaming and storage behavior

- Reading is on-demand from torrent peers.
- The app uses **temporary cache only** for required pieces while loading pages.
- Temporary torrent cache can be cleared from the Torrent screen.
- Full books are **not permanently downloaded by default**.

### Connection and reliability notes

- Status messages and notifications report metadata/import progress.
- Pause, resume, and cancel controls are available during import.
- If there are no peers/seeders or transfer stalls, reads may fail and should be retried later.

### Supported torrent archive formats

- Import discovery currently targets: `.cbz` and `.zip` archives.
- The reader streams image pages from those archives.

### Legal notice

Only import torrents you are legally allowed to access/distribute in your jurisdiction. Availability and performance depend on trackers, DHT, and active seeders.