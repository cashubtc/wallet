# Capturing takes

A **take** is one scripted run of the real app, recorded with `simctl`. The
edit never adds UI, so everything the film shows has to happen in a take.
Read this whole file before you script a new one; most of it is traps that
already cost hours.

## Contents

- [One-time setup](#one-time-setup)
- [How a take runs](#how-a-take-runs)
- [Writing a take](#writing-a-take)
- [Session scripts](#session-scripts)
- [Simulator facts and traps](#simulator-facts-and-traps)
- [Cleanup](#cleanup)

## One-time setup

Run everything from `.agents/skills/cashu-promo-video/tool/`.

```sh
capture/sim.sh              # find or create the "Cashu Promo" iPhone 17 Pro simulator
capture/mint.sh start       # local FakeWallet mint, "Local test mint", on 127.0.0.1:3340
capture/worktree.sh         # throwaway app worktree with the capture harness installed
capture/build.sh            # Release build of the app + UI-test runner, ad hoc signed
defaults write com.apple.iphonesimulator PasteboardAutomaticSync -bool false   # see "Clipboard"
```

- **`sim.sh`:** every script targets the simulator by UDID (resolved from
  `SIM_NAME`, default "Cashu Promo"), never `booted`, because other sessions
  keep their own simulators booted. Set `SIM_NAME` or `UDID` to use another
  device.
- **`mint.sh start|stop|status`:** runs `mint/config.toml` with the repo's own
  `CI/.cdk-bin/cdk-mintd` (fetched by `CI/setup-cdk.sh` if it's missing;
  override it with `CDK_MINTD`). The state lives in `mint/work/`. `start`
  refuses if something already serves port 3340. The mint is local and
  deterministic, and its sats are test sats. Testnut works too, but it was
  down for a whole day mid-project.
- **`worktree.sh`:** creates a detached worktree of the repo's HEAD next to
  the repo (`CAPTURE_WORKTREE`). It copies `capture/PromoTakes.swift` over
  `ios/CashuWalletUITests/MainTabUITests.swift` there, a file the Xcode
  project already registers, so no project edits are needed. That worktree
  is throwaway: never commit it or push from it. Re-run `worktree.sh` after
  editing `PromoTakes.swift`, and again after the app changes.
- **`build.sh`:** signs ad hoc (`CODE_SIGN_IDENTITY=-`). With
  `CODE_SIGNING_ALLOWED=NO` the keychain entitlement is missing and the wallet
  fails with -34018.

`capture/prep-sim.sh` erases the simulator back to factory state (Apple's
empty demo Messages threads, no wallet, no keychain). It then applies what
every take assumes: en_US, a 12-hour clock (9:41, not 09:41), and a status
bar at 9:41, full battery and full bars.

## How a take runs

```sh
capture/take.sh <testName> [--record] [--fresh] [KEY=VALUE …]    # APPEARANCE defaults to dark
```

- **`--fresh`:** wipes the wallet first (keychain reset and reinstall). An
  uninstall alone keeps the seed in the keychain, so onboarding is skipped.
- **`--record`:** runs `simctl io recordVideo --codec=h264` for the whole test
  and stamps the recorder's start (`take-start.txt`) so marks map onto the
  video.
- **`KEY=VALUE`:** forwarded to the test as `TEST_RUNNER_KEY`. `DRY=1` saves a
  screenshot at each `dryStill(...)` checkpoint, for checking a new take
  without watching the video.
- **`ALLOW_PASTE=1`** (as an environment variable): after a fresh install,
  sets Cashu's "Paste from Other Apps: Allow" (see
  [the paste trap](#simulator-facts-and-traps)).

The output lands in `takes/<test>-<appearance>/<stamp>/`: `take.mp4`,
`<test>-marks.json`, `xcodebuild.log` and any stills. `take.sh` stops a hung
xcodebuild 30 s after the suite's last line.

Convert a take to constant frame rate before cutting (the analysis does this
itself as `take-60.mp4`). `recordVideo` writes a frame only when pixels change,
and its timestamps are wall-clock accurate, so `ffmpeg -vf fps=60` (frames
held, no renumbering) gives frame i = take time i/60.

## Writing a take

Add a test to `capture/PromoTakes.swift`. Model it on `testSendChat` (one
phone) or on `testShare` + `testClaim` (two phones).

- **Launch like a user.** `PromoTakes` deliberately doesn't subclass the
  repo's `UITestBase`. That base sets `UITEST_DISABLE_ANIMATIONS=1`, which
  films a motionless app, and `CI_INTEGRATION_TEST=1`, which disables payment
  services.
- **XCUITest's idle wait is switched off** (`Quiescence.disable()`), because
  the onboarding field and the Receive sheet never go idle. Pace the take with
  `hold(seconds)` instead, and leave 1–2.5 s of stillness around each moment
  the film will use. Holds are free: cuts remove them.
- **Freeze the field** with `launch(["ASCII_FIELD_STATIC_TIME": "2.5"])` in
  every take that doesn't film the welcome. XCTest's taps turn on
  accessibility drawing annotation, which stalls the app's ~2,000-glyph field
  for 0.3–5 s. The backdrop the film draws is its own, so a frozen in-app
  field costs nothing.
- **Fund with `testFund`** (unrecorded): it's a fresh wallet on the local mint
  holding 5,000 sat. Record the feature in a second take that starts from the
  funded home.
- **Mark intents** with `mark("label")`, and use `tap(element, "label")`,
  which marks `tap:label`. Marks are XCTest's intent, not the touch: resolving
  an element can delay the real tap by up to a few seconds. The edit trusts
  pixels, not marks (see `cutting.md`).
- **Type on the app's keypad with `keypad("2500")`.** It resolves the keys
  once and taps by coordinate. Resolving per tap costs about 1 s and films as
  one digit per second.
- **Use the app's own controls,** the way a person would. Paste with Cashu's
  **Paste** button (`app.buttons["Paste from clipboard"]`), not a long-press
  on the field. Other apps are different: Messages has no paste button, so
  long-press its composer and tap the edit menu's **Paste**. The keyboard's
  "Paste from …" suggestion is a secure control that ignores synthesized taps.
- **Take one clean, continuous run.** Retake rather than patching in the
  edit.

## Session scripts

A session chains the unrecorded setup and the recorded take(s) for one film:

| Script | What it does |
|---|---|
| `capture/send-session.sh` | Erase the sim, `testFund --fresh`, then `testSendChat --record` |
| `capture/restore-session.sh` | `testFund --fresh`; enroll a Simulator face and answer Face ID; then `testRestoreFlow --record` |
| `capture/share-session.sh` | Erase the sim (so Kate Bell's thread starts empty), `testFund --fresh`, then record `testShare` (phone A). Check the pasteboard holds a token, then record `ALLOW_PASTE=1 testClaim --fresh` (phone B). |

All three default to dark and accept `DRY=1`. Write a new session script for
a new film, rather than running takes by hand, so a retake is one command.

`capture/render.sh` re-renders and QAs the three reference films. It only
works on a machine that has their takes, after a re-shoot and re-cut.

## Simulator facts and traps

**Messages**
- **No group chats.** The Simulator can only send in its two demo threads,
  John Appleseed at +1 (888) 555-1212 and Kate Bell at +1 (555) 564-8583.
  Any new thread, group or not, even one addressed by the demo contacts'
  emails, comes up as SMS, which the Simulator can't send. The send film uses
  John's thread; the chat film uses Kate's. Third-party chat apps aren't
  possible.
- **Sent messages live only in memory.** `simctl terminate com.apple.MobileSMS`
  empties the thread, so never quit Messages between a take that sends and a
  take that reads the bubble.
- A freshly erased Simulator shows a QuickPath tip over the keyboard the first
  time it appears. `dismissKeyboardTip` handles it.

**Face ID**
- **Reveal and Copy of the recovery phrase ask the device owner.**
  `restore-session.sh` enrolls a face
  (`notifyutil -p com.apple.BiometricKit.enrollmentChanged`). The take drops
  a `faceid-request` file (`requestFaceMatch()`), and the host answers it
  with `notifyutil -p com.apple.BiometricKit_Sim.pearl.match`.
- **The Face ID HUD has no glyph on the iOS 27 Simulator.** It's a flat grey
  box with the words "Face ID". The runtime's `face-id-spinner.ca` package
  lacks its scene. Never imitate the real animation. Cut around the box
  (find its frames with `scripts/region_luma.py`).

**No NFC, no camera**
- **The Send sheet's Tap row reads "Unavailable"** (dimmed), because the
  Simulator has no NFC reader. A phone shows it enabled. It's real
  Simulator UI, so it can't be painted over; keep the Send sheet's time on
  screen short.
- **Tap-to-pay and QR scanning can't be filmed at all.** Their UI is the
  system NFC sheet and the camera. Pick features the Simulator can really
  run.

**Clipboard**
- **Cashu's Paste button reads the pasteboard in code,** so iOS asks "Allow
  Paste?" for text from another app. With the permission revoked, the read
  just comes back empty. `capture/allow-paste.sh` writes "Paste from Other
  Apps: Allow" (TCC `kTCCServicePasteboard`) and restarts `tccd`. An
  uninstall clears it again, so `take.sh` re-applies it after `--fresh` when
  `ALLOW_PASTE=1`.
- **Allowed pastes show iOS's banner, "Cashu pasted from <source>".** Text put
  there with `simctl pbcopy` reads "…from CoreSimulatorBridge", which is
  wrong on camera. Copy the text inside the Simulator from the app it really
  comes from (`copyTokenFromMessages()` in `testClaim`).
- **The Simulator pasteboard can carry the host's clipboard** (CoreSimulator
  sync), so a take could paste whatever you last copied on your Mac. Before
  a capture session, turn that sync off yourself; no script does it:
  `defaults write com.apple.iphonesimulator PasteboardAutomaticSync -bool false`.
  Turn it back on at cleanup. Never print the pasteboard into a log or a
  transcript, and validate a captured token by its `cashu` prefix only.

**Rendering and timing**
- **Capture on an idle Mac.** Under heavy CPU load (a compile, an analysis
  run, an Android emulator) taps land about 3 s late and the app stalls.
- **The live field only ticks while DeviceHub shows the device.** Leave the
  Simulator window visible for any take that films the welcome's live field.
- **Taps turn on accessibility annotation** (see "freeze the field" above).
  For a take that must film the live field, drive it with raw HID touches
  (AXe's `axe touch --down --up`, which skips accessibility) instead of
  XCTest.
- **A launch from another app skews the launch offset.** `Analyze.launchOffset`
  calibrates marks against the Create tap. When the app launches from Messages
  just before Create (as in `testClaim`), the launch lands in its window and
  the offset comes out about 4 s early. Anchor that take's analysis on pixels
  (see `Analyze.claim`).

**Wallet state**
- **`CI_INTEGRATION_TEST=1` turns off payment services.** Takes launch the
  production path.
- **Every demo wallet publishes an encrypted mint-list backup to public Nostr
  relays** (Automatic mint backup is on by default). That makes "Find my
  mints" work. Use throwaway seeds only; each backup holds only the local
  mint's address.

## Cleanup

When the films are delivered:

```sh
capture/mint.sh stop
defaults write com.apple.iphonesimulator PasteboardAutomaticSync -bool true
git -C "$REPO_ROOT" worktree remove --force "$CAPTURE_WORKTREE"
```

Delete `takes/` when you no longer need to re-cut. It's gitignored and large.
