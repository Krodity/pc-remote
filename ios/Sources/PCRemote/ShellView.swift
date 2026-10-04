import SwiftUI
import SwiftTerm
import UIKit

/// A real PTY on the PC, rendered by SwiftTerm.
///
/// SwiftTerm is a full xterm emulator, so the powerline prompt, colours,
/// `top` and `vim` all render as they do at the desk — the Android build had
/// to hand-roll an ANSI interpreter for this.
struct ShellView: View {
    @EnvironmentObject var model: RemoteModel
    @StateObject private var session = ShellSession()

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                TerminalHost(session: session)
                if session.exited {
                    VStack(spacing: 10) {
                        Text("Shell exited").foregroundStyle(P.sub)
                        Button("Start a new shell") { session.open() }
                            .buttonStyle(.borderedProminent)
                    }
                    .padding(20)
                    .background(P.panel.opacity(0.95), in: RoundedRectangle(cornerRadius: 12))
                }
            }
            controlStrip
        }
        .background(P.term)
        .onAppear { session.attach(model) }
        .onChange(of: model.socketGen) { _, _ in session.open() }
    }

    /// The keys a soft keyboard lacks, always one tap away.
    private var controlStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                strip("^C") { model.ptySignal("SIGINT") }
                strip("^D") { model.ptyIn("\u{04}") }
                strip("^Z") { model.ptyIn("\u{1A}") }
                strip("^L") { model.ptyIn("\u{0C}") }
                strip("Tab") { model.ptyIn("\t") }
                strip("Esc") { model.ptyIn("\u{1B}") }
                strip("↑") { model.ptyIn("\u{1B}[A") }
                strip("↓") { model.ptyIn("\u{1B}[B") }
                strip("←") { model.ptyIn("\u{1B}[D") }
                strip("→") { model.ptyIn("\u{1B}[C") }
                strip("⌨︎") { session.toggleKeyboard() }
            }
            .padding(.horizontal, 10).padding(.vertical, 8)
        }
        .background(P.nav)
    }

    private func strip(_ label: String, _ action: @escaping () -> Void) -> some View {
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            action()
        } label: {
            Text(label).font(Fonts.mono(13)).foregroundStyle(P.text)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(P.panel, in: RoundedRectangle(cornerRadius: 6))
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(P.border))
        }
    }
}

/// Owns the terminal view so it survives tab switches, and bridges it to the
/// model's socket.
@MainActor
final class ShellSession: NSObject, ObservableObject, TerminalViewDelegate {
    @Published var exited = false

    let terminal: TerminalView
    private weak var model: RemoteModel?
    private var cols = 80
    private var rows = 24
    private var opened = false

    override init() {
        terminal = TerminalView(frame: CGRect(x: 0, y: 0, width: 390, height: 600),
                                font: Fonts.uiMono(12))
        super.init()
        terminal.terminalDelegate = self
        terminal.nativeBackgroundColor = UIColor(P.term)
        terminal.nativeForegroundColor = UIColor(P.text)
        terminal.caretColor = UIColor(P.accent)
        terminal.keyboardAppearance = .dark
    }

    func attach(_ model: RemoteModel) {
        guard self.model == nil else { return }
        self.model = model
        model.onPtyOut = { [weak self] s in self?.terminal.feed(text: s) }
        model.onPtyReady = { [weak self] in self?.exited = false }
        model.onPtyExit = { [weak self] in
            self?.exited = true
            self?.opened = false
        }
        if model.link == .online { open() }
    }

    func open() {
        guard let model else { return }
        exited = false
        opened = true
        model.ptyOpen(cols: cols, rows: rows)
    }

    func toggleKeyboard() {
        if terminal.isFirstResponder {
            _ = terminal.resignFirstResponder()
        } else {
            _ = terminal.becomeFirstResponder()
        }
    }

    // ── TerminalViewDelegate ─────────────────────────────────────────────────
    nonisolated func send(source: TerminalView, data: ArraySlice<UInt8>) {
        let s = String(decoding: data, as: UTF8.self)
        Task { @MainActor in self.model?.ptyIn(s) }
    }

    nonisolated func sizeChanged(source: TerminalView, newCols: Int, newRows: Int) {
        Task { @MainActor in
            self.cols = newCols
            self.rows = newRows
            if self.opened { self.model?.ptyResize(cols: newCols, rows: newRows) }
        }
    }

    nonisolated func setTerminalTitle(source: TerminalView, title: String) {}
    nonisolated func hostCurrentDirectoryUpdate(source: TerminalView, directory: String?) {}
    nonisolated func scrolled(source: TerminalView, position: Double) {}
    nonisolated func requestOpenLink(source: TerminalView, link: String, params: [String: String]) {
        if let url = URL(string: link) {
            Task { @MainActor in UIApplication.shared.open(url) }
        }
    }
    nonisolated func rangeChanged(source: TerminalView, startY: Int, endY: Int) {}
    nonisolated func clipboardCopy(source: TerminalView, content: Data) {
        if let s = String(data: content, encoding: .utf8) {
            Task { @MainActor in UIPasteboard.general.string = s }
        }
    }
}

private struct TerminalHost: UIViewRepresentable {
    let session: ShellSession

    func makeUIView(context: Context) -> TerminalView { session.terminal }
    func updateUIView(_ v: TerminalView, context: Context) {}
}
