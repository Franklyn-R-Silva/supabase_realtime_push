import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'supabase_realtime_push_platform_interface.dart';

/// An implementation of [SupabaseRealtimePushPlatform] that uses method channels.
class MethodChannelSupabaseRealtimePush extends SupabaseRealtimePushPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('supabase_realtime_push');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>(
      'getPlatformVersion',
    );
    return version;
  }
}
