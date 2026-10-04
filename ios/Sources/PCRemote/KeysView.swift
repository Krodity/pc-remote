import SwiftUI

/// Modifiers latch on tap; the next key folds them in and clears them.
private let modifiers: [(String, Color)] = [
    ("ctrl", P.purple), ("alt", P.purple), ("shift", P.purple),
    ("super", P.accent), ("altgr", P.sub), ("caps", P.sub),
]

private let specials: [(String, Color)] = [
    ("esc", P.red), ("tab", P.sub), ("del", P.red), ("backspace", P.red),
    ("home", P.sub), ("end", P.sub), ("pgup", P.sub), ("pgdn", P.sub),
    ("insert", P.sub), ("menu", P.sub), ("printscreen", P.sub), ("pause", P.sub),
]

private let fnKeys = (1...12).map { "f\($0)" }

/// Read off the live Hyprland config, same list as the Android build.
private let combos: [(String, String)] = [
    ("super+enter", "Terminal"),
    ("super+space", "Launcher"),
    ("super+q", "Close window"),
    ("super+f", "Fullscreen"),
    ("super+shift+enter", "Browser"),
    ("super+shift+f", "Files"),
    ("ctrl+c", "Copy"),
    ("ctrl+v", "Paste"),
    ("ctrl+x", "Cut"),
    ("ctrl+z", "Undo"),
    ("ctrl+a", "Select all"),
    ("ctrl+s", "Save"),
    ("ctrl+alt+delete", "Session menu"),
    ("super+shift+s", "Screenshot"),
]

private let media: [(String, String)] = [
    ("playpause", "Play/Pause"), ("previoussong", "Prev"), ("nextsong", "Next"),
    ("volumedown", "Vol −"), ("volumeup", "Vol +"), ("mute", "Mute"),
]

struct KeysView: View {
    @EnvironmentObject var model: RemoteModel
    @State private var draft = ""

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                TextField("Type text to send…", text: $draft)
                    .font(Fonts.mono(15))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.send)
                    .onSubmit(send)
                    .padding(12)
                    .background(P.bg, in: RoundedRectangle(cornerRadius: 8))
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(P.border))
                Button(action: send) {
                    Image(systemName: "paperplane.fill").foregroundStyle(.black)
                        .frame(width: 46, height: 46)
                        .background(P.accent, in: RoundedRectangle(cornerRadius: 8))
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .background(P.surface)
            Rectangle().fill(P.border).frame(height: 1)

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    section("MODIFIERS") {
                        grid(3, modifiers) { k, c in
                            KeyTile(label: k, accent: c, active: model.heldModifiers.contains(k)) {
                                model.toggleModifier(k)
                            }
                        }
                    }
                    section("SPECIAL KEYS") {
                        grid(4, specials) { k, c in
                            KeyTile(label: k, accent: c, size: 11) { model.sendKey(k) }
                        }
                    }
                    section("NAVIGATE") {
                        DPad { model.sendKey($0) }.frame(maxWidth: .infinity)
                    }
                    section("FUNCTION KEYS") {
                        grid(6, fnKeys.map { ($0, P.text) }) { k, _ in
                            KeyTile(label: k.uppercased(), size: 11) { model.sendKey(k) }
                        }
                    }
                    section("MEDIA") {
                        grid(3, media) { k, label in
                            KeyTile(label: label, mono: false, size: 12) { model.sendKey(k) }
                        }
                    }
                    section("QUICK COMBOS") {
                        grid(2, combos) { combo, label in
                            ComboTile(combo: combo, label: label) { model.sendCombo(combo) }
                        }
                    }
                    if !model.keyLog.isEmpty {
                        section("SENT") {
                            VStack(alignment: .leading, spacing: 2) {
                                ForEach(Array(model.keyLog.suffix(8).reversed().enumerated()), id: \.offset) { _, s in
                                    Text(s).font(Fonts.mono(11)).foregroundStyle(P.sub)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(10)
                            .background(P.surface, in: RoundedRectangle(cornerRadius: 8))
                        }
                    }
                }
                .padding(.bottom, 12)
            }

            // Latched modifiers are easy to forget; keep them visible.
            if !model.heldModifiers.isEmpty {
                HStack {
                    Text(model.heldModifiers.joined(separator: "+") + "+…")
                        .font(Fonts.mono(13)).foregroundStyle(P.accent)
                    Spacer()
                    Button("clear") { model.clearModifiers() }.font(.caption).foregroundStyle(P.sub)
                }
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(P.panel)
            }
        }
        .background(P.bg)
    }

    private func send() {
        model.sendText(draft)
        draft = ""
    }

    private func section<C: View>(_ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.caption2.bold()).foregroundStyle(P.sub).tracking(1)
            content()
        }
        .padding(.horizontal, 14).padding(.top, 14)
    }

    private func grid<A, B, C: View>(_ cols: Int, _ items: [(A, B)],
                                     @ViewBuilder cell: @escaping (A, B) -> C) -> some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: cols), spacing: 8) {
            ForEach(items.indices, id: \.self) { i in cell(items[i].0, items[i].1) }
        }
    }
}

private struct KeyTile: View {
    let label: String
    var accent: Color = P.text
    var active = false
    var mono = true
    var size: CGFloat = 13
    let action: () -> Void

    var body: some View {
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            action()
        } label: {
            Text(label)
                .font(mono ? Fonts.mono(size) : .system(size: size))
                .foregroundStyle(active ? .black : accent)
                .lineLimit(1).minimumScaleFactor(0.6)
                .frame(maxWidth: .infinity).padding(.vertical, 12)
                .background(active ? accent : P.panel, in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(active ? accent : P.border))
        }
    }
}

private struct ComboTile: View {
    let combo: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            action()
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                Text(combo.split(separator: "+").map { $0.prefix(1).uppercased() + $0.dropFirst() }
                        .joined(separator: "+"))
                    .font(Fonts.mono(12)).foregroundStyle(P.accent)
                Text(label).font(.caption).foregroundStyle(P.sub)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(P.panel, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(P.border))
        }
    }
}

/// The circular arrow cluster, Enter at its centre.
private struct DPad: View {
    let send: (String) -> Void
    private let cell: CGFloat = 58

    var body: some View {
        ZStack {
            Circle().fill(P.panel).overlay(Circle().stroke(P.border))
            VStack(spacing: 0) {
                arrow("up", "chevron.up")
                HStack(spacing: 0) {
                    arrow("left", "chevron.left")
                    Button { tap("enter") } label: {
                        Image(systemName: "return").foregroundStyle(P.sub)
                            .frame(width: 40, height: 40)
                            .background(P.surface, in: Circle())
                            .overlay(Circle().stroke(P.accentD, lineWidth: 1.5))
                    }
                    .frame(width: cell, height: cell)
                    arrow("right", "chevron.right")
                }
                arrow("down", "chevron.down")
            }
        }
        .frame(width: cell * 3, height: cell * 3)
    }

    private func arrow(_ key: String, _ icon: String) -> some View {
        Button { tap(key) } label: {
            Image(systemName: icon).font(.title3).foregroundStyle(P.text)
                .frame(width: cell, height: cell)
                .contentShape(Rectangle())
        }
    }

    private func tap(_ key: String) {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        send(key)
    }
}
