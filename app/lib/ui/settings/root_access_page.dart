import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../state/root_access.dart';
import '../common/risk_notice.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';
import 'settings_widgets.dart';

/// 设置 › AI › ROOT权限: the switch that unlocks the app's direct root
/// invocation (RootAccess.exec). Turning it on always asks again
/// (RiskLevel.high); turning it off is immediate.
class RootAccessPage extends ConsumerStatefulWidget {
  const RootAccessPage({super.key});

  @override
  ConsumerState<RootAccessPage> createState() => _RootAccessPageState();
}

class _RootAccessPageState extends ConsumerState<RootAccessPage> {
  bool? _enabled;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    unawaited(
      RootAccess.isEnabled().then((v) {
        if (mounted) setState(() => _enabled = v);
      }),
    );
  }

  Future<void> _toggle(bool next) async {
    if (_busy) return;
    if (next && !await confirmRisk(context, RiskNotices.rootAccess) || !mounted) return;
    setState(() => _busy = true);
    try {
      await RootAccess.setEnabled(next);
      if (mounted) setState(() => _enabled = next);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    final su = ref.watch(rootSuAvailableProvider).value;
    final enabled = _enabled ?? false;
    return AppPage.list(
      title: l10n.rootAccessTitle,
      children: [
        SettingsSection(
          children: [
            SettingsSwitchTile(
              key: const ValueKey('root-access-toggle'),
              title: l10n.hubRootAccess,
              subtitle: su == null ? null : (su ? l10n.rootAccessSuOk : l10n.rootAccessSuMissing),
              value: enabled,
              onChanged: _enabled == null || _busy || su == false ? null : _toggle,
            ),
          ],
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 12, 24, 0),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(AppIcons.warning, size: 18, color: colors.warning),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  l10n.rootAccessWarning,
                  style: theme.textTheme.bodySmall?.copyWith(color: colors.warning),
                ),
              ),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 0),
          child: Text(
            l10n.rootAccessEnabledHint,
            style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
          ),
        ),
      ],
    );
  }
}
