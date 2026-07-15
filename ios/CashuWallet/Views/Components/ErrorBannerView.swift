import SwiftUI
import Cdk

// MARK: - Severity

/// The one severity vocabulary shared by every error surface in the app.
///
/// - `error`   — the action is blocked or just failed. Saturated red.
/// - `caution` — non-blocking "proceed carefully / this won't work here". Orange.
///               (Orange also means *pending* elsewhere; the warning-triangle vs
///               pending clock-badge iconography keeps the two distinct.)
/// - `info`    — validation-in-progress that isn't a failure yet. Quiet secondary,
///               never a saturated hue.
enum ErrorSeverity {
    case error, caution, info

    var icon: String {
        switch self {
        case .error:   return "exclamationmark.triangle.fill"
        case .caution: return "exclamationmark.circle.fill"
        case .info:    return "info.circle.fill"
        }
    }

    /// Text + icon tint. Maps to DESIGN.md state tokens.
    var foreground: Color {
        switch self {
        case .error:   return Color(.systemRed)   // state-error  #FF3B30
        case .caution: return .orange             // state-pending #FF9500
        case .info:    return .secondary          // no saturated hue
        }
    }

    /// Surface fill behind a tinted notice/banner. Maps to the *-tint tokens.
    var tint: Color {
        switch self {
        case .error:   return Color(.systemRed).opacity(0.18)  // error-tint   #FF3B302E
        case .caution: return Color.orange.opacity(0.10)       // pending-tint #FF95001A
        case .info:    return Color(.secondarySystemBackground)
        }
    }

    /// Prefix spoken by VoiceOver so the tier is announced, not just the message.
    var announcementPrefix: String {
        switch self {
        case .error:   return "Error. "
        case .caution: return "Caution. "
        case .info:    return ""
        }
    }
}

// MARK: - Shared banner (async / system failures, screen-level)

/// Standardized error/info banner for async or system *failures* not tied to a
/// single control (mint unreachable, payment failed, backup failed). Prefer the
/// `.errorBanner(_:)` modifier to pin it to the bottom safe area; place it inline
/// only where the layout already reserves a slot for it.
struct ErrorBannerView: View {
    let message: String
    var severity: ErrorSeverity = .error
    var retry: (() -> Void)? = nil
    var onDismiss: (() -> Void)? = nil

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: severity.icon)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(severity.foreground)
                .accessibilityHidden(true)

            Text(message)
                .font(.footnote)
                .foregroundStyle(severity.foreground)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)

            if let retry {
                Button("Retry", action: retry)
                    .font(.footnote.weight(.semibold))
                    .buttonStyle(.plain)
                    .foregroundStyle(severity.foreground)
            }

            if let onDismiss {
                Button(action: onDismiss) {
                    // Compact banner: 32pt target (not the full 44) so a short
                    // single-line banner doesn't visibly inflate.
                    Image(systemName: "xmark")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .frame(width: 32, height: 32)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Dismiss")
            }
        }
        .padding(12)
        .background(severity.tint, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(Color(.separator), lineWidth: 0.5)
        )
        .accessibilityElement(children: .combine)
        .accessibilityLabel(severity.announcementPrefix + message)
        .onAppear {
            guard severity != .info else { return }
            AccessibilityNotification.Announcement(message).post()
        }
    }
}

// MARK: - Inline notice (preconditions / validation, tied to a control)

/// A calm, control-tied notice for preconditions and validation feedback — the
/// thing that sits directly under a field or amount and tells the user why they
/// can't proceed yet, plus what to do about it.
///
/// Defaults to a bare caption (no box) so most sites stay quiet; opt into
/// `tinted` only where the message deserves a surface (e.g. an insufficient-
/// balance notice carrying amounts and an action).
struct InlineNotice: View {
    let message: String
    /// Optional bold leading line (e.g. "New mint"). When present the `message`
    /// drops to a secondary explanatory body — turning the notice into a titled
    /// callout instead of a single tinted caption. Keep it to a few words.
    var title: String? = nil
    var severity: ErrorSeverity = .error
    /// Optional second line, always secondary — for amounts / supporting detail.
    var detail: String? = nil
    /// Hide the leading glyph (e.g. the seed word-counter, which reads as plain text).
    var showsIcon: Bool = true
    /// Wrap in a 12pt tint surface. Off by default to preserve the existing calm look.
    var tinted: Bool = false
    private var appError: AppError? = nil
    @State private var presentedError: AppError?

    init(
        message: String,
        title: String? = nil,
        severity: ErrorSeverity = .error,
        detail: String? = nil,
        showsIcon: Bool = true,
        tinted: Bool = false
    ) {
        self.message = message
        self.title = title
        self.severity = severity
        self.detail = detail
        self.showsIcon = showsIcon
        self.tinted = tinted
    }

    /// Failure-only initializer. Validation, caution, pending, and success copy
    /// continues to use the non-clickable string initializer above.
    init(error: AppError, tinted: Bool = true) {
        self.message = error.info.userMessage
        self.severity = .error
        self.detail = "Tap for details and reporting"
        self.tinted = tinted
        self.appError = error
    }

    var body: some View {
        Group {
            if let appError, appError.isReportable {
                Button { presentedError = appError } label: { noticeContent }
                    .buttonStyle(.plain)
                    .accessibilityHint("Opens error details and reporting")
            } else {
                noticeContent
            }
        }
        .sheet(item: $presentedError) { ErrorDetailsSheet(error: $0) }
    }

    private var noticeContent: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            if showsIcon {
                Image(systemName: severity.icon)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(severity.foreground)
                    .accessibilityHidden(true)
            }

            VStack(alignment: .leading, spacing: 2) {
                if let title {
                    Text(title)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.primary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                // With a title the message becomes the calm secondary body;
                // untitled it carries the severity hue as the primary line.
                Text(message)
                    .font(title == nil ? .caption : .caption2)
                    .foregroundStyle(title == nil ? severity.foreground : Color.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                if let detail {
                    Text(detail)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(tinted ? 10 : 0)
        .background(
            tinted ? AnyShapeStyle(severity.tint) : AnyShapeStyle(Color.clear),
            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
        )
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityText)
    }

    private var accessibilityText: String {
        var parts = [severity.announcementPrefix + (title.map { "\($0). " } ?? "") + message]
        if let detail { parts.append(detail) }
        return parts.joined(separator: " ")
    }
}

// MARK: - Error report details

private struct ErrorDetailsSheet: View {
    let error: AppError
    @Environment(\.dismiss) private var dismiss
    @State private var note = ""
    @State private var isSending = false
    @State private var deliveryError: String?
    @State private var receipt: NostrReportReceipt?

    private var preview: NostrErrorReport {
        error.preparedReport(userNote: note.isEmpty ? nil : note)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Message") { Text(preview.userMessage) }
                Section("Report preview") {
                    detail("Code / category", "\(preview.errorCode.map(String.init) ?? "—") · \(category)")
                    detail("Operation", preview.operation)
                    detail("Report ID", preview.reportId)
                    detail("App", "\(preview.appName) \(preview.appVersion) (\(preview.appBuild))")
                    detail("Platform", "\(preview.platform) \(preview.osVersion)")
                    detail("Technical detail", preview.technicalMessage)
                }
                Section("Optional note") {
                    TextEditor(text: $note)
                        .frame(minHeight: 90)
                        .disabled(isSending || receipt != nil)
                        .onChange(of: note) { _, value in
                            if value.utf8.count > 1_024 { note = String(value.prefix(1_024)) }
                        }
                    Text("\(note.utf8.count)/1024 bytes")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if !NostrErrorReporter.isConfigured {
                    Section { Text("Reporting is unavailable in this build.").foregroundStyle(.red) }
                }
                if let deliveryError {
                    Section { Text(deliveryError).foregroundStyle(.red) }
                }
                if let receipt {
                    Section {
                        Text(
                            receipt.failedRelays == 0
                                ? "Report sent."
                                : "Report sent to \(receipt.acceptedRelays) relay(s); \(receipt.failedRelays) failed."
                        )
                        .foregroundStyle(.green)
                    }
                }
            }
            .navigationTitle("Error details")
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(isSending)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(receipt == nil ? "Cancel" : "Done") { dismiss() }
                        .disabled(isSending)
                }
                if receipt == nil {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(deliveryError == nil ? "Send Report" : "Retry") { send() }
                            .disabled(isSending || !NostrErrorReporter.isConfigured)
                    }
                }
            }
        }
    }

    private var category: String {
        switch error.info.category {
        case .protocol: "protocol"
        case .internal: "internal"
        }
    }

    @ViewBuilder
    private func detail(_ label: String, _ value: String) -> some View {
        LabeledContent(label) {
            Text(value).multilineTextAlignment(.trailing).textSelection(.enabled)
        }
    }

    private func send() {
        guard !isSending else { return }
        isSending = true
        deliveryError = nil
        Task {
            do {
                receipt = try await NostrErrorReporter.send(error, userNote: note.isEmpty ? nil : note)
            } catch {
                // A report delivery failure stays in this sheet; it never creates another AppError.
                deliveryError = "The report could not be sent. Check your connection and retry."
            }
            isSending = false
        }
    }
}

// MARK: - Banner presentation modifier

extension View {
    /// Pins the shared error banner to the bottom safe area while `message` is
    /// non-nil. Reuses an existing `@State var errorMessage: String?` — no new
    /// observable object. Use for screen-level/async failures; do NOT use on
    /// screens whose bottom safe area is owned by a primary CTA (Send/Pay) —
    /// those use `InlineNotice` instead.
    func errorBanner(
        _ message: Binding<String?>,
        severity: ErrorSeverity = .error,
        retry: (() -> Void)? = nil
    ) -> some View {
        modifier(ErrorBannerModifier(message: message, severity: severity, retry: retry))
    }
}

private struct ErrorBannerModifier: ViewModifier {
    @Binding var message: String?
    var severity: ErrorSeverity
    var retry: (() -> Void)?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .bottom) {
                if let message {
                    ErrorBannerView(
                        message: message,
                        severity: severity,
                        retry: retry,
                        onDismiss: { withAnimation(.snappy) { self.message = nil } }
                    )
                    .padding(.horizontal)
                    .padding(.bottom, 8)
                    // Enter slides up from the bottom edge; exit is a quiet fade only
                    // (Jakub: exits are subtler than entrances — the user's focus has
                    // already moved on). See DESIGN.md §6 exit convention.
                    .transition(
                        reduceMotion
                            ? .opacity
                            : .asymmetric(
                                insertion: .move(edge: .bottom).combined(with: .opacity),
                                removal: .opacity
                            )
                    )
                }
            }
            .animation(reduceMotion ? nil : .snappy, value: message)
    }
}
