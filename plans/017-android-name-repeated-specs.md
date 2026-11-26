# 017 — Name Android's repeated fade specs in CashuMotion

- **Status**: DONE
- **Commit**: e61dcb3c (`main`; the audit ran on a branch at 18ba3c89, one PR behind)
- **Severity**: LOW
- **Category**: Cohesion & tokens
- **Estimated scope**: 1 file extended, about 30 files touched by a mechanical replace; no visible change

## Problem

`android/app/src/main/java/com/cashu/me/ui/theme/Motion.kt` already holds the
app's choreography constants (`CashuMotion`) and a `fadeThrough()` helper, and
`docs/android/DESIGN-ANDROID.md` says new motion should use springs or
motion-scheme tokens, "not hand-tuned tweens". Two recipes are nevertheless
typed out at every call site. Counted under
`android/app/src/main/java/com/cashu/me`:

| Typed at call sites | Count | What it is |
| --- | --- | --- |
| `spring(stiffness = Spring.StiffnessMedium)` | 53 | the quiet cross-fade used for amounts, titles, hints, header icons |
| `tween(200)` with `tween(150)` | 15 / 12 | the "standard pair" for a sheet body giving way to the payment status screen |
| `tween(220)` | 7 | a third fade duration for the same kind of swap |
| `tween(280)` | 2 | a fourth |

```kotlin
// android/app/src/main/java/com/cashu/me/ui/components/Amount.kt:152-155 — current
transitionSpec = {
    fadeIn(spring(stiffness = Spring.StiffnessMedium))
        .togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMedium)))
},

// android/app/src/main/java/com/cashu/me/ui/receive/ReceiveLightningScreen.kt:623-624 — current
targetState = successInfo,
transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },

// android/app/src/main/java/com/cashu/me/ui/send/SendEcashScreen.kt:969-971 — current
visible = claimState == ClaimState.Checking,
enter = fadeIn(tween(220)) + expandVertically(tween(220)),
exit = fadeOut(tween(220)) + shrinkVertically(tween(220)),

// android/app/src/main/java/com/cashu/me/ui/settings/RestoreWalletScreen.kt:107 — current
transitionSpec = { fadeIn(tween(280)).togetherWith(fadeOut(tween(280))) },
```

Files that type the `tween(200)` / `tween(150)` / `tween(220)` literals:
`ui/mints/MintTransferScreen.kt` (6), `ui/send/SendEcashScreen.kt` (4),
`ui/receive/ReceiveLightningScreen.kt` (4), `ui/components/PaymentStatusScreen.kt` (4),
`ui/send/UnifiedSendScreen.kt` (2), `ui/receive/ReceiveEcashDetailScreen.kt` (1),
`ui/receive/CashuRequestDetailScreen.kt` (1).

Nothing here looks wrong on screen today. The cost is that the same swap can
only be retuned by editing a dozen files, and that 200, 220 and 280 are three
answers to one question.

## Target

Add names to `Motion.kt`; replace the literals. Every name carries today's value.

```kotlin
// android/app/src/main/java/com/cashu/me/ui/theme/Motion.kt — add inside `object CashuMotion`
/** A sheet body giving way to the payment status screen, and back. */
const val StatusFadeInMs = 200
const val StatusFadeOutMs = 150
```

```kotlin
// android/app/src/main/java/com/cashu/me/ui/theme/Motion.kt — add below `fadeThrough()`
/**
 * The quiet cross-fade for a value or label changing in place (amounts, sheet
 * titles, field hints). A spring so a second change mid-fade retargets.
 */
fun <T> quietFadeSpec(): FiniteAnimationSpec<T> = spring(stiffness = Spring.StiffnessMedium)

/** [quietFadeSpec] as a ready-made `AnimatedContent` transition. */
fun quietFade(): ContentTransform =
    fadeIn(quietFadeSpec()) togetherWith fadeOut(quietFadeSpec())

/** A sheet body giving way to the payment status screen (iOS: the status face's opacity swap). */
fun statusSwap(): ContentTransform =
    fadeIn(tween(CashuMotion.StatusFadeInMs)) togetherWith fadeOut(tween(CashuMotion.StatusFadeOutMs))
```

Call sites then read:

```kotlin
transitionSpec = { quietFade() }                      // was the two-line StiffnessMedium pair
transitionSpec = { statusSwap() }                     // was fadeIn(tween(200)) togetherWith fadeOut(tween(150))
enter = fadeIn(quietFadeSpec()), exit = fadeOut(quietFadeSpec())   // AnimatedVisibility sites
```

## Repo conventions to follow

- `fadeThrough()` at `ui/theme/Motion.kt:70-72` is the exemplar: a top-level
  function returning a `ContentTransform`, built from `CashuMotion` constants,
  with a doc comment naming its iOS twin.
- Where a site composes the pair with something else
  (`fadeIn(tween(200)) + scaleIn(...)`, `.using(SizeTransform(...))`,
  `.using(null)`), replace only the spec inside:
  `fadeIn(tween(CashuMotion.StatusFadeInMs)) + scaleIn(...)`.

## Steps

1. Add the two constants and three functions above to `ui/theme/Motion.kt`, with the imports they need (`FiniteAnimationSpec`, `Spring`, `spring`).
2. Replace every exact two-sided `fadeIn(spring(stiffness = Spring.StiffnessMedium))` + `fadeOut(spring(stiffness = Spring.StiffnessMedium))` `AnimatedContent` pair with `quietFade()`; replace the remaining single uses with `quietFadeSpec()`.
3. Replace every exact `fadeIn(tween(200)) togetherWith fadeOut(tween(150))` with `statusSwap()`; replace remaining lone `tween(200)` / `tween(150)` fades with the two constants.
4. Leave `tween(220)` and `tween(280)` sites as literals in this pass and list them in the pull request description. Folding them onto 200/150 changes timing and belongs to a follow-up with captures.
5. Remove imports that are no longer used in the touched files.

## Boundaries

- Do NOT change any value. This plan is a rename.
- Do NOT touch `CashuNavHost.kt`, `TwoFaceScreen.kt`, `OnboardingHandoff.kt`, `AsciiField.kt`, `SpinnerRing.kt`, or anything that already reads `MaterialTheme.motionScheme.*`.
- Do NOT touch `RollingAmountText.kt`'s private `RollSpec` / `CrossFadeSpec`; they are local on purpose.
- Do NOT add new dependencies.
- If a step doesn't match the code you find, STOP and report instead of improvising.

## Verification

- **Mechanical** (from `android/`):
  - `./gradlew :app:compileDebugKotlin` succeeds.
  - `./gradlew :app:testDebugUnitTest -Duser.language=en -Duser.country=US` passes (this machine's locale breaks one currency test without the locale flags).
  - `./gradlew :app:validateDebugScreenshotTest` passes with **no** updated references. Any changed golden means a value changed; stop and find it.
  - `grep -rn "spring(stiffness = Spring.StiffnessMedium)" app/src/main/java --include='*.kt'` matches only `ui/theme/Motion.kt` and `ui/components/RollingAmountText.kt`.
- **Feel check**: none expected. Spot-check on a device or the API 29 emulator: type an amount in Send, flip sats ↔ fiat, pay a test invoice and watch the status screen arrive. Each must look the same as a build of `main`.
- **Done when**: the grep above is clean, screenshot tests pass untouched, and the 220/280 sites are listed for a follow-up.

## Outcome (2026-11-26, branch `polish/motion-audit`)

In as planned, with one naming change: the spring is `quietSpec()` rather than
`quietFadeSpec()`, because sixteen of its uses are not fades (a control dimming, a
scale settling). All 53 literals are replaced, across 22 files. The nine exact
status pairs are `statusSwap()`. `tween(220)` and `tween(280)` remain as literals:
`ui/send/SendEcashScreen.kt` (claim check row), `ui/settings/RestoreWalletScreen.kt`
(step swap), and the others the grep in this plan lists.

Compiles; 1018 unit tests pass; `validateDebugScreenshotTest` passes with no
baseline changed.
