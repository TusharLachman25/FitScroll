import test from 'node:test';
import assert from 'node:assert/strict';

import {
  EXPIRY_MS,
  balanceSeconds,
  earn,
  expiringWithin,
  nextExpiryAt,
  purge,
  spend,
} from '../js/bank.js';

const NOW = 1_700_000_000_000;
const HOUR = 60 * 60 * 1000;
const CAP = 1440 * 60;
const hoursAgo = (h) => NOW - h * HOUR;

test('one rep banks exactly one minute', () => {
  const { credits, grantedSeconds } = earn([], 1, NOW, CAP);
  assert.equal(grantedSeconds, 60);
  assert.equal(balanceSeconds(credits, NOW), 60);
});

test('a credit just under 24h old is still spendable', () => {
  const credits = [{ earnedAt: NOW - (EXPIRY_MS - 1000), remainingSeconds: 600 }];
  assert.equal(balanceSeconds(credits, NOW), 600);
});

test('a credit at exactly 24h has expired', () => {
  const credits = [{ earnedAt: NOW - EXPIRY_MS, remainingSeconds: 600 }];
  assert.equal(balanceSeconds(credits, NOW), 0);
});

test('expiry removes only the stale batch', () => {
  const credits = [
    { earnedAt: hoursAgo(30), remainingSeconds: 600 },
    { earnedAt: hoursAgo(2), remainingSeconds: 300 },
  ];
  assert.equal(balanceSeconds(credits, NOW), 300);
  assert.equal(purge(credits, NOW).length, 1);
});

test('spending drains the oldest credit first', () => {
  const credits = [
    { earnedAt: hoursAgo(1), remainingSeconds: 120 },
    { earnedAt: hoursAgo(20), remainingSeconds: 120 },
  ];
  const outcome = spend(credits, 120, NOW);
  assert.equal(outcome.spentSeconds, 120);
  assert.equal(outcome.credits.length, 1);
  // The newer batch must survive; draining it first would let the older one
  // expire unspent.
  assert.equal(outcome.credits[0].earnedAt, hoursAgo(1));
});

test('spending more than the balance reports the shortfall', () => {
  const outcome = spend([{ earnedAt: NOW, remainingSeconds: 45 }], 60, NOW);
  assert.equal(outcome.spentSeconds, 45);
  assert.equal(outcome.credits.length, 0);
});

test('a partly spent credit keeps its original earned timestamp', () => {
  const earnedAt = hoursAgo(10);
  const outcome = spend([{ earnedAt, remainingSeconds: 300 }], 100, NOW);
  assert.equal(outcome.credits[0].earnedAt, earnedAt);
  assert.equal(outcome.credits[0].remainingSeconds, 200);
});

test('expired credits cannot be spent', () => {
  const outcome = spend([{ earnedAt: hoursAgo(25), remainingSeconds: 600 }], 60, NOW);
  assert.equal(outcome.spentSeconds, 0);
});

test('earning is clamped at the cap and reports the waste', () => {
  const smallCap = 10 * 60;
  const existing = [{ earnedAt: NOW, remainingSeconds: 8 * 60 }];
  const outcome = earn(existing, 5, NOW, smallCap);
  assert.equal(outcome.grantedSeconds, 2 * 60);
  assert.equal(outcome.wastedSeconds, 3 * 60);
  assert.equal(balanceSeconds(outcome.credits, NOW), smallCap);
});

test('expired credits free up room under the cap', () => {
  const smallCap = 10 * 60;
  const existing = [{ earnedAt: hoursAgo(25), remainingSeconds: smallCap }];
  assert.equal(earn(existing, 10, NOW, smallCap).grantedSeconds, smallCap);
});

test('next expiry tracks the oldest live credit', () => {
  const credits = [
    { earnedAt: hoursAgo(1), remainingSeconds: 60 },
    { earnedAt: hoursAgo(20), remainingSeconds: 60 },
  ];
  assert.equal(nextExpiryAt(credits, NOW), hoursAgo(20) + EXPIRY_MS);
  assert.equal(nextExpiryAt([], NOW), null);
});

test('expiring-within counts only the batches about to die', () => {
  const credits = [
    { earnedAt: hoursAgo(23), remainingSeconds: 120 },
    { earnedAt: hoursAgo(2), remainingSeconds: 300 },
  ];
  assert.equal(expiringWithin(credits, NOW, HOUR), 120);
});

test('a full day cycle - earn, partly spend, let the rest expire', () => {
  let credits = earn([], 30, NOW, CAP).credits;
  assert.equal(balanceSeconds(credits, NOW), 30 * 60);

  const afterScrolling = NOW + 2 * HOUR;
  credits = spend(credits, 18 * 60, afterScrolling).credits;
  assert.equal(balanceSeconds(credits, afterScrolling), 12 * 60);

  assert.equal(balanceSeconds(credits, NOW + 25 * HOUR), 0);
});

