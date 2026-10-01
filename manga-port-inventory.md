# Kodami manga-only Tadami port inventory

This inventory is based on the actual effective Kodami build tree (Komikku base plus the compatible overlay) and the Tadami-Aniyomi-fork source tree.

## Component mapping

| Area | Tadami source family | Kodami result | Decision |
|---|---|---|---|
| Manga hero/title | `entries/manga/components/aurora/MangaHeroContent.kt`, `MangaGlassHeroCard.kt`, `MangaInfoCard.kt` | `MangaInfoHeader.kt` with hero cover, blur, gradient, dynamic background and responsive tablet branch | Adapted |
| Cover actions | `MangaCoverDialog.kt` and poster actions | `MangaCoverDialog.kt`, `MangaDialogs.kt`, existing cover callbacks | Keep Komikku behavior |
| Metadata/status/genres | `MangaDetailsSnapshot.kt`, status formatter | `MangaInfoHeader.kt`, metadata rows, status and source actions | Keep Komikku behavior; Aurora surface added |
| Description/Markdown/show-more | Tadami info-card description | `MarkdownRender.kt` and `ExpandableMangaDescription` with animated expansion | Keep Komikku behavior |
| Chapter list/progress | `MangaChapterCardCompact.kt`, download indicator | `MangaChapterListItem.kt`, download indicator, chapter settings and selection | Keep Komikku behavior |
| Manga actions | `MangaActionCard.kt` | `MangaInfoButtons.kt`, `MangaBottomActionMenu.kt`, toolbar and dialogs | Keep Komikku behavior |
| Notes/edit/tracking | Aurora note/action cards | `MangaNotes*`, tracking dialogs, edit-info flows | Keep Komikku behavior |
| Related/recommendations | Tadami related/history components | `RelatedMangasRow.kt` and browse recommendation flows | Keep Komikku behavior |
| Library cards | Tadami Aurora library cards and badges | `CommonMangaItem.kt`, grid/list/masonry variants, Treasury aura/frame and reusable Aurora/Glass card treatment | Adapted |
| Library organization | Tadami library pager/settings | `LibraryPager`, `LibraryToolbar`, `LibrarySettingsDialog`, categories, filters, sorting, grouping, bulk actions | Keep Komikku behavior |
| Library performance | Tadami adaptive/lazy grids | Komikku lazy grids, masonry, fast scroll and large-library safeguards | Keep Komikku behavior |
| Browse/source list | Tadami manga source components | `BrowseSourceScreen`, source list/grid/toolbar/filter/dialog components | Keep Komikku behavior |
| Global search | Tadami global manga search components | `GlobalSearchScreen`, result cards and toolbar | Keep Komikku behavior |
| Feed/related/migration | Tadami manga browse flows | Komikku feed, related, migration and extension flows | Keep Komikku behavior |
| Settings/sheets/dialogs | Tadami manga settings and action sheets | Existing Komikku manga settings, notes, tracking, cover, filter, source and confirmation dialogs | Keep Komikku behavior |
| Aurora/Glass foundation | Tadami Aurora theme, glass cards and adaptive helpers | `MangaAuroraDesignSystem.kt` plus existing Material3 theme; manga surfaces only | Adapted |
| Treasury themes | Tadami Treasury registrations | `MangaTreasuryVisuals.kt` and preference selectors | All manga effects immediately available |
| Reader | Tadami reader differences | Komikku reader remains primary: engine, rendering, gestures, zoom, caching, navigation and settings retained | Keep Komikku; no visual transplant |

## Treasury coverage

The manga Treasury selector is always unlocked. No achievement, profile progression, reward state or unlock predicate is consulted.

Included identifiers cover:

- Themes: Aurora Prime, Onyx Gold, Sakura Noir, Nebula Tide, Event Horizon Library, Void Red and Lattice Protocol.
- Named effects: Blood of Lilith, Weeping Void, Core Melt and Crimson Glitch.
- Frames: Crimson/Glitch Red, Neon, Hologram, Prismatic, Trinity Orbit, Deep Archive, Hybrid Scroll and Ascendant.
- Background/atmosphere effects: Petal Storm, Neon Orbit, Trinity Constellation, Deep Space Archive, Shadow Realm and Ink Water.

Profile-only nickname/home-badge behavior, Home-only rewards, anime/player effects, novel effects and unrelated achievement UI are not exposed as manga customisations.

## Reader comparison decision

| Difference class | Result |
|---|---|
| Komikku already provides reader engine/rendering/zoom/gestures/navigation/caching/settings | Keep Komikku |
| Tadami visual-only reader styling | Do not port |
| Tadami feature not present and demonstrably useful | No reader replacement was required for the current Komikku baseline |

## Explicit exclusions

- Tadami Home layout, hero, banner, recent cards, navigation, animations and Home-only effects.
- Anime, anime-player, light-novel and novel-reader features.
- Achievement/progression requirements for manga visual effects.
- Rewriting the Komikku database, downloading engine, source/extension architecture or reader engine.

## Verification record

- Kotlin compilation: successful after correction of Aurora Compose context errors.
- Debug APK assembly: successful.
- Final package: `com.manus.komikku`.
- Final version: `1.14.8` / version code `92`.
- APK verification: v2 signature verification and ZIP integrity to be performed on the final artifact before delivery.
