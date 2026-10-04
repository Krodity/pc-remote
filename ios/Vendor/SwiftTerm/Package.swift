// swift-tools-version: 5.9

// SwiftTerm v1.13.0 (MIT, github.com/migueldeicaza/SwiftTerm), vendored.
//
// Upstream's manifest excludes the Apple/iOS sources under `#if os(Linux)`.
// A manifest is evaluated on the *build* host, so an xtool cross-compile
// from Linux to iOS drops the whole TerminalView. This manifest keeps them.

import PackageDescription

let package = Package(
    name: "SwiftTerm",
    platforms: [.iOS(.v13), .macOS(.v13)],
    products: [.library(name: "SwiftTerm", targets: ["SwiftTerm"])],
    targets: [
        .target(
            name: "SwiftTerm",
            path: "Sources/SwiftTerm",
            exclude: ["Mac/README.md"],
            // Copied, not processed: there is no Metal compiler on Linux, and
            // the optional Metal renderer compiles this from source at runtime.
            resources: [.copy("Apple/Metal/Shaders.metal")]
        ),
    ]
)
