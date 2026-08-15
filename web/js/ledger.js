/**
 * Rebuilds a balance from an event log, mirroring
 * android/.../data/BankLedger.kt rule for rule.
 *
 * The synced bank is an append-only log rather than a stored number, because
 * devices go offline and a number cannot be merged: whichever device wrote
 * second would silently erase the other's work. A log can be merged, and this
 * fold is what turns it back into something spendable.
 *
 * The fold is deterministic. Events replay in occurredAt order with ties broken
 * by id, and each applies against the balance *as it stood at that moment* — a
 * spend can only consume credits that were live when it happened. Any device
 * holding the same events therefore computes the same balance, whatever order
 * they arrived in.
 */

import { EXPIRY_MS, earnSeconds, purge, spend as spendCredits } from './bank.js';

export const EARN = 'earn';
export const SPEND = 'spend';

/**
 * How far back events remain relevant. Nothing older than the 24h expiry
 * window can affect a balance; the extra hour absorbs clock skew between
 * devices.
 */
export const RELEVANT_WINDOW_MS = EXPIRY_MS + 60 * 60 * 1000;

/** @typedef {{ id: string, kind: 'earn'|'spend', seconds: number, occurredAt: number }} BankEvent */

export function newEventId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  // Only reached on ancient engines. Collisions here would mean a dropped
  // event, not a duplicated one, since ids are used for de-duplication.
  return `${Date.now()}-${Math.random().toString(16).slice(2)}-${Math.random().toString(16).slice(2)}`;
}

export function makeEvent(kind, seconds, occurredAt) {
  return { id: newEventId(), kind, seconds, occurredAt };
}

/**
 * Canonical replay order.
 *
 * Sorting by id as well as time is not cosmetic: two devices can record events
 * in the same millisecond, and without a tie-break each would fold them in its
 * own arrival order and disagree about the balance.
 */
function ordered(events) {
  const seen = new Map();
  for (const event of events) if (!seen.has(event.id)) seen.set(event.id, event);
  return [...seen.values()].sort((a, b) => a.occurredAt - b.occurredAt || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

/** Replays `events` into the credits they leave behind. */
export function fold(events, now, capSeconds) {
  let credits = [];

  for (const event of ordered(events)) {
    if (event.kind === EARN) {
      credits = earnSeconds(credits, event.seconds, event.occurredAt, capSeconds).credits;
    } else {
      // A spend larger than the balance simply takes what is there. Two devices
      // scrolling offline at once can overspend between syncs; clamping rather
      // than going negative keeps the merged result sane without inventing
      // minutes to claw back.
      credits = spendCredits(credits, event.seconds, event.occurredAt).credits;
    }
  }

  return purge(credits, now);
}

export function balanceFromEvents(events, now, capSeconds) {
  return fold(events, now, capSeconds).reduce((total, c) => total + c.remainingSeconds, 0);
}

/**
 * Merges two logs, keeping one copy of each event.
 *
 * De-duplication is by id, so the same event arriving from the server and from
 * the local queue collapses to one rather than being counted twice.
 */
export function merge(local, remote) {
  return ordered([...local, ...remote]);
}

/** Drops events too old to influence any future balance. */
export function prune(events, now) {
  return events.filter((e) => now - e.occurredAt <= RELEVANT_WINDOW_MS);
}

/** Wire format for Supabase. */
export function toRow(event, userId, deviceLabel) {
  return {
    id: event.id,
    user_id: userId,
    kind: event.kind,
    seconds: event.seconds,
    occurred_at: new Date(event.occurredAt).toISOString(),
    device_label: deviceLabel,
  };
}

export function fromRow(row) {
  return {
    id: row.id,
    kind: row.kind,
    seconds: row.seconds,
    occurredAt: Date.parse(row.occurred_at),
  };
}
