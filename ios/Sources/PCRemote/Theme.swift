import SwiftUI
import CoreText

/// The Android app's palette, carried over unchanged.
///
/// A fixed dark scheme on purpose: the colours carry meaning (amber =
/// directory, red = danger, purple = modifier, cyan = the one interactive
/// accent), and letting the system recolour them would lose it.
enum P {
    static let bg = Color(hex: 0x07101C)
    static let nav = Color(hex: 0x0B1523)
    static let surface = Color(hex: 0x0F1B2D)
    static let panel = Color(hex: 0x132237)
    static let border = Color(hex: 0x1A2F4A)
    static let accent = Color(hex: 0x22D3EE)
    static let accentD = Color(hex: 0x0891B2)
    static let glow = Color(hex: 0x22D3EE, alpha: 0.12)
    static let green = Color(hex: 0x34D399)
    static let red = Color(hex: 0xF87171)
    static let amber = Color(hex: 0xFBBF24)
    static let purple = Color(hex: 0xA78BFA)
    static let text = Color(hex: 0xC8DDEF)
    static let sub = Color(hex: 0x5C7FA3)
    static let mute = Color(hex: 0x1E3350)
    static let term = Color(hex: 0x050C18)
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }
}

/// JetBrains Mono, the Nerd Font build.
///
/// Not cosmetic: the PC's shell prompt is powerline-styled, and those glyphs
/// live in the Private Use Area. With the system monospace font they render
/// as boxes. SIL Open Font License — see docs/OFL-JetBrainsMono.txt.
enum Fonts {
    /// Registers the bundled TTFs with the process. Idempotent.
    static func register() {
        guard let dir = Bundle.module.resourceURL?.appendingPathComponent("Resources"),
              let files = try? FileManager.default.contentsOfDirectory(
                at: dir, includingPropertiesForKeys: nil)
        else { return }
        for url in files where url.pathExtension == "ttf" {
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }

    /// The real PostScript name is read back rather than guessed, since the
    /// Nerd Font builds have renamed their families more than once.
    static let monoFamily: String = {
        register()
        guard let dir = Bundle.module.resourceURL?.appendingPathComponent("Resources"),
              let data = try? Data(contentsOf: dir.appendingPathComponent("jetbrains_mono_nf_regular.ttf")),
              let desc = CTFontManagerCreateFontDescriptorFromData(data as CFData),
              let name = CTFontDescriptorCopyAttribute(desc, kCTFontNameAttribute) as? String
        else { return "Menlo" }
        return name
    }()

    static func mono(_ size: CGFloat) -> Font {
        .custom(monoFamily, size: size)
    }

    static func uiMono(_ size: CGFloat) -> UIFont {
        UIFont(name: monoFamily, size: size)
            ?? .monospacedSystemFont(ofSize: size, weight: .regular)
    }
}
