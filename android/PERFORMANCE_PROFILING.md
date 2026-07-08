# Android Performance Profiling

Use these Gradle targets after installing or creating a disposable test wallet on the benchmark device:

```sh
cd android
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :macrobenchmark:connectedCheck
```

For local emulator/debug smoke checks, build through Gradle and run the self-instrumenting benchmark APK directly so the local run can opt into emulator/debug suppressions and skip profile compilation:

```sh
cd android
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:assembleDebug :macrobenchmark:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r macrobenchmark/build/outputs/apk/debug/macrobenchmark-debug.apk
adb shell am instrument -w \
  -e targetPackage com.jcashu.wallet.debug \
  -e compilationMode none \
  -e androidx.benchmark.suppressErrors EMULATOR,DEBUGGABLE \
  org.cashu.wallet.benchmark/androidx.test.runner.AndroidJUnitRunner
```

The macrobenchmark suite measures cold startup, Settings open/scroll, Settings privacy toggles, and Home/History/Mints list scrolling with `StartupTimingMetric` and `FrameTimingMetric`. Release-device runs should leave the default `compilationMode` as `Partial`; the `none` mode above is only for local debug smoke where ProfileInstaller/profile compilation can distort or block results.

Debug builds also install a JankStats listener in `MainActivity` and debug-only recomposition probes in Home and Settings. While exercising those screens, capture aggregate signals with:

```sh
adb logcat -s CashuWallet.UI
```

Keep benchmark screenshots, traces, and device names out of commits and PRs. Record only aggregate frame timing and jank-rate observations in release notes or the update plan.
