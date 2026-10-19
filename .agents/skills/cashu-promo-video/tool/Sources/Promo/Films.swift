import Foundation

/// The single-feature films cut from one take each. Spans are take-60 times,
/// authored by eye from timestamped contact sheets of the takes they were
/// cut from (shot 2026-09-30; not in the repo): a retake needs re-cutting.
/// Every span trims idle holds; cuts between two frames of the same still
/// screen are invisible, so most cuts only compress time.
enum Films {
    typealias S = SequenceFilm.Span
    typealias L = SequenceFilm.Line

    /// Create a token and post it in a chat. 17.4 s + end card.
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

    /// Back up, delete, restore: the funds come back. 33.0 s + end card.
    ///
    /// The Simulator's Face ID HUD is an empty grey box (no glyph), up for
    /// f1473–1618 after Reveal and f1747–1901 after Copy. The cut steps
    /// around both (maintainer's call, 2026-09-30): no imitation of the real animation.
    static func restore(_ edl: EDL) throws -> SequenceFilm {
        try SequenceFilm(edl: edl, take: "restore", spans: [
            S(from: 14.7, length: 2.4),   // home 5,000 → Settings
            S(from: 18.3, length: 1.2),   // → Backup & Restore
            S(from: 21.6, length: 2.7),   // Backup seed phrase → the sheet, Reveal tapped
            S(from: 27.0, length: 2.1),   // the words (HUD gone), Copy tapped
            S(from: 31.8, length: 1.2),   // (HUD gone) the sheet goes
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
        ], lines: [
            L(text: "Copy your recovery phrase.", span: 0, through: 4),
            L(text: "Delete the wallet.", span: 5, through: 6),
            L(text: "Restore from the phrase.", span: 7, through: 9),
            L(text: "Find your mints.", span: 10, through: 12),
            L(text: "Your balance comes back.", span: 13, through: 14),
        ])
    }

    static func named(_ name: String, _ edl: EDL) throws -> SequenceFilm {
        switch name {
        case "send": return try send(edl)
        case "restore": return try restore(edl)
        default: throw PromoError("no film \(name)")
        }
    }
}
