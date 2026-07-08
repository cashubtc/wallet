# Android Performance Profiling

Use these Gradle targets after installing or creating a disposable test wallet on the benchmark device:

```sh
cd android
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :macrobenchmark:connectedCheck
```

The macrobenchmark suite measures cold startup, Settings open/scroll, Settings privacy toggles, and Home/History/Mints list scrolling with `StartupTimingMetric` and `FrameTimingMetric`. Debug builds also install a JankStats listener in `MainActivity` so local Android Studio profiling can correlate janky frames while scrolling Settings, Home, History, Mints, scanner, Send, and Receive.

Keep benchmark screenshots, traces, and device names out of commits and PRs. Record only aggregate frame timing and jank-rate observations in release notes or the update plan.
