import SwiftUI

extension EnvironmentValues {
    @Entry var compactPaymentDetails = false
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
        .compactBottomSheetSurface()
        .contentFitDetent(contentHeight, enabled: fitsContent, estimate: 500, navigationBar: true)
        .presentationDragIndicator(.visible)
        .accessibilityAction(.escape) { dismiss() }
    }
}
