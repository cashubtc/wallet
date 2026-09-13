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
