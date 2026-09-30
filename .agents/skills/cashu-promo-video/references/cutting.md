# Cutting a film

A film is code: its cuts are spans in `tool/Sources/Promo/Films.swift` (one
phone) or clips in a film class like `MessageFilm.swift` (two phones), fed by
an EDL of take paths and in-points. Render → look → adjust → render.

## Contents

- [EDLs and marks](#edls-and-marks)
- [One phone: spans in Films.swift](#one-phone-spans-in-filmsswift)
- [Two phones: a film class](#two-phones-a-film-class)
- [Finding frames](#finding-frames)
- [Cutting rules of thumb](#cutting-rules-of-thumb)

## EDLs and marks

An EDL (`tool/edl/<film>-dark.json`) names the take folders and a set of
points in take seconds. It points at local takes, so `edl/` is gitignored:

```sh
.build/release/promo edl-seq edl/<film>-dark.json <take>=takes/<test>-dark/<stamp>
.build/release/promo edl-message edl/message-dark.json share=<takes/…> claim=<takes/…>
```

`edl-seq` puts every mark from the take onto the video clock. For each
`tap:` mark, it adds `<label>.r`: the first frame after the mark that differs
from the mark's own frame, which is when the screen answered. Treat both as
hints:
- **Marks are XCTest's intent.** The real touch can trail its mark by up to a
  few seconds while XCTest resolves the element.
- **A mark's clock comes from `Analyze.launchOffset`.** It calibrates against
  the first of `tap:create`, `tap:send` or `tap:settings` found in the take.
  If your take has none of them, it silently falls back to an offset of 0,
  and every mark can be seconds out. Add a probe for your take's first
  unmistakable tap (a rect whose look changes only on that tap). The offset
  can also be off when something unusual happens near the calibration tap
  (see the launch-offset trap in `capture.md`).
- **`.r` is unreliable when a tap lands mid-animation,** because the screen
  was already changing.

So spans are authored by eye from contact sheets, and two-phone films find
their in-points from pixels (`Analyze.share`, `Analyze.claim`). A retake
changes every time, so it always needs re-cutting.

## One phone: spans in Films.swift

Add a static func and a `case` in `Films.named`:

```swift
/// <What the film shows>. <n> s + end card.
static func lightning(_ edl: EDL) throws -> SequenceFilm {
    try SequenceFilm(edl: edl, take: "lightning", spans: [
        S(from: 12.3, length: 2.4),   // home 0 → Receive → Bitcoin
        S(from: 16.2, length: 2.1),   // 5 · 50 · 500 · 5,000
        S(from: 19.5, length: 1.8),   // Create → the invoice QR
        S(from: 24.9, length: 2.7),   // "Payment Received!" as its check fades in
    ], lines: [
        L(text: "Receive over Lightning.", span: 0, through: 3),
    ])
}
```

(The spans and caption above are illustrative. Real in-points come from your
take, and every caption needs a source; see `style.md` → Copy.)

- **`S(from:length:)`:** plays the take from `from` (take seconds) for `length`
  seconds. Each length must be a whole number of half-beats (0.3 s), or the
  initializer throws. That keeps every cut on the grid.
- **`L(text:span:through:)`:** puts one caption over spans `span…through`. It
  enters 0.1 s after its first span starts and leaves 0.3 s before the next
  span. One caption at a time: don't overlap the ranges.
- **Comment every span** with what the viewer sees, and note anything you cut
  around and why. The next person re-cuts from those comments.
- The end card (6 beats) is added automatically.

Render and check it:

```sh
.build/release/promo render-seq <film> edl/<film>-dark.json out/<film>-1x1-dark.mp4 dark out/<film>-1x1-dark.mov
.build/release/promo qa out/<film>-1x1-dark.mp4 <seconds> <film> edl/<film>-dark.json
```

`render-seq` warns about hold or motion violations before rendering, and
prints the film's length, which is `qa`'s expected seconds. `qa` rejects a film it doesn't know. For a new single-phone film, add its
name to the `case "send", "restore":` line in `main.swift`'s `qa` switch.
That case allows the app's green throughout, so narrow the window if your
film should have no green. A new two-phone film gets its own case, with its
green windows declared.

## Two phones: a film class

Follow `MessageFilm.swift`:
- **The layout:** two `AppSpace`s side by side (`Stage.phone(x:)`, a 60 px
  gap).
- **One clip list per phone.** A clip is `(take, takeIn, filmIn, filmOut)`,
  and `still: true` holds a frame. That's how the phone that isn't acting
  waits.
- **Beats on the grid:** `beatStarts` in beats, `Grid.snap` or `snapDown` for
  derived cut points, and an explicit `cuts` list, which QA checks against
  the grid.
- **Cards:** set by hand, one at a time.
- **In-points from pixels:** an `Analyze.<take>` function that finds each
  event in the frames (`firstChange` in an app-point rect, anchored on
  something unmistakable), plus an `edl-<film>` command to write the EDL.

## Finding frames

**Contact sheets** show what's on screen when:

```sh
../scripts/contact_sheet.sh takes/<…>/take-60.mp4 20 6 sheet.png 10 12 150 "iw:ih*0.6:0:ih*0.3"
```

Frame i is at `start + i/fps`. Look at the sheet, then narrow the window.

**Region luma** finds the exact frame something changes in:

```sh
../scripts/region_luma.py takes/<…>/take-60.mp4 24.4 0.4 372 1076 460 460
```

It prints the frames where the region's mean luma moves. A take is 1206 ×
2622 px, 3× the app's points. Three patterns cover most needs:
- **A flat region jumping:** a toast, a pill, or the Face ID box fading in.
- **A small bump before a big one:** a tap's glass press, then its content.
- **A steady fall:** a sheet leaving.

Always check a candidate cut frame with your own eyes before committing it:
extract it with `ffmpeg -ss <t> -i take-60.mp4 -frames:v 1 f.png`.

## Cutting rules of thumb

- **Trim idle time freely.** A cut between two frames of the same still
  screen is invisible.
- **Enter on arrival.** Cut into a screen once it has resolved, past any
  blank page, grey placeholder or spinner.
- **Leave on departure.** Cut out on the first frame of an exit (a sheet
  going down), or just before an app switch, which records as black.
- **Don't cut mid-type.** Let a keypad sequence play whole, or cut on a digit.
- **Hold the payoff.** The confirmation screen gets the longest span in the
  film.
- **Work around what's missing.** When the Simulator can't render something
  (the Face ID glyph), skip its frames and let the hard cut read as the app's
  answer. Never paint in a substitute.
- **Keep numbers as the app rendered them.** No speed ramps over amounts;
  every span plays at 1×.
