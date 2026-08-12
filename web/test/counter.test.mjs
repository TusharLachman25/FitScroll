import test from 'node:test';
import assert from 'node:assert/strict';

import { COACHING, PHASE, PushUpCounter, angleAt, profileFor } from '../js/counter.js';

const standard = profileFor(3); // down 90, up 156, body 150, 600ms
const casual = profileFor(1); // down 115, up 145, body 115, 350ms
const brutal = profileFor(5); // down 72, up 168, body 165, 900ms

const FRAME_MS = 33; // ~30fps

/** Holds an angle for several frames so the smoother converges. */
function hold(counter, elbow, startAt, { body = 180, confidence = 0.9, frames = 8 } = {}) {
  let t = startAt;
  for (let i = 0; i < frames; i += 1) {
    counter.onFrame({ elbowAngle: elbow, bodyLineAngle: body, confidence }, t);
    t += FRAME_MS;
  }
  return t;
}

function frame(counter, elbow, at, { body = 180, confidence = 0.9 } = {}) {
  return counter.onFrame({ elbowAngle: elbow, bodyLineAngle: body, confidence }, at);
}

test('angleAt measures a straight line as 180 degrees', () => {
  const straight = angleAt({ x: 0, y: 0 }, { x: 1, y: 0 }, { x: 2, y: 0 });
  assert.ok(Math.abs(straight - 180) < 0.001);

  const right = angleAt({ x: 0, y: 1 }, { x: 0, y: 0 }, { x: 1, y: 0 });
  assert.ok(Math.abs(right - 90) < 0.001);
});

test('a clean controlled rep counts', () => {
  const counter = new PushUpCounter(standard);
  const topDone = hold(counter, 175, 0);
  hold(counter, 70, topDone);
  const result = frame(counter, 175, topDone + 900);

  assert.equal(counter.reps, 1);
  assert.equal(result.repJustCounted, true);
});

test('consecutive reps accumulate', () => {
  const counter = new PushUpCounter(standard);
  let t = hold(counter, 175, 0);

  for (let i = 0; i < 5; i += 1) {
    const bottomAt = t;
    hold(counter, 70, bottomAt);
    t = bottomAt + 900;
    frame(counter, 175, t);
    t += FRAME_MS;
  }

  assert.equal(counter.reps, 5);
});

test('a half rep that never reaches depth does not count', () => {
  const counter = new PushUpCounter(standard);
  const topDone = hold(counter, 175, 0);
  const bottomDone = hold(counter, 120, topDone);
  frame(counter, 175, bottomDone + 900);

  assert.equal(counter.reps, 0);
});

test('a rep faster than the minimum duration is rejected', () => {
  const counter = new PushUpCounter(standard);
  const topDone = hold(counter, 175, 0);
  hold(counter, 70, topDone);
  const result = frame(counter, 175, topDone + 200);

  assert.equal(counter.reps, 0);
  assert.match(result.rejection, /fast/i);
});

test('an arm swiped across the camera in one frame banks nothing', () => {
  const counter = new PushUpCounter(standard);
  const topDone = hold(counter, 175, 0);

  frame(counter, 60, topDone);
  const result = frame(counter, 175, topDone + FRAME_MS);

  assert.equal(counter.reps, 0);
  assert.ok(result.rejection);
});

test('sagging hips void the rep', () => {
  const counter = new PushUpCounter(standard);
  const topDone = hold(counter, 175, 0);
  hold(counter, 70, topDone, { body: 120 });
  const result = frame(counter, 175, topDone + 900, { body: 120 });

  assert.equal(counter.reps, 0);
  assert.match(result.rejection, /straight/i);
});

test('form broken while resting at the top does not void the next rep', () => {
  const counter = new PushUpCounter(standard);
  let t = hold(counter, 175, 0);
  t = hold(counter, 175, t, { body: 100 });
  t = hold(counter, 70, t);
  frame(counter, 175, t + 900);

  assert.equal(counter.reps, 1);
});

test('losing the subject preserves the count but voids the rep in flight', () => {
  const counter = new PushUpCounter(standard);
  let t = hold(counter, 175, 0);
  hold(counter, 70, t);
  frame(counter, 175, t + 900);
  assert.equal(counter.reps, 1);

  t = hold(counter, 70, t + 933);
  const lost = frame(counter, 70, t, { confidence: 0.1 });
  assert.equal(counter.reps, 1);
  assert.equal(lost.phase, PHASE.SEARCHING);
  assert.equal(lost.coaching, COACHING.FINDING_YOU);

  frame(counter, 175, t + 500);
  assert.equal(counter.reps, 1);
});

test('a null pose is treated as a tracking loss', () => {
  const counter = new PushUpCounter(standard);
  hold(counter, 175, 0);
  const result = counter.onFrame(null, 500);
  assert.equal(result.phase, PHASE.SEARCHING);
});

test('the same shallow rep counts on casual and not on standard', () => {
  const run = (profile) => {
    const counter = new PushUpCounter(profile);
    const topDone = hold(counter, 175, 0);
    hold(counter, 100, topDone);
    frame(counter, 175, topDone + 1200);
    return counter.reps;
  };

  assert.equal(run(casual), 1);
  assert.equal(run(standard), 0);
});

test('brutal demands a slower rep than standard accepts', () => {
  const run = (profile) => {
    const counter = new PushUpCounter(profile);
    const topDone = hold(counter, 178, 0);
    hold(counter, 65, topDone);
    frame(counter, 178, topDone + 700);
    return counter.reps;
  };

  assert.equal(run(standard), 1);
  assert.equal(run(brutal), 0);
});

test('changing strictness mid-session abandons the rep in progress', () => {
  const counter = new PushUpCounter(standard);
  const topDone = hold(counter, 175, 0);
  hold(counter, 70, topDone);

  counter.setProfile(brutal);
  const result = frame(counter, 175, topDone + 2000);

  assert.equal(counter.reps, 0);
  assert.equal(result.phase, PHASE.TOP);
});

test('reset clears the count and the state machine', () => {
  const counter = new PushUpCounter(standard);
  const topDone = hold(counter, 175, 0);
  hold(counter, 70, topDone);
  frame(counter, 175, topDone + 900);
  assert.equal(counter.reps, 1);

  counter.reset();
  assert.equal(counter.reps, 0);
  assert.equal(counter.onFrame(null, 0).phase, PHASE.SEARCHING);
});
