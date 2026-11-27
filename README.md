# supabase_realtime_push

Push notifications on Android **without Firebase**. A native service holds one
Supabase Realtime connection, and your database decides what the notification
shade shows.

> **Status: work in progress, not published yet.** The design is in
> [docs/contract.md](docs/contract.md).

## How it works

1. The app registers the device with your Supabase project (a random topic and
   secret, one pair per device).
2. A native Android service keeps one WebSocket to Realtime, listening on that
   device's topic. No Flutter engine, no Google services.
3. When something changes, the database sends an empty `changed` signal.
4. The service asks a SQL function for the **snapshot** (every notification the
   device should show right now) and makes the shade match it.

Nothing is lost when the socket drops: every reconnect fetches the snapshot
again.

## Platform support

| Android | iOS | Web | Desktop |
|---|---|---|---|
| ✅ | ❌ (iOS does not allow a background socket; use APNs) | ❌ | ❌ |

## Self-hosted Supabase: raise the Realtime connection limit

Every device keeps one Realtime connection open all day. Self-hosted Realtime
accepts **200** concurrent connections per tenant by default
(`TENANT_MAX_CONCURRENT_USERS`) and refuses the rest with `429`, so the
refused phones silently stop getting notifications. Set it in the Realtime
container before rolling this out. See
[docs/contract.md §8](docs/contract.md#8-self-hosted-realtime).

## License

MIT
