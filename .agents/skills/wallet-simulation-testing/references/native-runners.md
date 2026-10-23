# Native runner runbook

Run commands from the selected wallet checkout. Shell variables below must be
set for this run; example IDs, paths, or ports from another session are not safe
defaults. Keep the exact commands and custom test source in private evidence.
This runbook is an operator-led recipe, not an unattended payment driver.

## Device and build isolation

Inspect `xcrun simctl list devices available`, `xcrun simctl list devicetypes`,
and `xcrun simctl list runtimes`. Create a new compatible simulator with
`xcrun simctl create "$RUN_LABEL" "$IOS_DEVICE_TYPE" "$IOS_RUNTIME"`, save its
returned UDID as `IOS_UDID`, then boot only that UDID. Set `DEVELOPER_DIR` to the
selected Xcode's `Contents/Developer`; avoid changing global `xcode-select`.

Inspect installed Android SDK images and AVDs. Create a dedicated AVD with a
compatible installed image using `avdmanager create avd --name "$AVD_NAME"
--package "$ANDROID_SYSTEM_IMAGE"`. Start it with an unused explicit port:
`emulator -avd "$AVD_NAME" -port "$EMULATOR_PORT" -no-snapshot -no-audio`.
Set `ANDROID_SERIAL` from that port and verify it with `adb -s "$ANDROID_SERIAL"
get-state`. Do not use unqualified `adb` or `simctl ... booted` with multiple devices.

Build iOS Debug with normal simulator signing so Keychain works:

```sh
xcodebuild build-for-testing -project ios/CashuWallet.xcodeproj \
  -scheme CashuWallet -configuration Debug \
  -destination "platform=iOS Simulator,id=$IOS_UDID" \
  -derivedDataPath "$RUN_PRIVATE/ios-build" \
  > "$RUN_PRIVATE/ios-build.log" 2>&1
```

Do not set `CODE_SIGNING_ALLOWED=NO`. Discover the resulting `.app`, install it
on the new device with `simctl install`, and verify its bundle ID (currently
`com.cashu.me`). On Android use the supported JDK (currently 17), `ANDROID_HOME`,
and the repo wrapper from `android/`:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
  > "$RUN_PRIVATE/android-build.log" 2>&1
adb -s "$ANDROID_SERIAL" install app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Install the main APK only as part of initial provisioning. Do not reinstall or
clear data to solve automation problems after funding. Confirm artifact paths,
application ID (currently `com.cashu.me.debug`), and target package on the selected
revision rather than assuming a different build flavor matches.

## XCTest with the real runtime

Use a narrowly selected temporary test class deriving directly from `XCTestCase`.
`UITestBase` currently sets `CI_INTEGRATION_TEST=1` and `RESET_WALLET=1`; inheriting
it unmodified can erase funded state and alter production services. Use:

```swift
import XCTest

final class WalletSimulationJourney: XCTestCase {
    func testObservedJourney() throws {
        continueAfterFailure = false
        executionTimeAllowance = 300
        let app = XCUIApplication(bundleIdentifier: "com.cashu.me")
        app.launchEnvironment = ["UITEST_DISABLE_ANIMATIONS": "1"]
        app.launch() // no reset, seed, placeholder mint, or deterministic CI mode
        // Execute only the current scenario's actions, based on observed state.
        // Before each action, select one existing hittable element explicitly.
        // After each action, assert the expected state and capture evidence.
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.lifetime = .keepAlways
        add(screenshot)
        let tree = XCTAttachment(string: app.debugDescription)
        tree.lifetime = .keepAlways
        add(tree)
    }
}
```

The snippet is a preserving launch/evidence harness, not an executed financial
scenario. Add the observed steps and assertions to it. Put the class in the UI
test target: append to an already compiled test source temporarily or explicitly
add a new file to the Xcode project; a new Swift file is not always discovered.
Save a patch/private backup and do not overwrite unrelated user edits.

Run only that class/method, serially, using the products built with the driver:

```sh
xcodebuild test-without-building -project ios/CashuWallet.xcodeproj \
  -scheme CashuWallet -destination "platform=iOS Simulator,id=$IOS_UDID" \
  -derivedDataPath "$RUN_PRIVATE/ios-build" \
  -only-testing:CashuWalletUITests/WalletSimulationJourney/testObservedJourney \
  -parallel-testing-enabled NO -resultBundlePath "$RUN_PRIVATE/step.xcresult" \
  > "$RUN_PRIVATE/ios-runner.log" 2>&1
```

Use a unique result bundle for each invocation. `app.terminate(); app.launch()`
preserves data with the same non-reset environment. Animated welcome/QR screens
can prevent XCTest idling; the animation flag addresses this without disabling
recovery. Handle OS paste permission explicitly with an authorized UI tool or
an XCTest interruption handler; a blocked permission is not a failed payment.

## Android UI Automator with the real container

The repository's `CashuUiTestRunner` creates `UiTestApplication`, which does not
automatically install the application container. A custom native journey should
install the default production dependencies, not a fake gateway or fixture:

```kotlin
package com.cashu.me

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.cashu.me.App.AppContainer
import com.cashu.me.App.MainActivity
import com.cashu.me.App.UiTestApplication
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletSimulationJourney {
    @Test fun observedJourney() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext.applicationContext as UiTestApplication
        app.installContainer(AppContainer(app))
        ActivityScenario.launch(MainActivity::class.java).use {
            val device = UiDevice.getInstance(inst)
            // Add the observed scenario's steps and assertions here.
            // Use By.res/By.desc/By.text with a bounded Until.findObject wait.
            // Require a unique match and check Boolean action results.
            val evidence = File(app.filesDir, "simulation-evidence").apply { mkdirs() }
            device.dumpWindowHierarchy(File(evidence, "step.xml"))
            check(device.takeScreenshot(File(evidence, "step.png")))
        }
    }
}
```

Save under `android/app/src/androidTest/java/com/cashu/me/`, build/install only
the driver test APK after changes, and select precisely that test. Avoid the
Gradle connected suite/Orchestrator on funded wallets: the build config defaults
`clearPackageData=true`. Direct instrumentation with an explicit false override:

```sh
adb -s "$ANDROID_SERIAL" shell am instrument -w \
  -e class com.cashu.me.WalletSimulationJourney#observedJourney \
  -e clearPackageData false \
  com.cashu.me.debug.test/com.cashu.me.test.CashuUiTestRunner \
  > "$RUN_PRIVATE/android-runner.log" 2>&1
```

Export evidence via `adb -s "$ANDROID_SERIAL" exec-out run-as
com.cashu.me.debug cat files/simulation-evidence/step.xml` into private files.
Use unique step filenames in a multi-step test. Keep ordinary production
recovery/polling enabled; restarting this runner terminates the test process,
so record interruption and eventual recovery separately.

## Interactive operation and snapshots

For long operator-driven sessions, adapt the harness into a bounded command loop
if needed. A host bridge must bind only loopback, use a per-run private mailbox,
accept one uniquely identified command at a time, reject duplicate IDs, and
persist acknowledgements/evidence. No auto-retry of financial actions. Treat a
lost acknowledgement as unknown until inspected; test timeout can occur after
a payment succeeded. Do not expose a payment-control endpoint on the LAN.
Validate the bridge with read-only dumps before authorizing commands that spend.

Prefer stable accessibility identifiers. Resolve duplicate labels; check action
return values; observe again after animations. Use coordinates only from a fresh
native screenshot at the current device size. If ADB goes offline or the tree
is empty, repair/restart only the owned runner before diagnosing a wallet hang.

For SQLite evidence, discover the active database path from current source/app
container and verify schema with `PRAGMA table_info`; do not assume a historical
path. On iOS, obtain the data container with `simctl get_app_container "$IOS_UDID"
com.cashu.me data` and use SQLite's read-only connection plus backup API. On
Android, quiesce the owned app/process at a documented point before copying the
main database and its matching WAL/SHM through `run-as`; copying just the main
file while writes continue can omit settled transactions. If interruption would
affect the scenario, implement an in-process consistent backup instead. Retain
all copied sidecars together, then verify `PRAGMA integrity_check` on the copy.

Run `python3 .agents/skills/wallet-simulation-testing/scripts/audit_snapshot.py
"$SNAPSHOT_DB" --output "$SUMMARY_JSON"` on the consistent copy. It exports
balances and selected transaction/quote/saga fields,
not the proof secrets, invoices, preimages, or saga payload. It is evidence for
durable state, not proof that two snapshots are coherent or that the mint settled.
Preserve `.xcresult`, driver sources, build logs, UI trees and screenshots
privately; those artifacts can contain recovery phrases or bearer tokens.
