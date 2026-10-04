// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "PCRemote",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        // xtool wants exactly one library product: the app itself.
        .library(name: "PCRemote", targets: ["PCRemote"]),
    ],
    dependencies: [
        // Vendored, not fetched — see Vendor/SwiftTerm/Package.swift for why.
        .package(path: "Vendor/SwiftTerm"),
    ],
    targets: [
        .target(
            name: "PCRemote",
            dependencies: [.product(name: "SwiftTerm", package: "SwiftTerm")],
            resources: [.copy("Resources")]
        ),
    ]
)
