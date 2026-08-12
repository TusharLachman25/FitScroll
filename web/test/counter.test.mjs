import test from 'node:test';
import assert from 'node:assert/strict';

import { COACHING, PHASE, PushUpCounter, angleAt, profileFor } from '../js/counter.js';

const standard = profileFor(3); // down 92, up 152, body 128, grace 800ms, 600ms
const casual = profileFor(1); // down 115, up 142, body 100, grace 1500ms, 350ms
const brutal = profileFor(5); // down 72, up 162, body 148, grace 350ms, 900ms

const FRAME_MS = 33; // ~30fps
const SETTLE = 300; // long enough for the angle smoother to converge

/** Feeds a held pose at camera frame rate, as a real descent arrives. */
function holdFor(counter, elbow, startAt, durationMs, opts = {}) {
  const { body = 180, confidence = 0.9, bodyConfidence = 0.9 } = opts;
  let t = startAt;
  const end = startAt + durationMs;
  while (t <= end) {
    counter.onFrame({ elbowAngle: elbow, bodyLineAngle: body, confidence, bodyConfidence }, t);
    t += FRAME_MS;
  }
  return t;
}

function frame(counter, elbow, at, opts = {}) {
  const { body = 180, confidence = 0.9, bodyConfidence = 0.9 } = opts;
  return counter.onFrame(
    { elbowAngle: elbow, bodyLineAngle: body, confidence, bodyConfidence },
    at,
  );
}

test('angleAt measures a straight line as 180 degrees', () => {
  assert.ok(Math.abs(angleAt({ x: 0, y: 0 }, { x: 1, y: 0 }, { x: 2, y: 0 }) - 180) < 0.001);
  assert.ok(Math.abs(angleAt({ x: 0, y: 1 }, { x: 0, y: 0 }, { x: 1, y: 0 }) - 90) < 0.001);
});

test('a clean controlled rep counts', () => {
  const counter = new PushUpCounter(standard);
  const top = holdFor(counter, 175, 0, SETTLE);
  holdFor(counter, 70, top, 500);
  const result = frame(counter, 175, top + 900);

  assert.equal(counter.reps, 1);
  assert.equal(result.repJustCounted, true);
});

test('consecutive reps accumulate', () => {
  const counter = new PushUpCounter(standard);
  let t = holdFor(counter, 175, 0, SETTLE);

  for (let i = 0; i < 5; i += 1) {
    const descentAt = t;
    holdFor(counter, 70, descentAt, 500);
    t = descentAt + 900;
    frame(counter, 175, t);
    t += FRAME_MS;
  }

  assert.equal(counter.reps, 5);
});

test('a half rep that never reaches depth does not count', () => {
  const counter = new PushUpCounter(standard);
  const top = holdFor(counter, 175, 0, SETTLE);
  holdFor(counter, 120, top, 500);
  frame(counter, 175, top + 900);

  assert.equal(counter.reps, 0);
});

test('a rep faster than the minimum duration is rejected', () => {
  const counter = new PushUpCounter(standard);
  const top = holdFor(counter, 175, 0, SETTLE);
  holdFor(counter, 70, top, 100);
  const result = frame(counter, 175, top + 200);

  assert.equal(counter.reps, 0);
  assert.match(result.rejection, /fast/i);
});

test('an arm swiped across the camera in one frame banks nothing', () => {
  const counter = new PushUpCounter(standard);
  const top = holdFor(counter, 175, 0, SETTLE);

  frame(counter, 60, top);
  const result = frame(counter, 175, top + FRAME_MS);

  assert.equal(counter.reps, 0);
  assert.ok(result.rejection);
});

test('sustained hip sag voids the rep', () => {
  const counter = new PushUpCounter(standard);
  const top = holdFor(counter, 175, 0, SETTLE);
  holdFor(counter, 70, top, 1200, { body: 110 });
  const result = frame(counter, 175, top + 1400, { body: 110 });

  assert.equal(counter.reps, 0);
  assert.match(result.rejection, /hips/i);
});

test('a few noisy frames do not void an otherwise clean rep', () => {
  const counter = new PushUpCounter(standard);
  const top = holdFor(counter, 175, 0, SETTLE);
  let t = holdFor(counter, 70, top, 300);

  // Three frames of hip jitter, ~100ms, far inside the 800ms grace. This is
  // the shape of pose-model noise on a motionless subject, and latching on it
  // was rejecting real push-ups.
  for (let i = 0; i < 3; i += 1) {
    frame(counter, 70, t, { body: 100 });
    t += FRAME_MS;
  }

  holdFor(counter, 70, t, 200);
  frame(counter, 175, top + 900);

  assert.equal(counter.reps, 1);
});

test('form is not judged when the legs are not confidently visible', () => {
  const counter = new PushUpCounter(standard);
  const opts = { body: 90, bodyConfidence: 0.1 };

  const top = holdFor(counter, 175, 0, SETTLE, opts);
  holdFor(counter, 70, top, 600, opts);
  const result = frame(counter, 175, top + 900, opts);

  assert.equal(counter.reps, 1);
  assert.equal(result.formJudged, false);
  assert.equal(result.formOk, true); // reported fine because it was not assessed
});

test('form broken while resting at the top does not void the next rep', () => {
  const counter = new PushUpCounter(standard);
  let t = holdFor(counter, 175, 0, SETTLE);
  t = holdFor(counter, 175, t, 1500, { body: 100 });
  const descentAt = t;
  holdFor(counter, 70, descentAt, 500);
  frame(counter, 175, descentAt + 900);

  assert.equal(counter.reps, 1);
});

test('a tracking gap does not burn the form budget', () => {
  const counter = new PushUpCounter(standard);
  const top = holdFor(counter, 175, 0, SETTLE);
  holdFor(counter, 70, top, 300);

  // Ten seconds away, then a bad-form frame on return. Charging the whole gap
  // would void a rep for time in which nobody was being tracked.
  frame(counter, 70, top + 10_000, { body: 100 });
  frame(counter, 175, top + 10_100);

  assert.equal(counter.reps, 1);
});

test('losing the subject preserves the count but voids the rep in flight', () => {
  const counter = new PushUpCounter(standard);
  let t = holdFor(counter, 175, 0, SETTLE);
  holdFor(counter, 70, t, 500);
  frame(counter, 175, t + 900);
  assert.equal(counter.reps, 1);

  t = holdFor(counter, 70, t + 933, SETTLE);
  const lost = frame(counter, 70, t, { confidence: 0.1 });
  assert.equal(counter.reps, 1);
  assert.equal(lost.phase, PHASE.SEARCHING);
  assert.equal(lost.coaching, COACHING.FINDING_YOU);

  frame(counter, 175, t + 500);
  assert.equal(counter.reps, 1);
});

test('a null pose is treated as a tracking loss', () => {
  const counter = new PushUpCounter(standard);
  holdFor(counter, 175, 0, SETTLE);
  assert.equal(counter.onFrame(null, 500).phase, PHASE.SEARCHING);
});

test('the same shallow rep counts on casual and not on standard', () => {
  const run = (profile) => {
    const counter = new PushUpCounter(profile);
    const top = holdFor(counter, 175, 0, SETTLE);
    holdFor(counter, 100, top, 600);
    frame(counter, 175, top + 1200);
    return counter.reps;
  };

  assert.equal(run(casual), 1);
  assert.equal(run(standard), 0);
});

test('brutal demands a slower rep than standard accepts', () => {
  const run = (profile) => {
    const counter = new PushUpCounter(profile);
    const top = holdFor(counter, 178, 0, SETTLE);
    holdFor(counter, 65, top, 400);
    frame(counter, 178, top + 700);
    return counter.reps;
  };

  assert.equal(run(standard), 1);
  assert.equal(run(brutal), 0);
});

test('middling tracking confidence counts on every level', () => {
  // Confidence used to scale with the dial, so the same clean rep counted on
  // level 1 and silently vanished on level 5. Looking down at the floor drops
  // the pose model's likelihood across every landmark, which meant the top
  // levels were quietly demanding a better view rather than a better push-up.
  const run = (profile) => {
    const counter = new PushUpCounter(profile);
    const opts = { confidence: 0.45, bodyConfidence: 0.45 };
    const top = holdFor(counter, 178, 0, SETTLE, opts);
    holdFor(counter, 65, top, 900, opts);
    frame(counter, 178, top + 1200, opts);
    return counter.reps;
  };

  assert.equal(run(casual), 1);
  assert.equal(run(brutal), 1);
});

test('casual tolerates a body line that brutal rejects', () => {
  const run = (profile) => {
    const counter = new PushUpCounter(profile);
    const top = holdFor(counter, 175, 0, SETTLE, { body: 140 });
    holdFor(counter, 60, top, 1200, { body: 140 });
    frame(counter, 175, top + 1400, { body: 140 });
    return counter.reps;
  };

  assert.equal(run(casual), 1); // 140 clears casual's 100
  assert.equal(run(brutal), 0); // and misses brutal's 155
});

test('changing strictness mid-session abandons the rep in progress', () => {
  const counter = new PushUpCounter(standard);
  const top = holdFor(counter, 175, 0, SETTLE);
  holdFor(counter, 70, top, 500);

  counter.setProfile(brutal);
  const result = frame(counter, 175, top + 2000);

  assert.equal(counter.reps, 0);
  assert.equal(result.phase, PHASE.TOP);
});

test('reset clears the count and the state machine', () => {
  const counter = new PushUpCounter(standard);
  const top = holdFor(counter, 175, 0, SETTLE);
  holdFor(counter, 70, top, 500);
  frame(counter, 175, top + 900);
  assert.equal(counter.reps, 1);

  counter.reset();
  assert.equal(counter.reps, 0);
  assert.equal(counter.onFrame(null, 0).phase, PHASE.SEARCHING);
});
