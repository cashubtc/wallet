# House style: the exact numbers

Every value here is what the renderer already does. `SequenceFilm` and
`MessageFilm` in `tool/Sources/Promo/` apply them, and `promo qa` checks the
ones a machine can check. Change a value only when the maintainer asks for it,
and then change it in the code, not in a single film.

## Contents

- [Format](#format)
- [Stage](#stage)
- [Captions](#captions)
- [Rhythm](#rhythm)
- [The field backdrop](#the-field-backdrop)
- [End card](#end-card)
- [Color](#color)
- [Copy](#copy)
- [What the films show, and what they never show](#what-the-films-show-and-what-they-never-show)
- [Rejected on purpose](#rejected-on-purpose)

## Format

| | |
|---|---|
| Frame | 1080 × 1080 (1:1), 60 fps |
| Appearance | Dark only: canvas `#000`, ink `#FFF`. Takes are shot with `simctl ui appearance dark`. |
| Audio | None. The export has no audio stream at all. |
| Master | H.264 High, yuv420p, CRF 14, preset slow, BT.709, faststart, bit-exact, metadata stripped |
| Mezzanine | ProRes 422 HQ (`prores_ks` profile 3), yuv422p10le, BT.709 |
| Length | Whatever the feature needs. The reference films run 16.2 s, 21.0 s and 36.6 s. |

## Stage

- **One phone:** it's centred at 0.945× app scale (a 402 × 874 pt phone draws
  at about 382 × 826 px), with its bottom 40 px above the frame's bottom edge.
  It stays the same size and in the same place for the whole film.
- **Two phones** (the chat film): they're the same size and height, side by
  side with a 60 px gap, centred as a pair. The left phone is the sender and
  the right one the receiver.
- **The phone** is the take itself, clipped to the screen's 62 pt corner
  radius, with a 1.5 px outline in ink at 0.9 alpha. There's no chassis
  render, shadow, glow or tilt.
- **The phone never moves.** Only the footage inside it changes.

## Captions

| | |
|---|---|
| Face | Inter Display SemiBold (in `tool/Fonts/`). SF Pro appears only inside the footage, because its licence covers UI mock-ups, not marketing. |
| Size | 76 px, about 7% of the frame height. It stays readable muted on a phone at arm's length. |
| Position | Centred horizontally, on the line y = 110 above the phone's top. Tracking is −0.01. |
| Entrance | Rises 22% of its size and fades in over 0.5 s, easing out. |
| Exit | Fades over 0.25 s, easing in and out. |
| Timing | Enters 0.1 s after its first span starts, and starts to leave 0.3 s before the span after its last one. |
| Count | One caption on screen at a time, never two lines in motion at once. |
| Hold | On screen for at least `words ÷ 3 + 0.5` s. `promo qa` fails a shorter one. |
| Register | Sentence case, one short declarative sentence, ending with a period. Match the app's onboarding voice: factual, brief, never breathless. |

A film has 3 to 5 captions. Each one names what the viewer is looking at, or
the reason it matters. They're never a feature list.

## Rhythm

- **The 100 BPM grid:** a beat is 0.6 s (36 frames) and a half-beat is 0.3 s
  (18 frames). Every visible cut lands on a half-beat. `SequenceFilm` makes
  this true by construction, because span lengths must be whole half-beats.
- **Cuts are hard cuts.** Most of them only trim idle time: a cut between two
  frames of the same still screen can't be seen.
- **Cut on the app's own timing.** Enter a screen once it has resolved (the
  token screen as its QR finishes; "Payment Received!" as its check fades in),
  and leave a screen as it starts to move away (a sheet's exit, just before
  an app switch, which records as black).
- **Film the reaction, not the reach.** XCTest taps show no finger, so a tap
  reads as the app's response to it.
- **The end card** gets 6 beats (3.6 s).

## The field backdrop

The faint ASCII landscape behind the phones is the app's own onboarding field
(`AsciiField.swift`), ported to `tool/Sources/Field/` and proven against
`docs/product/ascii-field-vectors.json` by `swift test`.

- It's drawn in JetBrains Mono (which has a real ₿), in the phones' own cell
  grid, at 0.45 alpha.
- It goes quiet under the caption band and under the end card's block.
- The speed is the app's (0.45), and the field never outruns it.
- It's the only moving thing outside the phone, and it's never a transition
  device. There are no curtains, erosions or morphs between shots.

## End card

- The film hard-cuts to it on the grid.
- "Cashu.me" is set at 150 px, centred at y 470.
- The official App Store badge (black) comes first, then the Google Play
  badge. They're centred at y 640, trimmed to their ink, both 88 px tall and
  26 px apart (at least a quarter of their height, per Apple's guidelines).
  The files are in `tool/assets/badges/`.

## Color

- Everything the film sets is black and white.
- Green is the app's own confirmed-state green, and it appears only where the
  app shows it (a payment received, a "+" amount, an enabled toggle). The film
  never adds a colour.
- `promo qa` scans for green outside each film's allowed window.

## Copy

- **Trace every caption to a source:** the app's own strings (search
  `ios/CashuWallet`) or `README.md`. Record the source next to the line in
  your pitch.
- **Never claim:** anonymous, untraceable, non-custodial, "no fees", instant
  settlement everywhere. Mints hold the bitcoin behind ecash.
- **The balances are test sats** from a local FakeWallet mint. Never imply
  real money moved.
- **Ban:** no emoji, exclamation-driven hype, "You earned…", or crypto-bro
  register (see `docs/product/PRODUCT.md`: Quiet · Precise · Native).

## What the films show, and what they never show

Shown as-is, by the maintainer's decision:

- The demo mint's name, "Local test mint", in every mint row.
- In the restore film only, the mint address `127.0.0.1:3340` that the
  restore types in, and the 12 revealed seed words. They belong to a
  throwaway wallet; an ordinary wallet's seed is never filmed.
- The Simulator's real system UI: Messages' demo contacts (fictional 555
  numbers), iOS's "Cashu pasted from Messages" banner, and the "◀ Messages"
  back link.

Never shown:

- Any UI the app didn't render in that take. Nothing is mocked, redrawn or
  composited: no fake touch dots, no fake Face ID animation, no imitation of
  another app.
- A mint address outside the restore film. `promo qa` OCRs every sixth frame
  for "127.0.0.1", "localhost" and "testnut".
- Anything that identifies a person or a machine: record with `simctl` only,
  never a desktop capture; override the status bar to 9:41; strip metadata.
- An animated number. The film never counts up or rolls an amount; the app's
  own transitions are fine.

## Rejected on purpose

These were tried in earlier cuts and removed because "the video is doing too
much". Don't bring them back without the maintainer asking:

- A 30 s master montage of many features, and its teaser. It showed too many
  features; each feature gets its own film.
- Camera moves: crops, push-ins, pans, tilts or perspective drift.
- Field curtains, erosion wipes, vault morphs and QR dissolves as transitions.
- A light-mode variant.
- 16:9 and 9:16 versions.
