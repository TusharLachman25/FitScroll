# FitScroll

**Earn your scroll.** 1 push-up = 1 minute of Instagram. Run out of banked minutes and Instagram locks.

FitScroll counts your push-ups with your phone's camera, banks each rep as one minute of screen time, and enforces that budget on the apps you choose. Minutes expire 24 hours after you earn them, so you can't grind once on Sunday and coast all week.

---

## How it works

| | |
|---|---|
| **Earn** | Camera + on-device pose detection counts push-ups. 1 clean rep = 1 minute. |
| **Bank** | Each minute expires exactly 24h after it was earned. Oldest minutes are spent first, so nothing evaporates that could have been used. |
| **Spend** | While a blocked app is in the foreground, your balance drains in real time — one second per second, measured rather than counted in ticks. A notification carries the running balance for the whole session, counting down by the second so you can see it moving. |
| **Lock** | At zero, a lock screen lands **on top of** the blocked app — mid-scroll, not just on open. Leaving it drops you to the home screen, so there's no way through it. |

Everything that counts a rep or spends a minute runs on-device. No account, no sync, no analytics, and no camera frame ever leaves the phone.

Sideloaded builds make exactly one network request: a fetch of a public file saying whether this build has been retired. See [Retiring a build](#retiring-a-build).

---

## Install (Android)

Grab an APK from the [latest CI run](../../actions/workflows/ci.yml) (artifact `fitscroll-release-apk`), or build one yourself below.

| APK | Size | Use it if |
|---|---|---|
| `FitScroll-0.4.0.apk` | 44 MB | **Send this one.** Runs on any phone, nothing to explain |
| `FitScroll-0.4.0-arm64.apk` | 29 MB | You want it smaller — every phone from roughly 2016 onward |
| `FitScroll-0.4.0-arm32.apk` | 24 MB | You want it smaller — older 32-bit device |

Transfer to your phone and open it. You'll need to allow installing from unknown sources; FitScroll is sideloaded, not on Play.

### Then grant the accessibility service

**Required.** Settings → Accessibility → FitScroll → On. This is how FitScroll knows which app is in front. It's declared `canRetrieveWindowContent="false"`, so it receives package names and *cannot* read the contents of any screen.

It listens for two kinds of event. A **window-state change** says a window just took the screen, which is the only signal that an app switch happened at all. A **scroll** says a package is on screen right now — needed because the first only describes transitions: pull the notification shade down over Instagram and the app underneath never goes away, so nothing announces its return when the shade closes. FitScroll reads nothing off either event but the package name and the window class; a scroll event carries no text and no view contents.

Two things get in the way on a sideloaded build, both by design on Android's part:

- **Play Protect blocks the install.** Any app that can see the foreground app trips this. Play Store → profile → Play Protect → ⚙ → turn off scanning, install, turn it back on. (Installing over ADB skips this entirely.)
- **"Controlled by restricted setting."** Android 13+ won't let a sideloaded app enable Accessibility until you unlock it: Settings → Apps → FitScroll → ⋮ → **Allow restricted settings**. (Also not needed if you installed over ADB.)

**Optional: Display over other apps.** Settings → Apps → Special access → Display over other apps → FitScroll. Blocking works without it — the lock screen is an *accessibility overlay*, which the service is granted directly. This permission only lets the lock screen's button jump you straight to the camera instead of you opening FitScroll yourself. When it's missing, that button says so rather than appearing to do nothing.

**Optional: Notifications.** Asked for once, on first launch. They carry two things: your balance counting down while a locked app is open, and a warning an hour before banked minutes expire unspent. Refuse it and you lose those two messages — nothing else changes.

---

## Build it yourself

Needs JDK 17+ and the Android SDK (`ANDROID_HOME` set, or `android/local.properties` with `sdk.dir`).

```bash
cd android
./gradlew testDebugUnitTest     # 125 unit tests
./gradlew assembleRelease       # APKs in app/build/outputs/apk/release/
```

### Signing

Android identifies an app by package name **and signing certificate**. A build signed with a different key cannot update one already installed — it has to be uninstalled first, which deletes the user's banked minutes. So the key you hand builds out with is the key you are committed to.

`assembleRelease` looks for `android/keystore.properties`. If it is missing it falls back to the debug key, so a fresh clone still builds something installable. **Anything you give to another person should be built with the real key**, or you have no upgrade path to them.

```bash
keytool -genkeypair -v \
  -keystore fitscroll-release.jks \
  -alias fitscroll \
  -keyalg RSA -keysize 4096 -validity 10000

cp android/keystore.properties.example android/keystore.properties
# fill in the passwords, then build
```

Both the `.jks` and `keystore.properties` are gitignored. Back the `.jks` up somewhere you will still have it in five years: lose it and you can never ship an update that installs over what people already have. The long validity is deliberate — Play rejects uploads signed with an expired key, and an app already on a phone cannot be re-signed.

---

## Form strictness

Settings has a 1–5 dial. Each level tightens five things at once, because loosening only depth produces a counter that pays a fast sloppy half-rep the same as a slow clean one.

| Level | | Elbow depth | Lockout | Body line | Sag allowance | Min rep time | Body travel |
|---|---|---|---|---|---|---|---|
| 1 | Casual | 115° | 142° | 100° | 1.50s | 0.35s | 0.20× |
| 2 | Relaxed | 105° | 148° | 116° | 1.10s | 0.45s | 0.25× |
| 3 | Standard | 92° | 152° | 128° | 0.80s | 0.6s | 0.30× |
| 4 | Strict | 82° | 157° | 138° | 0.55s | 0.75s | 0.35× |
| 5 | Brutal | 72° | 162° | 148° | 0.35s | 0.9s | 0.40× |

**Body travel** is how far your shoulders must drop during a rep, as a multiple of your own shoulder-to-hip length. It's what makes a rep a *push-up* rather than an arm movement: stand up, hold the phone in front of you and curl, and every angle gate passes — locked-out elbow at the top, bent at the bottom, dead-straight body line throughout. Only the fact that your body never moved gives it away. Measured as a ratio so it holds at any distance from the lens, and set low enough that anyone actually on the floor clears it without thinking about it.

**Tracking confidence is not on this dial**, and that's deliberate. It used to be — higher levels demanded a higher landmark likelihood — which meant level 5 was quietly asking for a *better view of you*, not a better push-up. Looking down at the floor is enough to drop the pose model's confidence across every landmark, so reps stopped counting entirely at level 5 while the identical rep counted at level 1. There is now a single visibility floor for all five levels; strictness governs form only.

The workout screen draws the tracked skeleton live: arms brighten as you approach the required depth, and the plank line turns red the moment your hips leave tolerance. That's there so a rejected rep reads as feedback rather than as a broken app.

**Why the body-line numbers look lenient.** This is a 2D estimate from one camera. Unless the lens sits exactly perpendicular to you, perspective foreshortens your torso and a genuinely straight back measures well under 180°. Thresholds that are correct in geometry reject real push-ups at real phone placements.

**Sag allowance** is how long within a rep you may be outside tolerance before it's voided. It exists because the pose model jitters by a few degrees on a motionless subject, and judging frame by frame threw away clean reps over a single noisy sample. Real sag lasts; noise doesn't.

If your legs are outside the frame the model *guesses* your knee position, so FitScroll declines to judge your back at all rather than failing you on a guess. The workout screen says **"back not checked"** when that happens — move the phone back if you want the full check.

---

## Retiring a build

Copies handed out by hand have no update channel, so every sideloaded build checks one published file and withdraws itself if told to. This is how a preview build gets stood down once a supported one exists.

The file lives at [release/status.json](release/status.json) and has to be reachable over plain HTTPS — the raw URL of a public repo, or a gist:

```json
{ "minVersionCode": 1, "message": "...", "updateUrl": "" }
```

It is a **version floor, not a switch**. Raise `minVersionCode` above the `versionCode` of the builds you want to stand down and they retire themselves; every build at or above it carries on. One number retires everything older at once, and a notice can never retire a build that did not exist when it was written. The committed default of `1` retires nothing.

When a build retires it **stops blocking first**. Nothing stays locked, the lock screen comes down, the drain stops, and the app shows what happened with whatever `message` and `updateUrl` you published. The banked minutes stay on disk untouched, so they are still there for a build that installs over the top.

Three things worth knowing before relying on it:

- **It fails open, deliberately.** No answer — offline, firewalled, file moved, GitHub down — leaves the last known answer standing, and the first answer is "supported". Bricking someone's app because their aeroplane has no wifi would be a worse bug than a build living too long. The flip side is that blocking one domain defeats it. Like the rest of FitScroll, it is a commitment device rather than DRM.
- **Checked at most every six hours**, and cached, so a retirement can take a while to land and survives restarts once it does.
- **Nothing about it is one-way.** Lower the floor again and the build comes back.

The Play build should not carry the check at all — it has a real update channel and no business calling home. Build it with the URL blanked:

```bash
./gradlew assembleRelease -Pfitscroll.statusUrl=
```

That compiles the notice URL to an empty string, and the gate never touches the network.

---

## Honest notes

**This is a commitment device, not a jail.** On Android you can disable the accessibility service or uninstall the app in under a minute. It works by adding friction at the moment of the impulse, not by being unbreakable. If you want a hard block, use Screen Time or Digital Wellbeing with a passcode someone else holds.

**The APK requests more permissions than FitScroll uses.** FitScroll itself declares five: `CAMERA`, `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `INTERNET` (for the retirement check, and nothing else) and the accessibility service binding. `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` and `FOREGROUND_SERVICE` are merged in from ML Kit's transitive Google Play Services dependencies, not declared by this code — see [AndroidManifest.xml](android/app/src/main/AndroidManifest.xml) for what FitScroll actually asks for. They're left in place because stripping permissions from GMS libraries is a known cause of runtime crashes. The pose model is bundled in the APK and inference is local: no camera frame is uploaded anywhere.

**Rep counting is good, not perfect.** It's a 2D pose estimate from one camera. Prop the phone side-on with your whole body in frame. Bad lighting, a head-on angle, or baggy clothing all degrade it. Raise the strictness if it's generous; lower it if it's stingy.

**Expiry rides the wall clock, and you own the wall clock.** Winding it back can't mint minutes — a credit stamped in the future is pulled back to now, so the worst it buys is one ordinary day — but nothing here pretends to be tamper-proof. See the first note.

**Sometimes the meter has to guess, and it never guesses for long.** Android reports app switches, not a live answer to "what is on screen"; a service that gets killed mid-scroll, or a phone unlocked back into whatever it was showing, leaves FitScroll with a remembered package and no way to check it. It resumes on that guess — the alternative is free scrolling every time the process is recycled — but a guessed *drain* expires after 30 seconds unless something confirms it, so the most a wrong one can cost you is half a minute. Touch the feed and the first scroll settles it. A guessed *lock* is not put on the same clock, because the failures are not comparable: a drain that guessed wrong spends a bank silently, while a lock that guessed wrong is a screen in front of you with a button on it.

**The balance counts in seconds below an hour.** Above it, hours and minutes. This is partly so a running meter is visibly running: a number that only moves once a minute looks identical whether the drain is working or stuck, which made every real bug in it hard to tell from an imagined one.

---

## Tests

125 tests, no device required.

The rules live in pure functions taking an explicit `now` — the bank, the rep counter, the blocking decision, the drain reconciler, the per-second meter — so expiry boundaries, oldest-first spending, cap overflow, every anti-cheat gate and the noise tolerance that stops clean reps being thrown away are all pinned down without a device or a 24-hour wait.

The parts that genuinely need Android get Robolectric: the stored ledger, its reload, the day rollover, and the corrupt-ledger path that has to fail into an empty bank rather than a crash loop — because a crash loop would leave the accessibility service holding the block with no way to reach the camera.

## License

MIT — see [LICENSE](LICENSE).
