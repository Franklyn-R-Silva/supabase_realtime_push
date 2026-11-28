# supabase_realtime_push example

A small Android app that goes through the whole flow: sign in, register the
device, start the service, get a notification sent from SQL, tap it and sign
out. Every step is explained in the
[package README](https://github.com/Franklyn-R-Silva/supabase_realtime_push#readme).

## What you need

- An Android phone or emulator (Android 7.0 or later) and Flutter 3.44 or later.
- A Supabase project, on Supabase Cloud or self-hosted.

## Run it

1. **Database:** run
   [`supabase/realtime_push.sql`](https://github.com/Franklyn-R-Silva/supabase_realtime_push/blob/main/supabase/realtime_push.sql)
   in the SQL editor. It is safe to run again.
2. **A user:** in Authentication > Users, add a user with an email and a
   password.
3. **Keys:** copy `.env.example` to `.env` and fill in `SUPABASE_URL` and
   `SUPABASE_PUBLISHABLE_KEY` (Project Settings > API Keys). `.env` is
   git-ignored.
4. **Start the app** from this folder:

   ```sh
   flutter run --dart-define-from-file=.env
   ```

## Try it

1. Sign in with that user.
2. Tap **Turn on notifications** and allow them. **Service** turns `running`.
3. Copy the **User id** shown on the screen and send a notification from the
   SQL editor:

   ```sql
   select realtime_push_send(
     '<user id>'::uuid,
     'hello',                 -- id: sending 'hello' again replaces it
     'Hello',
     'Sent from the database',
     '{"route": "/hello"}'    -- handed back to the app on tap
   );
   ```

   Swipe the app away from recents and send again: the notification still
   arrives, because the service keeps its own connection.
4. Tap the notification. The app opens and **Last tap** shows its id and data.
5. **Battery exemption:** until you grant it, Android keeps a "connected"
   notification on screen. Tap **Ask** to remove it.
6. **Sign out** unregisters the phone and stops the service, so this user's
   notifications stop arriving on it.
