/**
 * The minute bank's arithmetic, mirroring android/.../data/BankMath.kt.
 *
 * One push-up mints one minute; every minute expires exactly 24h after the rep
 * that earned it. Pure functions taking an explicit `now`, so the rules are
 * testable under `node --test` without a browser or a clock.
 *
 * Storage lives in store.js, and the event log that syncs across devices lives
 * in ledger.js. This file deliberately imports neither, so both can build on it
 * without a cycle.
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
  return earnSeconds(credits, reps * SECONDS_PER_REP, now, capSeconds);
}

/**
 * As `earn`, but in seconds rather than reps.
 *
 * Replaying a synced ledger works in seconds: an event records what was banked,
 * not how many push-ups produced it, so changing the exchange rate never
 * retroactively rewrites history.
 */
export function earnSeconds(credits, seconds, now, capSeconds) {
  const live = purge(credits, now);
  if (seconds <= 0) return { credits: live, grantedSeconds: 0, wastedSeconds: 0 };

  const room = Math.max(0, capSeconds - live.reduce((t, c) => t + c.remainingSeconds, 0));
  const granted = Math.min(seconds, room);

  return {
    credits: granted > 0 ? [...live, { earnedAt: now, remainingSeconds: granted }] : live,
    grantedSeconds: granted,
    wastedSeconds: seconds - granted,
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
  /**
   * Whether to hand you straight back to the gated app when you can afford it.
   *
   * The iOS automation reopens FitScroll on every launch of the gated app, so
   * without this it interrupts even when there is nothing to decide. On means
   * the interruption is reserved for the moment it carries information: an
   * empty bank.
   */
  autoReturn: true,
};

export const MIN_CAP_MINUTES = 15;
export const MAX_CAP_MINUTES = 1440;
