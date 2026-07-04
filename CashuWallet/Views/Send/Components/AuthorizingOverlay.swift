import SwiftUI

/// Full-screen payment status shared by every "Pay" flow (Lightning/BOLT11/BOLT12,
/// on-chain, and Cashu requests). Processing / success / failure are ONE layout: a
/// fixed 72pt icon slot morphs `spinner → green check → red X` in place, with the
/// preserved payment facts (amount / mint / method / fee) shown once beneath and a
/// pinned Liquid Glass CTA. The caller owns the toolbar header ("Pay Lightning" …).
struct PaymentStatusView: View {
    enum Phase: Equatable {
        case processing
        case success
        /// `isCaution` renders an amber warning (e.g. MintSettling) instead of a red X.
        case failure(message: String, isCaution: Bool = false)
    }

    /// A preserved payment fact rendered as one detail row (Amount / Mint / Method / Max fee).
    struct DetailRow: Identifiable {
        let icon: String
        let label: String
        let value: String
        var id: String { label }
    }

    let details: [DetailRow]
    let phase: Phase

    var processingTitle: String = "Authorizing…"
    var successTitle: String = "Payment Sent!"
    var failureTitle: String = "Payment Failed"

    /// Success → dismiss/complete (Done tap). Failure → back to confirm (Try Again).
    let onDone: () -> Void
    let onRetry: () -> Void

    private var phaseKey: Int {
        switch phase {
        case .processing: return 0
        case .success:    return 1
        case .failure:    return 2
        }
    }

    private var statusTitle: String {
        switch phase {
        case .processing: return processingTitle
        case .success:    return successTitle
        case .failure:    return failureTitle
        }
    }

    private var failureMessage: String? {
        if case .failure(let message, _) = phase, !message.isEmpty { return message }
        return nil
    }

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 0)

            VStack(spacing: 16) {
                iconSlot

                VStack(spacing: 8) {
                    Text(statusTitle)
                        .font(.title2.weight(.semibold))
                        .contentTransition(.opacity)
                        .multilineTextAlignment(.center)

                    // Reserved slot so success ↔ failure never nudges the icon above it.
                    Text(failureMessage ?? " ")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .lineLimit(3)
                        .opacity(failureMessage == nil ? 0 : 1)
                        .padding(.horizontal, 32)
                        .frame(minHeight: 44)
                }
            }

            Spacer(minLength: 0)

            if !details.isEmpty {
                VStack(spacing: 0) {
                    ForEach(Array(details.enumerated()), id: \.element.id) { index, row in
                        detailRow(row)
                        if index < details.count - 1 { divider }
                    }
                }
                .padding(.horizontal)
                .padding(.bottom, 12)
            }

            actionButton
                .padding(.horizontal)
                .padding(.bottom, 16)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .animation(.snappy(duration: 0.35), value: phaseKey)
        .onChange(of: phase) { _, newPhase in handlePhase(newPhase) }
        .onAppear { handlePhase(phase) }
    }

    // MARK: Morphing icon slot (fixed footprint — never moves or resizes)

    @ViewBuilder
    private var iconSlot: some View {
        ZStack {
            switch phase {
            case .processing:
                SpinnerRing()
                    .transition(.opacity.combined(with: .scale(scale: 0.9)))
            case .success:
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 64))
                    .foregroundStyle(.green)
                    .symbolEffect(.bounce, value: phaseKey)
                    .transition(.scale(scale: 0.7).combined(with: .opacity))
            case .failure(_, let isCaution):
                Image(systemName: isCaution ? "exclamationmark.triangle.fill" : "xmark.circle.fill")
                    .font(.system(size: 64))
                    .foregroundStyle(isCaution ? .orange : .red)
                    .symbolEffect(.bounce, value: phaseKey)
                    .transition(.scale(scale: 0.7).combined(with: .opacity))
            }
        }
        .frame(width: 72, height: 72)
    }

    @ViewBuilder
    private var actionButton: some View {
        switch phase {
        case .processing:
            // Reserve the CTA footprint so Done/Try Again don't shift layout in.
            Button(action: {}) { Text(verbatim: " ") }
                .glassButton()
                .disabled(true)
                .opacity(0)
                .accessibilityHidden(true)
        case .success:
            Button(action: onDone) { Text("Done") }
                .glassButton()
        case .failure:
            Button(action: onRetry) { Text("Try Again") }
                .glassButton()
        }
    }

    private func detailRow(_ row: DetailRow) -> some View {
        HStack {
            Label(row.label, systemImage: row.icon)
                .foregroundStyle(.secondary)
            Spacer()
            Text(row.value)
                .fontWeight(.medium)
                .multilineTextAlignment(.trailing)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .font(.subheadline)
        .padding(.horizontal, 4)
        .padding(.vertical, 14)
        .accessibilityElement(children: .combine)
    }

    /// Hairline separator matching the pay screens' detail rows (no boxed background).
    private var divider: some View {
        Rectangle()
            .fill(Color(.separator))
            .frame(height: 0.5)
            .padding(.horizontal, 4)
    }

    private func handlePhase(_ newPhase: Phase) {
        switch newPhase {
        case .success:
            HapticFeedback.notification(.success)
        case .failure(_, let isCaution):
            HapticFeedback.notification(isCaution ? .warning : .error)
        case .processing:
            break
        }
    }
}

/// 64pt loading ring that shares the checkmark's diameter, so the processing →
/// success cross-fade reads as the ring "closing" into the check rather than a
/// small pill spinner jumping to a large glyph.
private struct SpinnerRing: View {
    @State private var spinning = false

    var body: some View {
        Circle()
            .trim(from: 0.1, to: 1.0)
            .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 6, lineCap: .round))
            .frame(width: 64, height: 64)
            .rotationEffect(.degrees(spinning ? 360 : 0))
            .animation(.linear(duration: 0.9).repeatForever(autoreverses: false), value: spinning)
            .onAppear { spinning = true }
            .accessibilityLabel("Processing")
    }
}
