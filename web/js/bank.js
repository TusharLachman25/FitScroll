/**
 * The minute bank, mirroring android/.../data/BankMath.kt rule for rule.
 *
 * One push-up mints one minute; every minute expires exactly 24h after the rep
 * that earned it. Kept as pure functions taking an explicit `now` so the rules
 * are testable under `node --test` without a browser or a clock.
 */

export const EXPIRY_MS = 24 * 60 * 60 * 1000;
export const SECONDS_PER_REP = 60;
const ONE_HOUR_MS = 60 * 60 * 1000;

/** @typedef {{ earnedAt: number, remainingSeconds: number }} Credit */

/** Drops credits that are fully spent or past their 24h window. */
export function purge(credits, now) {
  return credits.filter((c) => c.remainingSeconds > 0 && now - c.earnedAt < EXPIRY_MS);
}

export function balanceSeconds(credits, now) {
  return purge(credits, now).reduce((total, c) => total + c.remainingSeconds, 0);
}

/**
 * Banks `reps` push-ups, clamped so the balance never exceeds `capSeconds`.
 * Overflow is reported rather than silently dropped.
 */
export function earn(credits, reps, now, capSeconds) {
  const live = purge(credits, now);
  if (reps <= 0) return { credits: live, grantedSeconds: 0, wastedSeconds: 0 };

  const wanted = reps * SECONDS_PER_REP;
  const room = Math.max(0, capSeconds - live.reduce((t, c) => t + c.remainingSeconds, 0));
  const granted = Math.min(wanted, room);

  return {
    credits: granted > 0 ? [...live, { earnedAt: now, remainingSeconds: granted }] : live,
    grantedSeconds: granted,
    wastedSeconds: wanted - granted,
  };
}

/**
 * Deducts up to `seconds`, oldest credit first.
 *
 * Oldest-first matters: draining the newest would let older batches expire
 * unspent, destroying minutes that were already earned.
 */
export function spend(credits, seconds, now) {
  const live = purge(credits, now).sort((a, b) => a.earnedAt - b.earnedAt);
  if (seconds <= 0) return { credits: live, spentSeconds: 0 };

  let outstanding = seconds;
  const remaining = [];

  for (const credit of live) {
    if (outstanding <= 0) {
      remaining.push(credit);
      continue;
    }
    const taken = Math.min(outstanding, credit.remainingSeconds);
    outstanding -= taken;
    const left = credit.remainingSeconds - taken;
    // The earnedAt timestamp is deliberately carried over untouched. Refreshing
    // it on spend would let anyone extend minutes forever by dipping into
    // Instagram for a second before each deadline.
    if (left > 0) remaining.push({ ...credit, remainingSeconds: left });
  }

  return { credits: remaining, spentSeconds: seconds - outstanding };
}

export function nextExpiryAt(credits, now) {
  const live = purge(credits, now);
  if (live.length === 0) return null;
  return Math.min(...live.map((c) => c.earnedAt + EXPIRY_MS));
}

export function expiringWithin(credits, now, windowMs = ONE_HOUR_MS) {
  return purge(credits, now)
    .filter((c) => c.earnedAt + EXPIRY_MS - now <= windowMs)
    .reduce((total, c) => total + c.remainingSeconds, 0);
}

export const DEFAULTS = {
  capMinutes: 1440,
  strictness: 3,
  warnBeforeLock: true,
};

export const MIN_CAP_MINUTES = 15;
export const MAX_CAP_MINUTES = 1440;

/**
 * localStorage-backed ledger.
 *
 * Reads are defensive: a corrupt payload resets to an empty bank rather than
 * throwing, because an app that will not start is worse than a lost balance —
 * on iOS this page is the only way back to earning minutes.
 */
export class BankStore {
  constructor(storage = globalThis.localStorage, keyPrefix = 'fitscroll') {
    this.storage = storage;
    this.keys = {
      credits: `${keyPrefix}.credits`,
      settings: `${keyPrefix}.settings`,
      stats: `${keyPrefix}.stats`,
      session: `${keyPrefix}.session`,
    };
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

  credits() {
    const stored = this.#read(this.keys.credits, []);
    return Array.isArray(stored) ? stored : [];
  }

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

  stats() {
    return this.#read(this.keys.stats, { today: 0, todayDate: '', allTime: 0 });
  }

  balanceSeconds(now = Date.now()) {
    return balanceSeconds(this.credits(), now);
  }

  snapshot(now = Date.now()) {
    const credits = purge(this.credits(), now);
    this.#write(this.keys.credits, credits);
    const stats = this.stats();
    const today = new Date(now).toISOString().slice(0, 10);
    return {
      balanceSeconds: credits.reduce((t, c) => t + c.remainingSeconds, 0),
      nextExpiryAt: nextExpiryAt(credits, now),
      expiringSoonSeconds: expiringWithin(credits, now),
      repsToday: stats.todayDate === today ? stats.today : 0,
      repsAllTime: stats.allTime || 0,
    };
  }

  earn(reps, now = Date.now()) {
    const { capMinutes } = this.settings();
    const outcome = earn(this.credits(), reps, now, capMinutes * 60);
    this.#write(this.keys.credits, outcome.credits);

    if (reps > 0) {
      const stats = this.stats();
      const today = new Date(now).toISOString().slice(0, 10);
      this.#write(this.keys.stats, {
        todayDate: today,
        today: (stats.todayDate === today ? stats.today : 0) + reps,
        allTime: (stats.allTime || 0) + reps,
      });
    }
    return outcome;
  }

  spend(seconds, now = Date.now()) {
    const outcome = spend(this.credits(), seconds, now);
    this.#write(this.keys.credits, outcome.credits);
    return outcome.spentSeconds;
  }

  clear() {
    this.#write(this.keys.credits, []);
  }

  // --------------------------------------------------------------- sessions

  /**
   * Records that the user has left for a blocked app.
   *
   * iOS gives a web app no way to observe another app, so time spent away is
   * reconciled on return instead of metered live: we stamp a departure time and
   * settle the difference the next time this page runs. That is the whole
   * reason the Shortcut re-opens FitScroll on every Instagram launch — it is
   * what makes the next settlement happen.
   */
  startSession(now = Date.now()) {
    this.#write(this.keys.session, { startedAt: now });
  }

  /**
   * Charges for a session that is still open, and closes it.
   * Returns the seconds deducted, or 0 when there was nothing outstanding.
   */
  settleSession(now = Date.now()) {
    const session = this.#read(this.keys.session, null);
    if (!session || typeof session.startedAt !== 'number') return 0;

    this.#write(this.keys.session, null);

    const elapsed = Math.floor((now - session.startedAt) / 1000);
    // A clock that moved backwards, or a stamp from a previous install, must
    // not credit the user with negative usage.
    if (elapsed <= 0) return 0;

    return this.spend(elapsed, now);
  }

  hasOpenSession() {
    return this.openSessionStartedAt() !== null;
  }

  /** When the current session began, or null if none is open. */
  openSessionStartedAt() {
    const session = this.#read(this.keys.session, null);
    return session && typeof session.startedAt === 'number' ? session.startedAt : null;
  }
}
