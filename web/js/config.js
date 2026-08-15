/**
 * Supabase project this build talks to.
 *
 * The anon key is meant to be public and is safe in a public repository. It
 * authenticates nothing on its own: every table has row-level security whose
 * policies only ever match `auth.uid()`, so an unauthenticated request reads
 * nothing and is refused on write. The service_role key is the dangerous one
 * and must never appear in this project.
 */
export const SUPABASE_URL = 'https://iqqzbsvvbuapytfrlymq.supabase.co';

export const SUPABASE_ANON_KEY =
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImlxcXpic3Z2YnVhcHl0ZnJseW1xIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODY3NDc5NTYsImV4cCI6MjEwMjMyMzk1Nn0.zAT6o6TviBmE4EN9VSsZNUt8v-PC0nCCc5wdXgzaX1c';
