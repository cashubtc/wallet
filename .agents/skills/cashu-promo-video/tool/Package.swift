// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "cashu-promo",
    platforms: [.macOS(.v15)],
    products: [
        .executable(name: "promo", targets: ["Promo"]),
    ],
    targets: [
        // The ASCII field's pure math, copied from the app (see the header in
        // FieldMath.swift). No UI imports, so the parity tests drive it bare.
        .target(name: "Field"),
        .executableTarget(name: "Promo", dependencies: ["Field"]),
        .testTarget(name: "FieldTests", dependencies: ["Field"]),
    ],
    swiftLanguageModes: [.v5]
)
