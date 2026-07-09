import SwiftUI

struct MintDiscoverySheet: View {
    let addMint: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var walletManager: WalletManager
    @ObservedObject private var discoveryManager = MintDiscoveryManager.shared
    @ObservedObject private var settings = SettingsManager.shared

    @State private var searchText = ""
    @State private var addedURLsThisSession: Set<String> = []
    @State private var previews: [String: MintIdentityPreview] = [:]

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("Discover Mints")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Done") { dismiss() }
                            .fontWeight(.semibold)
                    }
                }
        }
        .onDisappear {
            previews = [:]
            discoveryManager.clearDiscoveredMints()
        }
    }

    @ViewBuilder
    private var content: some View {
        if !settings.useWebsockets {
            NativeEmptyState(
                title: "WebSockets Required",
                systemImage: "antenna.radiowaves.left.and.right.slash",
                description: "Enable WebSocket connections in Settings to discover mints over Nostr."
            )
        } else {
            List {
                if discoveryManager.isDiscovering {
                    Section {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text("Discovering mints…")
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                if !addedMints.isEmpty {
                    Section {
                        ForEach(addedMints) { mint in
                            addedRow(for: mint)
                        }
                    } header: {
                        Text("Added")
                    }
                }

                if !discoverableMints.isEmpty {
                    Section {
                        ForEach(discoverableMints) { mint in
                            discoverableRow(for: mint)
                        }
                    } header: {
                        Text("Discovered")
                    }
                } else if addedMints.isEmpty && !discoveryManager.isDiscovering {
                    Section {
                        if searchText.isEmpty {
                            NativeEmptyState(
                                title: "No Mints Found",
                                systemImage: "magnifyingglass",
                                description: "Pull down to retry.",
                                style: .section
                            )
                        } else {
                            NativeEmptyState(
                                title: "No Results",
                                systemImage: "magnifyingglass",
                                description: "No mint matches \"\(searchText)\".",
                                style: .section
                            )
                        }
                    }
                    .listRowBackground(Color.clear)
                }
            }
            .animation(.smooth(duration: 0.3), value: addedURLsThisSession)
            .searchable(text: $searchText, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search mints")
            .refreshable {
                await discoveryManager.discoverMints()
                await loadMissingPreviews()
            }
            .task {
                if discoveryManager.discoveredMints.isEmpty {
                    await discoveryManager.discoverMints()
                }
                await loadMissingPreviews()
            }
            .task(id: discoveredMintURLsKey) {
                await loadMissingPreviews()
            }
        }
    }

    private var filteredMints: [DiscoveredMint] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return discoveryManager.discoveredMints }
        return discoveryManager.discoveredMints.filter { mint in
            displayName(for: mint).localizedCaseInsensitiveContains(query)
                || mint.url.localizedCaseInsensitiveContains(query)
        }
    }

    private var discoveredMintURLsKey: String {
        discoveryManager.discoveredMints.map(\.url).joined(separator: "\n")
    }

    private var addedMints: [DiscoveredMint] {
        filteredMints.filter { isAlreadyAdded($0) }
    }

    private var discoverableMints: [DiscoveredMint] {
        filteredMints.filter { !isAlreadyAdded($0) }
    }

    private func isAlreadyAdded(_ mint: DiscoveredMint) -> Bool {
        addedURLsThisSession.contains(mint.url)
            || walletManager.mints.contains(where: { $0.url == mint.url })
    }

    @ViewBuilder
    private func addedRow(for mint: DiscoveredMint) -> some View {
        HStack(spacing: 12) {
            MintAvatarView(iconUrl: avatarIconUrl(for: mint), name: displayName(for: mint), size: 36)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(displayName(for: mint))
                    .font(.body)
                Text(mint.url)
                    .font(.caption)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer(minLength: 8)

            Image(systemName: "checkmark.circle.fill")
                .font(.title3)
                .symbolEffect(.bounce, value: reduceMotion ? false : addedURLsThisSession.contains(mint.url))
                .accessibilityLabel("Added")
        }
        .foregroundStyle(.secondary)
        .opacity(0.7)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func discoverableRow(for mint: DiscoveredMint) -> some View {
        HStack(spacing: 12) {
            MintAvatarView(iconUrl: avatarIconUrl(for: mint), name: displayName(for: mint), size: 36)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(displayName(for: mint))
                    .font(.body)
                Text(mint.url)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer(minLength: 8)

            Button {
                withAnimation(.smooth(duration: 0.3)) {
                    addedURLsThisSession.insert(mint.url)
                }
                addMint(mint.url)
                HapticFeedback.selection()
            } label: {
                Image(systemName: "plus.circle.fill")
                    .font(.title3)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Add \(displayName(for: mint))")
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private func avatarIconUrl(for mint: DiscoveredMint) -> String? {
        previews[mint.url]?.iconUrl
            ?? mint.iconUrl
            ?? walletManager.mints.first(where: { $0.url == mint.url })?.iconUrl
    }

    private func displayName(for mint: DiscoveredMint) -> String {
        if let name = previews[mint.url]?.name, !name.isEmpty { return name }
        return mint.displayName
    }

    private func loadMissingPreviews() async {
        for mint in discoveryManager.discoveredMints where previews[mint.url] == nil {
            guard let info = await walletManager.fetchMintPreviewInfo(url: mint.url) else { continue }
            previews[mint.url] = MintIdentityPreview(name: info.name, iconUrl: info.iconUrl)
        }
    }
}

private struct MintIdentityPreview {
    let name: String?
    let iconUrl: String?
}

#Preview {
    MintDiscoverySheet(addMint: { _ in })
        .environmentObject(WalletManager())
}
