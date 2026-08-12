# FitScroll

**Earn your scroll.** 1 push-up = 1 minute of Instagram. Run out of banked minutes and Instagram locks.

FitScroll counts your push-ups with your phone's camera, banks each rep as one minute of screen time, and enforces that budget on the apps you choose. Minutes expire 24 hours after you earn them, so you can't grind once and coast for a week.

---

## How it works

| | |
|---|---|
| **Earn** | Camera + on-device pose detection counts push-ups. 1 rep = 1 minute. |
| **Bank** | Each minute expires exactly 24h after it was earned. Oldest minutes are spent first. Bank cap is configurable. |
| **Spend** | While a blocked app is in the foreground, your balance drains in real time. |
| **Lock** | At zero, the blocked app is covered by a lock screen — mid-scroll, not just on open. |

Everything runs on-device. No account, no server, no network calls, no data leaves your phone.

---

## Repo layout

```
android/     Native Kotlin app (the real enforcement)
web/         PWA for iPhone (counter + bank, soft block)
docs/        Setup guides, including iOS Shortcuts automation
```

---

## Platform reality check

**Android** gets genuine enforcement. An Accessibility Service watches for blocked apps coming to the foreground and covers them with the lock screen, and keeps draining your balance while you scroll.

**iPhone** does not, and cannot. iOS gives third-party apps no way to see what app is in the foreground or to block one. The only sanctioned mechanism is Apple's Screen Time API (`FamilyControls`), whose entitlement requires a paid Apple Developer account plus individual approval from Apple. So the PWA gives you the push-up counter and the bank, and pairs with an iOS **Shortcuts** personal automation that interrupts Instagram launches and sends you to FitScroll. That's friction, not a lock — see [docs/IOS_SETUP.md](docs/IOS_SETUP.md).

Worth stating plainly on both platforms: **this is a commitment device, not a jail.** Anyone determined can disable the accessibility service or delete the app. It works because it adds friction at the moment of the impulse, not because it's unbreakable.

---

## Status

Under active development. See commit history for progress.

## License

MIT — see [LICENSE](LICENSE).
