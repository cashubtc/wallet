---
name: cashu-promo-video
description: Make Cashu.me promo videos in the house style. These are short, silent, dark 1:1 films of the real iOS app, recorded in the Simulator and composited by the bundled Swift renderer. They show whole phones, hard cuts on a 100 BPM grid, one Inter Display caption at a time, the app's ASCII field faint behind, and an end card with "Cashu.me" and the App Store and Google Play badges. Use it whenever someone wants a promo, launch, social, feature or explainer video, clip or reel of the Cashu wallet. Use it too to re-shoot, re-cut or extend the message, send or restore films, or to ask how they were made or why they look the way they do, even without saying "promo" or "skill". Not for README screenshots (ios-sim-screenshots), PR before/after evidence (wallet-ui-visual-review), or Android footage.
compatibility: macOS with Xcode 27 and an iOS 27 Simulator runtime (iPhone 17 Pro), a Swift 6 toolchain, and ffmpeg/ffprobe on PATH. The local mint uses the repo's CI/setup-cdk.sh binary.
---

# Cashu.me promo videos

Make a new film that sits in the existing set without standing out. The three
reference films are **message** (16.2 s), **send** (21.0 s) and **restore**
(36.6 s). They were shaped over many rounds with the maintainer, and the value
of this skill is their consistency. The style is settled; the craft is in
choosing the feature, scripting a clean take, and cutting it tight.

Everything runs from `tool/`, a self-contained Swift package:
- The renderer, QA and frame analysis live in `Sources/Promo`.
- The app's ASCII field math, with parity tests, lives in `Sources/Field`.
- The capture harness lives in `capture/`.
- The fonts, store badges and a local test mint config are bundled.

## The house style is the product

These rules are what make five films look like one set. Each one exists
because something else was tried and cut.

1. **One feature per film.** A 30 s montage of many features was built and
   retired: "the master is showing too many features".
2. **Only the real app.** Every pixel of UI comes from a Simulator recording
   of this repo's build. The film adds only the field backdrop, the captions,
   a hairline phone outline and the end card. When the Simulator can't show
   something, cut around it or drop the beat. That covers the Face ID glyph,
   group chats, NFC and the camera. Never mock, redraw or imitate UI. The
   whole promise is "this is the app".
3. **Silent, 1080 × 1080, 60 fps, dark.** There's no audio stream at all, so
   the type carries the story.
4. **Whole phones, still.** One phone centred, or two side by side, at one
   size for the whole film. There are no crops, zooms, pans, curtains or
   dissolves: an earlier cut with them was "doing too much… overdesigned".
5. **Hard cuts on the 100 BPM half-beat grid** (0.3 s). Rhythm is the only
   rhythm a silent film has.
6. **One caption at a time** in Inter Display SemiBold, 76 px, sentence
   case, ending in a period. It holds for at least words ÷ 3 + 0.5 s, and
   never two lines move at once. It has to read muted, on a phone, first
   time.
7. **True, sourced copy.** Every caption traces to the app's strings or
   `README.md`. Never say anonymous, untraceable, non-custodial or "no fees".
   The sats are test sats.
8. **Numbers as the app renders them.** The edit never animates an amount.
9. **Colour only from the app.** The film is black and white; green appears
   only where the app shows it (a payment received).
10. **The end card:** "Cashu.me" plus the official App Store and Google Play
    badges.
11. **No PII.** Record with `simctl` only, set the status bar to 9:41,
    strip metadata, and use throwaway wallets on the local mint. Some things
    are shown as-is by decision: the mint name "Local test mint" and, in the
    restore film only, its typed address and the throwaway seed words.

Exact numbers are in [references/style.md](references/style.md). If a request
conflicts with these rules (16:9, light mode, music, voice-over, zooms, a
mocked screen, a multi-feature reel), name the rule and why it exists, offer
the in-style version, and deviate only when the maintainer confirms. Record
any confirmed deviation in the film's comments and the PR.

## Workflow

### 1. Pitch the film (checkpoint)

Before any capture, write and show:
- The feature, in one sentence.
- 3–5 captions, each with its source string and file.
- A beat outline of shots, in order, with rough seconds.
- What the take must do in the app, and any Simulator limits it runs into.
  Check [references/capture.md](references/capture.md) → Simulator facts.

Get a yes first. A session takes 5–15 minutes of Simulator time, and a wrong
storyboard wastes all of it. Study [references/films.md](references/films.md)
for how the existing three were structured.

### 2. Set up (once per machine)

From `tool/`:

```sh
swift build -c release && swift test    # renderer + field parity tests
capture/sim.sh                          # the "Cashu Promo" simulator
capture/mint.sh start                   # Local test mint on 127.0.0.1:3340
capture/worktree.sh                     # throwaway app worktree + capture harness
capture/build.sh                        # app + UI-test runner
defaults write com.apple.iphonesimulator PasteboardAutomaticSync -bool false
```

### 3. Script and record the take

- **Add a test** to `capture/PromoTakes.swift` and a session script to
  `capture/`. Then re-run `capture/worktree.sh` and `capture/build.sh`.
  [references/capture.md](references/capture.md) covers the harness, the
  patterns (fund with `testFund`, freeze the field, pace with holds, use the
  app's own controls) and every Simulator trap found so far. Read it before
  writing the test.
- **Run the session with `DRY=1` first** to get stills at each checkpoint.
  Then record for real.
- **Check the take** before cutting it, with a contact sheet
  (`scripts/contact_sheet.sh`). Retake rather than patch in the edit.

### 4. Cut

Author the spans in `tool/Sources/Promo/Films.swift` (one phone), or write a
film class like `MessageFilm` (two phones). Run `promo edl-seq` for the EDL.
[references/cutting.md](references/cutting.md) covers EDLs and marks, finding
exact frames (`scripts/region_luma.py`), and the cutting rules of thumb.

### 5. Render and QA

```sh
.build/release/promo render-seq <film> edl/<film>-dark.json out/<film>-1x1-dark.mp4 dark out/<film>-1x1-dark.mov
.build/release/promo qa out/<film>-1x1-dark.mp4 <seconds> <film> edl/<film>-dark.json
```

Every `qa` check must pass. Then step through each cut by eye.
[references/qa.md](references/qa.md) lists what the machine checks, what you
check, and what only a human watching on a phone can judge.

### 6. Review (checkpoint) and deliver

Show the maintainer:
- A still from each shot.
- The `qa` output.
- The film itself.

Iterate on the cut before re-shooting. Hand over the `.mp4` and `.mov` from
`tool/out/` directly: never commit media, takes or EDLs that point at local
takes. Then clean up (`capture.md` → Cleanup).

## Where things are

| Path (under this skill) | What |
|---|---|
| `tool/Sources/Promo/Films.swift` | Single-phone films as spans (send, restore) |
| `tool/Sources/Promo/MessageFilm.swift` | The two-phone chat film |
| `tool/Sources/Promo/FilmKit.swift` | The grid, stage, cards, backdrop and end card: the style in code |
| `tool/Sources/Promo/Analyze.swift` | Frame analysis for in-points and launch offsets |
| `tool/Sources/Promo/QA.swift` | Automated checks |
| `tool/capture/` | `PromoTakes.swift` (the XCTest takes), `take.sh`, sessions, sim/mint/worktree setup |
| `scripts/` | `contact_sheet.sh` and `region_luma.py` for authoring cuts |
| `references/` | `style.md`, `films.md`, `capture.md`, `cutting.md`, `qa.md` |

The generated folders (`.build/`, `DerivedData/`, `takes/`, `edl/`, `out/`,
`mint/work/`) are gitignored. The iOS app is the only platform filmed. There's
no Android capture path yet, which is a known parity gap for the films, not
for the app.
