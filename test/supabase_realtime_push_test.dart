import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:supabase_realtime_push/supabase_realtime_push.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('supabase_realtime_push');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <MethodCall>[];
  late Object? Function(MethodCall call) reply;

  setUp(() {
    calls.clear();
    reply = (_) => null;
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return reply(call);
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
    debugDefaultTargetPlatformOverride = null;
  });

  // What the native side does when the user taps a notification.
  Future<void> tapFromAndroid(Object? arguments) async {
    await messenger.handlePlatformMessage(
      channel.name,
      channel.codec.encodeMethodCall(MethodCall('tap', arguments)),
      (_) {},
    );
  }

  group('device', () {
    test('returns the credentials the platform created', () async {
      reply = (_) => {'topic': 't' * 43, 'secret': 's' * 43};

      final device = await SupabaseRealtimePush.device();

      expect(device, PushDevice(topic: 't' * 43, secret: 's' * 43));
      expect(device.registerParams, {
        'p_topic': 't' * 43,
        'p_secret': 's' * 43,
        'p_platform': 'android',
      });
      expect(device.unregisterParams, {'p_topic': 't' * 43});
    });

    test('never prints the secret', () {
      const device = PushDevice(topic: 'topic', secret: 'do-not-log-me');

      expect(device.toString(), isNot(contains('do-not-log-me')));
    });

    test('fails when the platform returns no credentials', () async {
      reply = (_) => null;

      await expectLater(SupabaseRealtimePush.device(), throwsStateError);
    });

    test('throws off Android instead of handing out a dead device', () async {
      debugDefaultTargetPlatformOverride = TargetPlatform.iOS;

      await expectLater(
        SupabaseRealtimePush.device(),
        throwsA(isA<UnsupportedError>()),
      );
      expect(calls, isEmpty);
    });
  });

  group('start', () {
    test('sends the whole configuration with its defaults', () async {
      await SupabaseRealtimePush.start(
        supabaseUrl: Uri.parse('https://xyz.supabase.co'),
        publishableKey: 'anon',
      );

      expect(calls.single.method, 'start');
      expect(calls.single.arguments, {
        'supabaseUrl': 'https://xyz.supabase.co',
        'publishableKey': 'anon',
        'snapshotFunction': 'realtime_push_snapshot',
        'channelName': 'Notifications',
        'connectedTitle': 'Connected',
        'connectedText': 'Waiting for new notifications',
        'smallIcon': null,
        'channels': <String, String>{},
        'foregroundService': true,
      });
    });

    test('sends foregroundService', () async {
      await SupabaseRealtimePush.start(
        supabaseUrl: Uri.parse('https://xyz.supabase.co'),
        publishableKey: 'anon',
        foregroundService: false,
      );
      expect((calls.single.arguments as Map)['foregroundService'], false);
    });

    test('sends the extra channels and rejects empty ids or names', () async {
      await SupabaseRealtimePush.start(
        supabaseUrl: Uri.parse('https://xyz.supabase.co'),
        publishableKey: 'anon',
        channels: {'offers': 'Offers'},
      );
      expect((calls.single.arguments as Map)['channels'], {'offers': 'Offers'});

      for (final channels in [
        {' ': 'Offers'},
        {'offers': ''},
      ]) {
        await expectLater(
          SupabaseRealtimePush.start(
            supabaseUrl: Uri.parse('https://xyz.supabase.co'),
            publishableKey: 'anon',
            channels: channels,
          ),
          throwsArgumentError,
          reason: '$channels',
        );
      }
      expect(calls, hasLength(1));
    });

    test('rejects a URL that is not http or https', () async {
      for (final url in ['wss://xyz.supabase.co', 'xyz.supabase.co', '']) {
        await expectLater(
          SupabaseRealtimePush.start(
            supabaseUrl: Uri.parse(url),
            publishableKey: 'k',
          ),
          throwsArgumentError,
          reason: url,
        );
      }
      expect(calls, isEmpty);
    });

    test('rejects an empty anon key', () async {
      await expectLater(
        SupabaseRealtimePush.start(
          supabaseUrl: Uri.parse('https://xyz.supabase.co'),
          publishableKey: '  ',
        ),
        throwsArgumentError,
      );
    });

    test(
      'rejects a function name that would change the request path',
      () async {
        for (final name in ['', 'a/b', '../rpc', 'x?y=1', '1abc', 'a' * 64]) {
          await expectLater(
            SupabaseRealtimePush.start(
              supabaseUrl: Uri.parse('https://xyz.supabase.co'),
              publishableKey: 'anon',
              snapshotFunction: name,
            ),
            throwsArgumentError,
            reason: name,
          );
        }
        expect(calls, isEmpty);
      },
    );

    test('still validates off Android, then does nothing', () async {
      debugDefaultTargetPlatformOverride = TargetPlatform.iOS;

      await expectLater(
        SupabaseRealtimePush.start(supabaseUrl: Uri(), publishableKey: 'k'),
        throwsArgumentError,
      );
      await SupabaseRealtimePush.start(
        supabaseUrl: Uri.parse('https://xyz.supabase.co'),
        publishableKey: 'anon',
      );
      expect(calls, isEmpty);
    });
  });

  test('stop passes forgetDevice', () async {
    await SupabaseRealtimePush.stop();
    await SupabaseRealtimePush.stop(forgetDevice: true);

    expect(calls.map((c) => c.arguments), [
      {'forgetDevice': false},
      {'forgetDevice': true},
    ]);
  });

  test('status maps the platform names, unknown ones to stopped', () async {
    for (final (name, status) in [
      ('running', PushStatus.running),
      ('rejected', PushStatus.rejected),
      ('stopped', PushStatus.stopped),
      ('something-new', PushStatus.stopped),
      (null, PushStatus.stopped),
    ]) {
      reply = (_) => name;
      expect(await SupabaseRealtimePush.status(), status, reason: '$name');
    }
  });

  test(
    'battery and permission answer false when the platform says nothing',
    () async {
      reply = (_) => null;

      expect(
        await SupabaseRealtimePush.isIgnoringBatteryOptimizations(),
        isFalse,
      );
      expect(
        await SupabaseRealtimePush.requestNotificationPermission(),
        isFalse,
      );
    },
  );

  group('taps', () {
    test('launchTap decodes the data JSON', () async {
      reply = (_) => {'id': 'chat:42', 'data': '{"route":"/chat/42","n":1}'};

      final tap = await SupabaseRealtimePush.launchTap();

      expect(tap?.id, 'chat:42');
      expect(tap?.data, {'route': '/chat/42', 'n': 1});
    });

    test('launchTap is null when the app was not opened by a tap', () async {
      reply = (_) => null;

      expect(await SupabaseRealtimePush.launchTap(), isNull);
    });

    test('a tap without data has an empty map', () async {
      reply = (_) => {'id': 'x', 'data': null};

      expect((await SupabaseRealtimePush.launchTap())?.data, isEmpty);
    });

    test(
      'taps while running reach the stream, malformed ones do not',
      () async {
        final received = <PushTap>[];
        final subscription = SupabaseRealtimePush.taps.listen(received.add);

        await tapFromAndroid({'id': 'a', 'data': '{"k":"v"}'});
        await tapFromAndroid({'data': '{}'}); // no id
        await tapFromAndroid('garbage');
        await tapFromAndroid({'id': 'b'});
        await pumpEventQueue();
        await subscription.cancel();

        expect(received.map((t) => t.id), ['a', 'b']);
        expect(received.first.data, {'k': 'v'});
      },
    );
  });

  test('off Android everything but device is a quiet no-op', () async {
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;

    expect(SupabaseRealtimePush.isSupported, isFalse);
    await SupabaseRealtimePush.stop();
    expect(await SupabaseRealtimePush.status(), PushStatus.stopped);
    expect(
      await SupabaseRealtimePush.isIgnoringBatteryOptimizations(),
      isFalse,
    );
    await SupabaseRealtimePush.requestIgnoreBatteryOptimizations();
    await SupabaseRealtimePush.openConnectedNotificationSettings();
    expect(await SupabaseRealtimePush.requestNotificationPermission(), isFalse);
    expect(await SupabaseRealtimePush.launchTap(), isNull);
    expect(calls, isEmpty);
  });
}
