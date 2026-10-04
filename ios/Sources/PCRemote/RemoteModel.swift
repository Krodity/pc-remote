import Foundation
import SwiftUI

/// App-wide state: the pairing, the live socket, and everything the tabs share.
@MainActor
final class RemoteModel: ObservableObject {
    @Published private(set) var pairing: Pairing
    @Published private(set) var link: Link = .offline
    @Published private(set) var latency: Int?
    @Published private(set) var sysinfo: SysInfo?
    @Published private(set) var lastError: String?

    @Published private(set) var heldModifiers: [String] = []
    @Published private(set) var keyLog: [String] = []
    @Published var toast: String?
    /// Bumped each time a fresh socket comes up. The agent ties a PTY to its
    /// socket, so the Shell tab watches this to reopen one after a reconnect.
    @Published private(set) var socketGen = 0

    private(set) var client: AgentClient?
    private var ws: URLSessionWebSocketTask?
    private var socketOpen = false
    private var socketStarted = Date.distantPast
    private var pingTask: Task<Void, Never>?

    // The Shell tab plugs in here; the model only routes bytes.
    var onPtyOut: ((String) -> Void)?
    var onPtyReady: (() -> Void)?
    var onPtyExit: (() -> Void)?

    init() {
        pairing = Store.loadPairing()
        if pairing.isSet { start() }
    }

    // ── pairing ──────────────────────────────────────────────────────────────
    func pair(_ p: Pairing) {
        Store.save(p)
        pairing = Store.loadPairing()
        start()
    }

    func unpair() {
        stop()
        Store.clear()
        pairing = Pairing()
        sysinfo = nil
    }

    /// Probe a host/token pair without persisting it.
    static func verify(_ p: Pairing) async throws -> SysInfo {
        try await AgentClient(pairing: p).sysinfo()
    }

    // ── connection lifecycle ─────────────────────────────────────────────────
    func start() {
        stop()
        client = AgentClient(pairing: pairing)
        link = .connecting
        openSocket()
        pingTask = Task { [weak self] in
            var tick = 0
            while !Task.isCancelled {
                await self?.heartbeat(refreshInfo: tick % 10 == 0)
                tick += 1
                try? await Task.sleep(for: .seconds(3))
            }
        }
    }

    func stop() {
        pingTask?.cancel()
        pingTask = nil
        ws?.cancel(with: .normalClosure, reason: nil)
        ws = nil
        socketOpen = false
        link = .offline
        latency = nil
    }

    /// iOS drops the socket whenever the app is backgrounded. Called on
    /// every return to the foreground so the user never waits for the timer.
    func resume() {
        guard pairing.isSet else { return }
        if pingTask == nil { start(); return }
        Task { await heartbeat(refreshInfo: true) }
    }

    private func heartbeat(refreshInfo: Bool) async {
        guard let client else { return }
        do {
            latency = try await client.ping()
            lastError = nil
            if socketOpen, let task = ws {
                // A socket that went through a background suspension can look
                // open and swallow every send. Only a pong proves it is alive.
                if await !Self.alive(task), ws === task { dropSocket() }
            }
            // Don't tear down a handshake that is still in progress.
            if !socketOpen && (ws == nil || Date().timeIntervalSince(socketStarted) > 8) {
                openSocket()
            }
            link = socketOpen ? .online : .connecting
            if refreshInfo || sysinfo == nil {
                sysinfo = try? await client.sysinfo()
            }
        } catch {
            latency = nil
            link = .offline
            lastError = error.localizedDescription
        }
    }

    /// WebSocket-level ping with a deadline; false on error or silence.
    private static func alive(_ task: URLSessionWebSocketTask) async -> Bool {
        await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            let once = Once(cont)
            task.sendPing { error in once.resume(error == nil) }
            DispatchQueue.global().asyncAfter(deadline: .now() + 4) { once.resume(false) }
        }
    }

    private func dropSocket() {
        ws?.cancel(with: .goingAway, reason: nil)
        ws = nil
        socketOpen = false
    }

    private func openSocket() {
        guard let client else { return }
        ws?.cancel(with: .goingAway, reason: nil)
        socketOpen = false
        socketStarted = Date()
        let task = client.socket()
        ws = task
        task.resume()
        receive(on: task)
    }

    private func receive(on task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            Task { @MainActor in
                guard let self, self.ws === task else { return }
                switch result {
                case .success(let msg):
                    if !self.socketOpen {
                        self.socketOpen = true
                        self.link = .online
                        self.socketGen += 1
                        // The agent released every held key when the old
                        // socket died; don't keep showing them as latched.
                        self.heldModifiers.removeAll()
                    }
                    if case .string(let s) = msg { self.handle(s) }
                    self.receive(on: task)
                case .failure:
                    self.socketOpen = false
                    self.ws = nil
                    if self.link == .online { self.link = .connecting }
                }
            }
        }
    }

    private func handle(_ text: String) {
        guard let obj = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let t = obj["t"] as? String else { return }
        switch t {
        case "pty.out": onPtyOut?(obj["d"] as? String ?? "")
        case "pty.ready": onPtyReady?()
        case "pty.exit": onPtyExit?()
        case "warn", "err": toast = obj["m"] as? String
        default: break  // hello, pong
        }
    }

    // ── sending ──────────────────────────────────────────────────────────────
    /// Fire-and-forget: a pointer delta is worthless a frame later, so a
    /// dropped one must never hold up the next.
    func send(_ msg: [String: Any]) {
        guard let ws,
              let data = try? JSONSerialization.data(withJSONObject: msg),
              let s = String(data: data, encoding: .utf8) else { return }
        ws.send(.string(s)) { _ in }
    }

    func move(dx: Double, dy: Double) { send(["t": "mouse", "dx": dx, "dy": dy]) }
    func button(_ b: String, down: Bool) { send(["t": "btn", "b": b, "down": down]) }
    func click(_ b: String, n: Int = 1) { send(["t": "click", "b": b, "n": n]) }
    func scroll(dy: Int, dx: Int = 0) { send(["t": "scroll", "dy": dy, "dx": dx]) }

    // ── keys ─────────────────────────────────────────────────────────────────
    /// Latched modifiers fold into the next key and then clear.
    func sendKey(_ key: String) {
        if heldModifiers.isEmpty {
            send(["t": "key", "k": key])
            log(key)
        } else {
            let combo = (heldModifiers + [key]).joined(separator: "+")
            send(["t": "combo", "k": combo])
            log(combo)
            clearModifiers()
        }
    }

    func sendCombo(_ combo: String) {
        send(["t": "combo", "k": combo])
        log(combo)
    }

    func toggleModifier(_ mod: String) {
        if let i = heldModifiers.firstIndex(of: mod) {
            heldModifiers.remove(at: i)
            send(["t": "mod", "k": mod, "down": false])
        } else {
            heldModifiers.append(mod)
            send(["t": "mod", "k": mod, "down": true])
        }
    }

    func clearModifiers() {
        for m in heldModifiers { send(["t": "mod", "k": m, "down": false]) }
        heldModifiers.removeAll()
    }

    func sendText(_ raw: String) {
        // SwiftUI's TextField can't turn off smart punctuation, and the
        // agent's US-QWERTY map can't type curly quotes or long dashes.
        let s = raw.asciiPunctuation
        guard !s.isEmpty else { return }
        send(["t": "text", "s": s])
        log("\"\(s)\"")
    }

    private func log(_ s: String) {
        keyLog.append(s)
        if keyLog.count > 40 { keyLog.removeFirst(keyLog.count - 40) }
    }

    // ── shell ────────────────────────────────────────────────────────────────
    func ptyOpen(cols: Int, rows: Int) { send(["t": "pty.open", "cols": cols, "rows": rows]) }
    func ptyIn(_ d: String) { send(["t": "pty.in", "d": d]) }
    func ptyResize(cols: Int, rows: Int) { send(["t": "pty.size", "cols": cols, "rows": rows]) }
    func ptySignal(_ sig: String) { send(["t": "pty.signal", "sig": sig]) }
}

/// Resumes a continuation at most once — for racing a callback against a timer.
private final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var cont: CheckedContinuation<Bool, Never>?

    init(_ cont: CheckedContinuation<Bool, Never>) { self.cont = cont }

    func resume(_ value: Bool) {
        lock.lock()
        let c = cont
        cont = nil
        lock.unlock()
        c?.resume(returning: value)
    }
}

extension String {
    /// Undo iOS smart punctuation: curly quotes, long dashes, the ellipsis.
    var asciiPunctuation: String {
        var out = self
        for (from, to) in [("\u{2018}", "'"), ("\u{2019}", "'"), ("\u{201C}", "\""), ("\u{201D}", "\""),
                           ("\u{2013}", "-"), ("\u{2014}", "--"), ("\u{2026}", "...")] {
            out = out.replacingOccurrences(of: from, with: to)
        }
        return out
    }
}
