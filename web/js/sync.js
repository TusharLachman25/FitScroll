/**
 * Account and sync for the web app.
 *
 * Uploads whatever this device recorded while it was the only one that knew,
 * then pulls back everything the account has seen elsewhere. Both directions
 * are safe to repeat: event ids are generated on the device, so re-sending
 * after a dropped connection collides on the primary key instead of banking a
 * set twice.
 */

import { createClient } from 'https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2/+esm';

import { SUPABASE_ANON_KEY, SUPABASE_URL } from './config.js';
import { RELEVANT_WINDOW_MS, fromRow, toRow } from './ledger.js';

export const supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
  auth: {
    persistSession: true,
    autoRefreshToken: true,
    // The OAuth redirect comes back with the grant in the URL; this consumes it
    // and swaps it for a session without the page having to parse anything.
    detectSessionInUrl: true,
    flowType: 'pkce',
  },
});

/**
 * Which device an event came from, for debugging a disagreement later.
 * Deliberately coarse — this is not a fingerprint.
 */
function deviceLabel() {
  const standalone =
    window.matchMedia?.('(display-mode: standalone)')?.matches === true ||
    window.navigator.standalone === true;
  const platform = /iPhone|iPad|iPod/.test(navigator.userAgent) ? 'ios' : 'web';
  return `${platform}-${standalone ? 'app' : 'browser'}`;
}

export async function currentUser() {
  const { data } = await supabase.auth.getSession();
  return data?.session?.user ?? null;
}

export function onAuthChange(handler) {
  supabase.auth.onAuthStateChange((_event, session) => handler(session?.user ?? null));
}

export async function signInWithGoogle() {
  // Always the directory, never the page.
  //
  // Safari opens this app at `.../FitScroll/` while the home-screen copy opens
  // at `.../FitScroll/index.html`, and sending whichever one happened to be
  // current meant two different redirect targets. Supabase only honours a
  // redirect it recognises and silently falls back to the project's Site URL
  // otherwise, so the installed app was being bounced to localhost while Safari
  // worked fine.
  //
  // Normalising to the directory gives one URL to allowlist, keeps the redirect
  // inside the manifest scope so the standalone app stays standalone, and does
  // not hard-code the deployment path.
  const redirectTo = new URL('.', window.location.href).href;

  return supabase.auth.signInWithOAuth({
    provider: 'google',
    options: { redirectTo },
  });
}

/**
 * Ends the session and forgets the cached ledger on this device.
 *
 * The server copy is append-only and untouched — signing back in replays it to
 * the same balance. Clearing locally matters because the next person to sign in
 * on this device must not inherit the previous account's minutes.
 */
export async function signOut(store) {
  await supabase.auth.signOut();
  store.clearLocalCache();
}

/**
 * Pushes anything unsent, then pulls the account's recent history.
 *
 * Push happens first on purpose: pulling first and merging would make this
 * device's own unsent work look like it had already been accounted for.
 */
export async function syncNow(store) {
  const user = await currentUser();
  if (!user) return { ok: false, reason: 'signed-out' };

  const pending = store.pendingEvents();
  if (pending.length > 0) {
    const { error } = await supabase
      .from('bank_events')
      .upsert(pending.map((event) => toRow(event, user.id, deviceLabel())), {
        onConflict: 'id',
        ignoreDuplicates: true,
      });

    // A failed push is not fatal. The events stay queued and the next sync
    // retries them; the balance on this device is already correct locally.
    if (error) return { ok: false, reason: 'push-failed', error };
    store.markSynced(pending.map((event) => event.id));
  }

  const since = new Date(Date.now() - RELEVANT_WINDOW_MS).toISOString();
  const { data, error } = await supabase
    .from('bank_events')
    .select('id,kind,seconds,occurred_at')
    .gte('occurred_at', since);

  if (error) return { ok: false, reason: 'pull-failed', error };

  store.ingestRemote((data ?? []).map(fromRow));
  return { ok: true, events: data?.length ?? 0 };
}

// ---------------------------------------------------------------- settings

export async function pullSettings(store) {
  const user = await currentUser();
  if (!user) return null;

  const { data, error } = await supabase
    .from('profiles')
    .select('strictness,bank_cap_minutes,warn_before_lock')
    .eq('user_id', user.id)
    .maybeSingle();

  if (error || !data) return null;

  return store.saveSettings({
    strictness: data.strictness,
    capMinutes: data.bank_cap_minutes,
    warnBeforeLock: data.warn_before_lock,
  });
}

export async function pushSettings(store) {
  const user = await currentUser();
  if (!user) return;

  const settings = store.settings();
  await supabase.from('profiles').upsert(
    {
      user_id: user.id,
      strictness: settings.strictness,
      bank_cap_minutes: settings.capMinutes,
      warn_before_lock: settings.warnBeforeLock,
      updated_at: new Date().toISOString(),
    },
    { onConflict: 'user_id' },
  );
}
