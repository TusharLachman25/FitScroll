import { MAX_CAP_MINUTES } from './bank.js';
import { BankStore } from './store.js';
import { COACHING, PushUpCounter, STRICTNESS, profileFor } from './counter.js';
import { ARM_BONES, BODY_BONES, FRAME_BONES, DRAWN_LANDMARKS, PoseTracker } from './pose.js';

const APP_VERSION = '0.1.0';

/**
 * How long after starting a session a re-entry is treated as the automation
 * bouncing rather than the user genuinely coming back.
 *
 * The iOS Shortcut fires on every launch of the gated app, including the launch
 * FitScroll itself triggers. Without this window, tapping "Start a session"
 * would immediately settle a zero-second session and gate the user again.
 */
const AUTOMATION_BOUNCE_MS = 12_000;

const CAP_STEPS = [15, 30, 45, 60, 90, 120, 180, 240, 360, 480, 720, 1440];
const RING_CIRCUMFERENCE = 326.7;

const store = new BankStore();
const $ = (id) => document.getElementById(id);

// --------------------------------------------------------------- formatting

function formatMinutes(minutes) {
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours === 0) return `${rest}m`;
  if (rest === 0) return `${hours}h`;
  return `${hours}h ${rest}m`;
}

function formatBalance(seconds) {
  if (seconds <= 0) return '0m';
  if (seconds < 60) return `${seconds}s`;
  return formatMinutes(Math.floor(seconds / 60));
}

function formatTimeUntil(epochMillis, now = Date.now()) {
  const delta = epochMillis - now;
  if (delta <= 0) return 'now';
  const hours = Math.floor(delta / 3_600_000);
  const minutes = Math.floor(delta / 60_000) % 60;
  if (hours > 0) return `in ${hours}h ${minutes}m`;
  if (minutes > 0) return `in ${minutes}m`;
  return 'in under a minute';
}

// ------------------------------------------------------------------- views

const views = {
  home: $('view-home'),
  workout: $('view-workout'),
  settings: $('view-settings'),
};

let activeView = 'home';

function show(name) {
  activeView = name;
  Object.entries(views).forEach(([key, element]) => {
    element.classList.toggle('hidden', key !== name);
  });
  if (name !== 'workout') stopWorkout();
  if (name === 'home') renderHome();
  if (name === 'settings') renderSettings();
}

// -------------------------------------------------------------------- home

function renderHome() {
  const now = Date.now();
  const snapshot = store.snapshot(now);
  const empty = snapshot.balanceSeconds <= 0;

  $('balance-card').classList.toggle('empty', empty);
  $('balance-eyebrow').textContent = empty ? 'Bank empty' : 'Banked';
  $('balance-value').textContent = formatBalance(snapshot.balanceSeconds);

  const note = $('balance-note');
  note.classList.remove('urgent');
  if (empty) {
    note.textContent = 'Locked. One push-up buys one minute.';
  } else if (snapshot.expiringSoonSeconds > 0) {
    note.textContent = `${formatBalance(snapshot.expiringSoonSeconds)} expires within the hour — use it or lose it.`;
    note.classList.add('urgent');
  } else if (snapshot.nextExpiryAt) {
    note.textContent = `Oldest minutes expire ${formatTimeUntil(snapshot.nextExpiryAt, now)}.`;
  } else {
    note.textContent = 'Ready to spend.';
  }

  $('reps-today').textContent = String(snapshot.repsToday);
  $('reps-all-time').textContent = String(snapshot.repsAllTime);

  const sessionOpen = store.hasOpenSession();
  $('session-banner').classList.toggle('hidden', !sessionOpen);

  const spendButton = $('spend');
  spendButton.disabled = empty;
  spendButton.textContent = empty
    ? 'Nothing banked yet'
    : sessionOpen
      ? 'Session running'
      : 'Start a session';
}

/**
 * Reconciles time spent in the gated app.
 *
 * iOS gives a web page no way to watch another app, so usage is settled on
 * return rather than metered live. Anything inside the bounce window is the
 * automation re-triggering on the launch we caused ourselves, not a real
 * return, and must not close the session.
 */
function settleIfReturned() {
  const startedAt = store.openSessionStartedAt();
  if (startedAt === null) return;
  if (Date.now() - startedAt < AUTOMATION_BOUNCE_MS) return;
  store.settleSession();
}

function startSession() {
  const scheme = store.settings().targetScheme || 'instagram://app';
  store.startSession();
  renderHome();
  window.location.href = scheme;
}

// ---------------------------------------------------------------- settings

function renderSettings() {
  const settings = store.settings();
  const profile = profileFor(settings.strictness);

  const picker = $('strictness-picker');
  picker.innerHTML = '';
  STRICTNESS.forEach((candidate) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = String(candidate.level);
    button.setAttribute('aria-pressed', String(candidate.level === settings.strictness));
    button.addEventListener('click', () => {
      store.saveSettings({ strictness: candidate.level });
      renderSettings();
    });
    picker.appendChild(button);
  });

  $('strictness-name').textContent = profile.label;
  $('strictness-blurb').textContent = profile.blurb;
  $('strictness-detail').textContent =
    `Bend past ${profile.downElbowAngle}°, lock out past ${profile.upElbowAngle}°, ` +
    `hold your body straighter than ${profile.minBodyLineAngle}°, and take at least ` +
    `${(profile.minRepMs / 1000).toFixed(1)}s per rep.`;

  const capIndex = Math.max(
    0,
    CAP_STEPS.findIndex((step) => step >= settings.capMinutes),
  );
  $('cap-slider').value = String(capIndex);
  $('cap-value').textContent = formatMinutes(Math.min(settings.capMinutes, MAX_CAP_MINUTES));

  $('target-scheme').value = settings.targetScheme || 'instagram://app';
  $('version-line').textContent =
    `FitScroll ${APP_VERSION} · running as a ${runtimeLabel()}. ` +
    'Everything stays on this device.';
}

/**
 * Whether this is the home-screen web app or an ordinary browser tab.
 *
 * Worth showing, because iOS gives the two separate storage containers for the
 * same site: minutes banked in one are invisible in the other. Since the
 * Shortcuts automation can only hand a URL to Safari, anyone using it and the
 * home-screen icon together ends up with two banks and no clue why.
 */
function runtimeLabel() {
  const standalone =
    window.matchMedia?.('(display-mode: standalone)')?.matches === true ||
    window.navigator.standalone === true;
  return standalone ? 'home-screen app' : 'browser tab';
}

// ----------------------------------------------------------------- workout

let tracker = null;
let stream = null;
let wakeLock = null;
let counter = new PushUpCounter(profileFor(store.settings().strictness));
let useFrontCamera = true;
let lastRepCount = 0;

/**
 * Keeps the display awake during a set.
 *
 * Doing push-ups means not touching the phone, so the screen dims and sleeps
 * mid-set and the camera stops seeing anything. Failures are ignored on
 * purpose: the API is missing on older Safari and the request is rejected on a
 * backgrounded page, and neither is a reason to interrupt a workout.
 */
async function acquireWakeLock() {
  if (!('wakeLock' in navigator) || wakeLock) return;
  try {
    wakeLock = await navigator.wakeLock.request('screen');
    wakeLock.addEventListener?.('release', () => {
      wakeLock = null;
    });
  } catch {
    wakeLock = null;
  }
}

function releaseWakeLock() {
  wakeLock?.release?.().catch?.(() => {});
  wakeLock = null;
}

async function startWorkout() {
  counter = new PushUpCounter(profileFor(store.settings().strictness));
  lastRepCount = 0;
  updateHud({
    reps: 0,
    depth: 0,
    formOk: true,
    formJudged: true,
    coaching: COACHING.FINDING_YOU,
    rejection: null,
  });

  const video = $('camera');
  video.classList.toggle('mirrored', useFrontCamera);

  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: {
        facingMode: useFrontCamera ? 'user' : 'environment',
        width: { ideal: 1280 },
        height: { ideal: 720 },
      },
      audio: false,
    });
    video.srcObject = stream;
    await video.play();
    $('camera-error').classList.add('hidden');
    acquireWakeLock();
  } catch (error) {
    showCameraError(
      error?.name === 'NotAllowedError'
        ? 'Camera access was denied. Allow it in Settings to count push-ups.'
        : 'No camera could be opened on this device.',
    );
    return;
  }

  try {
    tracker = new PoseTracker(video, onPoseResult);
    await tracker.load();
    tracker.start();
  } catch {
    showCameraError('The pose model could not load. Check your connection and try again.');
  }
}

function stopWorkout() {
  tracker?.close();
  tracker = null;
  stream?.getTracks().forEach((track) => track.stop());
  stream = null;
  releaseWakeLock();
  const video = $('camera');
  if (video) video.srcObject = null;
}

function showCameraError(detail) {
  $('camera-error').classList.remove('hidden');
  $('camera-error-detail').textContent = detail;
}

function onPoseResult({ landmarks, metrics }) {
  counter.setProfile(profileFor(store.settings().strictness));
  const update = counter.onFrame(metrics, performance.now());

  if (update.reps > lastRepCount) {
    lastRepCount = update.reps;
    navigator.vibrate?.(30);
  }

  updateHud(update);
  drawSkeleton(landmarks, update);
}

function updateHud(update) {
  $('rep-value').textContent = String(update.reps);
  $('rep-label').textContent = update.reps === 1 ? 'rep' : 'reps';

  // Saying so is the difference between "this level is oddly lenient" and
  // knowing the legs are out of shot, which is fixable by moving the phone.
  const profile = profileFor(store.settings().strictness);
  $('strictness-pill').textContent =
    update.formJudged === false
      ? `${profile.label.toUpperCase()} · BACK NOT CHECKED`
      : profile.label.toUpperCase();

  const ring = $('ring-progress');
  ring.style.strokeDashoffset = String(RING_CIRCUMFERENCE * (1 - update.depth));
  ring.classList.toggle('bad-form', !update.formOk);

  const coaching = $('coaching');
  coaching.textContent = update.coaching;
  coaching.classList.toggle('bad-form', !update.formOk);

  const rejection = $('rejection');
  rejection.textContent = update.rejection ?? '';
  rejection.classList.toggle('hidden', !update.rejection);

  const bank = $('bank-set');
  bank.disabled = update.reps <= 0;
  bank.textContent = update.reps > 0 ? `Bank ${update.reps} min` : 'Do a rep to bank minutes';
  $('discard-set').classList.toggle('hidden', update.reps <= 0);
}

/**
 * Draws the tracked skeleton over the preview.
 *
 * The video is object-fit: cover, so the same scale-by-the-larger-ratio-then-
 * centre transform has to be applied here or the skeleton drifts off the body
 * near the frame edges.
 */
function drawSkeleton(landmarks, update) {
  const canvas = $('overlay');
  const video = $('camera');
  const ctx = canvas.getContext('2d');

  const ratio = window.devicePixelRatio || 1;
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;
  if (canvas.width !== width * ratio || canvas.height !== height * ratio) {
    canvas.width = width * ratio;
    canvas.height = height * ratio;
  }

  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, width, height);

  if (!landmarks || !video.videoWidth) return;

  const scale = Math.max(width / video.videoWidth, height / video.videoHeight);
  const offsetX = (width - video.videoWidth * scale) / 2;
  const offsetY = (height - video.videoHeight * scale) / 2;

  const project = (landmark) => {
    const x = landmark.x * video.videoWidth * scale + offsetX;
    return {
      x: useFrontCamera ? width - x : x,
      y: landmark.y * video.videoHeight * scale + offsetY,
    };
  };

  const confidenceOf = (landmark) =>
    typeof landmark.visibility === 'number' ? landmark.visibility : 1;

  const armColour = update.depth > 0.5 ? '#4ade80' : '#94a3b8';
  const bodyColour = update.formOk ? 'rgba(74, 222, 128, 0.9)' : '#f43f5e';

  const drawBones = (bones, colour, lineWidth) => {
    ctx.strokeStyle = colour;
    ctx.lineWidth = lineWidth;
    ctx.lineCap = 'round';
    bones.forEach(([startIndex, endIndex]) => {
      const start = landmarks[startIndex];
      const end = landmarks[endIndex];
      if (!start || !end) return;
      // A limb the model is guessing at should not be drawn as though it were
      // measured; a confident-looking wrong skeleton is worse than a gap.
      if (confidenceOf(start) < 0.35 || confidenceOf(end) < 0.35) return;
      const a = project(start);
      const b = project(end);
      ctx.beginPath();
      ctx.moveTo(a.x, a.y);
      ctx.lineTo(b.x, b.y);
      ctx.stroke();
    });
  };

  drawBones(FRAME_BONES, 'rgba(148, 163, 184, 0.5)', 3);
  drawBones(BODY_BONES, bodyColour, 5);
  drawBones(ARM_BONES, armColour, 6);

  DRAWN_LANDMARKS.forEach((index) => {
    const landmark = landmarks[index];
    if (!landmark || confidenceOf(landmark) < 0.35) return;
    const point = project(landmark);
    ctx.fillStyle = 'rgba(255, 255, 255, 0.85)';
    ctx.beginPath();
    ctx.arc(point.x, point.y, 4, 0, Math.PI * 2);
    ctx.fill();
  });
}

// ------------------------------------------------------------------ wiring

$('open-settings').addEventListener('click', () => show('settings'));
$('settings-back').addEventListener('click', () => show('home'));
$('workout-back').addEventListener('click', () => show('home'));

$('start-workout').addEventListener('click', async () => {
  show('workout');
  await startWorkout();
});

$('spend').addEventListener('click', startSession);

$('flip-camera').addEventListener('click', async () => {
  useFrontCamera = !useFrontCamera;
  stopWorkout();
  await startWorkout();
});

$('bank-set').addEventListener('click', () => {
  const outcome = store.earn(counter.reps);
  counter.reset();
  lastRepCount = 0;

  const granted = Math.floor(outcome.grantedSeconds / 60);
  const wasted = Math.floor(outcome.wastedSeconds / 60);
  if (wasted > 0 && granted === 0) {
    alert('Bank is already full — those reps earned nothing.');
  } else if (wasted > 0) {
    alert(`Banked ${formatMinutes(granted)} — ${formatMinutes(wasted)} hit your bank limit.`);
  }

  show('home');
});

$('discard-set').addEventListener('click', () => {
  counter.reset();
  lastRepCount = 0;
  updateHud({
    reps: 0,
    depth: 0,
    formOk: true,
    formJudged: true,
    coaching: COACHING.GET_SET,
    rejection: null,
  });
});

$('cap-slider').addEventListener('input', (event) => {
  const index = Number(event.target.value);
  store.saveSettings({ capMinutes: CAP_STEPS[index] });
  renderSettings();
});

$('target-scheme').addEventListener('change', (event) => {
  store.saveSettings({ targetScheme: event.target.value.trim() });
});

$('clear-bank').addEventListener('click', () => {
  if (confirm('Delete every banked minute? This cannot be undone.')) {
    store.clear();
    renderSettings();
  }
});

// Returning to the page is the only moment iOS gives us to reconcile usage.
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState !== 'visible') return;
  settleIfReturned();
  if (activeView === 'home') renderHome();
  // The browser drops a screen wake lock whenever the page is hidden, and does
  // not restore it, so mid-workout it has to be asked for again.
  if (activeView === 'workout') acquireWakeLock();
});

// The balance falls as credits expire even with nobody touching the screen.
setInterval(() => {
  if (activeView === 'home') renderHome();
}, 1000);

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js').catch(() => {
    // Offline support is a bonus; the app is fully usable without it.
  });
}

settleIfReturned();
show('home');
