# Layout-jump fixes: manual test plan

Branch: `qa/layout-jump-audit`, now merged on top of the mint-transfer branch
(PR #404). Report with before/after frames:
`docs/qa/layout-jump-audit-2026-10-21/index.html` (section 9 covers the
second pass: ecash and transfer).

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
- A second wallet to claim tokens from and to generate tokens with (the
  Nutshell CLI in `CI/.nutshell-venv` works: `cashu -h https://testnut.cashu.space
  invoice 1000`, then `cashu send 50` / `cashu receive <token>`).
- Two mints for Transfer. Release and debug builds refuse cleartext mint URLs,
  so use Testnut plus a second HTTPS mint (a real one with a few sats is fine:
  a 21 sat transfer costs a sat or two in fees), or run the local CDK mint
  (`./CI/start-cdk.sh`, port 3339) with a one-line local change that sets
  `allowCleartextLocalTestMints = true` on `UiRuntimePolicy.Production` for
  the capture build only. Do not commit that change.
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

### 8a. Receive Ecash: keyboard, wrapping, and the handoff (A13, A14, A15)

1. Home → Receive. Tap "Paste a Cashu token" from a closed keyboard.
2. Expect: the sheet rides up with the keyboard over ~200 ms and the field is
   never covered. Fail if the sheet is already at the top in the frame before
   the keyboard starts moving (it used to jump 290 dp in one frame).
3. Paste a token (or type one): the field grows to its wrapped height and the
   Scan/Ecash/Bitcoin rows slide down over ~300 ms. Fail if the rows hop a
   line at a time.
4. The token routes to the full-screen claim page. Expect: the keyboard goes
   down together with the sheet, the wallet home is briefly visible without a
   keyboard, then the claim page fades in. Fail if the keyboard is still on
   screen after the sheet has gone, or vanishes in one frame as the page
   enters.
5. Tap Receive on the claim page: "Claiming…" morphs into the green check and
   "Payment Received!", the amount and rows fade in, then Done.
6. Also: Send → paste a token into the destination field. Same handoff, same
   expectations.

### 8b. Send Ecash: the "Checking…" row (A12)

1. Home → Send → Ecash → 50 → Send. Leave the token face alone.
2. After about two minutes the wallet stops polling and "Checking…" leaves.
   Expect: the row fades and shrinks over ~220 ms and Unit/Mint slide up with
   it. Fail if Unit/Mint hop up in one frame.
3. Claim the token from the second wallet while the face is still polling.
   Expect the "Claimed" full-screen success to fade in. If the face never
   reports the claim, note it: the claim probe looked unreliable on the Pixel
   during the audit and that is a logic issue, not a layout one.

### 8c. Transfer (A16, and the flow as a whole)

Needs two mints with a balance on the source (fund the second mint through
Receive → Bitcoin → mint picker).

1. Mints tab → Transfer. Tap the swap button twice: names and balances replace
   in place, the glyph half-turns; nothing else moves.
2. Type 21, tap Max: the amount and the trailing "Max" → "Using Max" cross-fade.
   Long-press delete to clear, type 21 again.
3. Continue. Expect: the entry face fades out, a spinner, the review (amount,
   From, To, Network fee, Total, "Transfer ₿21") fades in; the back chevron
   fades in next to the title rather than popping. Fail if the chevron is
   already there on the first frame of the transition.
4. Transfer. Expect: the hero becomes the spinner with "Transferring… / Leaving
   …", the rows hold still, then the spinner morphs into the check, "Total"
   leaves, the amount takes the hero, Done fades in.
5. From the review, tap the chevron: the entry fades back in and the chevron
   fades out. Close the sheet with Back.

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
5. Second pass (I9, I11): the invoice landing cut again on the merged branch
   and now fades on Testnut and on a local mint alike. While the invoice is
   showing, watch the QR through a status refresh (a local mint that pays at
   once refreshes within a few seconds): it must stay solid black. Fail if the
   modules flash grey for a frame.

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

### 13. Send Ecash: token face and Claimed (I7, I8)

1. Home → Send → Ecash → 50 → Send.
2. Expect: the keypad face slides out to the leading edge and the token face
   slides in from the trailing edge over ~0.3 s, title cross-fading to
   "Pending Ecash"; the QR renders into the card afterwards. Fail if the token
   face is simply there in the next frame.
3. Copy the token and claim it from the second wallet. Within ~10 s the face
   should fade to the full-screen "Claimed" success. Fail if it cuts.
4. Also: with checking disabled in Settings, use Check Status; the same
   expectations apply.

### 14. Receive Ecash: paste → claim page → received

1. Home → Receive → Paste (with a token on the clipboard).
2. Expect: the sheet dismisses, the claim page covers the screen, the review
   rows (fee, mint) fill in, Receive → "Claiming…" → the check materialises
   and "Payment Received!" and the amount fade in, Done.

### 15. Mints: Add mint (I10)

1. Mints tab → Add mint → type a mint URL → Add mint.
2. Expect: when the add completes, the keyboard slides down and the sheet
   dismisses; the list shows the new mint. Fail if the keyboard disappears in
   one frame while the sheet is still up.

### 16. Transfer on iOS (regression)

Not changed in this pass; it looked right in the audit. Run the same steps as
8c once: open, swap twice, Max, clear, 21, Continue, Transfer, Done, and the
Back path.

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
| `ui/shell/WalletFlowHost.kt` (second pass) | Receive sheet insets exclude the IME as well as Send's. |
| `ui/receive/ReceiveEcashScreen.kt` | Outermost `animateContentSize` on the sheet column; `routeInput` hides the keyboard and clears focus before handing off a token or payable. |
| `ui/send/UnifiedSendScreen.kt` (second pass) | Keyboard controller/focus manager declared before `advance`; the token handoff hides the keyboard first. |
| `ui/send/SendEcashScreen.kt` (second pass) | "Checking…" row in an `AnimatedVisibility` with its gap inside the animated block. |
| `ui/mints/MintTransferScreen.kt` | One `SheetHeader` for both steps; chevron passed only on review. |
| `Views/Send/SendView.swift` (second pass) | Token landing and both claimed paths hop to the next run-loop pass, then animate; `isGenerating` / `isCheckingClaim` cleared inside the animation; no `defer`. |
| `Views/Mints/AddMintSheet.swift` | Focus binding cleared before `onAdded`. |

## Automated checks already run

- Android: 808 unit tests pass, Compose screenshot validation passes, lint
  passes.
- iOS: app and UI-test bundle build clean; the unit-test target was started
  after the change (see the branch notes for the result).
- No test files changed in the commit.
- Second pass: Android unit tests, screenshot validation and lint pass on the
  merged branch; the iOS app and UI-test bundle build clean.
