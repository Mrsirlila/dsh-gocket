import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The direct root invocation interface (MethodChannel `dsh/root`,
/// RootPlugin.kt): `exec` runs `su -c` on the device and is refused natively
/// until the person enabled it on 设置 › AI › ROOT权限.
abstract final class RootAccess {
  static const _channel = MethodChannel('dsh/root');

  /// Whether an `su` binary exists; no root prompt is triggered.
  static Future<bool> available() async => await _channel.invokeMethod<bool>('available') ?? false;

  static Future<bool> isEnabled() async => await _channel.invokeMethod<bool>('isEnabled') ?? false;

  static Future<void> setEnabled(bool enabled) => _channel.invokeMethod<void>('setEnabled', {'enabled': enabled});

  /// Runs [command] through `su -c`; the result merges stderr so failures are
  /// visible. Throws [PlatformException] `disabled` while the switch is off.
  static Future<RootExecResult> exec(String command) async {
    final map = await _channel.invokeMapMethod<String, dynamic>('exec', {'command': command});
    return RootExecResult(code: map?['code'] as int? ?? -1, output: map?['output'] as String? ?? '');
  }
}

class RootExecResult {
  const RootExecResult({required this.code, required this.output});

  final int code;
  final String output;
}

/// Whether the device offers an `su` binary; re-pumped on the page.
final rootSuAvailableProvider = FutureProvider<bool>((_) => RootAccess.available());
