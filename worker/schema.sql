-- ============================================================================
--  Voice AI Assistant — AI processing layer (Phase A)
--  Run this ONCE in Supabase → SQL Editor → New query → Run.
--  Safe to re-run: everything uses IF NOT EXISTS / idempotent guards.
-- ============================================================================

-- 1) Mark which captured entries the worker has already turned into tasks.
alter table public.entries
  add column if not exists processed_at timestamptz;

create index if not exists entries_unprocessed_idx
  on public.entries (processed_at)
  where processed_at is null;

-- 2) Tasks derived from entries by the AI worker.
create table if not exists public.tasks (
  id               uuid primary key default gen_random_uuid(),
  user_id          uuid,
  source_entry_id  uuid references public.entries(id) on delete set null,
  title            text not null,
  details          text default '',
  kind             text not null default 'smalltask'
                     check (kind in ('deadline','smalltask','recurring','idea')),
  status           text not null default 'open'
                     check (status in ('open','done','snoozed','expired','cancelled')),
  recipient        text,
  lang             text default 'ru',
  deadline         timestamptz,
  next_reminder_at timestamptz,
  reminder_rule    jsonb default '{}'::jsonb,   -- { times:[iso...], snoozes_left:int }
  tg_message_id    bigint,                       -- last reminder message (for edits)
  created_at       timestamptz default now(),
  updated_at       timestamptz default now()
);

create index if not exists tasks_due_idx
  on public.tasks (next_reminder_at)
  where status = 'open';

-- RLS: the app (user key) may read/update its own tasks; the worker uses the
-- service key, which bypasses RLS entirely.
alter table public.tasks enable row level security;

drop policy if exists tasks_select_own on public.tasks;
create policy tasks_select_own on public.tasks
  for select using (auth.uid() = user_id);

drop policy if exists tasks_update_own on public.tasks;
create policy tasks_update_own on public.tasks
  for update using (auth.uid() = user_id);

-- 3) Small key/value store for worker state (telegram chat_id, update offset…).
create table if not exists public.app_settings (
  key   text primary key,
  value jsonb not null default '{}'::jsonb
);
-- Worker-only: no RLS policies → only the service key can touch it.
alter table public.app_settings enable row level security;
