# Link Resolver

Native Android app that resolves shortlinks to their final link **in the background over plain HTTP**: no browser, no ads.
Tap a `linkshortx.in` or `urlshortx.io` link (e.g. in Telegram), the app opens, waits out the server timers, and shows the final
`telegram.me` / `t.me` link. Tap **Open** to go to Telegram.

v1 supports the LinkShortX network (`linkshortx.in`, `urlshortx.io`): entry link → blog "Step 1..N" pages
(e.g. `hindisink.com`) → entry host "Get Link" → destination. A new blog domain is picked up automatically: the
entry host's first redirect carries the original link in its query, which marks the target as part of the flow.
It usually takes 20–40 s, because the site enforces a real wait of about 9 s on its server for each step.

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
  ui/       Compose screens + ViewModel (resolution survives rotation)
```

Build locally: `./gradlew lint testDebugUnitTest assembleDebug` (JDK 17+, Android SDK 37).

The final destination is **never requested**: once a redirect leaves the flow hosts, that URL is the result.
