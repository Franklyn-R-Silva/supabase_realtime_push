// package:material_ui needs Flutter 3.47; the example still supports 3.32.
// ignore: deprecated_member_use
import 'package:flutter/material.dart';
import 'package:supabase_realtime_push/supabase_realtime_push.dart';

// Run with: flutter run --dart-define-from-file=.env (see .env.example).
const _supabaseUrl = String.fromEnvironment('SUPABASE_URL');
const _anonKey = String.fromEnvironment('SUPABASE_ANON_KEY');

void main() => runApp(const MaterialApp(home: PushDemo()));

/// Shows the service's state and the steps an app goes through.
class PushDemo extends StatefulWidget {
  /// Creates the demo screen.
  const PushDemo({super.key});

  @override
  State<PushDemo> createState() => _PushDemoState();
}

class _PushDemoState extends State<PushDemo> {
  PushDevice? _device;
  PushStatus _status = PushStatus.stopped;
  bool _exempt = false;
  String _lastTap = '-';

  @override
  void initState() {
    super.initState();
    SupabaseRealtimePush.taps.listen((tap) => _showTap(tap));
    SupabaseRealtimePush.launchTap().then((tap) {
      if (tap != null) _showTap(tap);
    });
    _refresh();
  }

  void _showTap(PushTap tap) {
    if (mounted) setState(() => _lastTap = '${tap.id} ${tap.data}');
  }

  Future<void> _refresh() async {
    if (!SupabaseRealtimePush.isSupported) return;
    final device = await SupabaseRealtimePush.device();
    final status = await SupabaseRealtimePush.status();
    final exempt = await SupabaseRealtimePush.isIgnoringBatteryOptimizations();
    if (!mounted) return;
    setState(() {
      _device = device;
      _status = status;
      _exempt = exempt;
    });
  }

  Future<void> _start() async {
    await SupabaseRealtimePush.requestNotificationPermission();
    await SupabaseRealtimePush.start(
      supabaseUrl: Uri.parse(_supabaseUrl),
      anonKey: _anonKey,
    );
    await _refresh();
  }

  Future<void> _stop() async {
    await SupabaseRealtimePush.stop();
    await _refresh();
  }

  Future<void> _askExemption() async {
    await SupabaseRealtimePush.requestIgnoreBatteryOptimizations();
    await _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final configured = _supabaseUrl.isNotEmpty && _anonKey.isNotEmpty;
    return Scaffold(
      appBar: AppBar(title: const Text('supabase_realtime_push')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (!SupabaseRealtimePush.isSupported)
            const Text('This plugin runs on Android only.')
          else ...[
            if (!configured)
              const Text(
                'Set SUPABASE_URL and SUPABASE_ANON_KEY: '
                'flutter run --dart-define-from-file=.env',
              ),
            ListTile(title: const Text('Status'), subtitle: Text(_status.name)),
            ListTile(
              title: const Text('Device topic'),
              subtitle: SelectableText(_device?.topic ?? '-'),
            ),
            const ListTile(
              title: Text('1. Register the device'),
              subtitle: Text(
                'Signed in, call realtime_push_register with '
                'device.registerParams from your own Supabase client.',
              ),
            ),
            ListTile(
              title: const Text('Battery exemption'),
              subtitle: Text(
                _exempt
                    ? 'Granted: no "connected" notification'
                    : 'Not granted: Android shows a "connected" notification',
              ),
              trailing: _exempt
                  ? null
                  : TextButton(
                      onPressed: _askExemption,
                      child: const Text('Ask'),
                    ),
            ),
            ListTile(title: const Text('Last tap'), subtitle: Text(_lastTap)),
            const SizedBox(height: 8),
            FilledButton(
              onPressed: configured ? _start : null,
              child: const Text('2. Start'),
            ),
            const SizedBox(height: 8),
            OutlinedButton(onPressed: _stop, child: const Text('Stop')),
          ],
        ],
      ),
    );
  }
}
