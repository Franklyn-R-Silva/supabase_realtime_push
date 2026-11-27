-- supabase_realtime_push: the database side.
--
-- Run this whole file in the SQL editor or as a migration. It is re-runnable:
-- running it again (for example after upgrading the package) changes nothing
-- that is already right.
--
-- Why a topic + secret per device, and not the user's session: the Android
-- service runs with the app closed. If it used the app's refresh token there
-- would be two owners of one token, and GoTrue's refresh-token rotation revokes
-- the whole family when a used token comes back: the user would be signed out
-- for no visible reason. So each device makes its own pair. The topic names a
-- public Realtime channel that only ever carries an empty "changed" signal;
-- the content comes from realtime_push_snapshot over HTTPS and needs the
-- secret, of which this database keeps only the sha256.
--
-- Why a snapshot, not events: sockets drop. If notifications rode on events,
-- the one that fell in the gap would never be shown. Every signal and every
-- reconnect just says "go look", and the device makes its shade match the
-- snapshot. Nothing is lost and there is no queue to grow.
--
-- How to use:
--   - The app, signed in: realtime_push_register(topic, secret) after start,
--     realtime_push_unregister(topic) on sign-out.
--   - Your backend (service_role) notifies with the ready-made inbox:
--       select realtime_push_send(user_id, 'chat:42', 'Ana', 'See you at 3',
--                                 '{"route": "/chat/42"}');
--     Same key = same notification, replaced in place.
--   - The app clears it when the user has seen it:
--       select realtime_push_mark_read('chat:42');
--   - Already have your own unread data? Replace realtime_push_snapshot_for
--     with `create or replace` (it gets the user id, and must return the JSON
--     in docs/contract.md section 3), and call
--     realtime_push_signal(array[user_id]) from your own triggers. Running
--     this file again keeps your version; drop it to get the default back.
--
-- Contract: docs/contract.md, sections 1, 3 and 7.

-- =============================================================================
-- Devices
-- =============================================================================

create table if not exists public.realtime_push_devices (
  -- 32 random bytes, base64url without padding.
  topic       text primary key check (topic ~ '^[A-Za-z0-9_-]{43}$'),
  user_id     uuid not null references auth.users (id) on delete cascade,
  secret_hash bytea not null,
  platform    text not null,
  created_at  timestamptz not null default now(),
  seen_at     timestamptz not null default now()
);

create index if not exists realtime_push_devices_user_id
  on public.realtime_push_devices (user_id);

-- No policies on purpose: only the functions below touch this table.
alter table public.realtime_push_devices enable row level security;
revoke all on table public.realtime_push_devices from public, anon, authenticated;

-- A topic already owned by another user is refused, not taken over: only
-- someone who learned another user's topic could ask for that, and the app
-- never reuses a pair across accounts.
create or replace function public.realtime_push_register(
  p_topic    text,
  p_secret   text,
  p_platform text default 'android'
)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user uuid := auth.uid();
begin
  if v_user is null then
    raise exception 'realtime_push_register: not signed in' using errcode = '42501';
  end if;
  if p_topic is null or p_topic !~ '^[A-Za-z0-9_-]{43}$' then
    raise exception 'realtime_push_register: invalid topic' using errcode = '22023';
  end if;
  if p_secret is null or p_secret !~ '^[A-Za-z0-9_-]{43}$' then
    raise exception 'realtime_push_register: invalid secret' using errcode = '22023';
  end if;
  if p_platform is distinct from 'android' then
    raise exception 'realtime_push_register: unsupported platform' using errcode = '22023';
  end if;

  insert into public.realtime_push_devices as d (topic, user_id, secret_hash, platform)
  values (p_topic, v_user, sha256(convert_to(p_secret, 'UTF8')), p_platform)
  on conflict (topic) do update
     set secret_hash = excluded.secret_hash,
         platform    = excluded.platform,
         seen_at     = now()
   where d.user_id = excluded.user_id;

  if not found then
    raise exception 'realtime_push_register: topic belongs to another user' using errcode = '42501';
  end if;

  -- Reinstalling the app makes a new pair and orphans the old one. Devices of
  -- this user that have not fetched a snapshot for 60 days go away here.
  delete from public.realtime_push_devices d
   where d.user_id = v_user
     and d.topic <> p_topic
     and d.seen_at < now() - interval '60 days';
end;
$$;

create or replace function public.realtime_push_unregister(p_topic text)
returns void
language sql
volatile
security definer
set search_path = ''
as $$
  delete from public.realtime_push_devices
   where topic = p_topic
     and user_id = auth.uid();
$$;

-- =============================================================================
-- The ready-made inbox
-- =============================================================================

create table if not exists public.realtime_push_notifications (
  id         bigint generated always as identity primary key,
  user_id    uuid not null references auth.users (id) on delete cascade,
  key        text not null check (key <> ''),
  title      text not null,
  body       text not null default '',
  data       jsonb not null default '{}' check (jsonb_typeof(data) = 'object'),
  silent     boolean not null default false,
  created_at timestamptz not null default now(),
  read_at    timestamptz
);

-- Serves both the snapshot and realtime_push_mark_read.
create index if not exists realtime_push_notifications_unread
  on public.realtime_push_notifications (user_id, key, id desc)
  where read_at is null;

-- Users read their own rows; every write goes through the functions below.
alter table public.realtime_push_notifications enable row level security;
revoke all on table public.realtime_push_notifications from public, anon, authenticated;
grant select on table public.realtime_push_notifications to authenticated;

drop policy if exists realtime_push_notifications_own on public.realtime_push_notifications;
create policy realtime_push_notifications_own on public.realtime_push_notifications
  for select to authenticated
  using (user_id = (select auth.uid()));

create or replace function public.realtime_push_send(
  p_user   uuid,
  p_key    text,
  p_title  text,
  p_body   text default '',
  p_data   jsonb default '{}',
  p_silent boolean default false
)
returns bigint
language sql
volatile
security definer
set search_path = ''
as $$
  insert into public.realtime_push_notifications (user_id, key, title, body, data, silent)
  values (p_user, p_key, p_title, p_body, p_data, p_silent)
  returning id;
$$;

create or replace function public.realtime_push_mark_read(p_key text)
returns void
language sql
volatile
security definer
set search_path = ''
as $$
  update public.realtime_push_notifications
     set read_at = now()
   where user_id = auth.uid()
     and key = p_key
     and read_at is null;
$$;

-- =============================================================================
-- The snapshot
-- =============================================================================

-- The default reads the inbox: one notification per key (its newest unread
-- row), newest first, at most 20; badge = every unread row. Created only when
-- missing, so a version you wrote survives running this file again.
do $do$
begin
  if to_regprocedure('public.realtime_push_snapshot_for(uuid)') is null then
    create function public.realtime_push_snapshot_for(p_user uuid)
    returns jsonb
    language sql
    stable
    set search_path = ''
    as $fn$
      with unread as (
        select n.id, n.key, n.title, n.body, n.data, n.silent
          from public.realtime_push_notifications n
         where n.user_id = p_user
           and n.read_at is null
      ),
      newest as (
        select distinct on (u.key) u.*
          from unread u
         order by u.key, u.id desc
      ),
      shown as (
        select * from newest order by id desc limit 20
      )
      select jsonb_build_object(
        'badge', (select count(*) from unread),
        'notifications', coalesce(
          (select jsonb_agg(jsonb_build_object(
                    'id', s.key,
                    'revision', s.id,
                    'title', s.title,
                    'body', s.body,
                    'silent', s.silent,
                    'data', s.data
                  ) order by s.id desc)
             from shown s),
          '[]'::jsonb
        )
      );
    $fn$;
  end if;
end
$do$;

-- Called by the device without a session. A wrong pair returns null without
-- saying whether the topic or the secret was wrong.
create or replace function public.realtime_push_snapshot(p_topic text, p_secret text)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user uuid;
  v_seen timestamptz;
begin
  select d.user_id, d.seen_at
    into v_user, v_seen
    from public.realtime_push_devices d
   where d.topic = p_topic
     and d.secret_hash = sha256(convert_to(p_secret, 'UTF8'));

  if v_user is null then
    return null;
  end if;

  -- Every signal fetches a snapshot; stamping once a minute is enough.
  if v_seen < now() - interval '1 minute' then
    update public.realtime_push_devices set seen_at = now() where topic = p_topic;
  end if;

  return public.realtime_push_snapshot_for(v_user);
end;
$$;

-- =============================================================================
-- The "changed" signal
-- =============================================================================

-- Never raises: a push must not fail the write that caused it.
create or replace function public.realtime_push_signal(p_users uuid[])
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  perform realtime.send('{}'::jsonb, 'changed', 'push:' || d.topic, false)
     from public.realtime_push_devices d
    where d.user_id = any (p_users);
exception when others then
  raise warning 'realtime_push_signal: % (%)', sqlerrm, sqlstate;
end;
$$;

-- One signal per statement, not per row: marking ten rows read sends one.
-- Any insert, update or delete can change the snapshot, so all three signal.
create or replace function public.realtime_push_notifications_changed()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform public.realtime_push_signal(array(select distinct c.user_id from changed c));
  return null;
end;
$$;

drop trigger if exists realtime_push_notifications_inserted on public.realtime_push_notifications;
create trigger realtime_push_notifications_inserted
  after insert on public.realtime_push_notifications
  referencing new table as changed
  for each statement execute function public.realtime_push_notifications_changed();

drop trigger if exists realtime_push_notifications_updated on public.realtime_push_notifications;
create trigger realtime_push_notifications_updated
  after update on public.realtime_push_notifications
  referencing new table as changed
  for each statement execute function public.realtime_push_notifications_changed();

drop trigger if exists realtime_push_notifications_deleted on public.realtime_push_notifications;
create trigger realtime_push_notifications_deleted
  after delete on public.realtime_push_notifications
  referencing old table as changed
  for each statement execute function public.realtime_push_notifications_changed();

-- =============================================================================
-- Who may call what
-- =============================================================================

-- Supabase grants execute on new functions to anon and authenticated by
-- default, so revoking from public alone is not enough.
revoke all on function public.realtime_push_register(text, text, text) from public, anon, authenticated;
revoke all on function public.realtime_push_unregister(text) from public, anon, authenticated;
revoke all on function public.realtime_push_send(uuid, text, text, text, jsonb, boolean) from public, anon, authenticated;
revoke all on function public.realtime_push_mark_read(text) from public, anon, authenticated;
revoke all on function public.realtime_push_snapshot_for(uuid) from public, anon, authenticated;
revoke all on function public.realtime_push_snapshot(text, text) from public, anon, authenticated;
revoke all on function public.realtime_push_signal(uuid[]) from public, anon, authenticated;
revoke all on function public.realtime_push_notifications_changed() from public, anon, authenticated;

grant execute on function public.realtime_push_register(text, text, text) to authenticated;
grant execute on function public.realtime_push_unregister(text) to authenticated;
grant execute on function public.realtime_push_mark_read(text) to authenticated;
grant execute on function public.realtime_push_snapshot(text, text) to anon, authenticated;
grant execute on function public.realtime_push_send(uuid, text, text, text, jsonb, boolean) to service_role;
grant execute on function public.realtime_push_signal(uuid[]) to service_role;
