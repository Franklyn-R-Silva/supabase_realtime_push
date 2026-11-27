// package:material_ui needs Flutter 3.47; the example still supports 3.35.
// ignore: deprecated_member_use
import 'package:flutter/material.dart';
import 'package:supabase_flutter/supabase_flutter.dart';
import 'package:supabase_realtime_push/supabase_realtime_push.dart';

// Run with: flutter run --dart-define-from-file=.env (see .env.example).
const _supabaseUrl = String.fromEnvironment('SUPABASE_URL');
const _publishableKey = String.fromEnvironment('SUPABASE_PUBLISHABLE_KEY');
const _configured = _supabaseUrl != '' && _publishableKey != '';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  if (_configured) {
    await Supabase.initialize(
      url: _supabaseUrl,
      publishableKey: _publishableKey,
    );
  }
  runApp(const MaterialApp(home: PushDemo()));
}

/// Signs in, registers the device, starts the service and shows its state.
class PushDemo extends StatefulWidget {
  /// Creates the demo screen.
  const PushDemo({super.key});

  @override
  State<PushDemo> createState() => _PushDemoState();
}

class _PushDemoState extends State<PushDemo> {
  final _email = TextEditingController();
  final _password = TextEditingController();
  PushStatus _status = PushStatus.stopped;
  bool _exempt = false;
  String _lastTap = '-';
  String? _error;

  SupabaseClient get _supabase => Supabase.instance.client;

  bool get _signedIn => _configured && _supabase.auth.currentSession != null;

  @override
  void initState() {
    super.initState();
    if (!_configured || !SupabaseRealtimePush.isSupported) return;
    _supabase.auth.onAuthStateChange.listen((state) async {
      // Also covers sessions that end without the button (revoked, password
      // changed): the next person on this phone must not get these.
      if (state.event == AuthChangeEvent.signedOut) {
        await SupabaseRealtimePush.stop(forgetDevice: true);
      }
      await _refresh();
    });
    SupabaseRealtimePush.taps.listen(_showTap);
    SupabaseRealtimePush.launchTap().then((tap) {
      if (tap != null) _showTap(tap);
    });
    _refresh();
  }

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  void _showTap(PushTap tap) {
    if (mounted) setState(() => _lastTap = '${tap.id} ${tap.data}');
  }

  Future<void> _refresh() async {
    final status = await SupabaseRealtimePush.status();
    final exempt = await SupabaseRealtimePush.isIgnoringBatteryOptimizations();
    if (!mounted) return;
    setState(() {
      _status = status;
      _exempt = exempt;
    });
  }

  // Shows the error instead of losing it in the console.
  Future<void> _run(Future<void> Function() action) async {
    setState(() => _error = null);
    try {
      await action();
    } on Object catch (e) {
      if (mounted) setState(() => _error = '$e');
    }
    await _refresh();
  }

  Future<void> _signIn() => _run(() async {
    await _supabase.auth.signInWithPassword(
      email: _email.text.trim(),
      password: _password.text,
    );
  });

  // The order matters: the database must know the device before the service
  // asks it for a snapshot, or the first answer is `rejected`.
  Future<void> _turnOn() => _run(() async {
    await SupabaseRealtimePush.requestNotificationPermission();
    final device = await SupabaseRealtimePush.device();
    await _supabase.rpc<void>(
      'realtime_push_register',
      params: device.registerParams,
    );
    await SupabaseRealtimePush.start(
      supabaseUrl: Uri.parse(_supabaseUrl),
      publishableKey: _publishableKey,
    );
  });

  // Unregister while the session is still valid, then forget the pair so the
  // next user of this phone gets a new one.
  Future<void> _signOut() => _run(() async {
    final device = await SupabaseRealtimePush.device();
    try {
      await _supabase.rpc<void>(
        'realtime_push_unregister',
        params: device.unregisterParams,
      );
    } on Object {
      // Offline: sign out anyway. The forgotten pair can no longer read
      // anything.
    }
    await SupabaseRealtimePush.stop(forgetDevice: true);
    await _supabase.auth.signOut();
  });

  Future<void> _askExemption() =>
      _run(SupabaseRealtimePush.requestIgnoreBatteryOptimizations);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('supabase_realtime_push')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (!SupabaseRealtimePush.isSupported)
            const Text('This plugin runs on Android only.')
          else if (!_configured)
            const Text(
              'Copy .env.example to .env, fill it in and run '
              'flutter run --dart-define-from-file=.env',
            )
          else if (!_signedIn) ...[
            TextField(
              controller: _email,
              decoration: const InputDecoration(labelText: 'Email'),
              keyboardType: TextInputType.emailAddress,
            ),
            TextField(
              controller: _password,
              decoration: const InputDecoration(labelText: 'Password'),
              obscureText: true,
            ),
            const SizedBox(height: 16),
            FilledButton(onPressed: _signIn, child: const Text('Sign in')),
          ] else ...[
            ListTile(
              title: const Text('User id (for realtime_push_send)'),
              subtitle: SelectableText(_supabase.auth.currentUser?.id ?? '-'),
            ),
            ListTile(
              title: const Text('Service'),
              subtitle: Text(_status.name),
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
              onPressed: _status == PushStatus.running ? null : _turnOn,
              child: const Text('Turn on notifications'),
            ),
            const SizedBox(height: 8),
            OutlinedButton(onPressed: _signOut, child: const Text('Sign out')),
          ],
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 16),
              child: Text(
                _error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
        ],
      ),
    );
  }
}
