# Contract

How the Dart API, the Android service and your Supabase project talk to each
other. Code in `lib/`, `android/` and `supabase/` follows this file; a change
to the contract changes this file in the same PR.

## The idea in one paragraph

Firebase is what normally wakes a closed Android app. Without it, the app has
to keep its own connection open. A native Android service (no Flutter engine,
so a few MB of RAM) holds **one** WebSocket to Supabase Realtime and listens
on a public topic that only this device knows. The database never sends
content over that socket: it sends an empty `changed` signal. The service then
asks a SQL function for the **snapshot**, the full list of notifications the
device should show right now, and makes the notification shade match it.

Why a snapshot and not events: sockets drop (Doze, elevators, Wi-Fi to mobile).
If notifications rode on events, the one that fell in the gap would never be
shown. With a snapshot, every signal and every reconnect just says "go look",
nothing is lost, a notification read on another device disappears on this
one, and the database needs no queue.

## 1. Device credentials

| Name | Format | Where it lives |
|---|---|---|
| `topic` | 32 random bytes, base64url without padding (43 chars, `^[A-Za-z0-9_-]{43}$`) | device (plain) and database (plain, primary key) |
| `secret` | 32 random bytes, base64url without padding (43 chars) | device (plain) and database (**sha256 only**) |

- Generated on the device with `SecureRandom`, once, and kept in the app's
  private `SharedPreferences` until `stop(forgetDevice: true)`.
- The topic is the Realtime channel name, so anyone who learns it can only
  learn *that something changed*. Content needs the secret.
- Why not the user's session: the service runs with the app closed. Sharing
  the app's refresh token would make two owners of one token, and GoTrue's
  refresh-token rotation revokes the whole family when a used token comes back:
  the user would be logged out for no visible reason.

## 2. Realtime

- URL: `<supabaseUrl with http→ws, https→wss>/realtime/v1/websocket?apikey=<publishableKey>&vsn=1.0.0`
- Join: topic `realtime:push:<topic>`, event `phx_join`, payload
  `{"config":{"broadcast":{"self":false},"presence":{"key":""},"private":false},"access_token":"<key>"}`.
  `access_token` goes only when the key is a JWT (the legacy anon key,
  `eyJ…`); a new opaque key (`sb_publishable_…`) is not a JWT and the join
  leaves the field out. Servers up to v2.102.3 accept a join without it.
- Heartbeat: topic `phoenix`, event `heartbeat`, payload `{}`, every 25 s. No
  reply within the next interval means the socket is dead: reconnect.
- Any `broadcast` on the joined topic means "changed". Its payload is ignored.
- After every successful join (first or reconnect) the service fetches the
  snapshot.
- At most one snapshot fetch runs and at most one waits. A signal or join that
  arrives during a fetch only marks the waiting one, so 500 signals from one
  bulk insert cost two fetches, not 500.
- A refused join, or a `phx_close` or `phx_error` on the topic while the
  socket is up, counts as a drop: close the socket and reconnect.
- Reconnect backoff: 1, 2, 5, 10, 30 s, then every 30 s, each wait multiplied
  by a random factor between 0.5 and 1.5.
- Supabase Cloud closes a public Realtime connection after at most 24 hours.
  A server close after a long healthy session reconnects after a random 0 to
  5 s, without growing the backoff.
- Why random: a Realtime restart drops every device at once. Self-hosted
  Realtime accepts 100 joins per second by default
  (`TENANT_MAX_JOINS_PER_SECOND`), so a fleet that retries in lockstep gets
  most joins refused, wave after wave.

The topic must be **public** (`private: false`). Projects that turned off
public Realtime access cannot use this package.

## 3. Snapshot

`POST <supabaseUrl>/rest/v1/rpc/realtime_push_snapshot`
(function name configurable in `start`), header `apikey: <publishableKey>`,
body `{"p_topic": "...", "p_secret": "..."}`.

The key goes in `apikey` only, never in `Authorization: Bearer`: the new keys
are not JWTs and fail anything that parses one, and the API gateway puts the
anon role in place when `Authorization` is missing.

Response:

```json
{
  "badge": 2,
  "notifications": [
    {
      "id": "chat:42",
      "revision": 1017,
      "title": "Ana",
      "body": "See you at 3",
      "silent": false,
      "data": {"route": "/chat/42"}
    }
  ]
}
```

| Field | Type | Meaning |
|---|---|---|
| `badge` | int, optional | Launcher badge count (`setNumber`) |
| `notifications[].id` | string, required | Stable key. Same id = same notification, replaced in place |
| `notifications[].revision` | int64, required | Grows when the notification has something new to alert about |
| `notifications[].title` | string, required | |
| `notifications[].body` | string, optional | |
| `notifications[].silent` | bool, optional (false) | Show without sound or vibration |
| `notifications[].data` | object, optional | Handed back to Dart when the user taps |

Unknown fields are ignored, so the server can add fields without breaking old
clients.

**`null` response** means the topic/secret pair is not registered (the user
signed out on another device, or the row was deleted). The service removes its
notifications, stops itself and reports status `rejected`.

HTTP errors and timeouts (10 s) are retried on the next signal or reconnect;
the shade is left as it was.

## 4. Reconciling the shade

For each fetched snapshot, in order:

1. Every notification this package posted whose `id` is not in the snapshot is
   cancelled.
2. For each listed notification:
   - if the user swiped it away at revision `r` and `revision <= r`: skip it;
   - else post it (Android tag = `id`), alerting only if `revision` is greater
     than the last revision alerted for that `id` and `silent` is false;
     otherwise update it silently (`setOnlyAlertOnce`).
3. Alerted and dismissed revisions are pruned to the ids in the snapshot.

Tapping a notification opens the app's launch activity and hands
`{id, data}` to Dart.

## 5. The Android service

- Runs in the app process, without the Flutter engine.
- **Battery-optimization exempt** (the app asked and the user allowed it): a
  plain background service, no notification of its own. Android lets an exempt
  app start and keep a background service.
- **Not exempt**: a foreground service of type `remoteMessaging` (Android 14+),
  with a low-importance "connected" notification. Without it, Android stops a
  background service about a minute after the app leaves the screen.
  `remoteMessaging` has no daily time limit and may be started from
  `BOOT_COMPLETED`.
- The choice is re-made on every start command, so granting the exemption later
  removes the "connected" notification.
- `START_STICKY`, restarted after reboot and app update, and checked by a
  periodic watchdog.

## 6. Dart ↔ Android

MethodChannel `supabase_realtime_push`.

| Method | Arguments | Returns |
|---|---|---|
| `device` | — | `{topic, secret}` (creates them on first call) |
| `start` | `{supabaseUrl, publishableKey, snapshotFunction, channelName, connectedTitle, connectedText, smallIcon?}` | `null`. Errors: `bad_arguments`; `start_failed` when Android refuses to start the service (from the background on Android 12+). The status stays `running`, so the next `start`, a reboot or an app update tries again |
| `stop` | `{forgetDevice: bool}` | `null` |
| `status` | — | `"stopped"`, `"running"` or `"rejected"` |
| `isIgnoringBatteryOptimizations` | — | `bool` |
| `requestIgnoreBatteryOptimizations` | — | `null`. Opens the direct dialog when the **app's** manifest declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, the battery-optimization settings list otherwise. The plugin never declares that permission itself: Google Play allows it only for apps that cannot use FCM, and a merged manifest would put it in every app |
| `requestNotificationPermission` | — | `bool` (Android 13+ runtime permission; `true` below 13) |
| `launchTap` | — | `{id, data}` or `null`: the tap that opened the app |

Android → Dart on the same channel: `tap` with `{id, data}` when the user taps
a notification while the app is running.

`data` crosses the channel as a JSON string and is decoded in Dart.

## 7. Database

`supabase/realtime_push.sql` creates, in `public`:

| Object | Who calls it | What it does |
|---|---|---|
| `realtime_push_devices` | nobody directly (RLS on, no policies) | `topic`, `user_id`, `secret_hash`, `platform`, `created_at`, `seen_at` |
| `realtime_push_register(p_topic, p_secret, p_platform default 'android')` | the app, signed in | Upserts the device for `auth.uid()`. A topic owned by another user is refused. Also deletes the caller's other devices not seen for 60 days |
| `realtime_push_unregister(p_topic)` | the app, signed in | Deletes the caller's own device |
| `realtime_push_snapshot(p_topic, p_secret)` | the service, `anon` (also `authenticated`) | Checks the pair, stamps `seen_at` (at most once a minute), returns `realtime_push_snapshot_for(user)` or `null` |
| `realtime_push_snapshot_for(p_user)` | the snapshot function only | **Yours to replace** with `create or replace`; running the script again keeps your version. It runs inside `realtime_push_snapshot`, whose `search_path` is empty: give yours its own `set search_path`, or schema-qualify every name. The default reads `realtime_push_notifications` |
| `realtime_push_signal(p_users uuid[])` | your triggers, `service_role` | `realtime.send('{}', 'changed', 'push:' \|\| topic, false)` to every device of those users. Errors become warnings: a push must never fail the write that caused it |
| `realtime_push_notifications` | the user reads own rows (RLS); writes go through the two functions below | A ready-made inbox. Any insert, update or delete signals the owners, once per statement |
| `realtime_push_send(p_user, p_key, p_title, p_body default '', p_data default '{}', p_silent default false)` | your backend, `service_role` | Inserts an inbox row and returns its id. Same `p_key` = same notification |
| `realtime_push_mark_read(p_key)` | the app, signed in | Sets `read_at` on the caller's unread rows of that key |

The default snapshot has one notification per `key`: its newest unread row,
with `revision` = that row's id. Newest first, at most 20; `badge` counts every
unread row.

`secret_hash` is `sha256()` of the secret's UTF-8 bytes, built into Postgres
11 and later, so no extension is needed.

## 8. Self-hosted Realtime

Every device holds one connection all day, and an open app's own Supabase
client holds another. Realtime refuses new connections past the tenant's
`max_concurrent_users` with `429 Too many connected users`, and the refused
device silently stops getting notifications.

- Supabase Cloud: the plan's quota (200 Free, 500 Pro, then billed per peak).
- Self-hosted: `TENANT_MAX_CONCURRENT_USERS`, **200 by default**. With
  `SEED_SELF_HOST=true` the tenant is recreated on every boot from that
  variable, so set it in the Realtime container's environment.
- Every `realtime.send` counts against the tenant's events per second
  (`TENANT_MAX_EVENTS_PER_SECOND`, **100 by default** when self-hosted). A
  signal to many users at once, such as an announcement to the whole fleet,
  goes past it, and Realtime then drops broadcasts for a while. Raise it before
  fleet-wide sends, or spread them out.
