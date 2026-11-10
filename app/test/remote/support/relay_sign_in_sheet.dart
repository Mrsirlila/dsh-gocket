// The merge with DSH Pocket put the DSH Remote sign-in providers in a sheet
// opened from the hosts group's 「登录 DSH Remote」 tile. Tests written against
// the old inline card open that sheet first with this helper.

import 'package:dsh_app/l10n/l10n.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// Pumps eight 60ms frames: the sheet's entrance and the provider list.
Future<void> _frames(WidgetTester tester) async {
  for (var i = 0; i < 8; i++) {
    await tester.pump(const Duration(milliseconds: 60));
  }
}

/// Opens the sign-in sheet unless it is already open.
///
/// Closed, only the group's tile carries the title; open, the sheet's card
/// carries a second one.
Future<void> ensureRelaySignInSheet(WidgetTester tester) async {
  if (find.text(appL10n.remoteRelaySignInTitle).evaluate().length > 1) return;
  final tile = find.byKey(const ValueKey('remote-relay-sign-in'));
  expect(tile, findsOneWidget, reason: 'the hosts group keeps the sign-in tile');
  await tester.tap(tile);
  await _frames(tester);
}
