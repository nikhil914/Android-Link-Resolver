# Link Resolver

Native Android app that resolves shortlinks to their final link **in the background over plain HTTP**: no browser, no ads.
Tap a `linkshortx.in` or `urlshortx.io` link (e.g. in Telegram), the app opens, waits out the server timers, and shows the final
`telegram.me` / `t.me` link. Tap **Open** to go to Telegram.

v1 supports the LinkShortX network (`linkshortx.in`, `urlshortx.io`): entry link → blog "Step 1..N" pages
(e.g. `hindisink.com`) → entry host "Get Link" → destination. A new blog domain is picked up automatically: the
entry host's first redirect carries the original link in its query, which marks the target as part of the flow.
It usually takes 20–40 s, because the site enforces a real wait of about 9 s on its server for each step.

## Saved collections (Saved tab)

Save channel posts under a name, browse them later as text (offline), and resolve them whenever you like:

- **Save whole channel** (Telegram tab → open a channel): reads the full history (up to 50,000 messages, in the
  background with progress) and stores every post's text and all its URLs. **Update** later adds only newer posts.
- **Save selected**: stores just the posts holding the links you ticked, with only those links, under a custom name.
- Supported links are worked out when a collection is shown, so domains learned later are picked up in old saves.
- In a collection: search posts, *Only with links*, select (per link, per post, Unresolved, All), **Resolve N
  selected** → a batch named "<collection> — results". The **Results** tab lists only this collection's results
  (latest per link) with Copy all, Share and CSV named after the collection.
- Rename or delete a collection (deleting keeps resolved links in History).
- Stored in the Room database (v2: tables `collections`, `collection_posts`; batches link to their collection). The
  v1 → v2 migration keeps existing history.

## Watch channels, background mode, shortcuts

- **Watch channels.** In a Telegram channel tap **Watch**. A WorkManager job (every 15 min – 6 h, default 1 h) reads
  posts newer than the last handled one, picks out supported links not seen before, resolves them in the job itself
  (at most 24 per check; the rest next time) and sends a notification with the final links (Copy all, tap = batch).
  With *Resolve new links automatically* off it only notifies, and the tap opens the channel. Exempting the app from
  battery optimisation makes checks more punctual.
- **Background mode.** Tapped and shared single links resolve without opening a screen; the result is a notification
  with **Open** and **Copy**.
- **Quick Settings tile "Resolve clipboard".** Resolves every link in the clipboard (Android 13+: *Add tile* in
  Settings).
- **Open Telegram links in Telegram.** Final `t.me` links become `tg://` links (bot start, channel, post, invite), so
  Telegram opens directly instead of via the browser.
- **App lock.** Fingerprint, face or screen PIN to open the app (it holds the Telegram login). Relocks after a minute
  away. Link taps still resolve without unlocking.

## New shortener domains, browser mode, site health (Settings tab)

- **Generic resolver.** Any link you share or paste is tried with a generic AdLinkFly-style resolver, even if its
  domain is unknown (Resolve tab → *Try this link*). It understands the "Click here to continue" pre-page, plain-form
  step pages on unknown blog domains (it checks an unknown first redirect once before calling it the destination),
  the arm call and Get Link. A domain that resolves is **learned**: from then on it is picked out of pasted text and
  Telegram posts like the built-in ones. Domains can also be added or removed by hand. Captcha pages are reported, not
  solved.
- **Browser mode** (off by default). Makes the app a browser choice for every http(s) link. Set it as the default
  browser: known shortlinks are resolved, every other link opens in the browser you pick, at once. This is how
  tapped links from learned domains reach the app (the manifest can only list fixed domains).
- **Self-tuning waits.** A rejected step adds 0.5 s to that site's step waits (up to +10 s); after 5 clean runs it
  eases back by 0.25 s. Shown and resettable in Settings.
- **Daily site health check.** Once a day (WorkManager, any network) the latest working link of each site is resolved
  again in a fresh session. A site that starts failing gets a notification; its pages go into the debug bundle.
  *Check now* runs it at once.

## Many links at once (batches)

Paste one or many links on the **Resolve** tab, or several copied Telegram posts (select posts → Copy), or share
them to Link Resolver. Every supported link is picked out (duplicates dropped) and resolved in parallel:

- **Parallel resolves** setting (1–6, default 3). Each link always runs in its own session (own cookie jar), so links
  never share state. If two links still end with the same final link, the batch screen flags it.
- Runs in a foreground service with a progress notification, so it keeps going when you leave the app;
  *Cancel all* in the notification.
- Links already resolved successfully come straight from history (toggle "Resolve again even if already in history").
- Batch screen: live per-link step progress, Copy all / Share / CSV export of final links, Retry failed, Cancel,
  per-link log.
- **History** tab: every resolve (batches and single links) saved in a local database (Room); search, filter by
  status, copy all final links, export CSV.

## Telegram channels (private too)

The **Telegram** tab logs in to your own Telegram account with Telegram's official library
([TDLib](https://github.com/tdlib/td)) — read-only: it lists your chats and reads history, it never sends anything.

1. One time: get your own API ID at [my.telegram.org](https://my.telegram.org/apps) → *API development tools* →
   create an app → copy **api_id** and **api_hash** into the app. They are stored only on the phone (never in this
   repo or the APK).
2. Log in: phone number → code (arrives in Telegram) → 2-step password if you have one. The session is kept in
   the app's private storage (TDLib database encrypted with a random key).
3. Pick a channel → the app reads the newest 200 messages (*Load older* / *Load 1000 more* for more) and lists every
   post with supported links — including links hidden behind text, link previews and URL buttons. Each link shows
   ✓ and its final link if it was resolved before. Posts since your last visit are marked **NEW**.
4. Select links (per link, per post, *Unresolved*, *New*, *All*) → **Resolve N selected** → a normal batch; results
   are saved with the channel name and post id.

TDLib is compiled once by `.github/workflows/tdlib.yml` (commit pinned in `tdlib/VERSION`, arm64-v8a only) and
published as the `tdlib-<commit>` prerelease; the `:tdlib` module downloads it at build time. To update TDLib, change
`tdlib/VERSION` and push — the workflow builds the new version.

## Install

Every push builds a **debug APK** with GitHub Actions. Every push to `main` also publishes a
**GitHub Release** (tag `v<versionName>`) with the APK attached — grab it from *Releases*, or point
[Obtainium](https://github.com/ImranR98/Obtainium) at this repo for auto-updates. Other branches: Actions → the run →
*Artifacts*.

The version lives in `version.properties` and is bumped with every commit (versionCode +1, versionName patch +1).

All builds are signed with the same debug key (`debug.keystore`, committed on purpose), so a new build installs as an
update over the old one.

## Phone setup (one time)

1. **Open by default** (Android 12+): open the app and tap **Enable link opening**. Turn on *Open supported links*
   and tick `linkshortx.in` and `urlshortx.io`. The card in the app turns green when it's done.
2. **Telegram**: Telegram opens links in its own in-app browser, which never hands them to Android.
   In Telegram go to *Settings → Chat Settings* and turn off *In-App Browser*. Tapping a supported link then
   opens Link Resolver.
3. **Network**: the site blocks VPN/proxy/datacenter IPs, and some home broadband connections can't reach it at all.
   *Force mobile data* (on by default) routes requests over mobile data even when Wi-Fi is connected. If mobile data
   isn't available within 5 s, the app uses the normal network.

Fallbacks that always work: long-press a link → **Share → Link Resolver**, or paste it on the main screen.

## When a site changes

On a failed resolve, tap **Show debug log** (every hop: method, status, URL, page title) or **Share debug bundle**.
The bundle is a zip with the log and the HTML of every page from the last resolve. Put real pages into
`app/src/test/resources/fixtures/` to add parser tests.

## Release builds (later)

`app/build.gradle.kts` already supports a release key from env vars (`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`). CI only builds debug for now.

## Project layout

```
app/src/main/java/com/nik/linkresolver/
  engine/   pure Kotlin (no Android imports), unit-tested on the JVM
    Resolver, ResolverRegistry, ResolverRunner   pluggable resolvers picked by host
    LinkShortXResolver + LinkShortXConfig       the flow; all waits/selectors in one config
    RedirectFollower                            3xx / meta refresh / tiny JS redirects inside flow hosts
    FlowHttp, PageParser (Jsoup), DebugRecorder
  net/      OkHttp factory, binding to the cellular network
  engine/QueueWorker                            parallel queue (Semaphore), one session per link
  data/     settings, debug bundle store, Room DB (data/db), BatchManager (queue in DB)
  service/  ResolveService — foreground service that drains the queue
  telegram/ TDLib client wrapper (login, chats, history), link extraction from posts, Telegram tab
tdlib/      module: downloads the prebuilt TDLib (Java API + libtdjni.so)
  ui/       Compose screens + ViewModel (resolution survives rotation)
```

Build locally: `./gradlew :app:lint testDebugUnitTest assembleDebug` (JDK 17+, Android SDK 37).

The final destination is **never requested**: once a redirect leaves the flow hosts, that URL is the result.
