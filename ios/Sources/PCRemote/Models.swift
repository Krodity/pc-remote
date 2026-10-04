import Foundation

/// One row in the file browser. Mirrors `stat_entry` in the agent.
struct FsEntry: Decodable, Hashable, Identifiable {
    var name: String
    var isDir: Bool
    var size: Int64
    var mtime: Double
    var mode: String
    var link: Bool
    var readable: Bool
    var error: String?

    var id: String { name }

    enum CodingKeys: String, CodingKey {
        case name, isDir = "dir", size, mtime, mode, link, readable, error
    }

    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        isDir = try c.decodeIfPresent(Bool.self, forKey: .isDir) ?? false
        size = try c.decodeIfPresent(Int64.self, forKey: .size) ?? 0
        mtime = try c.decodeIfPresent(Double.self, forKey: .mtime) ?? 0
        mode = try c.decodeIfPresent(String.self, forKey: .mode) ?? ""
        link = try c.decodeIfPresent(Bool.self, forKey: .link) ?? false
        readable = try c.decodeIfPresent(Bool.self, forKey: .readable) ?? true
        error = try c.decodeIfPresent(String.self, forKey: .error)
    }
}

struct FsListing: Decodable {
    var path: String
    var parent: String?
    var entries: [FsEntry]
}

struct FsRead: Decodable {
    var path: String
    var text: String
    var size: Int64
    var binary: Bool?
    var truncated: Bool?
}

struct Place: Decodable, Hashable {
    var label: String
    var path: String
}

struct PlacesResponse: Decodable {
    var places: [Place]
}

struct MemInfo: Decodable {
    var used: Int64?
    var total: Int64?
    var percent: Double?
}

struct SysInfo: Decodable {
    var host: String?
    var user: String?
    var shell: String?
    var home: String?
    var os: String?
    var kernel: String?
    var uptime: Double?
    var load: [Double]?
    var cpu: Double?
    var mem: MemInfo?
    var disk: MemInfo?
    var uinput: String?
}

/// Connection state shown by the dot and latency readout in the top bar.
enum Link { case offline, connecting, online }

/// What a tap on a file does.
///
/// Same three destinations as Android: `xdg-open` on the PC, this app's own
/// viewer/editor/player, or another app on the phone via the share sheet.
enum OpenMode: String, CaseIterable {
    case pc, app, external

    var next: OpenMode {
        let all = Self.allCases
        return all[(all.firstIndex(of: self)! + 1) % all.count]
    }

    var label: String {
        switch self {
        case .pc: "Open on PC"
        case .app: "Open in app"
        case .external: "Open with…"
        }
    }
}

enum FileKind {
    case dir, image, video, audio, pdf, text, other

    static func of(_ e: FsEntry) -> FileKind {
        if e.isDir { return .dir }
        let ext = (e.name as NSString).pathExtension.lowercased()
        switch ext {
        case "png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "svg", "avif":
            return .image
        case "mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv", "flv", "ts", "m2ts", "3gp":
            return .video
        case "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "alac", "aiff":
            return .audio
        case "pdf":
            return .pdf
        case "txt", "md", "log", "json", "yaml", "yml", "toml", "ini", "conf", "cfg", "sh", "bash",
             "zsh", "fish", "py", "js", "ts", "tsx", "jsx", "kt", "kts", "swift", "c", "h", "cpp",
             "hpp", "rs", "go", "java", "rb", "lua", "css", "html", "htm", "xml", "csv", "sql",
             "service", "desktop", "env", "gitignore", "rules", "lock", "srt", "ass", "vtt":
            return .text
        default:
            return ext.isEmpty ? .text : .other
        }
    }

    var hasThumb: Bool { self == .image || self == .video || self == .pdf }
}
