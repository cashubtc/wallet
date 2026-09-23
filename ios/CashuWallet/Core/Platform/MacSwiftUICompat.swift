#if os(macOS)
import SwiftUI

// MARK: - iOS-only SwiftUI surface
//
// Reproduces the handful of SwiftUI modifiers that iOS has and macOS does not,
// as no-ops or as the nearest honest equivalent. That is what lets ~26 view
// files compile untouched instead of being rewritten around platform branches.
//
// Scope note, verified against the macOS 26.1 SDK rather than assumed:
// `PresentationDetent`, `presentationDetents` and `presentationDragIndicator`
// are all *available* on macOS and are deliberately NOT shimmed here — adding
// them made every `.medium` and `.large` ambiguous. Only genuinely absent or
// `@available(macOS, unavailable)` API belongs in this file.

// MARK: - Navigation bar

/// Mirrors `SwiftUI.NavigationBarItem`, which exists on macOS but is marked
/// unavailable. macOS titles a window, not a bar, and has no large/inline split.
enum NavigationBarItem {
    enum TitleDisplayMode {
        case automatic, inline, large
    }
}

extension View {
    func navigationBarTitleDisplayMode(_ mode: NavigationBarItem.TitleDisplayMode) -> some View {
        self
    }
}

// MARK: - Toolbar placement
//
// The bar-relative placements are iOS-only. Mapping leading onto `.navigation`
// and trailing onto `.primaryAction` preserves the intent — first item on the
// left, action on the right — using the placements macOS actually has.

extension ToolbarItemPlacement {
    static var topBarLeading: ToolbarItemPlacement { .navigation }
    static var topBarTrailing: ToolbarItemPlacement { .primaryAction }
    static var navigationBarLeading: ToolbarItemPlacement { .navigation }
    static var navigationBarTrailing: ToolbarItemPlacement { .primaryAction }
}

extension ToolbarPlacement {
    /// macOS has one toolbar per window; the navigation bar is that toolbar.
    static var navigationBar: ToolbarPlacement { .windowToolbar }

    /// There is no tab bar to hide on macOS. Resolving to the window toolbar
    /// keeps `.toolbar(.hidden, for: .tabBar)` meaning "hide the chrome", which
    /// is what the call sites are asking for — and the menu bar panel has no
    /// visible titlebar anyway.
    static var tabBar: ToolbarPlacement { .windowToolbar }
}

// MARK: - Search

extension SearchFieldPlacement {
    enum CashuNavigationBarDrawerDisplayMode {
        case automatic, always
    }

    /// iOS can park the search field in a drawer under the navigation bar.
    /// macOS puts it in the toolbar and offers no equivalent, so this resolves
    /// to the platform default.
    static func navigationBarDrawer(
        displayMode: CashuNavigationBarDrawerDisplayMode
    ) -> SearchFieldPlacement {
        .automatic
    }
}

// MARK: - List metrics

extension View {
    /// iOS-only spacing control for grouped list sections.
    func listSectionSpacing(_ spacing: CGFloat) -> some View {
        self
    }
}

// MARK: - Sheet sizing
//
// iOS sizes a sheet with detents and lets the content flex inside it. macOS
// accepts the same modifier but ignores it: a sheet is a window sized to its
// content's *ideal* size, and these phone layouts (scroll views, spacers,
// camera previews) have almost none, so every sheet collapsed to a strip. The
// detents are mapped onto an explicit frame instead, measured against the menu
// bar panel the sheet hangs from. Shadowing `presentationDetents` itself is
// not an option — the overload is ambiguous at some call sites — so views go
// through `sheetDetents(_:)`.

enum MacSheetMetrics {
    /// Inset from the panel so the sheet reads as attached to it.
    static let width = MacMenuBarController.panelSize.width - 24
    static let large = MacMenuBarController.panelSize.height - 40
    static let medium = (MacMenuBarController.panelSize.height / 2).rounded()

    /// A Mac sheet cannot be dragged between detents, so the tallest wins.
    static func height(for detents: Set<SheetDetent>) -> CGFloat {
        detents.map { detent in
            switch detent {
            case .medium: medium
            case .large: large
            case .height(let height): height
            }
        }.max() ?? large
    }
}

extension View {
    func macSheetFrame(height: CGFloat) -> some View {
        modifier(MacSheetChrome(height: min(height, MacSheetMetrics.large)))
    }
}

/// Set by `sheetDismissDisabled(_:)` from inside a sheet, read by its chrome.
struct SheetDismissDisabledKey: PreferenceKey {
    static let defaultValue = false
    static func reduce(value: inout Bool, nextValue: () -> Bool) {
        value = value || nextValue()
    }
}

/// Sizes a sheet and lets esc close it.
///
/// iOS sheets are closed by swiping down; the toolbar close button is the
/// fallback. macOS has no swipe, and a toolbar inside a sheet window is not
/// reliably rendered, so without this some sheets could only be escaped by
/// closing the whole panel. The shortcut is off while the sheet has asked not
/// to be dismissed — the same in-flight guard that blocks the swipe on iOS.
private struct MacSheetChrome: ViewModifier {
    let height: CGFloat

    @Environment(\.dismiss) private var dismiss
    @State private var dismissDisabled = false

    func body(content: Content) -> some View {
        content
            .frame(width: MacSheetMetrics.width, height: height)
            .onPreferenceChange(SheetDismissDisabledKey.self) { dismissDisabled = $0 }
            .background {
                Button("Close") { dismiss() }
                    .keyboardShortcut(.cancelAction)
                    .disabled(dismissDisabled)
                    .opacity(0)
                    .accessibilityHidden(true)
            }
    }
}

// MARK: - Full-screen presentation

/// macOS has no full-screen cover. A sheet is the honest equivalent: modal,
/// dismissible, owns the interaction. The menu bar panel presents it as a real
/// window sheet, so the flow reads the same even though the chrome differs.
extension View {
    func fullScreenCover<Item: Identifiable, Content: View>(
        item: Binding<Item?>,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping (Item) -> Content
    ) -> some View {
        sheet(item: item, onDismiss: onDismiss) { content($0).macSheetFrame(height: MacSheetMetrics.large) }
    }

    func fullScreenCover<Content: View>(
        isPresented: Binding<Bool>,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping () -> Content
    ) -> some View {
        sheet(isPresented: isPresented, onDismiss: onDismiss) { content().macSheetFrame(height: MacSheetMetrics.large) }
    }
}

// MARK: - Text input

/// Mirrors `UIKit.UIKeyboardType`. There is no software keyboard to configure
/// on macOS, so the hint is dropped.
enum UIKeyboardType {
    case `default`, asciiCapable, numbersAndPunctuation, URL, numberPad
    case phonePad, namePhonePad, emailAddress, decimalPad, twitter, webSearch
    case asciiCapableNumberPad
}

/// Mirrors `SwiftUI.TextInputAutocapitalization`. Hardware keyboards do not
/// autocapitalise, so there is nothing to suppress.
struct TextInputAutocapitalization {
    static let never = TextInputAutocapitalization()
    static let words = TextInputAutocapitalization()
    static let sentences = TextInputAutocapitalization()
    static let characters = TextInputAutocapitalization()
}

extension View {
    func keyboardType(_ type: UIKeyboardType) -> some View {
        self
    }

    func textInputAutocapitalization(_ style: TextInputAutocapitalization?) -> some View {
        self
    }
}
#endif
