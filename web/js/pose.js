/**
 * MediaPipe pose tracking for the browser, mirroring the Android PoseAnalyzer.
 *
 * Everything runs on-device through WebAssembly. No frame is uploaded, and the
 * page works with the network off once the service worker has cached the model.
 */

const TASKS_VISION =
  'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14/vision_bundle.mjs';
const WASM_ROOT = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14/wasm';
const MODEL_URL =
  'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task';

/** BlazePose landmark indices used by the counter and the overlay. */
export const LANDMARK = {
  NOSE: 0,
  LEFT_SHOULDER: 11,
  RIGHT_SHOULDER: 12,
  LEFT_ELBOW: 13,
  RIGHT_ELBOW: 14,
  LEFT_WRIST: 15,
  RIGHT_WRIST: 16,
  LEFT_HIP: 23,
  RIGHT_HIP: 24,
  LEFT_KNEE: 25,
  RIGHT_KNEE: 26,
  LEFT_ANKLE: 27,
  RIGHT_ANKLE: 28,
};

export const DRAWN_LANDMARKS = Object.values(LANDMARK);

export const ARM_BONES = [
  [LANDMARK.LEFT_SHOULDER, LANDMARK.LEFT_ELBOW],
  [LANDMARK.LEFT_ELBOW, LANDMARK.LEFT_WRIST],
  [LANDMARK.RIGHT_SHOULDER, LANDMARK.RIGHT_ELBOW],
  [LANDMARK.RIGHT_ELBOW, LANDMARK.RIGHT_WRIST],
];

/** The plank line — what turns red when the hips sag. */
export const BODY_BONES = [
  [LANDMARK.LEFT_SHOULDER, LANDMARK.LEFT_HIP],
  [LANDMARK.RIGHT_SHOULDER, LANDMARK.RIGHT_HIP],
  [LANDMARK.LEFT_HIP, LANDMARK.LEFT_KNEE],
  [LANDMARK.RIGHT_HIP, LANDMARK.RIGHT_KNEE],
  [LANDMARK.LEFT_KNEE, LANDMARK.LEFT_ANKLE],
  [LANDMARK.RIGHT_KNEE, LANDMARK.RIGHT_ANKLE],
];

export const FRAME_BONES = [
  [LANDMARK.LEFT_SHOULDER, LANDMARK.RIGHT_SHOULDER],
  [LANDMARK.LEFT_HIP, LANDMARK.RIGHT_HIP],
];

import { angleAt } from './counter.js';

/**
 * Reduces a skeleton to the elbow and body-line angles.
 *
 * Both sides are measured and blended by confidence rather than picking one
 * arm: in a side-on push-up the far arm is partly occluded, and trusting it
 * alone produces angles that swing wildly as the model guesses.
 */
export function metricsFrom(landmarks) {
  const side = (shoulder, elbow, wrist, hip, knee) => {
    const points = [shoulder, elbow, wrist, hip, knee].map((i) => landmarks[i]);
    if (points.some((p) => !p)) return null;

    const confidence = Math.min(
      ...points.map((p) => (typeof p.visibility === 'number' ? p.visibility : 1)),
    );

    return {
      elbowAngle: angleAt(points[0], points[1], points[2]),
      bodyLineAngle: angleAt(points[0], points[3], points[4]),
      confidence,
    };
  };

  const left = side(
    LANDMARK.LEFT_SHOULDER,
    LANDMARK.LEFT_ELBOW,
    LANDMARK.LEFT_WRIST,
    LANDMARK.LEFT_HIP,
    LANDMARK.LEFT_KNEE,
  );
  const right = side(
    LANDMARK.RIGHT_SHOULDER,
    LANDMARK.RIGHT_ELBOW,
    LANDMARK.RIGHT_WRIST,
    LANDMARK.RIGHT_HIP,
    LANDMARK.RIGHT_KNEE,
  );

  if (!left) return right;
  if (!right) return left;

  const weight = left.confidence + right.confidence;
  if (weight <= 0) return left;

  return {
    elbowAngle: (left.elbowAngle * left.confidence + right.elbowAngle * right.confidence) / weight,
    bodyLineAngle:
      (left.bodyLineAngle * left.confidence + right.bodyLineAngle * right.confidence) / weight,
    // The clearer side vouches for the blend; averaging would let an occluded
    // limb suppress a perfectly good reading.
    confidence: Math.max(left.confidence, right.confidence),
  };
}

/**
 * Loads the model and runs detection against a <video> element.
 */
export class PoseTracker {
  constructor(video, onResult) {
    this.video = video;
    this.onResult = onResult;
    this.landmarker = null;
    this.running = false;
    this.lastTimestamp = -1;
  }

  async load() {
    const { FilesetResolver, PoseLandmarker } = await import(TASKS_VISION);
    const fileset = await FilesetResolver.forVisionTasks(WASM_ROOT);

    this.landmarker = await PoseLandmarker.createFromOptions(fileset, {
      baseOptions: { modelAssetPath: MODEL_URL, delegate: 'GPU' },
      runningMode: 'VIDEO',
      numPoses: 1,
    });
  }

  start() {
    if (this.running) return;
    this.running = true;
    this.#loop();
  }

  stop() {
    this.running = false;
  }

  close() {
    this.stop();
    this.landmarker?.close?.();
    this.landmarker = null;
  }

  #loop = () => {
    if (!this.running) return;

    const video = this.video;
    if (this.landmarker && video.readyState >= 2) {
      // MediaPipe rejects a repeated or non-monotonic timestamp, which happens
      // whenever the render loop outruns the camera's frame rate.
      const timestamp = performance.now();
      if (timestamp > this.lastTimestamp) {
        this.lastTimestamp = timestamp;
        try {
          const result = this.landmarker.detectForVideo(video, timestamp);
          const landmarks = result?.landmarks?.[0] ?? null;
          this.onResult({
            landmarks,
            metrics: landmarks ? metricsFrom(landmarks) : null,
          });
        } catch {
          this.onResult({ landmarks: null, metrics: null });
        }
      }
    }

    requestAnimationFrame(this.#loop);
  };
}
