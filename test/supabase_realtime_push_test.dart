import 'package:flutter_test/flutter_test.dart';
import 'package:supabase_realtime_push/supabase_realtime_push.dart';
import 'package:supabase_realtime_push/supabase_realtime_push_platform_interface.dart';
import 'package:supabase_realtime_push/supabase_realtime_push_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockSupabaseRealtimePushPlatform
    with MockPlatformInterfaceMixin
    implements SupabaseRealtimePushPlatform {
  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  final SupabaseRealtimePushPlatform initialPlatform =
      SupabaseRealtimePushPlatform.instance;

  test('$MethodChannelSupabaseRealtimePush is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelSupabaseRealtimePush>());
  });

  test('getPlatformVersion', () async {
    final SupabaseRealtimePush supabaseRealtimePushPlugin =
        SupabaseRealtimePush();
    final MockSupabaseRealtimePushPlatform fakePlatform =
        MockSupabaseRealtimePushPlatform();
    SupabaseRealtimePushPlatform.instance = fakePlatform;

    expect(await supabaseRealtimePushPlugin.getPlatformVersion(), '42');
  });
}
