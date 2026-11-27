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

- Generated on the device with `SecureRandom`, once, and kept in a private
  file under `noBackupFilesDir` until `stop(forgetDevice: true)`. Auto Backup
  and device-to-device transfer copy `SharedPreferences`; a new phone restored
  from a backup must register its own pair, not read the old phone's
  notifications.
- The topic is the Realtime channel name, so anyone who learns it can only
  learn *that something changed*. Content needs the secret.
- Why not the user's session: the service runs with the app closed. Sharing
  the app's refresh token would make two owners of one token, and GoTrue's
  refresh-token rotation revokes the whole family when a used token comes back:
  the user would be logged out for no visible reason.

## 2. Realtime

- URL: `<supabaseUrl with http→ws, https→wss>/realtime/v1/websocket?apikey=<anonKey>&vsn=1.0.0`
- Join: topic `realtime:push:<topic>`, event `phx_join`, payload
  `{"config":{"broadcast":{"self":false},"presence":{"key":""},"private":false},"access_token":"<anonKey>"}`.
  `access_token` is what `realtime-js` sends and what Realtime's
  `phx_join.schema.json` lists as required; servers up to v2.102.3 also accept
  a join without it.
- Heartbeat: topic `phoenix`, event `heartbeat`, payload `{}`, every 25 s. No
  reply within the next interval means the socket is dead: reconnect.
- Any `broadcast` on the joined topic means "changed". Its payload is ignored.
- After every successful join (first or reconnect) the service fetches the
  snapshot.
- Reconnect backoff: 1, 2, 5, 10, 30 s, then every 30 s.

The topic must be **public** (`private: false`). Projects that turned off
public Realtime access cannot use this package.

## 3. Snapshot

`POST <supabaseUrl>/rest/v1/rpc/realtime_push_snapshot`
(function name configurable in `start`), headers `apikey: <anonKey>` and
`Authorization: Bearer <anonKey>`, body `{"p_topic": "...", "p_secret": "..."}`.

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
   - if the user swiped it away at revision `r` and `revision <= r`: skip it
     (and cancel it if it is still shown);
   - else post it (Android tag = `id`), alerting only if `revision` is greater
     than the last revision alerted for that `id` and `silent` is false;
     otherwise update it silently (`setOnlyAlertOnce`). Every listed `id`
     remembers its revision as alerted, silent or not, so a repost after a
     reboot or a later `silent: false` does not ring for the same revision.
3. Alerted revisions are pruned to the ids in the snapshot; a dismissed
   revision is kept only while it still hides something (`revision <= r`).

Only what is new, changed (revision, title, body, silent, data or badge) or
missing from the shade is posted; the rest is left alone. Android drops
notification updates past 5 per second per app, so reposting everything on
every snapshot loses changes. What is posted together keeps the snapshot's
order, first on top. Only the first shown notification carries `badge`:
launchers that show a number add them up.

Tapping a notification opens the app's launch activity and hands
`{id, data}` to Dart. A tap counts as a dismissal at the shown revision:
Android removes a tapped notification without telling the app, and the next
snapshot would otherwise put it back.

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
  periodic watchdog (an inexact alarm allowed while idle, about every 5 min).
- Not exempt, on Android 12+: when the system kills the process, neither the
  `START_STICKY` restart nor the watchdog may start a foreground service from
  the background, so the service stays down (and stops itself if it was
  restarted without the foreground) until the app is opened. Every app launch
  starts it again while the status is `running`, which means "turned on", not
  "connected right now".
- With the screen off a dropped socket is not reconnected: the CPU sleeps, the
  heartbeat stalls and the server closes the socket within a minute, so
  reconnecting would cost a handshake, a join and a snapshot every minute all
  night. The watchdog reads the snapshot instead, and turning the screen on
  reconnects.

## 6. Dart ↔ Android

MethodChannel `supabase_realtime_push`.

| Method | Arguments | Returns |
|---|---|---|
| `device` | — | `{topic, secret}` (creates them on first call) |
| `start` | `{supabaseUrl, anonKey, snapshotFunction, channelName, connectedTitle, connectedText, smallIcon?}` | `null` |
| `stop` | `{forgetDevice: bool}` | `null` |
| `status` | — | `"stopped"`, `"running"` or `"rejected"` |
| `isIgnoringBatteryOptimizations` | — | `bool` |
| `requestIgnoreBatteryOptimizations` | — | `null` (opens the system dialog) |
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
| `realtime_push_register(p_topic, p_secret, p_platform)` | the app, signed in | Upserts the device for `auth.uid()`. A topic owned by another user is refused |
| `realtime_push_unregister(p_topic)` | the app, signed in | Deletes the caller's own device |
| `realtime_push_snapshot(p_topic, p_secret)` | the service, `anon` | Checks the pair, stamps `seen_at`, returns `realtime_push_snapshot_for(user)` or `null` |
| `realtime_push_snapshot_for(p_user)` | the snapshot function | **Yours to replace.** The default reads `realtime_push_notifications` |
| `realtime_push_signal(p_users uuid[])` | your triggers, `service_role` | `realtime.send('{}', 'changed', 'push:' \|\| topic, false)` to every device of those users. Errors become warnings: a push must never fail the write that caused it |
| `realtime_push_notifications` | your backend, or the user (RLS: own rows) | A ready-made inbox: insert a row to notify, set `read_at` to clear it |

## 8. Self-hosted Realtime

Every device holds one connection all day, and an open app's own Supabase
client holds another. Realtime refuses new connections past the tenant's
`max_concurrent_users` with `429 Too many connected users`, and the refused
device silently stops getting notifications.

- Supabase Cloud: the plan's quota (200 Free, 500 Pro, then billed per peak).
- Self-hosted: `TENANT_MAX_CONCURRENT_USERS`, **200 by default**. With
  `SEED_SELF_HOST=true` the tenant is recreated on every boot from that
  variable, so set it in the Realtime container's environment.
