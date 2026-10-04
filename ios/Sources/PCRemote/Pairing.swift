import Foundation
import Security

/// Where the agent lives and the token that proves we were paired with it.
struct Pairing: Equatable {
    var host: String = ""
    var token: String = ""

    static let httpPort = 8778

    var isSet: Bool {
        !host.trimmingCharacters(in: .whitespaces).isEmpty
            && !token.trimmingCharacters(in: .whitespaces).isEmpty
    }

    /// Normalise whatever the user typed into "host:port".
    ///
    /// Accepts a MagicDNS name, an IPv4 or bare IPv6 literal, any of those
    /// with a port, and a full URL pasted from the pair page. A bare IPv6
    /// address must be bracketed before a port is appended, or its last
    /// colon reads as the port separator.
    private var hostPort: String {
        var h = host.trimmingCharacters(in: .whitespaces)
        for p in ["http://", "https://", "ws://", "wss://"] where h.hasPrefix(p) {
            h.removeFirst(p.count)
        }
        if let slash = h.firstIndex(of: "/") { h = String(h[..<slash]) }
        if h.isEmpty { return "localhost:\(Self.httpPort)" }
        if h.hasPrefix("[") {
            let after = h[(h.lastIndex(of: "]") ?? h.endIndex)...].dropFirst()
            return after.hasPrefix(":") ? h : "\(h):\(Self.httpPort)"
        }
        if h.filter({ $0 == ":" }).count > 1 { return "[\(h)]:\(Self.httpPort)" }
        return h.contains(":") ? h : "\(h):\(Self.httpPort)"
    }

    var httpBase: String { "http://\(hostPort)" }

    /// The WS port is the HTTP port + 1, as the agent lays them out.
    var wsBase: String {
        let hp = hostPort
        guard let i = hp.lastIndex(of: ":") else { return "ws://\(hp):\(Self.httpPort + 1)" }
        let port = Int(hp[hp.index(after: i)...]) ?? Self.httpPort
        return "ws://\(hp[..<i]):\(port + 1)"
    }

    /// Parses `pcremote://pair?host=…&token=…` from the agent's pair page.
    init?(url: URL) {
        guard url.scheme == "pcremote",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let host = items.first(where: { $0.name == "host" })?.value,
              let token = items.first(where: { $0.name == "token" })?.value
        else { return nil }
        self.host = host
        self.token = token
    }

    init(host: String = "", token: String = "") {
        self.host = host
        self.token = token
    }
}

/// Persistence. The token is the equivalent of an SSH key for this PC (a real
/// shell as your user), so it goes in the Keychain, not UserDefaults.
enum Store {
    private static var d: UserDefaults { .standard }
    private static let service = "uk.krodity.pcremote"

    static func loadPairing() -> Pairing {
        Pairing(host: d.string(forKey: "host") ?? "", token: keychainRead("token") ?? "")
    }

    static func save(_ p: Pairing) {
        d.set(p.host.trimmingCharacters(in: .whitespaces), forKey: "host")
        keychainWrite("token", p.token.trimmingCharacters(in: .whitespaces))
    }

    static func clear() {
        d.removeObject(forKey: "host")
        keychainDelete("token")
    }

    static var openMode: OpenMode {
        get { OpenMode(rawValue: d.string(forKey: "open_mode") ?? "") ?? .pc }
        set { d.set(newValue.rawValue, forKey: "open_mode") }
    }

    static var gridView: Bool {
        get { d.bool(forKey: "grid_view") }
        set { d.set(newValue, forKey: "grid_view") }
    }

    static var sensitivity: Double {
        get { d.object(forKey: "sensitivity") as? Double ?? 2 }
        set { d.set(newValue, forKey: "sensitivity") }
    }

    // ── Keychain ─────────────────────────────────────────────────────────────
    private static func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: key]
    }

    private static func keychainRead(_ key: String) -> String? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func keychainWrite(_ key: String, _ value: String) {
        keychainDelete(key)
        var q = query(key)
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }

    private static func keychainDelete(_ key: String) {
        SecItemDelete(query(key) as CFDictionary)
    }
}
