# The three reference films

They define the house style. Build a new film so it would sit in this set
without standing out. They're dark, 1:1 and 60 fps, and each ends on the
Cashu.me end card.

| Film | Length | Phones | Takes | Captions |
|---|---|---|---|---|
| **message**: "The message is the money." | 16.2 s | 2 | `testShare` + `testClaim` | Copy it. · Send it. · Paste it. It's yours. · The message is the money. |
| **send**: make a token, post it in a chat | 21.0 s | 1 | `testSendChat` | Send it like cash. · Whoever holds it, owns it. · Post it in any chat. |
| **restore**: back up, delete, restore | 36.6 s | 1 | `testRestoreFlow` | Copy your recovery phrase. · Delete the wallet. · Restore from the phrase. · Find your mints. · Your balance comes back. |

Their cuts live in code (`tool/Sources/Promo/Films.swift` and
`MessageFilm.swift`), and their in-points in `tool/edl/*-dark.json`. Those
EDLs point at take folders that exist only on the machine that shot them, so
they are worked examples, not something you can re-render. A retake means
re-cutting.

## Copy deck

Every line, and where its claim comes from:

| Line | Source |
|---|---|
| Copy it. | The token screen's **Copy** button, and its "Copied ecash token" toast (`Views/Send/SendView.swift`) |
| Send it. | README Features: tokens move as "copyable strings" |
| Paste it. It's yours. | "Paste a Cashu token" (`Views/Receive/ReceiveView.swift`); bearer: "Whoever holds it, owns it." (`Views/Main/OnboardingView.swift`) |
| The message is the money. | The maintainer's line; README "copyable strings" |
| Send it like cash. | "Ecash is bearer cash for Bitcoin." (`OnboardingView.swift`) |
| Whoever holds it, owns it. | `OnboardingView.swift`, verbatim |
| Post it in any chat. | README "copyable strings" |
| Copy your recovery phrase. | The **Copy Recovery Phrase** button (`Views/Settings/SettingsView.swift`) |
| Delete the wallet. | **Delete Wallet** (`SettingsView.swift`) |
| Restore from the phrase. | **Restore Wallet** → **Use Seed Phrase** (`OnboardingView.swift`) |
| Find your mints. | **Find my mints** (`OnboardingView.swift`) |
| Your balance comes back. | "Recovered: 5,000 sats" (`SettingsView.swift`, `RestoreMintResult.swift`) |

The paths are under `ios/CashuWallet/`. Cite the file and the string, not a
line number.

## send (a single take, `SequenceFilm`)

```swift
static func send(_ edl: EDL) throws -> SequenceFilm {
    try SequenceFilm(edl: edl, take: "send", spans: [
        S(from: 16.8, length: 3.6),   // home 5,000 → Send → the sheet, Ecash tapped
        // Straight to the keypad: between them the app drops the sheet to
        // the home for ~0.6 s before the Send Ecash sheet rises.
        S(from: 24.9, length: 3.6),   // 2 · 25 · 250 · 2,500
        S(from: 30.6, length: 1.8),   // Send → the token
        S(from: 34.8, length: 1.8),   // Copy → "Copied ecash token"
        S(from: 49.5, length: 3.6),   // John Appleseed's thread: hold → Paste
        S(from: 53.7, length: 3.0),   // send → the bubble → Delivered
    ], lines: [
        L(text: "Send it like cash.", span: 0, through: 1),
        L(text: "Whoever holds it, owns it.", span: 2, through: 3),
        L(text: "Post it in any chat.", span: 4, through: 5),
    ])
}
```

What to learn from it:
- **Spans compress time.** Idle waits (the invoice being paid, the app
  switch) fall between spans.
- **Cut past the app's own awkward beats.** The 0.6 s flash of the home
  between two sheets never makes the film.
- **The token screen enters as its QR resolves,** past the grey placeholder.
- **Messages is real.** The thread is the Simulator's demo contact John
  Appleseed. Messages has no paste button, so the take long-presses the
  composer and taps the edit menu's Paste.

## restore (a single take, `SequenceFilm`)

```swift
S(from: 14.7, length: 2.4),   // home 5,000 → Settings
S(from: 18.3, length: 1.2),   // → Backup & Restore
S(from: 21.6, length: 2.7),   // Backup seed phrase → the sheet, Reveal tapped
S(from: 27.0, length: 2.1),   // the words (the Face ID box gone), Copy tapped
S(from: 31.8, length: 1.2),   // (the Face ID box gone) the sheet goes
S(from: 38.7, length: 2.7),   // Delete Wallet → "Delete wallet?"
S(from: 42.0, length: 1.8),   // Delete → the welcome
S(from: 46.5, length: 3.0),   // Restore Wallet → the vault
S(from: 50.7, length: 2.1),   // Use Seed Phrase → word entry
S(from: 53.4, length: 1.8),   // Paste → "All 12 words verified."
S(from: 57.3, length: 2.1),   // Continue → "Add your mints."
S(from: 60.3, length: 1.2),   // Find my mints → checking
S(from: 64.8, length: 2.4),   // found: Local test mint → Restore from 1 mint
S(from: 67.8, length: 2.7),   // → "Recovered: 5,000 sats"
S(from: 72.3, length: 3.6),   // Continue → the handoff → home, 5,000 sat
```

The captions are "Copy your recovery phrase." (spans 0–4), "Delete the
wallet." (5–6), "Restore from the phrase." (7–9), "Find your mints." (10–12)
and "Your balance comes back." (13–14).

What to learn from it:
- **Cut around what the Simulator can't show.** Reveal and Copy ask for Face
  ID. On the iOS 27 Simulator that's an empty grey box with no glyph (a real
  iPhone 17 Pro animates it in the Dynamic Island). The film doesn't imitate
  the animation: it skips the box's frames, so Reveal hard-cuts to the words,
  and the Copy tap sits on the same still as the sheet going down.
- **Let the app's real flows carry the story.** "Find my mints" works because
  the app auto-publishes an encrypted mint-list backup to Nostr, so the restore
  finds the local mint without typing.
- **The app's own motion is the spectacle.** The welcome's vault morph and the
  handoff into the wallet are shown whole, never redrawn.

## message (two takes, `MessageFilm`, 27 beats)

It shows two whole phones side by side, both still. Phone A (left) sends and
phone B (right) receives. Both are the same Simulator, shot one after the
other, and the token in A's bubble is the exact token B claims.

| Beat | Time | Shot |
|---|---|---|
| 1 | 0–4.2 | A: Copy ("Copied ecash token") → cut into Messages, the edit menu's Paste, then send. B waits on its Receive sheet (a still). |
| 2 | 4.2–9.6 | B taps the Receive sheet's own **Paste** button (5.0). The token lands and iOS shows "Cashu pasted from Messages" (5.2). The film cuts on the sheet's exit into the claim page (5.4), then Receive (6.9) → Payment Received! (≈7.8). |
| 3 | 9.6–12.6 | Both hold: the bubble on A, the payment on B. "The message is the money." |
| 4 | 12.6–16.2 | End card (hard cut) |

What to learn from it:
- **Two phones need a bespoke film class.** Its in-points come from pixel
  analysis (`Analyze.share`, `Analyze.claim`), not hand-authored spans.
- **Use the app's own controls.** B taps Cashu's Paste button rather than
  long-pressing the field. That needs "Paste from Other Apps: Allow" (set by
  `ALLOW_PASTE=1`). B copies the bubble in Messages first, so iOS's banner
  names Messages as the source, as on a real phone.
- **The only green** is B's payment.
