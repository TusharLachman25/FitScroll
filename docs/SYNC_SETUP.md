# Setting up synced accounts

One bank across every device, backed by Supabase. This is the part that has to be done in a browser — creating projects and OAuth clients cannot be scripted.

Budget about ten minutes. At the end you hand over two values and the code gets wired up.

---

## 1. Create the Supabase project

1. Sign up at [supabase.com](https://supabase.com) (free tier is plenty — this stores a few dozen rows per user).
2. **New project**. Any name; pick the region closest to you.
3. Save the database password somewhere. You will not need it for the app, but you will need it if you ever run migrations from the CLI.
4. Once it finishes provisioning, go to **Project Settings → API** and copy:
   - **Project URL** — looks like `https://abcdefghijkl.supabase.co`
   - **anon public** key — a long JWT starting `eyJ...`

> The anon key is *designed* to be public and will be committed to this repo. It grants nothing on its own: every table has row-level security, and the policies only ever match `auth.uid()`. It is safe in a public repository. The **service_role** key is the dangerous one — never put that anywhere near this project.

---

## 2. Create the tables

The migration lives at [`supabase/migrations/20260815000000_bank_ledger.sql`](../supabase/migrations/20260815000000_bank_ledger.sql).

**Easiest:** open **SQL Editor** in the Supabase dashboard, paste the whole file in, and hit run.

**Or via the CLI** (already installed on this machine):

```bash
supabase login
supabase link --project-ref <your-project-ref>
supabase db push
```

To check it worked, open **Table Editor** — you should see `bank_events` and `profiles`, both showing "RLS enabled".

---

## 3. Create the Google OAuth client

1. Go to the [Google Cloud Console](https://console.cloud.google.com/apis/credentials).
2. Create a project if you have none.
3. Configure the **OAuth consent screen** if prompted — External, app name *FitScroll*, your email. It can stay in "Testing"; just add your own Google account under **Test users**.
4. **Create Credentials → OAuth client ID → Web application.**
5. Under **Authorised redirect URIs**, add exactly this, with your project ref:
   ```
   https://<your-project-ref>.supabase.co/auth/v1/callback
   ```
6. Copy the **Client ID** and **Client Secret**.

> One web client covers both platforms. Android signs in through a browser tab rather than the native Google account picker, which avoids registering the APK's signing fingerprint — worth it here, because a sideloaded build is signed with a debug key that would have to be re-registered on any machine that rebuilds it.

---

## 4. Point Supabase at Google

1. Supabase dashboard → **Authentication → Sign In / Providers → Google**.
2. Enable it, paste the Client ID and Client Secret, save.
3. Go to **Authentication → URL Configuration** and set:

   **Site URL**
   ```
   https://tusharlachman25.github.io/FitScroll/
   ```

   **Redirect URLs** — add all three:
   ```
   https://tusharlachman25.github.io/FitScroll/
   https://tusharlachman25.github.io/FitScroll/**
   fitscroll://auth-callback
   ```

The last one is the Android app coming back from the browser after you approve.

---

## 5. Hand over two values

Send back:

- Project URL
- anon public key

Those get committed to `web/js/config.js` and to the Android `BuildConfig`. Nothing else is needed.

---

## What the ledger actually stores

Two tables, and nothing that identifies you beyond what Google returns for sign-in.

`bank_events` is an **append-only log**: each row is *"earned 900 seconds at 14:03"* or *"spent 60 seconds at 14:19"*. Your balance is not stored anywhere — it is recomputed by replaying the log. That design does three things:

- **Offline devices merge cleanly.** The Android drain charges a second per second and cannot wait on the network. A stored balance would mean whichever device wrote last silently erased the other's work; a log just merges.
- **Signing out cannot destroy your bank.** There is deliberately no delete policy on the table, so a client physically cannot remove events. Logging out clears the local cache and the session; the ledger stays on the server, and signing back in replays it to the exact same balance.
- **Retries are safe.** Event ids are generated on the device, so re-uploading after a dropped connection collides on the primary key instead of banking the same set twice.

`profiles` holds your strictness level, bank cap, and warning preference, so those follow you between devices too.

Events older than seven days are prunable and affect nothing — every minute expires 24 hours after it was earned, so anything beyond that window cannot change a balance.
