# Link Resolver

Native Android app that resolves shortlinks to their final link **in the background over plain HTTP**: no browser, no ads.
Tap a `linkshortx.in` link (e.g. in Telegram), the app opens, waits out the server timers, and shows the final
`telegram.me` / `t.me` link. Tap **Open** to go to Telegram.

v1 supports the LinkShortX flow (`linkshortx.in` → `hindisink.com` "Step 1..N" → `linkshortx.in` "Get Link").
It usually takes 20–40 s, because the site enforces a real wait of about 9 s on its server for each step.

## Install

Every push builds an APK with GitHub Actions:

- **Any commit**: Actions → the run → *Artifacts* → `link-resolver-<version>` (a zip with the APK inside).
- **Releases**: push a tag `v0.1.0` (etc.). The APK is attached to a GitHub Release.

### Auto-update with Obtainium

1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. *Add app* → URL `https://github.com/nikhil914/Android-Link-Resolver`.
3. It picks up new releases (tags `v*`). Updates only install over each other if every build is signed with the
   **same key**, so set up signing (below) before the first tag.

## Phone setup (one time)

1. **Open by default** (Android 12+): open the app and tap **Enable link opening**. Turn on *Open supported links*
   and tick `linkshortx.in`. The card in the app turns green when it's done.
2. **Telegram**: Telegram opens links in its own in-app browser, which never hands them to Android.
   In Telegram go to *Settings → Chat Settings* and turn off *In-App Browser*. Tapping a `linkshortx.in` link then
   opens Link Resolver.
3. **Network**: the site blocks VPN/proxy/datacenter IPs, and some home broadband connections can't reach it at all.
   *Force mobile data* (on by default) routes requests over mobile data even when Wi-Fi is connected. If mobile data
   isn't available within 5 s, the app uses the normal network.

Fallbacks that always work: long-press a link → **Share → Link Resolver**, or paste it on the main screen.

## When a site changes

On a failed resolve, tap **Show debug log** (every hop: method, status, URL, page title) or **Share debug bundle**.
The bundle is a zip with the log and the HTML of every page from the last resolve. Put real pages into
`app/src/test/resources/fixtures/` to add parser tests.

## Release signing (one time)

Generate a keystore (keep it and the passwords safe; losing it means users must uninstall to update):

```sh
keytool -genkeypair -v -keystore link-resolver.jks -alias linkresolver \
  -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 link-resolver.jks > link-resolver.jks.b64   # macOS: base64 -i link-resolver.jks -o link-resolver.jks.b64
```

Add four repository secrets (*Settings → Secrets and variables → Actions*):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | contents of `link-resolver.jks.b64` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `linkresolver` (or whatever you used) |
| `KEY_PASSWORD` | key password |

Without them, CI still succeeds, but the release APK is signed with a throwaway debug key. Each such build is then
signed differently, so it won't install over the previous one.

## Project layout

```
app/src/main/java/com/nik/linkresolver/
  engine/   pure Kotlin (no Android imports), unit-tested on the JVM
    Resolver, ResolverRegistry, ResolverRunner   pluggable resolvers picked by host
    LinkShortXResolver + LinkShortXConfig       the flow; all waits/selectors in one config
    RedirectFollower                            3xx / meta refresh / tiny JS redirects inside flow hosts
    FlowHttp, PageParser (Jsoup), DebugRecorder
  net/      OkHttp factory, binding to the cellular network
  data/     settings, debug bundle store
  ui/       Compose screens + ViewModel (resolution survives rotation)
```

Build locally: `./gradlew lint testDebugUnitTest assembleRelease` (JDK 17+, Android SDK 37).

The final destination is **never requested**: once a redirect leaves the flow hosts, that URL is the result.
