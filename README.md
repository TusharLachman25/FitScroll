# FitScroll

**Earn your scroll.** 1 push-up = 1 minute of Instagram. Run out of banked minutes and Instagram locks.

FitScroll counts your push-ups with your phone's camera, banks each rep as one minute of screen time, and enforces that budget on the apps you choose. Minutes expire 24 hours after you earn them, so you can't grind once on Sunday and coast all week.

---

## How it works

| | |
|---|---|
| **Earn** | Camera + on-device pose detection counts push-ups. 1 clean rep = 1 minute. |
| **Bank** | Each minute expires exactly 24h after it was earned. Oldest minutes are spent first, so nothing evaporates that could have been used. |
| **Spend** | While a blocked app is in the foreground, your balance drains in real time — one second per second. |
| **Lock** | At zero, the blocked app is covered by a lock screen. Mid-scroll, not just on open. |

Everything runs on-device. No account, no server, no sync, no telemetry.

---

## Install (Android)

Grab an APK from the [latest CI run](../../actions/workflows/ci.yml) (artifact `fitscroll-release-apk`), or build one yourself below.

| APK | Size | Use it if |
|---|---|---|
| `app-arm64-v8a-release.apk` | 29 MB | **Almost certainly this one** — every phone from roughly 2016 onward |
| `app-armeabi-v7a-release.apk` | 23 MB | Older 32-bit device |
| `app-universal-release.apk` | 44 MB | Not sure — works on both |

Transfer to your phone and open it. You'll need to allow installing from unknown sources; FitScroll is sideloaded, not on Play.

### Then grant two permissions

Android deliberately refuses to let an app grant either of these to itself, so both are manual. FitScroll shows a banner on the home screen until both are on.

1. **Accessibility service** — Settings → Accessibility → FitScroll → On. This is how it knows which app is in front. It's declared `canRetrieveWindowContent="false"`, so it receives package names and *cannot* read the contents of any screen.
2. **Display over other apps** — Settings → Apps → Special access → Display over other apps → FitScroll. Android 10+ blocks background activity launches without this, and raising a lock screen over Instagram is exactly that. Without it, blocking degrades to pressing the home button.

---

## Build it yourself

Needs JDK 17+ and the Android SDK (`ANDROID_HOME` set, or `android/local.properties` with `sdk.dir`).

```bash
cd android
./gradlew testDebugUnitTest     # 33 unit tests
./gradlew assembleRelease       # APKs in app/build/outputs/apk/release/
```

Release builds are signed with your local debug key on purpose. FitScroll is sideloaded rather than shipped through Play, so this keeps `assembleRelease` directly installable without a keystore ever entering the repository.

### PWA

```bash
cd web
node --test                     # 33 rule tests, no install step
```

---

## iPhone

There's a PWA in [web/](web/) that gives you the push-up counter and the bank on iOS.

**It cannot lock anything, and that is not a bug I can fix.** iOS gives third-party apps no way to see which app is in the foreground or to block one. The only sanctioned mechanism is Apple's Screen Time API (`FamilyControls`), whose entitlement needs a paid Apple Developer account *plus* individual approval from Apple, and is unavailable on a free personal team.

What you get instead is a Shortcuts automation that opens FitScroll whenever Instagram launches, and time reconciled when you come back rather than metered live. That's real friction at the moment of the impulse — it's the same trick several commercial focus apps ship — but you can always swipe past it.

Full walkthrough and the failure modes: **[docs/IOS_SETUP.md](docs/IOS_SETUP.md)**.

The PWA deploys to GitHub Pages automatically **once this repo is public** — Pages can't serve a private repo on a Free plan, so the deploy job skips itself until then rather than failing on every push.

---

## Form strictness

Settings has a 1–5 dial. Each level tightens four things at once, because loosening only depth produces a counter that pays a fast sloppy half-rep the same as a slow clean one.

| Level | | Elbow depth | Lockout | Body line | Min rep time |
|---|---|---|---|---|---|
| 1 | Casual | 115° | 145° | 115° | 0.35s |
| 2 | Relaxed | 105° | 150° | 138° | 0.45s |
| 3 | Standard | 90° | 156° | 150° | 0.60s |
| 4 | Strict | 80° | 162° | 158° | 0.75s |
| 5 | Brutal | 72° | 168° | 165° | 0.90s |

The workout screen draws the tracked skeleton live: arms brighten as you approach the required depth, and the plank line turns red the moment your hips leave tolerance. That's there so a rejected rep reads as feedback rather than as a broken app.

---

## Honest notes

**This is a commitment device, not a jail.** On Android you can disable the accessibility service or uninstall the app in under a minute. It works by adding friction at the moment of the impulse, not by being unbreakable. If you want a hard block, use Screen Time or Digital Wellbeing with a passcode someone else holds.

**The APK requests more permissions than FitScroll uses.** `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` and `FOREGROUND_SERVICE` are merged in from ML Kit's transitive Google Play Services dependencies, not declared by this code — see [AndroidManifest.xml](android/app/src/main/AndroidManifest.xml) for what FitScroll actually asks for. They're left in place because stripping permissions from GMS libraries is a known cause of runtime crashes. The pose model is bundled in the APK and inference is local: no camera frame is uploaded anywhere.

**Rep counting is good, not perfect.** It's a 2D pose estimate from one camera. Prop the phone side-on with your whole body in frame. Bad lighting, a head-on angle, or baggy clothing all degrade it. Raise the strictness if it's generous; lower it if it's stingy.

---

## Repo layout

```
android/   Native Kotlin app — the real enforcement
web/       PWA for iPhone — counter, bank, soft gate
docs/      Setup guides, including the iOS Shortcuts automation
```

## Tests

66 tests, no device required. The bank and the rep counter are pure functions taking an explicit `now`, so every rule — expiry boundaries, oldest-first spending, cap overflow, and each anti-cheat gate — is pinned down on both platforms.

## License

MIT — see [LICENSE](LICENSE).
