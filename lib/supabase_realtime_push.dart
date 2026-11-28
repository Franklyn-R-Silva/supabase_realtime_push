/// Push notifications on Android without Firebase.
///
/// A native Android service holds one Supabase Realtime connection on a topic
/// only this device knows. When your database signals a change, the service
/// fetches the snapshot of notifications the device should show and makes the
/// notification shade match it. See `doc/contract.md` for the whole design.
///
/// ```dart
/// final device = await SupabaseRealtimePush.device();
/// await supabase.rpc('realtime_push_register', params: device.registerParams);
/// await SupabaseRealtimePush.start(
///   supabaseUrl: Uri.parse('https://xyz.supabase.co'),
///   publishableKey: supabasePublishableKey,
/// );
/// ```
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// The topic and secret that identify this device to your database.
///
/// Created on the device the first time [SupabaseRealtimePush.device] is
/// called and kept until `stop(forgetDevice: true)`.
@immutable
class PushDevice {
  /// Creates a device from the values the platform returned.
  const PushDevice({required this.topic, required this.secret});

  /// The Realtime topic the service listens on (43 base64url characters).
  final String topic;

  /// The secret that unlocks this device's snapshot. The database keeps only
  /// its sha256.
  final String secret;

  /// The parameters of `realtime_push_register`.
  Map<String, String> get registerParams => {
    'p_topic': topic,
    'p_secret': secret,
    'p_platform': 'android',
  };

  /// The parameters of `realtime_push_unregister`.
  Map<String, String> get unregisterParams => {'p_topic': topic};

  @override
  bool operator ==(Object other) =>
      other is PushDevice && other.topic == topic && other.secret == secret;

  @override
  int get hashCode => Object.hash(topic, secret);

  // Keeps the secret out of logs and error reports.
  @override
  String toString() => 'PushDevice(topic: $topic, secret: ***)';
}

/// A notification the user tapped.
@immutable
class PushTap {
  /// Creates a tap.
  const PushTap({required this.id, this.data = const {}});

  /// The notification's `id` in the snapshot.
  final String id;

  /// The notification's `data` in the snapshot.
  final Map<String, Object?> data;

  static PushTap? _fromPlatform(Object? raw) {
    if (raw is! Map) return null;
    final id = raw['id'];
    if (id is! String) return null;
    final data = raw['data'];
    final decoded = data is String && data.isNotEmpty ? jsonDecode(data) : null;
    return PushTap(
      id: id,
      data: decoded is Map ? decoded.cast<String, Object?>() : const {},
    );
  }

  @override
  String toString() => 'PushTap(id: $id, data: $data)';
}

/// What the service is doing.
enum PushStatus {
  /// Not started, stopped by [SupabaseRealtimePush.stop], or this install has
  /// no device pair (the app was restored from a backup): register and start.
  stopped,

  /// Turned on: the service runs, or comes back on the next app launch,
  /// reboot or app update. Not a promise of a live connection right now.
  running,

  /// The database answered that this device is not registered (signed out
  /// elsewhere, or unregistered). The service stopped itself; register the
  /// device again and call [SupabaseRealtimePush.start].
  rejected,
}

/// The package's entry point. Android only: see [isSupported].
abstract final class SupabaseRealtimePush {
  static const MethodChannel _channel = MethodChannel('supabase_realtime_push');

  static final StreamController<PushTap> _taps =
      StreamController<PushTap>.broadcast();

  static bool _listening = false;

  // A Postgres function name; it ends up in the request path.
  static final RegExp _functionName = RegExp(r'^[A-Za-z_][A-Za-z0-9_]{0,62}$');

  /// Whether this platform can run the service. Everything else is a no-op
  /// where this is false, except [device], which throws.
  static bool get isSupported =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  /// This device's topic and secret, created on the first call.
  ///
  /// Register them with your own signed-in Supabase client:
  /// `supabase.rpc('realtime_push_register', params: device.registerParams)`.
  ///
  /// Throws [UnsupportedError] where [isSupported] is false: a device that
  /// never connects must not be registered.
  static Future<PushDevice> device() async {
    if (!isSupported) {
      throw UnsupportedError('supabase_realtime_push runs on Android only.');
    }
    final raw = await _channel.invokeMapMethod<String, Object?>('device');
    final topic = raw?['topic'];
    final secret = raw?['secret'];
    if (topic is! String || secret is! String) {
      throw StateError('The platform returned no device credentials.');
    }
    return PushDevice(topic: topic, secret: secret);
  }

  /// Starts the service, and keeps it running across app restarts and reboots
  /// until [stop].
  ///
  /// Call it after the device is registered, while the app is on screen:
  /// Android 12+ refuses to start a foreground service from the background,
  /// and the call then throws a [PlatformException] with code `start_failed`.
  /// The service stays enabled, so the next start, a reboot or an app update
  /// tries again.
  ///
  /// - [supabaseUrl]: the project URL, `https://<ref>.supabase.co` or your
  ///   self-hosted gateway.
  /// - [publishableKey]: the project's publishable key (`sb_publishable_…`),
  ///   or the legacy anon key. Never a secret or service-role key.
  /// - [snapshotFunction]: the SQL function that returns the snapshot.
  /// - [channelName]: the notification channel name users see in settings.
  /// - [connectedTitle] and [connectedText]: the "connected" notification
  ///   Android requires while the app is not exempt from battery optimization.
  /// - [smallIcon]: a drawable resource name for the status bar; the app icon
  ///   when null.
  /// - [channels]: more notification channels, id to the name users see in
  ///   settings, so they can mute one kind (offers, say) and keep another. A
  ///   snapshot entry with `"channel": "<id>"` posts to that channel; any
  ///   other goes to [channelName].
  /// - [foregroundService]: with `false`, Android never shows the "connected"
  ///   notification. Without the battery exemption the connection is then
  ///   held only while the app is open, and with it closed a periodic check
  ///   reads the snapshot every 5 minutes or more: Android stretches it to an
  ///   hour or longer in Doze and for an app seldom opened. With the
  ///   exemption nothing changes: there is no notification either way.
  static Future<void> start({
    required Uri supabaseUrl,
    required String publishableKey,
    String snapshotFunction = 'realtime_push_snapshot',
    String channelName = 'Notifications',
    String connectedTitle = 'Connected',
    String connectedText = 'Waiting for new notifications',
    String? smallIcon,
    Map<String, String> channels = const {},
    bool foregroundService = true,
  }) async {
    if (!supabaseUrl.isScheme('https') && !supabaseUrl.isScheme('http') ||
        supabaseUrl.host.isEmpty) {
      throw ArgumentError.value(
        supabaseUrl,
        'supabaseUrl',
        'must be an http or https URL',
      );
    }
    if (publishableKey.trim().isEmpty) {
      throw ArgumentError.value(
        publishableKey,
        'publishableKey',
        'must not be empty',
      );
    }
    if (!_functionName.hasMatch(snapshotFunction)) {
      throw ArgumentError.value(
        snapshotFunction,
        'snapshotFunction',
        'must be a plain SQL function name',
      );
    }
    for (final MapEntry(:key, :value) in channels.entries) {
      if (key.trim().isEmpty || value.trim().isEmpty) {
        throw ArgumentError.value(
          channels,
          'channels',
          'ids and names must not be empty',
        );
      }
    }
    if (!isSupported) return;
    // No tap handler here: a tap with nobody listening to [taps] would be
    // dropped, while the platform keeps it for [launchTap] until Dart asks.
    await _channel.invokeMethod<void>('start', {
      'supabaseUrl': supabaseUrl.toString(),
      'publishableKey': publishableKey,
      'snapshotFunction': snapshotFunction,
      'channelName': channelName,
      'connectedTitle': connectedTitle,
      'connectedText': connectedText,
      'smallIcon': smallIcon,
      'channels': channels,
      'foregroundService': foregroundService,
    });
  }

  /// Stops the service and removes its notifications.
  ///
  /// With [forgetDevice], the topic and secret are deleted too and the next
  /// [device] call creates new ones: use it on sign-out, after calling
  /// `realtime_push_unregister`.
  static Future<void> stop({bool forgetDevice = false}) async {
    if (!isSupported) return;
    await _channel.invokeMethod<void>('stop', {'forgetDevice': forgetDevice});
  }

  /// What the service is doing now.
  static Future<PushStatus> status() async {
    if (!isSupported) return PushStatus.stopped;
    final name = await _channel.invokeMethod<String>('status');
    return PushStatus.values.asNameMap()[name] ?? PushStatus.stopped;
  }

  /// Whether the user exempted the app from battery optimization. Exempt
  /// apps run the service without the "connected" notification.
  static Future<bool> isIgnoringBatteryOptimizations() async {
    if (!isSupported) return false;
    return await _channel.invokeMethod<bool>(
          'isIgnoringBatteryOptimizations',
        ) ??
        false;
  }

  /// Asks the user to exempt the app from battery optimization.
  ///
  /// Opens the one-tap system dialog when your app's manifest declares
  /// `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and the
  /// battery-optimization settings list otherwise. The plugin does not declare
  /// that permission for you: Google Play accepts it only from apps that cannot
  /// use FCM.
  static Future<void> requestIgnoreBatteryOptimizations() async {
    if (!isSupported) return;
    await _channel.invokeMethod<void>('requestIgnoreBatteryOptimizations');
  }

  /// Opens the system settings of the "connected" notification alone, so the
  /// user can turn it off while the service keeps running. Before Android 8,
  /// which has no notification categories, the app's settings page. On
  /// Android 14+ the user can also just swipe that notification away.
  static Future<void> openConnectedNotificationSettings() async {
    if (!isSupported) return;
    await _channel.invokeMethod<void>('openConnectedNotificationSettings');
  }

  /// Asks for the notification permission (Android 13+). True when granted,
  /// and always true below Android 13.
  static Future<bool> requestNotificationPermission() async {
    if (!isSupported) return false;
    return await _channel.invokeMethod<bool>('requestNotificationPermission') ??
        false;
  }

  /// The tap that opened the app from a closed state, if any. Read it once
  /// at startup; taps while the app runs arrive on [taps].
  static Future<PushTap?> launchTap() async {
    if (!isSupported) return null;
    _listenForTaps();
    return PushTap._fromPlatform(
      await _channel.invokeMethod<Object?>('launchTap'),
    );
  }

  /// Taps on notifications while the app is running.
  static Stream<PushTap> get taps {
    _listenForTaps();
    return _taps.stream;
  }

  static void _listenForTaps() {
    if (_listening || !isSupported) return;
    _listening = true;
    _channel.setMethodCallHandler((call) async {
      if (call.method != 'tap') return;
      final tap = PushTap._fromPlatform(call.arguments);
      if (tap != null) _taps.add(tap);
    });
  }
}
