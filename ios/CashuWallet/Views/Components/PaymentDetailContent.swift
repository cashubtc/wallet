import SwiftUI

extension EnvironmentValues {
    @Entry var compactPaymentDetails = false
}

enum PaymentDetailMetrics {
    static let maxWidth: CGFloat = 320
    static let horizontalPadding: CGFloat = 16
    static let verticalPadding: CGFloat = 8
    static let minimumTouchHeight: CGFloat = 44
}

enum PaymentDetailLayout {
    case flow
    // Compact History sheets use their full width and roomier receipt rows.
    case history
}

extension View {
    /// Shared receipt typography and spacing; interactive rows keep native touch targets.
    func paymentDetailRow(layout: PaymentDetailLayout = .flow, isInteractive: Bool = false) -> some View {
        self
            .font(.footnote)
            .padding(.horizontal, layout == .history ? 4 : PaymentDetailMetrics.horizontalPadding)
            .padding(.vertical, layout == .history ? 12 : PaymentDetailMetrics.verticalPadding)
            .frame(minHeight: isInteractive || layout == .history ? PaymentDetailMetrics.minimumTouchHeight : nil)
            .frame(maxWidth: layout == .history ? .infinity : PaymentDetailMetrics.maxWidth)
            .frame(maxWidth: .infinity)
    }
}

/// Preserve the compact two-column receipt at ordinary sizes; never truncate
/// payment facts to keep that shape when text or the value needs more space.
struct PaymentDetailPair<Value: View>: View {
    let label: String
    @ViewBuilder var value: () -> Value
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        if dynamicTypeSize.isAccessibilitySize {
            stacked
        } else {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .firstTextBaseline) {
                    Text(label).foregroundStyle(.secondary)
                    Spacer(minLength: 16)
                    HStack(alignment: .firstTextBaseline) { value() }
                        .multilineTextAlignment(.trailing)
                        .fixedSize(horizontal: true, vertical: false)
                }
                stacked
            }
        }
    }

    private var stacked: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).foregroundStyle(.secondary)
            HStack(alignment: .firstTextBaseline) { value() }
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Fits the QR around the receipt's actual text, keeping related details together.
/// Scrolling remains available when accessibility text needs more than one screen.
struct PaymentDetailContent<Hero: View, Details: View>: View {
    @ViewBuilder let hero: (CGFloat) -> Hero
    @ViewBuilder let details: () -> Details
    @State private var detailsHeight: CGFloat = 0

    var body: some View {
        GeometryReader { geometry in
            let qrSize = max(120, min(280, geometry.size.width - 64,
                                      geometry.size.height - detailsHeight - 64))
            ScrollView {
                VStack(spacing: 16) {
                    hero(qrSize)
                    details()
                        .environment(\.compactPaymentDetails, geometry.size.height < 600)
                        .fixedSize(horizontal: false, vertical: true)
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { detailsHeight = $0 }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
    }
}

/// The full quote ID is the interoperable counter reference; its suffix is a visual aid.
struct QuoteReferenceDetails: View {
    let quoteID: String
    var request: String = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button {
                UIPasteboard.general.string = quoteID
                ConfirmationToast.show("Copied quote ID")
            } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Quote ID").font(.caption).foregroundStyle(.secondary)
                    Text("\(Text(String(quoteID.dropLast(6))).foregroundStyle(.secondary))\(Text(String(quoteID.suffix(6))).bold())")
                        .font(.footnote.monospaced())
                        .multilineTextAlignment(.leading)
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Quote ID: \(quoteID)")
            .accessibilityHint("Copy the full quote ID")
            if !request.isEmpty && request != quoteID {
                Button {
                    UIPasteboard.general.string = request
                    ConfirmationToast.show("Copied payment request")
                } label: {
                    Text(verbatim: request).font(.body).multilineTextAlignment(.leading)
                }
                .buttonStyle(.plain)
                .accessibilityHint("Copy the payment request")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}


/// Native activity presentation shared by transactions and stored requests.
/// Each body retains its adaptive QR, status cues and pinned actions.
struct ActivityDetailSheet<Content: View>: View {
    let title: String
    var contentHeight: CGFloat = 0
    var fitsContent = false
    var onShare: (() -> Void)?
    @ViewBuilder var content: () -> Content
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            content()
                .navigationBarTitleDisplayMode(.inline)
                .toolbarBackground(.hidden, for: .navigationBar)
                .toolbar {
                    ToolbarItem(placement: .principal) {
                        Text(title).font(.headline)
                    }
                    if let onShare {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button(action: onShare) {
                                Image(systemName: "square.and.arrow.up")
                                    .toolbarIconTapTarget()
                            }
                            .accessibilityLabel("Share")
                        }
                    }
                }
        }
        .walletSheetSurface(fillsScreen: !fitsContent)
        .contentFitDetent(contentHeight, enabled: fitsContent, estimate: 500, navigationBar: true)
        .presentationDragIndicator(.visible)
        .accessibilityAction(.escape) { dismiss() }
    }
}
