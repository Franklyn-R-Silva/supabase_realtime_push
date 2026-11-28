<h1 align="center">
  <img
    src="https://raw.githubusercontent.com/Franklyn-R-Silva/supabase_realtime_push/main/screenshots/hero.png"
    alt="supabase_realtime_push: your database rings an empty doorbell, and the phone fetches the snapshot of its notifications"
    width="860"
  />
</h1>

<p align="center">
  <a href="https://github.com/Franklyn-R-Silva/supabase_realtime_push/actions/workflows/ci.yaml"><img src="https://github.com/Franklyn-R-Silva/supabase_realtime_push/actions/workflows/ci.yaml/badge.svg" alt="CI" /></a>
  <a href="https://github.com/Franklyn-R-Silva/supabase_realtime_push/actions/workflows/github-code-scanning/codeql"><img src="https://github.com/Franklyn-R-Silva/supabase_realtime_push/actions/workflows/github-code-scanning/codeql/badge.svg" alt="CodeQL" /></a>
  <a href="https://scorecard.dev/viewer/?uri=github.com/Franklyn-R-Silva/supabase_realtime_push"><img src="https://api.scorecard.dev/projects/github.com/Franklyn-R-Silva/supabase_realtime_push/badge" alt="OpenSSF Scorecard" /></a>
  <a href="https://www.bestpractices.dev/projects/17222"><img src="https://www.bestpractices.dev/projects/17222/badge" alt="OpenSSF Best Practices" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License: MIT" /></a>
  <img src="https://img.shields.io/badge/platform-Android%207.0%2B-3DDC84?logo=android&amp;logoColor=white" alt="Platform: Android 7.0+" />
  <a href="https://supabase.com/docs/guides/realtime"><img src="https://img.shields.io/badge/Supabase-Realtime-3FCF8E?logo=supabase&amp;logoColor=white" alt="Supabase Realtime" /></a>
  <a href="https://flutter.dev"><img src="https://img.shields.io/badge/Flutter-%E2%89%A5%203.44-02569B?logo=flutter&amp;logoColor=white" alt="Flutter 3.44 or later" /></a>
</p>

<p align="center">
  <strong>Push notifications on Android without Firebase.</strong><br />
  One Supabase Realtime connection, an empty signal, and a notification shade
  that always matches your database.
</p>

<p align="center">
  <a href="#setup"><img
    src="https://img.shields.io/badge/Get_started-10B981?style=for-the-badge&amp;logo=supabase&amp;logoColor=white"
    alt="Get started"
  /></a>
  <br />
  Works with Supabase Cloud and with self-hosted Supabase.<br />
  No FCM project and no <code>google-services.json</code>.
</p>

> **Status: work in progress, not published yet.** The design is in
> [doc/contract.md](doc/contract.md).

---

## What it does

A push notification is the message that pops up on a phone, like "Your order
has shipped", even when the app is closed. On Android, apps almost always get
them through Firebase, a Google service that needs Google's apps on the phone.

This library does the same job without Google. It is for Android apps made
with Flutter (a toolkit for building apps) that use Supabase (a ready-made
database and server). A small helper on the phone keeps one connection open
to the server, without starting the whole app. It works like a doorbell: when
something changes, the server only rings the bell. The phone then asks "what
should I show now?" and makes its notification list match the answer. With the
screen on that takes a second or two; with the screen off the phone mostly
sleeps, so a notification can take up to about five minutes, or about fifteen
when the phone has been still for a long time. If the
connection drops, in an elevator or on weak Wi-Fi, the phone asks again once
it is back, so nothing is lost. A message read on another phone disappears
from this one.

## Who it is for

It is for company phones without Google, fleets that install the app from a
file instead of the Play Store, and teams that run their own Supabase server.
It is not for iPhones, which do not let an app keep a connection open in the
background. If your app is on the Play Store and every user has Google, use
Firebase: it shares one connection among all the apps on a phone. The cost is
real: each phone holds a connection to your server all day, and unless the
user lets the app skip battery saving, Android shows a small "connected"
notification that stays.

## Contents

- [How it works](#how-it-works)
- [Platform support](#platform-support)
- [Setup](#setup): [database](#1-database) ·
  [Flutter](#2-flutter) ·
  [register and start](#3-register-and-start-after-sign-in) ·
  [sign out](#4-sign-out) · [send](#5-send-a-notification) ·
  [taps](#6-handle-taps)
- [Your own data instead of the inbox](#your-own-data-instead-of-the-inbox)
- [Battery and the "connected" notification](#battery-and-the-connected-notification)
- [Security](#security)
- [Limits](#limits)
- [Feedback and contributing](#feedback-and-contributing)

---

## How it works

<p align="center">
  <img
    src="https://raw.githubusercontent.com/Franklyn-R-Silva/supabase_realtime_push/main/screenshots/how-it-works.png"
    alt="Sequence diagram. 1: the phone registers a topic and secret with Postgres. 2: it keeps one WebSocket to Realtime. 3: Postgres sends an empty changed signal through Realtime to the phone. 4: the phone fetches the snapshot from Postgres, and its notification shade then matches it: new ones alert, changed ones update quietly, gone ones disappear."
    width="860"
  />
</p>

1. The app registers the device with your project: a random topic and secret,
   one pair per device. The database keeps only the secret's hash.
2. A native Android service keeps one WebSocket to Realtime, listening on that
   device's topic. It runs without the Flutter engine, so it uses a few MB of
   memory.
3. When something changes, the database sends an empty `changed` signal to the
   topic. No content ever travels over the socket.
4. The service asks a SQL function for the **snapshot** (every notification
   the device should show right now) and makes the shade match it: new ones
   alert, changed ones update quietly, gone ones disappear.

## Platform support

| Android | iOS | Web | Desktop |
|---|---|---|---|
| ✅ 7.0+ | ❌ iOS does not allow a background socket; use APNs | ❌ | ❌ |

On other platforms every call is a no-op, except `device()`, which throws, so
you can keep one code path and check `SupabaseRealtimePush.isSupported`.

---

## Setup

### 1. Database

Run [`supabase/realtime_push.sql`](supabase/realtime_push.sql) as a migration.
It is idempotent and creates, in `public`:

| Object | Called by | Does |
|---|---|---|
| `realtime_push_register(p_topic, p_secret, p_platform)` | the app, signed in | Links this device to `auth.uid()` |
| `realtime_push_unregister(p_topic)` | the app, signed in | Unlinks it (on sign-out) |
| `realtime_push_snapshot(p_topic, p_secret)` | the service (`anon`) | Returns the snapshot, or `null` for an unknown device |
| `realtime_push_send(p_user, p_key, p_title, p_body, p_data, p_silent)` | your backend (`service_role`) | Puts a notification in the user's inbox, signals their devices, returns the row id |
| `realtime_push_mark_read(p_key)` | the app, signed in | Clears that notification on every device |
| `realtime_push_snapshot_for(p_user)` | the snapshot | **Yours to replace** (see below) |
| `realtime_push_signal(p_users)` | your triggers | Wakes those users' devices |

#### Self-hosted Supabase: raise the Realtime connection limit first

Every device keeps one Realtime connection open all day, and an open app's own
Supabase client holds another. Self-hosted Realtime accepts **200** concurrent
connections per tenant by default and refuses the rest with `429 Too many
connected users`. Refused phones silently stop getting notifications.

```yaml
# docker-compose, realtime service
environment:
  TENANT_MAX_CONCURRENT_USERS: "2000"
```

With `SEED_SELF_HOST=true` the tenant is recreated from that variable on every
boot, so set it in the environment, not in the database. On Supabase Cloud the
limit is your plan's Realtime quota.

`TENANT_MAX_JOINS_PER_SECOND` (100 by default) matters after a Realtime
restart, when every device rejoins. The service spreads its retries randomly,
so a fleet of a few thousand catches up within a minute; raise it if yours is
bigger. `TENANT_MAX_EVENTS_PER_SECOND` (also 100) counts every signal: an
announcement to the whole fleet at once goes past it and Realtime drops
broadcasts for a while, so raise it or spread such sends out.

### 2. Flutter

```yaml
dependencies:
  supabase_realtime_push:
    git: https://github.com/Franklyn-R-Silva/supabase_realtime_push
```

The plugin's manifest brings its permissions, service and receivers. One
permission it leaves to you on purpose: add
`<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />`
to your manifest only if you want the one-tap battery-exemption dialog and
Google Play's policy allows it for your app (see
[Battery](#battery-and-the-connected-notification)). Without it,
`requestIgnoreBatteryOptimizations()` opens the system list instead.

### 3. Register and start (after sign-in)

```dart
final supabase = Supabase.instance.client;

await SupabaseRealtimePush.requestNotificationPermission();
final device = await SupabaseRealtimePush.device();
await supabase.rpc('realtime_push_register', params: device.registerParams);
await SupabaseRealtimePush.start(
  supabaseUrl: Uri.parse('https://<ref>.supabase.co'),
  publishableKey: '<publishable or anon key>',
  channelName: 'Messages',
);
```

Call `start` while the app is on screen: Android 12+ refuses to start a
foreground service from the background. The service then survives the app
being closed, the phone rebooting and the app updating.

Run `device`, `realtime_push_register` and `start` again on every app start
while a session exists and `status()` is not `running`. A reinstall or a new
phone restores the app's settings from a backup but never the device pair, so
that install reports `stopped` until it registers its own; a `rejected` device
needs the same.

### 4. Sign out

```dart
final device = await SupabaseRealtimePush.device();
try {
  await supabase.rpc('realtime_push_unregister', params: device.unregisterParams);
} catch (_) {
  // Offline: sign out anyway. The forgotten pair can no longer read anything.
}
await SupabaseRealtimePush.stop(forgetDevice: true);
await supabase.auth.signOut();
```

`forgetDevice` deletes the pair, so the next person to sign in on this phone
gets a new one. Unregistering is best effort: without a network the row stays
on the server, but no phone holds its secret any more.

Sessions also end without your sign-out button: a password change, a revoked
session, a refresh that fails. Stop the service on every sign-out, or the next
person on a shared phone keeps seeing the previous user's notifications:

```dart
supabase.auth.onAuthStateChange.listen((state) {
  if (state.event == AuthChangeEvent.signedOut) {
    SupabaseRealtimePush.stop(forgetDevice: true);
  }
});
```

### 5. Send a notification

From SQL, an Edge Function with the service role, or a trigger:

```sql
select realtime_push_send(
  '9f3c…'::uuid,               -- the user
  'order:42',                   -- id: same id replaces the notification
  'Order shipped',
  'It leaves the warehouse today',
  '{"route": "/orders/42"}'     -- handed back to the app on tap
);
```

When the user opens it in the app, clear it everywhere:

```dart
await supabase.rpc('realtime_push_mark_read', params: {'p_key': 'order:42'});
```

Read rows stay so the app can show a history. Trim them on a schedule, for
example with `pg_cron`:

```sql
delete from public.realtime_push_notifications
 where read_at < now() - interval '30 days';
```

### 6. Handle taps

```dart
final launch = await SupabaseRealtimePush.launchTap(); // opened the app
SupabaseRealtimePush.taps.listen((tap) {               // app was running
  if (tap.data['route'] case final String route) router.go(route);
});
```

## Your own data instead of the inbox

The inbox table is a ready-made default. If your notifications already live in
your tables (unread chat messages, pending approvals), replace one function and
signal from your own triggers:

```sql
create or replace function public.realtime_push_snapshot_for(p_user uuid)
returns jsonb language sql stable
set search_path = public, pg_temp as $$
  select jsonb_build_object(
    'badge', count(*),
    'notifications', coalesce(jsonb_agg(jsonb_build_object(
      'id', 'chat:' || c.id,
      'revision', c.last_message_id,
      'title', c.title,
      'body', c.last_message_text,
      'data', jsonb_build_object('route', '/chat/' || c.id)
    ) order by c.last_message_at desc), '[]'::jsonb))
  from my_unread_chats(p_user) c;
$$;

-- your trigger: security definer, because realtime_push_signal is granted to
-- service_role only and a trigger running as the user would fail the write
create or replace function public.chat_message_signal()
returns trigger language plpgsql security definer
set search_path = '' as $$
begin
  perform public.realtime_push_signal(array[new.recipient_id]);
  return null;
end $$;

create trigger chat_message_signal after insert on public.chat_messages
  for each row execute function public.chat_message_signal();
```

- Use `p_user`, not `auth.uid()`: the device calls the snapshot without a
  session.
- `revision` must grow whenever the notification should alert again.
- Return at most about 20 notifications: Android keeps at most 50 per app and
  drops the rest without an error.
- Running `realtime_push.sql` again keeps your version; drop the function
  first to get the default back.
- The shape is in [doc/contract.md §3](doc/contract.md#3-snapshot).

## Battery and the "connected" notification

Android stops a background service about a minute after the app leaves the
screen, unless the app is exempt from battery optimization.

- **Not exempt:** the service runs in the foreground, and Android requires a
  low-priority "connected" notification while it does (`connectedTitle`,
  `connectedText`).
- **Exempt:** no notification. Ask with
  `SupabaseRealtimePush.requestIgnoreBatteryOptimizations()`.

`smallIcon` is looked up by name. If your release build shrinks resources,
keep that drawable (`tools:keep="@drawable/<name>"` in `res/raw/keep.xml`), or
the status bar falls back to the app icon.

Without the exemption, Android also cuts the network in Doze (screen off,
phone still for a while), so a notification can wait for the next maintenance
window. Exempt apps keep the network.

**Google Play:** asking for the exemption directly is
[acceptable](https://developer.android.com/training/monitoring-device-state/doze-standby#exemption-cases)
for a messaging app only when it *cannot* use FCM (no Google Play services on
the devices, or a technical dependency on another messaging service). Choosing
not to use FCM is not enough. Apps distributed outside Play, such as company
fleets installing an APK, are not bound by that policy. Apps targeting Android
14+ on Play also declare the `remoteMessaging` foreground service type in the
Play Console.

Some manufacturers kill background apps more aggressively than Android does.
If notifications stop on one brand, see [dontkillmyapp.com](https://dontkillmyapp.com).

## Security

- The topic is public and the signal carries nothing: knowing a topic tells
  you only that *something* changed for that device.
- Content needs the secret, which is 256 random bits; the database stores only
  its sha256.
- The service never uses the user's session. Sharing the app's refresh token
  with a second owner would trip GoTrue's refresh-token reuse detection and
  sign the user out.
- Unregistering a device (or deleting the user) makes the next snapshot
  `null`, and the service clears its notifications and stops.

Report vulnerabilities privately: see [SECURITY.md](SECURITY.md).

## Limits

- **Delivery is instant only with the screen on.** With the screen off the
  phone's processor sleeps, the connection's heartbeat stops and Realtime
  closes it within a minute. Reconnecting all night would drain the battery,
  so the service wakes every five minutes instead (up to about fifteen in
  deep Doze, Android's idle mode for a phone left still) and
  fetches the snapshot; turning the screen on reconnects at once. FCM does not
  have this limit, because Google holds that connection for every app.
- Android only.
- One Realtime connection per device, all day (see the self-hosted limit
  above).
- Supabase Cloud closes public Realtime connections after 24 hours; the service
  reconnects at once.
- The topic must be public: projects that disabled public Realtime channels
  cannot use this package.

---

## Troubleshooting

The service logs under one tag, never the secret or what a notification says:

```bash
adb logcat -s SupabaseRealtimePush
```

| You see | Cause |
|---|---|
| `snapshot failed: HTTP 404` | `snapshotFunction` names a function that does not exist, or `realtime_push.sql` was not run |
| `snapshot failed: HTTP 401` | Wrong `publishableKey` |
| `socket failed HTTP 429` | The Realtime connection limit; see [Self-hosted Supabase](#self-hosted-supabase-raise-the-realtime-connection-limit-first) |
| `join refused (error)` | Public Realtime channels are turned off in the project |
| `the server does not know this device` | The device is not registered (status `rejected`): register it and `start` again |
| `foreground not allowed from the background` | Android 12+ killed the service and the app is not exempt; it comes back when the app opens |
| Nothing at all | `start` was never called on this install (`status()` is `stopped`) |

## Feedback and contributing

- Bugs and ideas: [open an issue](https://github.com/Franklyn-R-Silva/supabase_realtime_push/issues).
- Code: see [CONTRIBUTING.md](CONTRIBUTING.md).
- Security problems: do not open an issue, see [SECURITY.md](SECURITY.md).

## License

MIT. See [LICENSE](LICENSE).
