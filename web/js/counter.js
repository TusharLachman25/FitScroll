/**
 * Push-up counting, mirroring android/.../pose/PushUpCounter.kt.
 *
 * Pure: no DOM, no camera, no clock of its own. The caller passes `now`, which
 * makes every anti-cheat rule exercisable from `node --test`.
 */

/**
 * Strictness levels 1-5, identical to the Android profiles.
 *
 * Each level tightens five things at once. Loosening only depth produces a
 * counter that pays a fast sloppy half-rep the same as a slow clean one.
 *
 * The body-line numbers sit well under a true 180-degree plank on purpose.
 * This is a 2D estimate from one camera, and unless the lens is exactly
 * perpendicular to you, perspective foreshortens the torso so a genuinely
 * straight back measures far lower. Geometrically "correct" thresholds reject
 * real push-ups at real phone placements.
 */
export const STRICTNESS = [
  {
    level: 1,
    label: 'Casual',
    blurb: 'Counts almost any up-and-down. Good for warming up or an awkward camera angle.',
    downElbowAngle: 115,
    upElbowAngle: 145,
    minBodyLineAngle: 100,
    formGraceMs: 1500,
    minRepMs: 350,
    minConfidence: 0.3,
  },
  {
    level: 2,
    label: 'Relaxed',
    blurb: 'Forgiving on depth, still expects a recognisable push-up.',
    downElbowAngle: 105,
    upElbowAngle: 150,
    minBodyLineAngle: 118,
    formGraceMs: 1100,
    minRepMs: 450,
    minConfidence: 0.4,
  },
  {
    level: 3,
    label: 'Standard',
    blurb: 'Roughly a gym-legal push-up: past 90 degrees, full lockout, straight back.',
    downElbowAngle: 90,
    upElbowAngle: 156,
    minBodyLineAngle: 132,
    formGraceMs: 800,
    minRepMs: 600,
    minConfidence: 0.5,
  },
  {
    level: 4,
    label: 'Strict',
    blurb: 'Chest low, full lockout, no hip sag. Expect your count to drop.',
    downElbowAngle: 80,
    upElbowAngle: 162,
    minBodyLineAngle: 144,
    formGraceMs: 500,
    minRepMs: 750,
    minConfidence: 0.58,
  },
  {
    level: 5,
    label: 'Brutal',
    blurb: 'Near-floor depth, dead-straight body, no bouncing. Every minute is earned.',
    downElbowAngle: 72,
    upElbowAngle: 168,
    minBodyLineAngle: 155,
    formGraceMs: 300,
    minRepMs: 900,
    minConfidence: 0.65,
  },
];

export function profileFor(level) {
  return STRICTNESS[Math.min(5, Math.max(1, level)) - 1];
}

export const PHASE = { SEARCHING: 'searching', TOP: 'top', BOTTOM: 'bottom' };

export const COACHING = {
  FINDING_YOU: 'Get your upper body in frame',
  GET_SET: 'Arms straight — get set at the top',
  GO_LOWER: 'Lower — bend those elbows',
  PUSH_UP: 'Push all the way back up',
  STRAIGHTEN_BODY: 'Straighten your back',
};

/** Interior angle at b formed by a-b-c, in degrees. */
export function angleAt(a, b, c) {
  const abx = a.x - b.x;
  const aby = a.y - b.y;
  const cbx = c.x - b.x;
  const cby = c.y - b.y;

  const magAb = Math.hypot(abx, aby);
  const magCb = Math.hypot(cbx, cby);
  if (magAb < 1e-4 || magCb < 1e-4) return 180;

  const cosine = Math.min(1, Math.max(-1, (abx * cbx + aby * cby) / (magAb * magCb)));
  return (Math.acos(cosine) * 180) / Math.PI;
}

const SMOOTHING = 0.35;
const RESET_JUMP_DEGREES = 45;
const NOT_SET = -1;
const REJECTION_HOLD_MS = 1800;

/**
 * Clamp on the gap between frames. After a pause or a tracking gap the raw
 * delta can be seconds long, and charging that to the form budget would void a
 * rep for time in which nobody was being tracked at all.
 */
const MAX_FRAME_DELTA_MS = 200;

/**
 * Smooths a noisy per-frame angle. Large jumps snap rather than blend, so
 * re-acquiring a subject does not drag a stale angle across the next rep.
 */
export function smoothAngle(previous, current, factor = SMOOTHING) {
  if (previous === null || previous === undefined) return current;
  if (Math.abs(previous - current) > RESET_JUMP_DEGREES) return current;
  return previous + (current - previous) * factor;
}

export class PushUpCounter {
  constructor(profile) {
    this.profile = profile;
    this.reset();
  }

  setProfile(next) {
    if (next.level === this.profile.level) return;
    this.profile = next;
    this.phase = PHASE.SEARCHING;
    this.descentStartedAt = NOT_SET;
    this.formBadMs = 0;
  }

  reset() {
    this.reps = 0;
    this.phase = PHASE.SEARCHING;
    this.descentStartedAt = NOT_SET;
    /**
     * Time spent outside body-line tolerance during the current rep.
     *
     * Accumulated rather than latched as a boolean. One frame of hip jitter is
     * not bad form, and latching on it threw away clean reps — the symptom
     * being "straighten your back" shouted at a perfectly straight back.
     */
    this.formBadMs = 0;
    this.lastFrameAt = NOT_SET;
    this.smoothedElbow = null;
    this.smoothedBody = null;
    this.rejectionMessage = null;
    this.rejectionExpiresAt = 0;
  }

  /**
   * Feeds one frame. Pass null `metrics` when no usable skeleton was found.
   * @param {{elbowAngle:number, bodyLineAngle:number, confidence:number, bodyConfidence:number}|null} metrics
   */
  onFrame(metrics, now) {
    const frameDelta =
      this.lastFrameAt === NOT_SET
        ? 0
        : Math.min(MAX_FRAME_DELTA_MS, Math.max(0, now - this.lastFrameAt));
    this.lastFrameAt = now;

    if (!metrics || metrics.confidence < this.profile.minConfidence) {
      // Losing the subject keeps banked reps — people step out of frame between
      // sets — but voids the rep in flight, since we cannot vouch for what
      // happened while the camera could not see them.
      this.phase = PHASE.SEARCHING;
      this.descentStartedAt = NOT_SET;
      this.formBadMs = 0;
      this.smoothedElbow = null;
      this.smoothedBody = null;
      return this.#update(COACHING.FINDING_YOU, 0, true, false, false, now);
    }

    const elbow = smoothAngle(this.smoothedElbow, metrics.elbowAngle);
    this.smoothedElbow = elbow;

    // Only judge the back when the torso landmarks are trustworthy. With legs
    // out of frame the model still emits a knee, it is just guessing, and
    // failing reps against a guessed joint is worse than not checking.
    const bodyConfidence =
      typeof metrics.bodyConfidence === 'number' ? metrics.bodyConfidence : metrics.confidence;
    const formJudged = bodyConfidence >= this.profile.minConfidence;

    let body = null;
    if (formJudged) {
      body = smoothAngle(this.smoothedBody, metrics.bodyLineAngle);
      this.smoothedBody = body;
    } else {
      this.smoothedBody = null;
    }

    const formOk = body === null || body >= this.profile.minBodyLineAngle;
    if (!formOk && this.phase !== PHASE.SEARCHING) this.formBadMs += frameDelta;

    const depth = Math.min(
      1,
      Math.max(
        0,
        (this.profile.upElbowAngle - elbow) /
          (this.profile.upElbowAngle - this.profile.downElbowAngle),
      ),
    );

    let counted = false;
    let coaching = COACHING.GET_SET;

    if (this.phase === PHASE.SEARCHING) {
      if (elbow >= this.profile.upElbowAngle) {
        this.phase = PHASE.TOP;
        this.descentStartedAt = NOT_SET;
        this.formBadMs = 0;
      }
      coaching = COACHING.GET_SET;
    } else if (this.phase === PHASE.TOP) {
      if (elbow < this.profile.upElbowAngle) {
        // The clock starts before the depth check, not instead of it. An arm
        // swiped past the lens crosses both thresholds inside one frame, and
        // leaving the timer unset there would arrive at the top with no
        // measurable duration and mint a free minute.
        if (this.descentStartedAt === NOT_SET) {
          this.descentStartedAt = now;
          this.formBadMs = 0;
        }
        if (elbow <= this.profile.downElbowAngle) this.phase = PHASE.BOTTOM;
      } else {
        this.descentStartedAt = NOT_SET;
      }
      coaching = formOk ? COACHING.GO_LOWER : COACHING.STRAIGHTEN_BODY;
    } else if (this.phase === PHASE.BOTTOM) {
      if (elbow >= this.profile.upElbowAngle) {
        // Fail closed: an unset timer means no descent was observed.
        const duration = this.descentStartedAt === NOT_SET ? 0 : now - this.descentStartedAt;

        if (duration < this.profile.minRepMs) {
          this.#reject('Too fast — control the rep', now);
        } else if (this.formBadMs > this.profile.formGraceMs) {
          this.#reject('Hips dropped — rep not counted', now);
        } else {
          this.reps += 1;
          counted = true;
        }

        this.phase = PHASE.TOP;
        this.descentStartedAt = NOT_SET;
        this.formBadMs = 0;
      }
      coaching = formOk ? COACHING.PUSH_UP : COACHING.STRAIGHTEN_BODY;
    }

    return this.#update(coaching, depth, formOk, formJudged, counted, now);
  }

  #reject(message, now) {
    this.rejectionMessage = message;
    this.rejectionExpiresAt = now + REJECTION_HOLD_MS;
  }

  #update(coaching, depth, formOk, formJudged, counted, now) {
    return {
      reps: this.reps,
      phase: this.phase,
      coaching,
      depth,
      formOk,
      formJudged,
      repJustCounted: counted,
      rejection: now < this.rejectionExpiresAt ? this.rejectionMessage : null,
    };
  }
}
