# QA and delivery

## Automated: `promo qa`

```sh
.build/release/promo qa out/<film>-1x1-dark.mp4 <expected seconds> <film> edl/<film>-dark.json
```

It fails the film unless every check passes:

| Check | Why |
|---|---|
| 0 audio streams | The films are silent by design, not missing a track. |
| 1080 × 1080, 60 fps, duration within 0.05 s | Consistency across the set. |
| No identifying metadata | Encoder tags, dates and paths never ship. |
| Green only inside the film's allowed windows | The film never adds colour. Green is the app's own confirmed state. |
| No mint address readable (Vision OCR, every 6th frame) | "127.0.0.1", "localhost" and "testnut" never appear, except where a film declares them. The restore film allows the typed-in `127.0.0.1`. |
| Cuts on the 100 BPM grid (it lists the frame numbers) | The rhythm is the only rhythm a silent film has. |
| Every card holds ≥ words ÷ 3 + 0.5 s | It must be readable muted, at arm's length. |
| Never more than one line in motion | One idea at a time. |

Paste the `qa` output (including the cut frames) into your review message.

## By eye, before showing anyone

- **Step through every cut** (a contact sheet around each one). Look for a
  one-frame flash of the wrong screen, a half-drawn sheet, a spinner or a
  blank page at the in-point.
- **Look for things the edit may have let through:** the Simulator's grey
  Face ID box, a black app-switch frame, a keyboard tip, or the host's
  clipboard text.
- **Check that no amount changes** except through the app's own animation.
- **Check the status bar** is at 9:41, and that no device name, username or
  path is anywhere in frame.

## Human only

- Watch each film on a phone at 1×, muted. Every caption should be readable
  on the first viewing.
- The maintainer signs off on the storyboard (before capture) and on the cut
  (before delivery).

## Delivery

- **Files:** `out/<film>-1x1-dark.mp4` (the master to post) and
  `out/<film>-1x1-dark.mov` (ProRes, for any further editing). Both are
  gitignored; hand them over directly, and never commit them.
- **Before a public release:** confirm that every claim still matches the
  current app copy.
- **Clean up:** see `capture.md` → Cleanup.
