# 019 — Bring the motion docs back in line with the code

- **Status**: DONE
- **Commit**: e61dcb3c (`main`; the audit ran on a branch at 18ba3c89, one PR behind)
- **Severity**: LOW
- **Category**: Cohesion & tokens (documentation)
- **Estimated scope**: 2 doc files, about 15 lines; no code

## Problem

The motion spec is what every later change is checked against, and three
statements in it no longer describe the app.

1. **Number-pad keys.** `docs/android/DESIGN-ANDROID.md:124` says "CTAs and
   number-pad keys spring-scale on press (`Buttons.kt`, `NumberPad.kt`)". The
   keys do not scale; they dim:

   ```kotlin
   // android/app/src/main/java/com/cashu/me/ui/components/NumberPad.kt:223-228 — current
   // Opacity-based press feedback: subtle dim on press (iOS-style, no background).
   val alpha by animateFloatAsState(
       targetValue = if (pressed) 0.4f else 1f,
       animationSpec = tween(durationMillis = 100),
       label = "key-press-alpha",
   )
   ```

2. **Numbers.** `docs/android/DESIGN-ANDROID.md:176` says `AmountText`
   cross-fades the whole string and that the per-digit roll "was retired
   (2026-07-10)". Since the home payment-arrival work the balance and its fiat
   line do roll changed digits:

   ```kotlin
   // android/app/src/main/java/com/cashu/me/ui/components/BalanceDisplay.kt:133 and :199 — current (main)
   rollDigits = true,
   ```

   with the roll implemented in `ui/components/RollingAmountText.kt` and
   `DigitRollPlan.kt`. The cross-fade is still what every other amount does.

3. **Allowed springs on iOS.** `docs/product/DESIGN.md` §6 "Allowed easings"
   names the celebration as the only `.spring`. The confirmation toast has its
   own (`ios/CashuWallet/Views/Components/ErrorBannerView.swift:514`,
   `.spring(response: 0.38, dampingFraction: 0.9)`), described in prose in the
   Notifications section but absent from the list, and bare `.snappy` (0.5 s) is
   used for value rolls although the list gives only explicit durations.

## Target

- `DESIGN-ANDROID.md:124-131`: say that CTAs spring-scale and number-pad keys
  dim to 0.4 over 100 ms with no scale, and why (a key has no surface to
  compress).
- `DESIGN-ANDROID.md:176-181`: say that amounts cross-fade as a whole string,
  **except** the home balance and its fiat line, which roll the digits that
  changed (`RollingAmountText`, critically damped `Spring.StiffnessMediumLow`)
  as the twin of iOS `.numericText`; a unit change still cross-fades.
- `DESIGN.md` §6 "Allowed easings": add the toast entrance spring and the bare
  `.snappy` value roll to the list, each with its one sanctioned use. If plan
  016 has landed, point the paragraph at `CashuMotion.swift` instead of listing
  numbers twice.

## Repo conventions to follow

- Both docs record changes as dated amendments in place, e.g.
  `*(2026-10-01: …)*` in `docs/product/DESIGN.md` §6 animation 8. Add the
  corrections the same way rather than silently rewriting history.
- Describe what is on screen in plain words; the docs avoid coined jargon.

## Steps

1. Edit `docs/android/DESIGN-ANDROID.md` at the two places above.
2. Edit `docs/product/DESIGN.md` §6 "Allowed easings".
3. Re-read `plans/README.md` and correct any status that has changed since it was last reconciled.

## Boundaries

- Docs only. Do NOT change code to match the docs; where they disagree, the shipped behaviour is the reference unless a plan says otherwise.
- Do NOT rewrite or remove the dated history notes.
- If a statement here no longer matches the code you find, STOP and report instead of improvising.

## Verification

- **Mechanical**: `grep -n "number-pad keys spring-scale" docs/android/DESIGN-ANDROID.md` returns nothing.
- **Feel check**: not applicable.
- **Done when**: each of the three statements can be checked against the file and line it cites.

## Outcome (2026-11-26, branch `polish/motion-audit`)

Both docs carry dated corrections for the three statements, plus a note on where
the motion values now live on each platform. The original sentences were kept and
corrected by a dated note beside them, as the docs do elsewhere, so the grep under
Verification still finds the old wording; read the note that follows it.
