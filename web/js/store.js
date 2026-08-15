/**
 * Local, event-sourced storage for the bank.
 *
 * Everything that changes the balance is recorded as an immutable event. The
 * balance itself is never stored — it is folded from the log on demand. That is
 * what lets two devices reconcile after being offline, and it is why signing
 * out cannot destroy a bank: the log lives on the server too, append-only.
 *
 * Reads are defensive throughout. A corrupt payload resets rather than throwing,
 * because on iOS this page is the only route back to earning minutes and an app
 * that will not start is worse than a lost balance.
 */

import {
  DEFAULTS,
  MAX_CAP_MINUTES,
  MIN_CAP_MINUTES,
  SECONDS_PER_REP,
  expiringWithin,
  nextExpiryAt,
} from './bank.js';
import {
  EARN,
  SPEND,
  balanceFromEvents,
  fold,
  makeEvent,
  merge,
  prune,
} from './ledger.js';

export class BankStore {
  constructor(storage = globalThis.localStorage, keyPrefix = 'fitscroll') {
    this.storage = storage;
    this.keys = {
      events: `${keyPrefix}.events`,
      pending: `${keyPrefix}.pending`,
      settings: `${keyPrefix}.settings`,
      stats: `${keyPrefix}.stats`,
      session: `${keyPrefix}.session`,
      clock: `${keyPrefix}.clock`,
      legacyCredits: `${keyPrefix}.credits`,
    };
    this.#migrateLegacyCredits();
  }

  #read(key, fallback) {
    try {
      const raw = this.storage.getItem(key);
      return raw ? JSON.parse(raw) : fallback;
    } catch {
      return fallback;
    }
  }

  #write(key, value) {
    try {
      this.storage.setItem(key, JSON.stringify(value));
    } catch {
      // Private browsing or a full quota. Losing persistence is survivable;
      // crashing the only screen that can unlock Instagram is not.
    }
  }

  /**
   * Converts a pre-sync balance into the event log.
   *
   * Someone who banked minutes before accounts existed should not lose them on
   * upgrade, so each surviving credit becomes an earn event stamped with the
   * time it was originally earned — preserving its expiry rather than silently
   * renewing it.
   */
  #migrateLegacyCredits() {
    const legacy = this.#read(this.keys.legacyCredits, null);
    if (!Array.isArray(legacy) || legacy.length === 0) return;
    if (this.#read(this.keys.events, null)) return;

    const events = legacy
      .filter((c) => c && c.remainingSeconds > 0 && typeof c.earnedAt === 'number')
      .map((c) => makeEvent(EARN, c.remainingSeconds, c.earnedAt));

    this.#write(this.keys.events, events);
    this.#write(this.keys.pending, events.map((e) => e.id));
    try {
      this.storage.removeItem(this.keys.legacyCredits);
    } catch {
      /* best effort */
    }
  }

  // ------------------------------------------------------------------ events

  events() {
    const stored = this.#read(this.keys.events, []);
    return Array.isArray(stored) ? stored : [];
  }

  /** Ids recorded locally but not yet accepted by the server. */
  pendingIds() {
    const stored = this.#read(this.keys.pending, []);
    return Array.isArray(stored) ? stored : [];
  }

  pendingEvents() {
    const pending = new Set(this.pendingIds());
    return this.events().filter((e) => pending.has(e.id));
  }

  /**
   * A timestamp for a new event that never repeats or moves backwards.
   *
   * Replay order is (occurredAt, id), and two events recorded in the same
   * millisecond would therefore be ordered by a random uuid. That is fine for
   * genuinely concurrent events on different devices, but on one device it
   * breaks causality: bank 25 minutes and immediately spend 9, and if the spend
   * sorts first it replays against an empty bank, takes nothing, and the spend
   * silently evaporates.
   *
   * Nudging each event to at least one millisecond after the previous one keeps
   * a device's own history in the order it happened. The drift is at most a few
   * milliseconds and expiry is measured in hours.
   */
  #nextEventTime(now) {
    const last = this.#read(this.keys.clock, 0);
    const stamped = Math.max(now, (typeof last === 'number' ? last : 0) + 1);
    this.#write(this.keys.clock, stamped);
    return stamped;
  }

  #record(kind, seconds, now) {
    const event = makeEvent(kind, seconds, this.#nextEventTime(now));
    const events = prune([...this.events(), event], now);
    this.#write(this.keys.events, events);

    const live = new Set(events.map((e) => e.id));
    this.#write(this.keys.pending, [...new Set([...this.pendingIds(), event.id])].filter((id) => live.has(id)));
    return event;
  }

  /** Marks uploaded events as no longer needing to be sent. */
  markSynced(ids) {
    const done = new Set(ids);
    this.#write(this.keys.pending, this.pendingIds().filter((id) => !done.has(id)));
  }

  /** Folds events fetched from the server into the local log. */
  ingestRemote(remoteEvents, now = Date.now()) {
    const merged = prune(merge(this.events(), remoteEvents), now);
    this.#write(this.keys.events, merged);

    const live = new Set(merged.map((e) => e.id));
    this.#write(this.keys.pending, this.pendingIds().filter((id) => live.has(id)));
  }

  /**
   * Forgets everything cached on this device without touching the server.
   *
   * Used on sign-out. The ledger is append-only server-side, so signing back in
   * replays it to the exact same balance — nothing is destroyed here, only
   * cleared from a device someone may be handing to somebody else.
   */
  clearLocalCache() {
    for (const key of [this.keys.events, this.keys.pending, this.keys.session, this.keys.stats]) {
      try {
        this.storage.removeItem(key);
      } catch {
        /* best effort */
      }
    }
  }

  // ---------------------------------------------------------------- settings

  settings() {
    return { ...DEFAULTS, ...this.#read(this.keys.settings, {}) };
  }

  saveSettings(patch) {
    const next = { ...this.settings(), ...patch };
    next.capMinutes = Math.min(MAX_CAP_MINUTES, Math.max(MIN_CAP_MINUTES, next.capMinutes));
    next.strictness = Math.min(5, Math.max(1, next.strictness));
    this.#write(this.keys.settings, next);
    return next;
  }

  #capSeconds() {
    return this.settings().capMinutes * 60;
  }

  // ----------------------------------------------------------------- reading

  balanceSeconds(now = Date.now()) {
    return balanceFromEvents(this.events(), now, this.#capSeconds());
  }

  snapshot(now = Date.now()) {
    const credits = fold(this.events(), now, this.#capSeconds());
    const startOfDay = new Date(now).setHours(0, 0, 0, 0);

    // Reps today are derived from the log rather than counted separately, so
    // the number follows you between devices like the balance does.
    const earnedTodaySeconds = this.events()
      .filter((e) => e.kind === EARN && e.occurredAt >= startOfDay)
      .reduce((total, e) => total + e.seconds, 0);

    return {
      balanceSeconds: credits.reduce((t, c) => t + c.remainingSeconds, 0),
      nextExpiryAt: nextExpiryAt(credits, now),
      expiringSoonSeconds: expiringWithin(credits, now),
      repsToday: Math.round(earnedTodaySeconds / SECONDS_PER_REP),
      repsAllTime: this.#read(this.keys.stats, { allTime: 0 }).allTime || 0,
    };
  }

  // ----------------------------------------------------------------- writing

  earn(reps, now = Date.now()) {
    if (reps <= 0) return { grantedSeconds: 0, wastedSeconds: 0 };

    const capSeconds = this.#capSeconds();
    const wanted = reps * SECONDS_PER_REP;
    const balance = balanceFromEvents(this.events(), now, capSeconds);
    const granted = Math.max(0, Math.min(wanted, capSeconds - balance));

    if (granted > 0) this.#record(EARN, granted, now);

    // All-time reps are a vanity counter and cannot be derived once old events
    // are pruned, so this one stays local.
    const stats = this.#read(this.keys.stats, { allTime: 0 });
    this.#write(this.keys.stats, { allTime: (stats.allTime || 0) + reps });

    return { grantedSeconds: granted, wastedSeconds: wanted - granted };
  }

  /**
   * Charges screen time. Returns what was actually available, which is less
   * than asked for exactly when the bank has run dry.
   */
  spend(seconds, now = Date.now()) {
    if (seconds <= 0) return 0;

    const available = balanceFromEvents(this.events(), now, this.#capSeconds());
    const taken = Math.min(seconds, available);
    // Recording only what was taken keeps the log honest; an event for more
    // than existed would be clamped on every future replay for no reason.
    if (taken > 0) this.#record(SPEND, taken, now);
    return taken;
  }

  /**
   * Zeroes the balance by spending it, rather than by deleting history.
   *
   * An append-only log has no other way to do this, and it is the better
   * behaviour anyway: the clear syncs to every other device instead of being
   * undone by the next merge.
   */
  clear(now = Date.now()) {
    const balance = balanceFromEvents(this.events(), now, this.#capSeconds());
    if (balance > 0) this.#record(SPEND, balance, now);
  }

  // --------------------------------------------------------------- sessions

  /**
   * Records that the user has left for a blocked app.
   *
   * iOS gives a web app no way to observe another app, so time spent away is
   * reconciled on return instead of metered live.
   */
  startSession(now = Date.now()) {
    this.#write(this.keys.session, { startedAt: now });
  }

  /** Charges for an open session and closes it. Returns seconds deducted. */
  settleSession(now = Date.now()) {
    const startedAt = this.openSessionStartedAt();
    if (startedAt === null) return 0;

    this.#write(this.keys.session, null);

    const elapsed = Math.floor((now - startedAt) / 1000);
    // A clock that moved backwards, or a stamp from a previous install, must
    // not credit the user with negative usage.
    if (elapsed <= 0) return 0;

    return this.spend(elapsed, now);
  }

  hasOpenSession() {
    return this.openSessionStartedAt() !== null;
  }

  openSessionStartedAt() {
    const session = this.#read(this.keys.session, null);
    return session && typeof session.startedAt === 'number' ? session.startedAt : null;
  }
}
