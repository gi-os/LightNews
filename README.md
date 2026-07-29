# LightNews

A newsletter reader for the Light Phone III. It shows one Gmail label, nothing else.
Swipe left for the next issue, and reading one actually marks it read in Gmail.

No inbox, no compose, no threads, no search. If it isn't in the label, it doesn't exist.

> ### ⚠ Experimental — v1, never run on hardware
>
> This has not been installed on a Light Phone III, or on any phone. It was written
> against the Gmail API docs and the LPIII's known constraints, and verified the only
> ways available without the device:
>
> - **It builds.** The CI workflow's `assembleDebug`/`assembleRelease` passes, so the
>   Kotlin compiles, Room's KSP pass generates, resources and the manifest merge cleanly,
>   and the APK is signed with the pinned certificate. That is the whole toolchain — what
>   it does not tell you is whether any of it behaves on the device.
> - **Reviewed hard.** Two adversarial review passes found and fixed 17 runtime bugs
>   (read-state corruption, a refetch that erased offline reads, orphaned cache files, a
>   deadlock risk, a regex that ate legitimate images). The fixes are described where they
>   live, in the comments.
>
> What is most likely to be wrong, in order:
>
> 1. **The OAuth redirect.** If the LightOS browser doesn't hand
>    `com.gios.lightnews:/oauth2redirect` back to the app, sign-in dies silently after
>    consent and nothing downstream matters. Test this first.
> 2. **How newsletters actually look** on a 1080×1240 monochrome panel. The CSS rewriting
>    is reasoned, not seen. Expect to tune `DARK_CSS` in `util/Html.kt` after the first
>    real issue.
> 3. **The sync's edge cases** — pagination, offline reads, a label that changes under it.
>    Every one of those paths is argued for in a comment and has never actually run.
>
> It also asks for `gmail.modify`, which is a restricted scope — this reads and writes to
> a real mailbox. Point it at an account you don't mind it touching first.

## How it reads

Newsletters are HTML built for a desktop mail client in 2011: a 600px fixed-width
table, inline colour on every cell, a tracking pixel in the footer. Two render modes,
one tap apart in the top bar:

- **DARK** — white on black to match LightOS. Layout tables are unwrapped to `display:
  block` so the copy reflows to the panel, and every inline colour is stripped out of
  the `style` attribute, because an attribute beats a stylesheet even with `!important`.
- **PAPER** — the newsletter's own design, with only the width fixed. A matte
  monochrome panel is the closest thing to the white paper these were designed for, and
  this is the mode for issues where the artwork is the point.

The document is loaded against `http://newsletter.invalid/` — a base that RFC 2606
guarantees can never resolve, so root-relative paths and stray anchors fail locally
instead of quietly fetching from a real host. http rather than https so that plain-http
artwork isn't mixed content, which WebView handles differently release to release.

Tracking pixels are dropped in both modes. `cid:` images are inlined as data URIs when
the message is cached, smallest first, up to 400 KB — a WebView cannot attach an OAuth
header, so anything not inlined by then can never load. Turning images on therefore
drops the cached bodies and refetches them; there is no way to add the art later.

Marking read happens when a page has *settled* for 1.5s, not on page change. Flicking
past six issues to reach the seventh should not silently clear all six.

## Setup

It's about ten minutes, and eight of them are Google's.

### 1. Gmail

Make a label — `LightNewsletter` by default — and a filter that files your newsletters
into it. Nested labels are fine; the app matches on the leaf name.

### 2. Google Cloud

1. New project → enable the **Gmail API**.
2. OAuth consent screen: **External**. Add the scope
   `https://www.googleapis.com/auth/gmail.modify`.
3. **Publish to Production.** Do not leave it in Testing — see gotchas.
4. Credentials → OAuth client ID → **Android**:
   - package name `com.gios.lightnews`
   - SHA-1 `74:7A:2D:9E:B8:3A:98:0A:4F:5D:AB:B9:07:B7:A5:A0:BF:D1:38:D4`
     (the committed `keystore/lightnews.jks`, which signs both debug and release)

An Android client has no secret. The id is not sensitive.

**The APKs on the Releases page have no client id compiled in** unless the repo secret
below was set when they were built — without it the app opens straight onto the setup
screen and cannot sign in.

### 3. Build

```bash
echo 'gmailClientId=YOUR_ID.apps.googleusercontent.com' >> local.properties
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For CI builds, set a repo secret `GMAIL_CLIENT_ID` instead. Push to `main` and the
workflow cuts a signed GitHub Release that Obtainium can follow; the tag is
`v<major>.<minor>.<run number>`, so it advances on every push without touching
`versionName`.

The redirect is `com.gios.lightnews:/oauth2redirect` — the package name, which Google
accepts for an Android client because that client is validated by package plus SHA-1
rather than by redirect URI. If a consent screen ever rejects it, the fallback is the
reversed client id; `app/build.gradle.kts` has the one line to change and a note about
what it costs.

## Gotchas, in the order they'll bite

**Refresh tokens die after 7 days** if the consent screen is left in *Testing* with
*External* users. Nothing detects this except a failed sync; the app catches
`invalid_grant`, drops the credentials and asks you to sign in again. Publishing to
Production makes it stop.

**`gmail.modify` is a restricted scope.** Unverified in Production means one scary
consent screen and a ~100-user cap. Full verification wants a CASA assessment. Fine for
one mailbox; not fine if you ever hand this to a friend.

**No push.** Gmail's watch API routes through Cloud Pub/Sub to Firebase, and Firebase
needs Play Services. So it polls: hourly on wi-fi via WorkManager, plus on app open.
WorkManager itself is fine without Play Services — it sits on JobScheduler.

**Play Services was never needed for OAuth.** It only ever supplied the account-picker
shortcut. AppAuth drives the phone's browser instead, with `AnyBrowserMatcher` because
the LightOS browser doesn't advertise Custom Tabs and the default matcher would refuse
to start.

**If the browser swallows the redirect**, the flow dies silently after consent. Nothing
in AppAuth can fix that; the fallback is to run consent on a laptop and QR the refresh
token in, the way LightPass takes an API key. Worth testing first — everything else
here is downstream of it.

**If LightOS ever ships without a WebView provider**, `WebViewSupport.isAvailable()`
returns false and the reader falls back to jsoup-extracted plain text with link targets
inlined. Degraded, not broken.

## Layout

```
auth/AuthManager.kt      AppAuth, token persistence, invalid_grant handling
gmail/GmailClient.kt     labels, list, get, attachments, batchModify
gmail/MimeParser.kt      MIME tree walk, base64url, charset, cid parts
util/Html.kt             the DARK/PAPER rewriters and the text fallback
data/NewsRepository.kt   sync, read-state reconciliation, body cache
data/NewsDatabase.kt     metadata only — bodies are files under filesDir/bodies
sync/SyncWorker.kt       hourly, unmetered
ui/ReaderScreen.kt       HorizontalPager, dwell-to-read
ui/HtmlView.kt           WebView with JS off, plus the provider probe
```

Sync is two list calls: one for the label, one for the label ∩ `UNREAD`. Everything in
the first and not the second was read somewhere else. Both the reconciliation and the
pruning are confined to the ids that call actually returned — anything past the page
boundary is left alone, or it would look deleted and vanish on every sync.

A sync looks at the newest 100 messages in the label; the cache keeps 130 issues, bodies
included. The cache is deliberately the larger of the two — at exactly 100, a single row
outside the window (a newsletter deleted in Gmail, or one holding an unpushed read) costs
a cache slot, and the oldest in-window issue then gets fetched and trimmed again on every
sync, forever. New messages are fetched 20 per pass, because a first run of a hundred
issues would otherwise be killed halfway through by WorkManager's ten-minute limit.

Marking all read goes out as one `batchModify` — which is all-or-nothing, so a single
message deleted in Gmail would fail the whole call; a failed batch is retried per id and
a 400/404 counts as settled. Reads made offline keep a `pendingRead` flag. Nothing in a
sync overwrites one: the reconciliation queries skip those rows, and a refetch of the
same message goes through a transactional upsert that carries the flag across. The one
exception is deliberate — past 260 cached issues a `pendingRead` row is dropped anyway,
because a push that fails permanently would otherwise let the cache grow without limit.

Two behaviours worth knowing because they are quiet:

- A message that fails to fetch three times is given up on for the life of the process.
  It stays in the list showing its one-line snippet.
- Read-state reconciliation only marks things read when Gmail returned the *whole* unread
  list in one page. So while more than 100 messages in the label are unread, marking one
  read in the Gmail web app won't be reflected here — silence from a truncated page is
  not evidence that something was read.

## Not doing

Sending, replying, archiving, multiple labels, multiple accounts, notifications,
attachments other than inline images, search. All of it is a different app.
