import SwiftUI

/// Shared restore input and mint staging state. Each host owns its own instance.
struct RestoreFlowState {
    var seedEntry = SeedPhraseEntry()
    var seedNotice: SeedEntryNotice?
    var mintUrlInput = ""
    var mintsToRestore: [String] = []
    var restoringMints: [String] = []
    var restorePhases: [String: MintRestorePhase] = [:]
    var stagedMintIconUrls: [String: String] = [:]
    var stagedMintNames: [String: String] = [:]
    /// Returns whether checksum review took focus away from the field.
    mutating func reviewChecksum(validate: (String) -> Bool) -> Bool {
        guard seedEntry.isComplete else { return false }
        guard !validate(seedEntry.phrase) else {
            seedNotice = nil
            return false
        }
        seedEntry.markReviewing()
        seedNotice = SeedEntryNotice(message: SeedEntryCopy.checksumBody, title: SeedEntryCopy.checksumTitle, severity: .error)
        HapticFeedback.notification(.error)
        return true
    }

    /// Returns the intended keyboard focus after a paste and checksum review.
    mutating func pasteSeed(_ content: String, validate: (String) -> Bool) -> Bool {
        switch seedEntry.fill(from: content) {
        case .filled:
            HapticFeedback.notification(.success)
            seedNotice = nil
            return !reviewChecksum(validate: validate)
        case .partial(let count):
            HapticFeedback.selection()
            seedNotice = SeedEntryNotice(message: SeedEntryCopy.pastePartial(count), severity: .caution)
        case .invalid(let index):
            HapticFeedback.notification(.warning)
            seedNotice = SeedEntryNotice(message: SeedEntryCopy.pasteInvalid(at: index), severity: .caution)
        case .unusable:
            HapticFeedback.notification(.error)
            seedNotice = SeedEntryNotice(message: SeedEntryCopy.pasteUnusable, severity: .caution)
        }
        return true
    }
    static func normalizedMintURL(from rawUrl: String) -> String? {
        var url = rawUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !url.isEmpty else { return nil }

        url = url.trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))

        if !url.hasPrefix("http://") && !url.hasPrefix("https://") {
            url = "https://" + url
        }

        if url.hasSuffix("/") {
            url = String(url.dropLast())
        }

        guard let parsed = URL(string: url), parsed.host != nil else { return nil }
        return url
    }
}
