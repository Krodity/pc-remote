import Foundation

struct AgentError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Talks to pc-agent over both of its ports.
///
/// REST covers anything request/response — listing, reading and writing
/// files, system stats. The socket covers anything that has to be live:
/// pointer deltas, keystrokes, and the terminal's byte stream.
final class AgentClient: Sendable {
    let pairing: Pairing

    private let http: URLSession

    /// A separate session for thumbnails and whole-file downloads, so a burst
    /// of them can never queue the ping behind it and make the app read
    /// "offline" — the bug the Android build hit with OkHttp's shared pool.
    private let bulk: URLSession

    init(pairing: Pairing) {
        self.pairing = pairing
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 60
        c.httpMaximumConnectionsPerHost = 4
        c.waitsForConnectivity = false
        http = URLSession(configuration: c)
        let b = URLSessionConfiguration.ephemeral
        b.timeoutIntervalForRequest = 120
        b.httpMaximumConnectionsPerHost = 6
        bulk = URLSession(configuration: b)
    }

    var authHeader: [String: String] { ["Authorization": "Bearer \(pairing.token)"] }

    func url(_ path: String, _ query: [String: String] = [:]) -> URL {
        var c = URLComponents(string: pairing.httpBase + path)!
        if !query.isEmpty {
            c.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
            // '+' is legal in a query but Python's parse_qs reads it as a space.
            c.percentEncodedQuery = c.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        }
        return c.url!
    }

    private func request(_ path: String, _ query: [String: String] = [:], method: String = "GET",
                         timeout: TimeInterval = 60) -> URLRequest {
        var r = URLRequest(url: url(path, query), timeoutInterval: timeout)
        r.httpMethod = method
        r.setValue("Bearer \(pairing.token)", forHTTPHeaderField: "Authorization")
        return r
    }

    private func check(_ data: Data, _ resp: URLResponse) throws {
        guard let h = resp as? HTTPURLResponse else { throw AgentError(message: "no response") }
        guard (200..<300).contains(h.statusCode) else {
            let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            throw AgentError(message: obj?["error"] as? String ?? "HTTP \(h.statusCode)")
        }
    }

    private func get<T: Decodable>(_ path: String, _ query: [String: String] = [:]) async throws -> T {
        let (data, resp) = try await http.data(for: request(path, query))
        try check(data, resp)
        return try JSONDecoder().decode(T.self, from: data)
    }

    @discardableResult
    private func post(_ path: String, _ body: [String: Any]) async throws -> Data {
        var r = request(path, method: "POST")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, resp) = try await http.data(for: r)
        try check(data, resp)
        return data
    }

    // ── REST ─────────────────────────────────────────────────────────────────
    /// Round-trip time in milliseconds.
    func ping() async throws -> Int {
        let t0 = Date()
        let (data, resp) = try await http.data(for: request("/api/ping", timeout: 6))
        try check(data, resp)
        return Int(Date().timeIntervalSince(t0) * 1000)
    }

    func sysinfo() async throws -> SysInfo { try await get("/api/sysinfo") }

    func places() async throws -> [Place] {
        let r: PlacesResponse = try await get("/api/fs/places")
        return r.places
    }

    func list(_ path: String) async throws -> FsListing { try await get("/api/fs/list", ["path": path]) }

    func read(_ path: String) async throws -> FsRead { try await get("/api/fs/read", ["path": path]) }

    func write(_ path: String, text: String) async throws {
        try await post("/api/fs/write", ["path": path, "text": text])
    }

    func mkdir(_ path: String) async throws { try await post("/api/fs/mkdir", ["path": path]) }

    func create(_ path: String) async throws { try await post("/api/fs/create", ["path": path]) }

    func rename(_ path: String, to name: String) async throws {
        try await post("/api/fs/rename", ["path": path, "name": name])
    }

    func copy(_ path: String, to: String) async throws {
        try await post("/api/fs/copy", ["path": path, "to": to])
    }

    /// Moves to the freedesktop trash unless `permanent`.
    func delete(_ path: String, permanent: Bool = false) async throws {
        try await post("/api/fs/delete", ["path": path, "permanent": permanent])
    }

    func openOnPC(_ path: String) async throws { try await post("/api/open", ["path": path]) }

    /// The file's bytes, inline (no Content-Disposition: attachment).
    func downloadURL(_ path: String) -> URL { url("/api/fs/download", ["path": path, "attach": "0"]) }

    func download(_ path: String) async throws -> Data {
        var r = URLRequest(url: downloadURL(path), timeoutInterval: 300)
        r.setValue("Bearer \(pairing.token)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await bulk.data(for: r)
        try check(data, resp)
        return data
    }

    /// Downloads to a temp file named like the original, for the share sheet.
    func downloadToFile(_ path: String) async throws -> URL {
        var r = URLRequest(url: downloadURL(path), timeoutInterval: 600)
        r.setValue("Bearer \(pairing.token)", forHTTPHeaderField: "Authorization")
        let (tmp, resp) = try await bulk.download(for: r)
        if let h = resp as? HTTPURLResponse, !(200..<300).contains(h.statusCode) {
            throw AgentError(message: "HTTP \(h.statusCode)")
        }
        let dir = Self.sharedDir
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let dest = dir.appendingPathComponent((path as NSString).lastPathComponent)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: tmp, to: dest)
        return dest
    }

    /// Where downloads for the share sheet and QuickLook land.
    static var sharedDir: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("shared", isDirectory: true)
    }

    /// A rendered preview JPEG. Throws if the agent cannot make one.
    func thumb(_ path: String, size: Int = 256) async throws -> Data {
        var r = URLRequest(url: url("/api/fs/thumb", ["path": path, "size": String(size)]), timeoutInterval: 60)
        r.setValue("Bearer \(pairing.token)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await bulk.data(for: r)
        try check(data, resp)
        return data
    }

    /// Streams the file from disk; a phone video would not fit in memory.
    /// The agent overwrites silently, so callers pick a free name first.
    func upload(_ path: String, file: URL) async throws {
        var r = request("/api/fs/upload", ["path": path], method: "POST", timeout: 3600)
        r.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        let (d, resp) = try await bulk.upload(for: r, fromFile: file)
        try check(d, resp)
    }

    // ── WebSocket ────────────────────────────────────────────────────────────
    func socket() -> URLSessionWebSocketTask {
        var c = URLComponents(string: pairing.wsBase + "/")!
        c.queryItems = [URLQueryItem(name: "token", value: pairing.token)]
        return http.webSocketTask(with: c.url!)
    }
}
