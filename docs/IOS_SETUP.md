# FitScroll on iPhone

## Read this first

**iOS cannot give you the real thing, and no amount of work on this repo will change that.**

Apple gives third-party apps no way to see which app is in the foreground and no way to block one. The only sanctioned mechanism is the Screen Time API (`FamilyControls` / `ManagedSettings` / `DeviceActivity`), and its entitlement requires a **paid Apple Developer account plus individual approval from Apple**. It is not available on a free personal team.

So on iPhone FitScroll gives you:

| | Android | iPhone |
|---|---|---|
| Camera push-up counting | ✅ | ✅ |
| 24h-expiring minute bank | ✅ | ✅ |
| Detects Instagram opening | ✅ real-time | ⚠️ via Shortcuts automation |
| Drains your balance live while you scroll | ✅ | ⚠️ settled when you come back |
| Actually prevents you using the app | ✅ | ❌ you can always swipe past |

The iPhone version is **friction at the moment of the impulse**, not a lock. That is genuinely useful — it is what apps like one sec are built on — but do not expect it to stop a determined you.

---

## 1. Open the web app

**https://tusharlachman25.github.io/FitScroll/**

Open it in **Safari**. Chrome on iOS cannot install web apps, so this only works there.

If you intend to use the Shortcuts automation in step 2, **stop here and bookmark it** — read *"Pick one runtime"* below before adding anything to your home screen, because the two do not share a bank.

If you would rather have the full-screen app and skip the automation: **Share** → **Add to Home Screen** → **Add**.

> Your banked minutes live in local storage. Clearing Safari website data, or deleting the home-screen icon, wipes your bank.

### Give it camera access

Open FitScroll, tap **Earn minutes**, and allow the camera when asked. Everything is processed on the phone — no video is recorded and no frame leaves the device.

---

## 2. Wire up the automation

This is what makes Instagram bounce you into FitScroll.

1. Open the **Shortcuts** app.
2. Go to the **Automation** tab.
3. Tap **+** (top right), then **Create Personal Automation**.
4. Choose **App**.
5. Next to *App*, tap **Choose**, select **Instagram**, and tap **Done**.
6. Make sure **Is Opened** is ticked and **Is Closed** is not.
7. Tap **Next**.
8. Tap **New Blank Automation** (or **Add Action**).
9. Search for **Open URLs** — *not* "Open App" — add it, and paste:
   ```
   https://tusharlachman25.github.io/FitScroll/
   ```
10. Tap **Next**.
11. **Turn off "Ask Before Running"** and confirm. This is the important step — leave it on and you get a notification you can ignore instead of a redirect.
12. Tap **Done**.

Now every time you open Instagram, iOS immediately opens FitScroll on top.

### Optional: a second automation, for accurate metering

Skip this and everything still works — but read what it fixes before deciding.

FitScroll cannot watch the clock while Instagram is in front. It stamps the time you left and settles the difference the next time it runs. That means it only knows *"you left at 4:07, you are back 33 minutes later"* — it has no idea what you did in between.

Scroll for three minutes, spend half an hour in Messages, then open Instagram again, and you are billed **thirty-three minutes**. You lose thirty you never spent scrolling.

A second automation stops the clock when you actually leave:

1. Shortcuts → **Automation** → **+** → **Create Personal Automation** → **App**
2. Choose **Instagram**
3. This time tick **Is Closed** and untick **Is Opened**
4. **Next** → **New Blank Automation** → **Open URLs**, and paste:
   ```
   https://tusharlachman25.github.io/FitScroll/?event=closed
   ```
5. **Next** → turn off **Ask Before Running** → **Done**

The `?event=closed` marker is what tells FitScroll it was opened because you left rather than because you arrived, so it stops the clock instead of handing you back into Instagram.

**The trade:** FitScroll now flashes up when you leave Instagram as well as when you open it. Some people find that more annoying than being over-billed; you are the one who has to live with it.

### Why not "Open App"?

Because FitScroll will not be in that list, and no amount of scrolling will find it. A home-screen web app is a *web clip*, not an installed application — iOS does not register it in the app list that Shortcuts reads. Only real App Store apps appear there, which is precisely the thing you cannot ship without a developer account.

`Open URLs` is the way in. The catch is what it opens.

---

## Pick one runtime and stay in it

`Open URLs` hands the link to **Safari**, not to your home-screen icon. iOS has no mechanism for a URL to launch an installed web clip.

That matters more than it sounds, because **iOS keeps separate storage for Safari and for a home-screen web app on the same site.** Your banked minutes live in local storage. Bank 30 minutes in the home-screen app, get redirected into Safari, and Safari will show you a bank of zero — not a bug, a different container.

So choose:

**Option A — Safari as your runtime (use this if you want the automation).**
Skip "Add to Home Screen" entirely, or delete the icon if you already made one. Open FitScroll from the automation, or bookmark the URL. Everything works: camera, counting, the bank. You just get Safari's address bar at the top.

**Option B — home-screen app, no automation.**
Keep the icon, drop the Shortcut, and open FitScroll yourself before reaching for Instagram. Full-screen, works offline, feels like an app. Relies entirely on your own discipline, since nothing will interrupt you.

Option A is the honest recommendation — the interruption is the whole point, and a nicer-looking app you never open is worth less than a Safari tab that gets in your way at the right moment.

If you have already banked minutes in the wrong container, they cannot be moved across. Easiest is to accept the loss and do a fresh set in whichever one you settle on.

---

## 3. How to actually use it

**When you have minutes banked**, FitScroll shows your balance and a *Start a session* button. Tapping it stamps a start time and jumps you to Instagram.

**When your bank is empty**, FitScroll shows the locked state and the only way forward is push-ups.

**When you come back to FitScroll**, it works out how long you were gone and deducts that from your bank.

That last part is the compromise. iOS will not let a web app watch the clock while another app is in front, so time is **reconciled on return** rather than metered live. In practice the automation is what triggers the reconciliation: the next time you open Instagram, FitScroll opens, settles the previous session, and re-checks your balance.

### The bounce

There is one rough edge worth knowing about. When FitScroll opens Instagram for you, the automation notices Instagram launching and opens FitScroll again. FitScroll ignores any return within 12 seconds precisely so this bounce does not settle a zero-second session and re-gate you — but you may still see it flick back once.

Two ways to avoid it entirely:

- After tapping *Start a session*, use the **App Switcher** to get back to Instagram rather than launching it fresh. Switching to an already-running app usually does not re-fire the automation.
- Or just tap *Start a session*, let it bounce once, and swipe back.

---

## 4. Things that will trip you up

**"FitScroll isn't in the Open App list."** It never will be. A home-screen web app is a web clip, not an installed application, and Shortcuts only lists real apps. Use the **Open URLs** action with the FitScroll address instead.

**"My bank says zero but I banked minutes yesterday."** You are probably switching between Safari and the home-screen icon. iOS gives those two separate storage containers on the same site, so each keeps its own bank. Pick one and stay in it — see *"Pick one runtime"* above.

**"The automation didn't run."** iOS sometimes needs the Shortcuts app to have been opened once since the last reboot. Open Shortcuts, then try again.

**"It asks before running."** Go back into the automation and turn off *Ask Before Running*. On older iOS versions this toggle is on the confirmation screen after you tap Next.

**"My minutes disappeared."** Either they expired — every minute dies 24 hours after the push-up that earned it — or Safari website data was cleared.

**"The camera is black."** Close the tab or app fully and reopen it. iOS occasionally hands a backgrounded web app a dead camera track.

**"It counted a rep I didn't do."** Raise the strictness in Settings. Level 3 is roughly a gym-legal push-up; level 5 wants near-floor depth, a dead-straight body, and no bouncing.

---

## Why not just use Screen Time?

You can, and for a hard block you probably should — Apple's own Screen Time with a passcode a friend holds is stricter than anything FitScroll can do on iOS.

What FitScroll adds is the *earning* half. Screen Time gives you a fixed allowance handed down from the past; FitScroll makes the allowance something you produce, forty seconds at a time, in the moment you want it. On Android it can enforce that. On iPhone it can only ask — but asking at exactly the right moment turns out to work more often than it has any right to.
