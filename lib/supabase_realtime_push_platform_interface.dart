import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'supabase_realtime_push_method_channel.dart';

/// Generated platform interface; the Dart API replaces it.
abstract class SupabaseRealtimePushPlatform extends PlatformInterface {
  /// Constructs a SupabaseRealtimePushPlatform.
  SupabaseRealtimePushPlatform() : super(token: _token);

  static final Object _token = Object();

  static SupabaseRealtimePushPlatform _instance =
      MethodChannelSupabaseRealtimePush();

  /// The default instance of [SupabaseRealtimePushPlatform] to use.
  ///
  /// Defaults to [MethodChannelSupabaseRealtimePush].
  static SupabaseRealtimePushPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [SupabaseRealtimePushPlatform] when
  /// they register themselves.
  static set instance(SupabaseRealtimePushPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  /// The Android version, from the generated template.
  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
