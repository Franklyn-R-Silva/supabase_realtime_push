-- Tests for supabase/realtime_push.sql on a plain Postgres. Run it with
-- tool/test_sql.sh. The first part stands in for what a Supabase project
-- already has; it is not part of what users install.

set client_min_messages = warning;

-- =============================================================================
-- Supabase stand-ins
-- =============================================================================

create role anon nologin;
create role authenticated nologin;
create role service_role nologin bypassrls;

create schema auth;
grant usage on schema auth to anon, authenticated, service_role;
create table auth.users (id uuid primary key);
create function auth.uid() returns uuid language sql stable as $$
  select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;

-- Records every call; fails while test.realtime_down is 'on'.
create schema realtime;
create table realtime.sent (
  id      bigserial primary key,
  payload jsonb,
  event   text,
  topic   text,
  private boolean
);
create function realtime.send(payload jsonb, event text, topic text, private boolean default true)
returns void language plpgsql as $$
begin
  if current_setting('test.realtime_down', true) = 'on' then
    raise exception 'realtime is down';
  end if;
  insert into realtime.sent (payload, event, topic, private) values (payload, event, topic, private);
end
$$;

-- What a Supabase project grants on new objects in public, so the script's
-- revokes are really exercised.
alter default privileges in schema public grant all on tables to anon, authenticated, service_role;
alter default privileges in schema public grant all on functions to anon, authenticated, service_role;
alter default privileges in schema public grant all on sequences to anon, authenticated, service_role;

-- =============================================================================
-- Helpers
-- =============================================================================

create schema test;
grant usage on schema test to public;

-- Acts as p_role (signed in as p_user, if given) until the transaction ends.
create function test.act_as(p_role text, p_user uuid default null) returns void language plpgsql as $$
begin
  perform set_config('role', p_role, true);
  perform set_config('request.jwt.claim.sub', coalesce(p_user::text, ''), true);
end
$$;

create function test.expect_error(p_sql text, p_state text) returns void language plpgsql as $$
declare
  v_state text;
begin
  begin
    execute p_sql;
  exception when others then
    v_state := sqlstate;
  end;
  if v_state is distinct from p_state then
    raise exception 'expected % but got % from: %', p_state, coalesce(v_state, 'no error'), p_sql;
  end if;
end
$$;

-- A valid 43-character topic or secret that is still readable in the tests.
create function test.pad43(p text) returns text language sql immutable as $$
  select rpad(p, 43, '_')
$$;

insert into auth.users (id) values (md5('alice')::uuid), (md5('bob')::uuid), (md5('carol')::uuid);

-- =============================================================================
-- The script, twice
-- =============================================================================

\echo '== install the script, then install it again'
\ir ../realtime_push.sql
\ir ../realtime_push.sql

do $$
begin
  assert (select count(*) from pg_proc
           where pronamespace = 'public'::regnamespace and proname like 'realtime\_push\_%') = 8,
         'eight functions, no duplicates';
  assert (select count(*) from pg_trigger
           where tgrelid = 'public.realtime_push_notifications'::regclass and not tgisinternal) = 3,
         'three triggers';
  assert (select count(*) from pg_policies where tablename = 'realtime_push_notifications') = 1,
         'one policy on notifications';
  assert (select count(*) from pg_policies where tablename = 'realtime_push_devices') = 0,
         'no policy on devices';
  assert (select relrowsecurity from pg_class where oid = 'public.realtime_push_devices'::regclass),
         'RLS on devices';
  assert (select relrowsecurity from pg_class where oid = 'public.realtime_push_notifications'::regclass),
         'RLS on notifications';
  assert (select count(*) from pg_indexes where tablename = 'realtime_push_devices') = 2,
         'two indexes on devices';
  assert (select count(*) from pg_indexes where tablename = 'realtime_push_notifications') = 2,
         'two indexes on notifications';
  assert not exists (select 1 from pg_proc
                      where pronamespace = 'public'::regnamespace and proname like 'realtime\_push\_%'
                        and prosecdef and not ('search_path=""' = any (coalesce(proconfig, '{}')))),
         'every security definer function pins an empty search_path';
end
$$;

-- =============================================================================
-- Devices
-- =============================================================================

\echo '== register and re-register'
do $$
begin
  perform test.act_as('authenticated', md5('alice')::uuid);
  perform public.realtime_push_register(test.pad43('alice-phone'), test.pad43('alice-secret-1'));
  perform public.realtime_push_register(test.pad43('alice-phone'), test.pad43('alice-secret-2'));
  perform public.realtime_push_register(test.pad43('alice-tablet'), test.pad43('alice-secret-3'), 'android');
  perform test.act_as('authenticated', md5('bob')::uuid);
  perform public.realtime_push_register(test.pad43('bob-phone'), test.pad43('bob-secret'));
  perform test.act_as('postgres');

  assert (select count(*) from public.realtime_push_devices) = 3, 'three devices';
  assert (select count(*) from public.realtime_push_devices where user_id = md5('alice')::uuid) = 2,
         'alice has two devices';
  assert (select secret_hash from public.realtime_push_devices where topic = test.pad43('alice-phone'))
         = sha256(convert_to(test.pad43('alice-secret-2'), 'UTF8')),
         're-registering replaces the secret hash';
  assert (select platform from public.realtime_push_devices where topic = test.pad43('bob-phone')) = 'android',
         'platform defaults to android';
end
$$;

\echo '== a topic owned by another user is refused'
do $$
begin
  perform test.act_as('authenticated', md5('bob')::uuid);
  perform test.expect_error(
    $q$select public.realtime_push_register(test.pad43('alice-phone'), test.pad43('bob-secret'))$q$, '42501');
  perform test.act_as('postgres');

  assert (select user_id from public.realtime_push_devices where topic = test.pad43('alice-phone'))
         = md5('alice')::uuid, 'alice keeps her topic';
  assert (select secret_hash from public.realtime_push_devices where topic = test.pad43('alice-phone'))
         = sha256(convert_to(test.pad43('alice-secret-2'), 'UTF8')), 'and her secret';
end
$$;

\echo '== invalid input is refused'
do $$
begin
  perform test.act_as('authenticated', md5('alice')::uuid);
  perform test.expect_error($q$select public.realtime_push_register(repeat('a', 42), test.pad43('s'))$q$, '22023');
  perform test.expect_error($q$select public.realtime_push_register(repeat('a', 44), test.pad43('s'))$q$, '22023');
  perform test.expect_error($q$select public.realtime_push_register(repeat('a', 42) || '=', test.pad43('s'))$q$, '22023');
  perform test.expect_error($q$select public.realtime_push_register(repeat('a', 42) || '+', test.pad43('s'))$q$, '22023');
  perform test.expect_error($q$select public.realtime_push_register(null, test.pad43('s'))$q$, '22023');
  perform test.expect_error($q$select public.realtime_push_register(test.pad43('alice-x'), 'short')$q$, '22023');
  perform test.expect_error($q$select public.realtime_push_register(test.pad43('alice-x'), null)$q$, '22023');
  perform test.expect_error(
    $q$select public.realtime_push_register(test.pad43('alice-x'), test.pad43('s'), 'ios')$q$, '22023');
  perform test.act_as('authenticated');
  perform test.expect_error($q$select public.realtime_push_register(test.pad43('nobody'), test.pad43('s'))$q$, '42501');
  perform test.act_as('postgres');

  assert (select count(*) from public.realtime_push_devices) = 3, 'nothing new was registered';
end
$$;

-- =============================================================================
-- Permissions
-- =============================================================================

\echo '== permissions'
do $$
declare
  r    record;
  priv text;
begin
  -- Read from the catalog: some refused calls give the same 42501 either way.
  for r in
    select *
      from (values
        ('public.realtime_push_register(text, text, text)', array['authenticated']),
        ('public.realtime_push_unregister(text)', array['authenticated']),
        ('public.realtime_push_mark_read(text)', array['authenticated']),
        ('public.realtime_push_snapshot(text, text)', array['anon', 'authenticated']),
        ('public.realtime_push_send(uuid, text, text, text, jsonb, boolean)', array[]::text[]),
        ('public.realtime_push_signal(uuid[])', array[]::text[]),
        ('public.realtime_push_snapshot_for(uuid)', array[]::text[]),
        ('public.realtime_push_notifications_changed()', array[]::text[])
      ) as t (fn, allowed),
      unnest(array['anon', 'authenticated']) as who
  loop
    assert has_function_privilege(r.who, r.fn, 'execute') = (r.who = any (r.allowed)),
           format('%s execute on %s', r.who, r.fn);
  end loop;
  assert has_function_privilege('service_role', 'public.realtime_push_send(uuid, text, text, text, jsonb, boolean)',
                                'execute'), 'service_role execute on send';
  assert has_function_privilege('service_role', 'public.realtime_push_signal(uuid[])', 'execute'),
         'service_role execute on signal';

  foreach priv in array array['select', 'insert', 'update', 'delete', 'truncate', 'references', 'trigger'] loop
    assert not has_table_privilege('anon', 'public.realtime_push_devices', priv), 'anon ' || priv || ' on devices';
    assert not has_table_privilege('authenticated', 'public.realtime_push_devices', priv),
           'authenticated ' || priv || ' on devices';
    assert not has_table_privilege('anon', 'public.realtime_push_notifications', priv),
           'anon ' || priv || ' on notifications';
    assert has_table_privilege('authenticated', 'public.realtime_push_notifications', priv) = (priv = 'select'),
           'authenticated ' || priv || ' on notifications';
  end loop;

  perform test.act_as('anon');
  perform test.expect_error($q$select public.realtime_push_register(test.pad43('anon'), test.pad43('s'))$q$, '42501');
  perform test.expect_error($q$select public.realtime_push_unregister(test.pad43('alice-phone'))$q$, '42501');
  perform test.expect_error($q$select public.realtime_push_mark_read('k')$q$, '42501');
  perform test.expect_error($q$select public.realtime_push_send(md5('alice')::uuid, 'k', 't')$q$, '42501');
  perform test.expect_error($q$select public.realtime_push_signal(array[md5('alice')::uuid])$q$, '42501');
  perform test.expect_error($q$select public.realtime_push_snapshot_for(md5('alice')::uuid)$q$, '42501');
  perform test.expect_error($q$select * from public.realtime_push_devices$q$, '42501');
  perform test.expect_error($q$select * from public.realtime_push_notifications$q$, '42501');
  perform public.realtime_push_snapshot(test.pad43('x'), test.pad43('y'));

  perform test.act_as('authenticated', md5('alice')::uuid);
  perform test.expect_error($q$select public.realtime_push_send(md5('alice')::uuid, 'k', 't')$q$, '42501');
  perform test.expect_error($q$select public.realtime_push_signal(array[md5('alice')::uuid])$q$, '42501');
  perform test.expect_error($q$select public.realtime_push_snapshot_for(md5('alice')::uuid)$q$, '42501');
  perform test.expect_error($q$select * from public.realtime_push_devices$q$, '42501');
  perform test.expect_error(
    $q$insert into public.realtime_push_notifications (user_id, key, title) values (md5('alice')::uuid, 'k', 't')$q$,
    '42501');
  perform test.expect_error($q$update public.realtime_push_notifications set read_at = now()$q$, '42501');
  perform test.expect_error($q$delete from public.realtime_push_notifications$q$, '42501');
  perform public.realtime_push_snapshot(test.pad43('x'), test.pad43('y'));

  perform test.act_as('service_role');
  perform public.realtime_push_signal(null);
  perform public.realtime_push_signal('{}');
  perform test.act_as('postgres');
end
$$;

-- =============================================================================
-- Snapshot
-- =============================================================================

\echo '== the snapshot needs the right pair, and says null otherwise'
do $$
declare
  v jsonb;
begin
  perform test.act_as('anon');
  assert public.realtime_push_snapshot(test.pad43('alice-phone'), test.pad43('alice-secret-1')) is null,
         'replaced secret';
  assert public.realtime_push_snapshot(test.pad43('alice-phone'), test.pad43('bob-secret')) is null,
         'another device''s secret';
  assert public.realtime_push_snapshot(test.pad43('nobody'), test.pad43('alice-secret-2')) is null,
         'unknown topic';
  assert public.realtime_push_snapshot(test.pad43('alice-phone'), null) is null, 'null secret';
  assert public.realtime_push_snapshot(null, null) is null, 'null pair';
  v := public.realtime_push_snapshot(test.pad43('alice-phone'), test.pad43('alice-secret-2'));
  perform test.act_as('postgres');

  assert v = '{"badge": 0, "notifications": []}'::jsonb, format('empty snapshot, got %s', v);
end
$$;

\echo '== the snapshot stamps seen_at, at most once a minute'
do $$
begin
  update public.realtime_push_devices set seen_at = now() - interval '2 minutes'
   where topic = test.pad43('alice-phone');
  perform test.act_as('anon');
  perform public.realtime_push_snapshot(test.pad43('alice-phone'), test.pad43('alice-secret-2'));
  perform test.act_as('postgres');
  assert (select seen_at from public.realtime_push_devices where topic = test.pad43('alice-phone')) = now(),
         'a stale seen_at is stamped';

  update public.realtime_push_devices set seen_at = now() - interval '30 seconds'
   where topic = test.pad43('alice-phone');
  perform test.act_as('anon');
  perform public.realtime_push_snapshot(test.pad43('alice-phone'), test.pad43('alice-secret-2'));
  perform test.act_as('postgres');
  assert (select seen_at from public.realtime_push_devices where topic = test.pad43('alice-phone'))
         = now() - interval '30 seconds', 'a recent seen_at is left alone';
end
$$;

\echo '== send, the exact snapshot shape and the signal'
do $$
declare
  v_id bigint;
  v    jsonb;
begin
  truncate realtime.sent;
  perform test.act_as('service_role');
  v_id := public.realtime_push_send(md5('alice')::uuid, 'chat:42', 'Ana', 'See you at 3', '{"route": "/chat/42"}');
  perform test.act_as('anon');
  v := public.realtime_push_snapshot(test.pad43('alice-phone'), test.pad43('alice-secret-2'));
  perform test.act_as('postgres');

  assert v = jsonb_build_object(
           'badge', 1,
           'notifications', jsonb_build_array(jsonb_build_object(
             'id', 'chat:42', 'revision', v_id, 'title', 'Ana', 'body', 'See you at 3',
             'silent', false, 'data', '{"route": "/chat/42"}'::jsonb))),
         format('snapshot shape, got %s', v);

  assert (select count(*) from realtime.sent) = 2, 'one signal per device of alice';
  assert (select array_agg(topic order by topic) from realtime.sent)
         = array['push:' || test.pad43('alice-phone'), 'push:' || test.pad43('alice-tablet')],
         'on her topics';
  assert (select bool_and(payload = '{}'::jsonb and event = 'changed' and not private) from realtime.sent),
         'an empty, public "changed" broadcast';

  truncate realtime.sent;
  perform test.act_as('service_role');
  v_id := public.realtime_push_send(md5('bob')::uuid, 'hello', 'Hi');
  perform test.act_as('anon');
  v := public.realtime_push_snapshot(test.pad43('bob-phone'), test.pad43('bob-secret'));
  perform test.act_as('postgres');

  assert v = jsonb_build_object(
           'badge', 1,
           'notifications', jsonb_build_array(jsonb_build_object(
             'id', 'hello', 'revision', v_id, 'title', 'Hi', 'body', '', 'silent', false, 'data', '{}'::jsonb))),
         format('defaults for body, data and silent, got %s', v);
  assert (select array_agg(topic) from realtime.sent) = array['push:' || test.pad43('bob-phone')],
         'only bob''s device is signalled';

  perform test.act_as('service_role');
  perform test.expect_error($q$select public.realtime_push_send(md5('alice')::uuid, 'k', 't', '', '[]')$q$, '23514');
  perform test.expect_error($q$select public.realtime_push_send(md5('alice')::uuid, '', 't')$q$, '23514');
  perform test.expect_error($q$select public.realtime_push_send(md5('alice')::uuid, 'k', null)$q$, '23502');
  perform test.expect_error($q$select public.realtime_push_send(md5('nobody')::uuid, 'k', 't')$q$, '23503');
  perform test.act_as('postgres');
end
$$;

\echo '== one notification per key: the newest unread row, newest key first'
do $$
declare
  a1 bigint;
  a2 bigint;
  a3 bigint;
  b1 bigint;
  b2 bigint;
  v  jsonb;
begin
  delete from public.realtime_push_notifications;
  perform test.act_as('service_role');
  a1 := public.realtime_push_send(md5('alice')::uuid, 'chat:42', 'Ana', 'one');
  b1 := public.realtime_push_send(md5('alice')::uuid, 'chat:7', 'Bruno', 'hey', '{}', true);
  a2 := public.realtime_push_send(md5('alice')::uuid, 'chat:42', 'Ana', 'two');
  a3 := public.realtime_push_send(md5('alice')::uuid, 'chat:42', 'Ana', 'three');
  perform test.act_as('postgres');

  v := public.realtime_push_snapshot_for(md5('alice')::uuid);
  assert v = jsonb_build_object(
           'badge', 4,
           'notifications', jsonb_build_array(
             jsonb_build_object('id', 'chat:42', 'revision', a3, 'title', 'Ana', 'body', 'three',
                                'silent', false, 'data', '{}'::jsonb),
             jsonb_build_object('id', 'chat:7', 'revision', b1, 'title', 'Bruno', 'body', 'hey',
                                'silent', true, 'data', '{}'::jsonb))),
         format('grouped by key, got %s', v);

  update public.realtime_push_notifications set read_at = now() where id = a3;
  v := public.realtime_push_snapshot_for(md5('alice')::uuid);
  assert (v ->> 'badge')::int = 3, format('badge after one read, got %s', v);
  assert (v #>> '{notifications,0,revision}')::bigint = a2 and v #>> '{notifications,0,body}' = 'two',
         format('the newest unread row of the key takes over, got %s', v);

  perform test.act_as('service_role');
  b2 := public.realtime_push_send(md5('alice')::uuid, 'chat:7', 'Bruno', 'again');
  perform test.act_as('postgres');
  v := public.realtime_push_snapshot_for(md5('alice')::uuid);
  assert v #>> '{notifications,0,id}' = 'chat:7' and (v #>> '{notifications,0,revision}')::bigint = b2,
         format('a new row moves its key to the top, got %s', v);
  assert v #>> '{notifications,1,id}' = 'chat:42', format('and the other key follows, got %s', v);
end
$$;

\echo '== at most 20 keys; the badge counts every unread row'
do $$
declare
  v jsonb;
begin
  perform test.act_as('service_role');
  for i in 1..25 loop
    perform public.realtime_push_send(md5('carol')::uuid, 'k' || i, 'title ' || i);
  end loop;
  perform public.realtime_push_send(md5('carol')::uuid, 'k25', 'title 25 again');
  perform test.act_as('postgres');

  v := public.realtime_push_snapshot_for(md5('carol')::uuid);
  assert (v ->> 'badge')::int = 26, format('badge, got %s', v ->> 'badge');
  assert jsonb_array_length(v -> 'notifications') = 20, 'twenty notifications';
  assert v #>> '{notifications,0,id}' = 'k25' and v #>> '{notifications,0,title}' = 'title 25 again',
         'the newest key first';
  assert v #>> '{notifications,19,id}' = 'k6', 'the oldest five keys are left out';
end
$$;

\echo '== mark_read clears a key, for its owner only, with one signal'
do $$
declare
  v jsonb;
begin
  delete from public.realtime_push_notifications;
  perform test.act_as('service_role');
  perform public.realtime_push_send(md5('alice')::uuid, 'chat:42', 'Ana', 'one');
  perform public.realtime_push_send(md5('alice')::uuid, 'chat:42', 'Ana', 'two');
  perform public.realtime_push_send(md5('alice')::uuid, 'chat:7', 'Bruno', 'hey');
  perform public.realtime_push_send(md5('bob')::uuid, 'chat:42', 'Ana', 'for bob');
  perform test.act_as('postgres');
  truncate realtime.sent;

  perform test.act_as('authenticated', md5('alice')::uuid);
  perform public.realtime_push_mark_read('chat:42');
  assert (select count(*) from public.realtime_push_notifications) = 3, 'alice reads her own three rows';
  assert not exists (select 1 from public.realtime_push_notifications where user_id <> md5('alice')::uuid),
         'and nobody else''s';
  perform test.act_as('postgres');

  assert not exists (select 1 from public.realtime_push_notifications
                      where user_id = md5('alice')::uuid and key = 'chat:42' and read_at is null),
         'alice''s chat:42 is read';
  assert (select count(*) from public.realtime_push_notifications
           where user_id = md5('bob')::uuid and read_at is null) = 1, 'bob''s chat:42 is untouched';
  v := public.realtime_push_snapshot_for(md5('alice')::uuid);
  assert (v ->> 'badge')::int = 1 and jsonb_array_length(v -> 'notifications') = 1
         and v #>> '{notifications,0,id}' = 'chat:7', format('only chat:7 is left, got %s', v);
  assert (select count(*) from realtime.sent) = 2, 'two rows read, still one signal per device';

  truncate realtime.sent;
  perform test.act_as('authenticated', md5('alice')::uuid);
  perform public.realtime_push_mark_read('chat:42');
  perform test.act_as('postgres');
  assert (select count(*) from realtime.sent) = 0, 'nothing to mark, no signal';

  delete from public.realtime_push_notifications where user_id = md5('bob')::uuid;
  assert (select array_agg(topic) from realtime.sent) = array['push:' || test.pad43('bob-phone')],
         'a delete signals too';
end
$$;

\echo '== a failing realtime.send does not fail the write (one WARNING below is expected)'
do $$
declare
  v_id bigint;
begin
  truncate realtime.sent;
  perform set_config('test.realtime_down', 'on', true);
  perform test.act_as('service_role');
  v_id := public.realtime_push_send(md5('alice')::uuid, 'chat:9', 'Caio', 'still saved');
  perform test.act_as('postgres');
  perform set_config('test.realtime_down', 'off', true);

  assert exists (select 1 from public.realtime_push_notifications where id = v_id), 'the row was written';
  assert (select count(*) from realtime.sent) = 0, 'and no signal went out';
end
$$;

-- =============================================================================
-- Device lifetime
-- =============================================================================

\echo '== unregister removes only the caller''s own device'
do $$
begin
  perform test.act_as('authenticated', md5('bob')::uuid);
  perform public.realtime_push_unregister(test.pad43('alice-tablet'));
  perform test.act_as('postgres');
  assert exists (select 1 from public.realtime_push_devices where topic = test.pad43('alice-tablet')),
         'bob cannot remove alice''s device';

  perform test.act_as('authenticated', md5('alice')::uuid);
  perform public.realtime_push_unregister(test.pad43('alice-tablet'));
  perform test.act_as('anon');
  assert public.realtime_push_snapshot(test.pad43('alice-tablet'), test.pad43('alice-secret-3')) is null,
         'an unregistered device gets null';
  perform test.act_as('postgres');
  assert not exists (select 1 from public.realtime_push_devices where topic = test.pad43('alice-tablet')),
         'alice removed her tablet';
  assert exists (select 1 from public.realtime_push_devices where topic = test.pad43('alice-phone')),
         'and kept her phone';
end
$$;

\echo '== registering drops the caller''s devices unseen for 60 days'
do $$
begin
  perform test.act_as('authenticated', md5('alice')::uuid);
  perform public.realtime_push_register(test.pad43('alice-tablet'), test.pad43('alice-secret-3'));
  perform test.act_as('postgres');
  update public.realtime_push_devices set seen_at = now() - interval '61 days'
   where topic in (test.pad43('alice-tablet'), test.pad43('bob-phone'));

  perform test.act_as('authenticated', md5('alice')::uuid);
  perform public.realtime_push_register(test.pad43('alice-phone'), test.pad43('alice-secret-2'));
  perform test.act_as('postgres');

  assert not exists (select 1 from public.realtime_push_devices where topic = test.pad43('alice-tablet')),
         'alice''s stale tablet is gone';
  assert exists (select 1 from public.realtime_push_devices where topic = test.pad43('alice-phone')),
         'her phone stays';
  assert exists (select 1 from public.realtime_push_devices where topic = test.pad43('bob-phone')),
         'bob''s stale phone is not hers to drop';
end
$$;

\echo '== deleting a user deletes their devices and notifications'
do $$
begin
  perform test.act_as('service_role');
  perform public.realtime_push_send(md5('bob')::uuid, 'k', 't');
  perform test.act_as('postgres');
  delete from auth.users where id = md5('bob')::uuid;

  assert not exists (select 1 from public.realtime_push_devices where user_id = md5('bob')::uuid), 'devices';
  assert not exists (select 1 from public.realtime_push_notifications where user_id = md5('bob')::uuid),
         'notifications';
end
$$;

-- =============================================================================
-- A replaced realtime_push_snapshot_for survives running the script again
-- =============================================================================

\echo '== running the script again keeps a replaced realtime_push_snapshot_for'
create or replace function public.realtime_push_snapshot_for(p_user uuid)
returns jsonb language sql stable as $$
  select '{"badge": 7, "notifications": []}'::jsonb
$$;

\ir ../realtime_push.sql

do $$
begin
  perform test.act_as('anon');
  assert public.realtime_push_snapshot(test.pad43('alice-phone'), test.pad43('alice-secret-2'))
         = '{"badge": 7, "notifications": []}'::jsonb, 'the replacement is kept';
  perform test.act_as('authenticated', md5('alice')::uuid);
  perform test.expect_error($q$select public.realtime_push_snapshot_for(md5('alice')::uuid)$q$, '42501');
  perform test.act_as('postgres');
end
$$;

\echo 'All SQL tests passed.'
