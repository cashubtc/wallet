# Layout-jump fixes: manual test plan

Branch: `qa/layout-jump-audit` (commits `6e580ab9` code, `752b5e8e` report).
Report with before/after frames: `docs/qa/layout-jump-audit-2026-10-21/index.html`.

## What changed, in one paragraph

Nothing in wallet logic, CDK, or networking. Every change is in how the Send and
Receive screens animate between states. On Android, the Send sheet now grows
instead of snapping, the mint-selector row stays put while a request is in
flight, the sheet header and "To" row no longer vanish during the quote wait,
the invoice status line no longer flickers, and two onboarding transitions were
tidied. On iOS, three state changes that landed as single-frame cuts (quote
arriving on Send, invoice arriving on Receive, payment received on Receive) now
cross-fade, and the Receive toolbar and "To" row stay mounted during creation.

## Setup

- A wallet with a mint that auto-pays invoices. Testnut works
  (`https://testnut.cashu.space`): any invoice you create is paid within ~2 s.
- Fund it once: Receive → Bitcoin → 1000 → Create invoice, wait for the check.
- An amountless destination to reach Send's amount step. This BOLT12 offer
  resolves and pays on Testnut:
  `lno1pgqpvggr25nht4nyqrgtnhxltctkdsfrf3myhj008f6fyulf4tplmarx8hxq`
- Android: a release build (`assembleRelease`, sign with the debug key) on a
  real phone. The debug build and the emulator both stutter on this app's blur
  effects and will hide what you are looking for.
- iOS: simulator or device, animations on (do not use the UI-test launch env).
- Watch at normal speed first, then use the OS screen recorder and scrub if
  something looks off. A jump is a single frame where something moves by more
  than its own height; a fade is many frames.

## Android

### 1. Send: sheet growth (A1, A5)

1. Home → Send. The compact sheet opens at the bottom.
2. Tap the destination field, paste the offer, then press Back to dismiss the
   keyboard **before** the destination resolves (it resolves after ~1 s).
3. Expect: the sheet grows from compact to full height as one smooth motion
   (~200 ms), the grey compact background blends into the full-height
   background during the growth, the back chevron and "To lno1…" row fade in,
   and the keypad face slides in after the growth.
4. Fail if: the sheet is at full height in one step, the header or "To" row
   pop in, or the grey/black ground switches abruptly.
5. Also with the keyboard still open: type a Lightning address
   (`erik@primal.net` resolves in under a second) and do not press Back.
   Expect one rise of the sheet to the top while the keyboard slides down
   beside it, and the keypad face fading in underneath. Fail if the sheet rises,
   drops back part-way and rises again (this was the first version of the fix),
   or if the keyboard lingers over the keypad after the sheet has settled.
6. Keyboard opening on the compact sheet: tap the destination field from a
   closed keyboard. The sheet should ride up with the keyboard and the field
   should never be covered.
7. Also try a long destination that wraps to two lines while typing: the
   sheet should grow by a line smoothly, not hop.
8. Send with no mints (fresh wallet, skip the mint step): the connect-mint
   form inside the Send sheet must still lift its URL field above the
   keyboard; it now pads for the keyboard itself because the Send sheet no
   longer does.

### 2. Send: back from the amount face

1. From the amount face, tap the back chevron.
2. Expect: the sheet starts shrinking on the very next frame and lands with
   the input face (about 300 ms, one decelerating motion); the chevron fades
   out, the "To" row disappears as the shrink starts, and the input face slides
   in with your destination still in it.
3. Fail if: the sheet hesitates before moving, keeps sinking after the input
   face has already landed, stays tall with empty space, or jumps down.
4. Also: close the sheet with a swipe and with the scrim from both faces.

### 3. Send: Continue → quote → confirm → pay (A4)

1. Amount face: type 21, tap Continue.
2. Expect: the title, chevron and "To" row stay exactly where they are. Only
   the body changes: keypad fades to "Processing…", spinner fades to the
   confirm face (amount, From, Network fee, Total, Pay button).
3. Tap Pay. Expect: chevron fades out, "To" row stays during "Processing…",
   then the "To" row shrinks away as "Payment sent" lands. Title never moves.
4. Fail if: the chevron or "To" row disappear during the quote wait, or the
   title shifts at any point.
5. Also test the failure path: use an invoice the mint cannot pay (any real
   mainnet invoice on Testnut) and confirm the error face lands with the
   header still in place and a usable back chevron.

### 4. Receive Lightning: Create invoice (A2)

1. Home → Receive → Bitcoin → type 1000.
2. Tap Create invoice.
3. Expect: "To Testnut mint" stays under the amount while the button shows its
   spinner; the amount does not move at all; then the keypad face cross-fades
   to the QR face.
4. Fail if: the amount drops and comes back, or "To Testnut mint" disappears.
5. While creating, try tapping "To Testnut mint": it should do nothing (the
   picker is withdrawn until the request lands). With one mint it has no
   chevron anyway; add a second mint to see the chevron and confirm it is
   inert only while creating.

### 5. Receive Lightning: payment received (A8)

1. With the QR showing, wait for Testnut to pay.
2. Expect: the status line goes "Expires in …" → "Adding to wallet…" → success
   face, with no flash of "Expires in" in between.
3. Fail if: you see the countdown reappear for an instant before the green
   check.
4. Also check a BOLT12 reusable offer and an on-chain address: their status
   rows should behave as before (this fix is gated to one-shot invoices).

### 6. Send Ecash (A3)

1. Home → Send → Ecash → type 50 → tap Send.
2. Expect: "From Testnut mint" and "₿… available · Max" stay in place under the
   amount while the button spins; then the face cross-fades to the token QR.
3. Fail if: the amount drops and pops back.
4. While sending, Max and the mint row should be inert.

### 7. Onboarding (A9, A11)

Needs a fresh install or "delete wallet".

1. Welcome → Create Wallet. Expect: the caution block ("Never share…") and the
   checkbox fade in in place. Fail if they slide in from the left edge,
   clipped.
2. Continue to the mint step, add a custom URL (Testnut), tap Add mint.
3. Expect: the ASCII curtain sweeps down over the mint list, holds, then
   dissolves to reveal the wallet once. Fail if the wallet home (balance,
   Receive/Send) is visible for an instant **before** the curtain covers the
   screen.
4. Also run the Restore path to the end and confirm the handoff still fires
   and lands on the wallet (the gate flip moved; restore has its own path and
   should be unaffected).
5. After onboarding, kill and relaunch: it must open on the wallet, not on
   onboarding again.

### 8. Regression sweep (Android)

- Mints tab → Add mint from the Mints screen (not onboarding) still adds and
  returns to the list.
- Mints tab → remove a mint, set active mint: unchanged.
- Every other sheet header (Receive, Send Ecash, Receive Ecash, Add mint,
  currency/unit pickers): the X / back control still appears and works; it
  now fades in rather than popping, which should be invisible in practice.
- Receive → Ecash → paste a token: unchanged.
- Rotate / large text: the Send sheet still grows correctly with the system
  font at 200 %.
- Reduce motion (Developer options → animator scale 0): the Send sheet should
  still reach full height with no stuck state.

## iOS

### 9. Send: Continue → quote → confirm (I2)

1. Home → Send, paste the offer. The compact sheet is replaced by the large
   payment sheet (expected; this is the unchanged I1 behaviour, see below).
2. Type 21, tap Continue.
3. Expect: keypad fades to "Processing…", then the confirm face (amount, From,
   fee rows, Pay) fades in over ~0.3 s.
4. Fail if: the confirm face appears in a single step, or the "To" row and
   chevron snap from dimmed to full brightness.
5. Also: Change Amount (chevron) → Continue again; and a quote failure (real
   mainnet invoice) should present the inline error with a fade.

### 10. Receive Lightning: Create invoice (I3)

1. Home → Receive → Bitcoin → type 1000 → Create invoice.
2. Expect: the method (⚡) and unit (SAT) toolbar buttons stay in the bar but
   are disabled while creating; "To Testnut mint" stays under the amount; the
   amount does not move; the invoice face fades in over the keypad.
3. Fail if: the toolbar buttons vanish, the amount drops, or the QR appears in
   one step.
4. Test all three request types: Lightning invoice, Reusable (BOLT12), and
   Bitcoin address. Each landing should fade; the reusable and on-chain paths
   use different code paths that were also wrapped.

### 11. Receive Lightning: payment received (I4)

1. With the invoice showing, wait for Testnut to pay.
2. Expect: the QR face dims out over ~0.2 s while the green check materialises
   on top, then "Payment Received!" and the amount fade in.
3. Fail if: the QR disappears in one frame leaving a blank screen before the
   check.
4. Also: after Done, the home screen should show the received-payment toast
   and the balance update exactly as before. The notification is now posted
   from inside an animation block; confirm it still arrives once, not twice.
5. Also: reusable offer paid twice in a row (two separate payments) should
   show the success face each time.

### 12. Regression sweep (iOS)

- Receive → Ecash paste, Send → Ecash: unchanged.
- Send with a fixed-amount invoice (straight to confirm): fades as in 9.
- Background the app mid-"Processing…" and return: no stuck spinner.
- Reduce Motion on: all of the above become plain opacity fades, nothing
  slides.
- VoiceOver: the disabled toolbar buttons during creation announce as dimmed;
  the "To" row and selector are still reachable.

## Known and intentionally unchanged

- **I1** iOS Send still dismisses the compact sheet and presents a new large
  sheet when a destination resolves, so the home screen is visible for about
  half a second. This is by design in `UnifiedSendView` (keyboard/detent race)
  and is a navigation decision, not an animation bug.
- **A7** Android bottom-sheet scrim reaches full darkness on the first frame.
  Material's own timing.
- **I5 / I6** Onboarding mint-list cut before the curtain; mint-detail rows
  reflow as data loads. Cosmetic.

## Where to look in the diff

| File | What to check |
| --- | --- |
| `ui/send/UnifiedSendScreen.kt` | Header + "To" row hoisted above the body `AnimatedContent`; `animateContentSize` is the **outermost** modifier on the sheet column. |
| `ui/shell/WalletFlowHost.kt` | Container colour animated between compact and full; Send's sheet insets exclude the IME (keyboard-blind height). |
| `ui/components/CashuModalBottomSheet.kt` | New `contentWindowInsets` parameter, default unchanged. |
| `ui/components/TwoFaceScreen.kt` | Face swaps no longer animate the container size (`SizeTransform` snaps); the host owns height. Also used by Receive Lightning and Send Ecash, where the host constrains the height, so no visible change there. |
| `ui/mints/ConnectMintSheetContent.kt` | The custom-URL form pads for the keyboard itself (needed inside Send). |
| `ui/components/SheetHeader.kt` | Leading icon is now `AnimatedVisibility`; keeps the last icon for the exit. |
| `ui/receive/ReceiveLightningScreen.kt` | Selector no longer gated on `creating`; settlement state latch for BOLT11. |
| `ui/send/SendEcashScreen.kt` | Selector no longer gated on `sending`; Max/picker withdrawn instead. |
| `ui/onboarding/OnboardingChassis.kt` | Empty slot/accessory branches render a full-width zero-height spacer. |
| `Core/Wallet/WalletManager.kt` | `addMint` preserves `needsOnboarding` instead of clearing it. |
| `Views/Send/SendView.swift` | Quote landing and `isWorking` inside one `withAnimation`. |
| `Views/Receive/ReceiveLightningView.swift` | Toolbar/selector gated by `.disabled`, not removal; three quote landings, settled refresh and `completeReceivedQuote` animated. |

## Automated checks already run

- Android: 808 unit tests pass, Compose screenshot validation passes, lint
  passes.
- iOS: app and UI-test bundle build clean; the unit-test target was started
  after the change (see the branch notes for the result).
- No test files changed in the commit.
