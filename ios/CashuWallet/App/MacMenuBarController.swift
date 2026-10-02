#if os(macOS)
import AppKit
import Combine
import SwiftUI

extension Notification.Name {
    static let cashuMenuBarPanelDidOpen = Notification.Name("cashu.menuBar.panelDidOpen")
    static let cashuMenuBarPanelDidClose = Notification.Name("cashu.menuBar.panelDidClose")
}

// MARK: - Panel
//
// Why an NSPanel and not `MenuBarExtra(.window)`.
//
// MenuBarExtra is five lines and would have been the obvious choice, but its
// window-style content is hosted in a popover, and a popover is not a window
// that can present a sheet. This app presents around fifty sheets — the entire
// send, receive, mint and settings surface. Hosting the root in a real
// panel keeps every one of them working, which makes the "manual" route the
// smaller change by a wide margin.

/// A chromeless panel that can still take key focus. Without key status every
/// text field in the wallet — amounts, mint URLs, the twelve-word restore —
/// would silently swallow keystrokes.
private final class MenuBarPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
}

// MARK: - Controller

@MainActor
final class MacMenuBarController: NSObject {
    /// Phone-shaped, because the UI inside it is. Tall enough for the wallet
    /// home without scrolling, short enough to fit a laptop screen under the
    /// menu bar.
    static let panelSize = NSSize(width: 400, height: 700)

    private var statusItem: NSStatusItem?
    private var panel: MenuBarPanel?
    private var outsideClickMonitor: Any?

    var isPanelVisible: Bool { panel?.isVisible == true }

    func install() {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        item.button?.image = Self.statusItemImage()
        item.button?.target = self
        item.button?.action = #selector(statusItemClicked)
        // Ask for both edges so a right-click can open the quit menu without
        // stealing the plain left-click that toggles the wallet.
        item.button?.sendAction(on: [.leftMouseUp, .rightMouseUp])
        statusItem = item
    }

    /// The cashew mark, as a template image.
    ///
    /// Template means monochrome plus alpha: AppKit discards the colours and
    /// tints the opaque pixels to match the menu bar, which is what makes an
    /// icon track light/dark, the "reduce transparency" setting and the
    /// highlight state when the panel is open. So the seven-colour mark is not
    /// usable here directly — the asset is the cashew's own outline with the
    /// sunglasses knocked out as a hole, which survives the tint and still
    /// reads as the logo at 18pt.
    ///
    /// Falls back to the old SF Symbol if the asset is ever missing, because a
    /// status item with no image is an invisible, unclickable dead zone in the
    /// menu bar rather than an obvious failure.
    private static func statusItemImage() -> NSImage? {
        let image = NSImage(named: "MenuBarCashu") ?? NSImage(
            systemSymbolName: "bitcoinsign.circle",
            accessibilityDescription: nil
        )
        // Menu bar icons are sized in points against the bar, not by their
        // intrinsic size; the mark is taller than it is wide, so height leads.
        if let image, image.size.height > 0 {
            let height: CGFloat = 18
            image.size = NSSize(width: (image.size.width / image.size.height) * height, height: height)
        }
        image?.isTemplate = true
        image?.accessibilityDescription = "Cashu Wallet"
        return image
    }

    // MARK: Click handling

    @objc private func statusItemClicked() {
        let isRightClick = NSApp.currentEvent?.type == .rightMouseUp
            || NSApp.currentEvent?.modifierFlags.contains(.control) == true

        if isRightClick {
            showContextMenu()
        } else {
            togglePanel()
        }
    }

    private func showContextMenu() {
        guard let button = statusItem?.button else { return }

        let menu = NSMenu()
        menu.addItem(
            withTitle: "Quit Cashu",
            action: #selector(NSApplication.terminate(_:)),
            keyEquivalent: "q"
        )

        // Popped up directly rather than assigned to `statusItem.menu`. Assigning
        // it would make the menu own *every* click, including the left click that
        // is supposed to open the wallet, and the assign/click/unassign dance
        // that avoids that is re-entrant enough to drop clicks.
        hidePanel()
        menu.popUp(
            positioning: nil,
            at: NSPoint(x: 0, y: button.bounds.minY - 6),
            in: button
        )
    }

    func togglePanel() {
        if isPanelVisible {
            hidePanel()
        } else {
            showPanel()
        }
    }

    // MARK: Show / hide

    /// Idempotent, so a deep link can call it without toggling the wallet shut.
    func showPanel() {
        guard !isPanelVisible else { return }
        let panel = panel ?? makePanel()
        self.panel = panel

        positionUnderStatusItem(panel)
        // An accessory app is not frontmost by default, so the panel would open
        // behind whatever the user was in and never take focus.
        NSApp.activate()
        panel.makeKeyAndOrderFront(nil)
        startWatchingForOutsideClicks()
        NotificationCenter.default.post(name: .cashuMenuBarPanelDidOpen, object: nil)
    }

    func hidePanel() {
        guard let panel, panel.isVisible else { return }
        stopWatchingForOutsideClicks()
        panel.orderOut(nil)
        NotificationCenter.default.post(name: .cashuMenuBarPanelDidClose, object: nil)
    }

    private func makePanel() -> MenuBarPanel {
        let panel = MenuBarPanel(
            contentRect: NSRect(origin: .zero, size: Self.panelSize),
            styleMask: [.titled, .fullSizeContentView, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        // Chromeless, but still a titled window underneath — that is what keeps
        // it sheet-capable while looking like a popover.
        panel.titleVisibility = .hidden
        panel.titlebarAppearsTransparent = true
        panel.standardWindowButton(.closeButton)?.isHidden = true
        panel.standardWindowButton(.miniaturizeButton)?.isHidden = true
        panel.standardWindowButton(.zoomButton)?.isHidden = true
        panel.isMovable = false
        panel.isFloatingPanel = true
        panel.level = .floating
        panel.hidesOnDeactivate = false
        panel.animationBehavior = .utilityWindow
        panel.isReleasedWhenClosed = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]

        // The frame is on the SwiftUI root, not just the window.
        //
        // NSHostingView negotiates its size with the window, and this UI was
        // written for a phone: give it no width constraint and it reports a
        // small ideal width, the window shrinks to that, and the wallet renders
        // as a sliver. Pinning the root fixes the negotiation at the size the
        // layout was designed for.
        let host = NSHostingView(
            rootView: AppRootView(
                walletManager: WalletManager(),
                navigationManager: NavigationManager(),
                appLockManager: .shared
            )
            .frame(width: Self.panelSize.width, height: Self.panelSize.height)
        )
        host.frame = NSRect(origin: .zero, size: Self.panelSize)
        panel.contentView = host
        panel.setContentSize(Self.panelSize)
        panel.contentMinSize = Self.panelSize
        panel.contentMaxSize = Self.panelSize

        return panel
    }

    private func positionUnderStatusItem(_ panel: NSPanel) {
        guard
            let button = statusItem?.button,
            let buttonWindow = button.window
        else { return }

        let buttonRect = buttonWindow.convertToScreen(button.convert(button.bounds, to: nil))
        let screen = buttonWindow.screen ?? NSScreen.main
        let size = panel.frame.size

        var x = buttonRect.midX - size.width / 2
        let y = buttonRect.minY - size.height - 6

        // Keep it on screen when the status item sits near the right edge.
        if let visible = screen?.visibleFrame {
            x = min(max(x, visible.minX + 8), visible.maxX - size.width - 8)
        }

        panel.setFrameOrigin(NSPoint(x: x, y: y))
    }

    // MARK: Dismissal

    /// Closes the panel on a click anywhere else, the way a menu behaves.
    ///
    /// A global monitor only sees events destined for *other* applications, so
    /// clicks inside the wallet never reach it and cannot dismiss it by
    /// accident. Sheets belong to the panel, so they are safe too.
    private func startWatchingForOutsideClicks() {
        guard outsideClickMonitor == nil else { return }
        outsideClickMonitor = NSEvent.addGlobalMonitorForEvents(
            matching: [.leftMouseDown, .rightMouseDown]
        ) { [weak self] _ in
            Task { @MainActor in self?.hidePanel() }
        }
    }

    private func stopWatchingForOutsideClicks() {
        guard let outsideClickMonitor else { return }
        NSEvent.removeMonitor(outsideClickMonitor)
        self.outsideClickMonitor = nil
    }
}

// MARK: - Delegate

@MainActor
final class MacMenuBarAppDelegate: NSObject, NSApplicationDelegate {
    private let controller = MacMenuBarController()

    /// `cashu:` links. The wallet's NavigationManager lives inside the SwiftUI
    /// tree, which may not be mounted yet — a link can arrive before the panel
    /// has ever been opened. A current-value subject holds the link until
    /// AppRootView subscribes, where a notification would be dropped.
    static let incomingURL = CurrentValueSubject<URL?, Never>(nil)

    func applicationDidFinishLaunching(_ notification: Notification) {
        controller.install()
    }

    func application(_ application: NSApplication, open urls: [URL]) {
        guard let url = urls.first else { return }
        Self.incomingURL.send(url)
        controller.showPanel()
    }
}
#endif
