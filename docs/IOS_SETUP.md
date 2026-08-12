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

## 1. Install the web app

1. Open the FitScroll URL in **Safari** (it must be Safari — Chrome on iOS cannot install web apps).
2. Tap the **Share** button.
3. Tap **Add to Home Screen**.
4. Name it *FitScroll* and tap **Add**.

Launch it from the home screen icon, not from Safari. Only the home-screen copy runs full-screen and keeps its own storage reliably.

> Your banked minutes live in this app's local storage. Deleting the home-screen icon, or clearing Safari website data, wipes your bank.

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
9. Search for **Open App**, add it, and pick **FitScroll**.
10. Tap **Next**.
11. **Turn off "Ask Before Running"** and confirm. This is the important step — leave it on and you get a notification you can ignore instead of a redirect.
12. Tap **Done**.

Now every time you open Instagram, iOS immediately opens FitScroll on top.

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

**"The automation didn't run."** iOS sometimes needs the Shortcuts app to have been opened once since the last reboot. Open Shortcuts, then try again.

**"It asks before running."** Go back into the automation and turn off *Ask Before Running*. On older iOS versions this toggle is on the confirmation screen after you tap Next.

**"My minutes disappeared."** Either they expired — every minute dies 24 hours after the push-up that earned it — or Safari website data was cleared.

**"The camera is black."** Close and reopen the app from the home screen. iOS occasionally hands a PWA a dead camera track after it has been backgrounded.

**"It counted a rep I didn't do."** Raise the strictness in Settings. Level 3 is roughly a gym-legal push-up; level 5 wants near-floor depth, a dead-straight body, and no bouncing.

---

## Why not just use Screen Time?

You can, and for a hard block you probably should — Apple's own Screen Time with a passcode a friend holds is stricter than anything FitScroll can do on iOS.

What FitScroll adds is the *earning* half. Screen Time gives you a fixed allowance handed down from the past; FitScroll makes the allowance something you produce, forty seconds at a time, in the moment you want it. On Android it can enforce that. On iPhone it can only ask — but asking at exactly the right moment turns out to work more often than it has any right to.
