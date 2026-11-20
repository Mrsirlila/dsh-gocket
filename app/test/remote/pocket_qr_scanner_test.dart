// The dsh-pocket QR scanner gates both decoders (camera and gallery photo) on
// the shared ML Kit initialization, like the pairing scanner's (see
// pairing_scanner_test.dart for the full channel/platform mock).

import 'dart:async';

import 'package:dsh_app/ui/remote/pocket_qr_scanner.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../chat/support/harness.dart';

class _Scanner extends MobileScannerPlatform {
  final captures = StreamController<BarcodeCapture?>.broadcast();
  int starts = 0;

  @override
  Stream<BarcodeCapture?> get barcodesStream => captures.stream;
  @override
  Stream<TorchState> get torchStateStream => const Stream.empty();
  @override
  Stream<double> get zoomScaleStateStream => const Stream.empty();

  @override
  Future<MobileScannerViewAttributes> start(StartOptions options) async {
    starts++;
    return const MobileScannerViewAttributes(
      cameraDirection: CameraFacing.back,
      currentTorchMode: TorchState.off,
      size: Size(640, 480),
    );
  }

  @override
  Widget buildCameraView() => const ColoredBox(color: Colors.black);
  @override
  Future<void> stop() async {}
  @override
  Future<void> dispose() async {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const appChannel = MethodChannel('dsh/app');
  const photoChannel = MethodChannel('plugins.flutter.io/image_picker');
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late MobileScannerPlatform original;
  late _Scanner scanner;
  late Completer<void> initialized;
  late int preparations;
  late bool initializeFails;

  setUp(() {
    expect(defaultTargetPlatform, TargetPlatform.android);
    original = MobileScannerPlatform.instance;
    scanner = _Scanner();
    MobileScannerPlatform.instance = scanner;
    initialized = Completer<void>();
    preparations = 0;
    initializeFails = false;
    messenger.setMockMethodCallHandler(appChannel, (call) {
      expect(call.method, 'initializePairingScanner');
      preparations++;
      if (initializeFails) throw PlatformException(code: 'scanner_initialization_failed');
      return initialized.future;
    });
    messenger.setMockMethodCallHandler(photoChannel, (call) async {
      expect(call.method, 'pickImage');
      return '/pocket-code.png';
    });
  });

  tearDown(() async {
    MobileScannerPlatform.instance = original;
    messenger.setMockMethodCallHandler(appChannel, null);
    messenger.setMockMethodCallHandler(photoChannel, null);
    await scanner.captures.close();
  });

  testWidgets('the camera waits for ML Kit initialization before starting', (tester) async {
    usePhoneView(tester);
    await tester.pumpWidget(testApp(const PocketQrScannerPage()));
    await tester.pump();
    expect(preparations, 1);
    expect(find.byType(MobileScanner), findsNothing);
    expect(scanner.starts, 0);

    initialized.complete();
    await tester.runAsync(() async {});
    await tester.pump();
    expect(find.byType(MobileScanner), findsOneWidget);
    expect(scanner.starts, 1);
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    expect(tester.takeException(), isNull);
  });

  testWidgets('initialization failure shows the scanner-unavailable message', (tester) async {
    usePhoneView(tester);
    initializeFails = true;
    await tester.pumpWidget(testApp(const PocketQrScannerPage()));
    await tester.pump();
    await tester.pump();
    expect(find.textContaining('二维码识别器不可用'), findsWidgets);
    expect(find.byType(MobileScanner), findsNothing);
    expect(scanner.starts, 0);
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    expect(tester.takeException(), isNull);
  });
}
