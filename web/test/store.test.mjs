import test from 'node:test';
import assert from 'node:assert/strict';

import { BankStore } from '../js/store.js';
import { EARN, balanceFromEvents } from '../js/ledger.js';

const NOW = 1_700_000_000_000;
const HOUR = 60 * 60 * 1000;
const CAP = 1440 * 60;

/** Minimal localStorage stand-in; node's is still experimental. */
function fakeStorage(seed = {}) {
  const map = new Map(Object.entries(seed));
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k),
    _dump: () => Object.fromEntries(map),
  };
}

// --------------------------------------------------------------- basic flow

test('earning records an event and raises the balance', () => {
  const store = new BankStore(fakeStorage());
  const outcome = store.earn(20, NOW);

  assert.equal(outcome.grantedSeconds, 20 * 60);
  assert.equal(store.balanceSeconds(NOW), 20 * 60);
  assert.equal(store.events().length, 1);
  assert.equal(store.events()[0].kind, EARN);
});

test('spending records only what was actually available', () => {
  const store = new BankStore(fakeStorage());
  store.earn(2, NOW);

  const taken = store.spend(300, NOW); // asked for five minutes, has two

  assert.equal(taken, 120);
  assert.equal(store.balanceSeconds(NOW), 0);
  // Logging a spend larger than the balance would be clamped on every future
  // replay for no reason, so only the real amount is recorded.
  assert.equal(store.events().at(-1).seconds, 120);
});

test('an earn and a spend in the same millisecond keep their order', () => {
  // Replay order is (occurredAt, id), so without a per-device logical clock
  // these two would be ordered by random uuid. Half the time the spend would
  // replay first against an empty bank, take nothing, and vanish.
  const store = new BankStore(fakeStorage());
  store.earn(25, NOW);
  store.spend(9 * 60, NOW);

  assert.equal(store.balanceSeconds(NOW), 16 * 60);
});

test('a burst of events in one millisecond all survive replay', () => {
  const store = new BankStore(fakeStorage());
  store.earn(10, NOW);
  for (let i = 0; i < 10; i += 1) store.spend(30, NOW);

  assert.equal(store.balanceSeconds(NOW), 10 * 60 - 300);
});

test('earning past the cap is reported as waste', () => {
  const store = new BankStore(fakeStorage());
  store.saveSettings({ capMinutes: 15 });

  const outcome = store.earn(20, NOW);

  assert.equal(outcome.grantedSeconds, 15 * 60);
  assert.equal(outcome.wastedSeconds, 5 * 60);
  assert.equal(store.balanceSeconds(NOW), 15 * 60);
});

test('clearing spends the balance rather than deleting history', () => {
  const store = new BankStore(fakeStorage());
  store.earn(10, NOW);

  store.clear(NOW);

  assert.equal(store.balanceSeconds(NOW), 0);
  // An append-only log has no other way to zero a balance, and this is the
  // better behaviour anyway: the clear syncs instead of being undone by the
  // next merge from another device.
  assert.equal(store.events().length, 2);
});

// ------------------------------------------------------------- iOS sessions

test('an open session is charged for the time spent away', () => {
  const store = new BankStore(fakeStorage());
  store.earn(30, NOW);

  store.startSession(NOW);
  const spent = store.settleSession(NOW + 5 * 60 * 1000);

  assert.equal(spent, 300);
  assert.equal(store.balanceSeconds(NOW + 5 * 60 * 1000), 25 * 60);
});

test('settling twice does not double charge', () => {
  const store = new BankStore(fakeStorage());
  store.earn(10, NOW);
  store.startSession(NOW);

  store.settleSession(NOW + 60_000);
  const second = store.settleSession(NOW + 120_000);

  assert.equal(second, 0);
  assert.equal(store.balanceSeconds(NOW + 120_000), 9 * 60);
});

test('a session longer than the balance drains it to zero, not below', () => {
  const store = new BankStore(fakeStorage());
  store.earn(2, NOW);
  store.startSession(NOW);

  const spent = store.settleSession(NOW + HOUR);

  assert.equal(spent, 120);
  assert.equal(store.balanceSeconds(NOW + HOUR), 0);
});

test('a backwards clock cannot credit usage', () => {
  const store = new BankStore(fakeStorage());
  store.earn(10, NOW);
  store.startSession(NOW);

  assert.equal(store.settleSession(NOW - 60_000), 0);
  assert.equal(store.balanceSeconds(NOW), 10 * 60);
});

// ----------------------------------------------------------------- recovery

test('a corrupt log resets rather than throwing', () => {
  const store = new BankStore(fakeStorage({ 'fitscroll.events': '{not json' }));

  assert.deepEqual(store.events(), []);
  assert.equal(store.balanceSeconds(NOW), 0);
});

test('a pre-sync balance is migrated into the log without renewing its expiry', () => {
  // Someone who banked minutes before accounts existed must not lose them on
  // upgrade, and must not silently gain a fresh 24 hours either.
  const earnedAt = NOW - 20 * HOUR;
  const storage = fakeStorage({
    'fitscroll.credits': JSON.stringify([{ earnedAt, remainingSeconds: 600 }]),
  });

  const store = new BankStore(storage);

  assert.equal(store.balanceSeconds(NOW), 600);
  assert.equal(store.events()[0].occurredAt, earnedAt);
  // Still on the original clock: gone four hours from now, not twenty-four.
  assert.equal(store.balanceSeconds(NOW + 5 * HOUR), 0);
  // And queued for upload, so the old balance reaches the account.
  assert.equal(store.pendingEvents().length, 1);
});

// ------------------------------------------------------------- sync plumbing

test('locally recorded events are queued for upload until acknowledged', () => {
  const store = new BankStore(fakeStorage());
  store.earn(5, NOW);

  assert.equal(store.pendingEvents().length, 1);

  store.markSynced(store.pendingEvents().map((e) => e.id));

  assert.equal(store.pendingEvents().length, 0);
  assert.equal(store.balanceSeconds(NOW), 5 * 60); // still spendable
});

test('remote events merge in without duplicating what is already local', () => {
  const store = new BankStore(fakeStorage());
  store.earn(10, NOW);
  const mine = store.events()[0];

  const fromOtherDevice = { id: 'other-device-event', kind: EARN, seconds: 300, occurredAt: NOW };
  store.ingestRemote([mine, fromOtherDevice], NOW);

  assert.equal(store.events().length, 2);
  assert.equal(store.balanceSeconds(NOW), 10 * 60 + 300);
});

test('signing out clears the device but the ledger replays to the same balance', () => {
  const store = new BankStore(fakeStorage());
  store.earn(25, NOW);
  store.spend(9 * 60, NOW);

  const serverLedger = store.events();
  const before = store.balanceSeconds(NOW);

  store.clearLocalCache();
  assert.equal(store.balanceSeconds(NOW), 0); // nothing left on the device

  // Signing back in pulls the append-only ledger down again.
  store.ingestRemote(serverLedger, NOW);

  assert.equal(store.balanceSeconds(NOW), before);
  assert.equal(before, 16 * 60);
});

test('settings survive a sign-out, since they are not part of the ledger', () => {
  const store = new BankStore(fakeStorage());
  store.saveSettings({ strictness: 5, capMinutes: 60 });

  store.clearLocalCache();

  assert.equal(store.settings().strictness, 5);
  assert.equal(store.settings().capMinutes, 60);
});

// -------------------------------------------------------------------- stats

test('reps today are derived from the log so they follow you between devices', () => {
  const store = new BankStore(fakeStorage());
  store.earn(12, NOW);
  store.earn(8, NOW);

  const snapshot = store.snapshot(NOW);
  assert.equal(snapshot.repsToday, 20);
  assert.equal(snapshot.repsAllTime, 20);
});

test('the balance a store reports matches folding its own log', () => {
  const store = new BankStore(fakeStorage());
  store.earn(30, NOW - 3 * HOUR);
  store.spend(11 * 60, NOW - 2 * HOUR);
  store.earn(4, NOW - HOUR);

  assert.equal(
    store.balanceSeconds(NOW),
    balanceFromEvents(store.events(), NOW, CAP),
  );
});
