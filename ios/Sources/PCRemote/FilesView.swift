import SwiftUI
import UniformTypeIdentifiers

/// What the Files tab is currently presenting over itself.
enum FileSheet: Identifiable {
    case text(String)
    case images([FsEntry], Int, String)
    case media(String)
    case preview(URL)
    case share(URL)

    var id: String {
        switch self {
        case .text(let p): "t:\(p)"
        case .images(_, let i, let d): "i:\(d):\(i)"
        case .media(let p): "m:\(p)"
        case .preview(let u): "p:\(u.path)"
        case .share(let u): "s:\(u.path)"
        }
    }
}

@MainActor
final class FilesModel: ObservableObject {
    @Published var path = "~"
    @Published var listing: FsListing?
    @Published var places: [Place] = []
    @Published var loading = false
    @Published var error: String?
    @Published var busy: String?
    @Published var sheet: FileSheet?
    @Published var openMode = Store.openMode { didSet { Store.openMode = openMode } }
    @Published var grid = Store.gridView { didSet { Store.gridView = grid } }
    @Published var showHidden = false
    /// A file the phone can't handle directly, waiting on the user's choice.
    @Published var fallback: Fallback?

    enum Fallback: Identifiable {
        case unplayable(FsEntry)
        case large(FsEntry, share: Bool)

        var id: String {
            switch self {
            case .unplayable(let e): "u:\(e.name)"
            case .large(let e, let s): "l:\(e.name):\(s)"
            }
        }

        var entry: FsEntry {
            switch self {
            case .unplayable(let e), .large(let e, _): e
            }
        }
    }

    /// Downloads past this size ask first — a film is gigabytes.
    static let largeFile: Int64 = 200 * 1024 * 1024

    /// Containers AVPlayer can open. Anything else plays as a black screen.
    private static let playable: Set<String> = [
        "mp4", "m4v", "mov", "3gp", "mp3", "m4a", "aac", "flac", "wav", "aiff", "alac", "caf",
    ]

    weak var remote: RemoteModel?
    var client: AgentClient? { remote?.client }

    var entries: [FsEntry] {
        let all = listing?.entries ?? []
        return showHidden ? all : all.filter { !$0.name.hasPrefix(".") }
    }

    func full(_ e: FsEntry) -> String {
        let base = listing?.path ?? path
        return base == "/" ? "/\(e.name)" : "\(base)/\(e.name)"
    }

    func load(_ p: String? = nil) async {
        guard let client else { return }
        loading = true
        defer { loading = false }
        do {
            let l = try await client.list(p ?? path)
            listing = l
            path = l.path
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
        if places.isEmpty { places = (try? await client.places()) ?? [] }
    }

    func up() async {
        if let parent = listing?.parent { await load(parent) }
    }

    func tap(_ e: FsEntry) async {
        if e.isDir { await load(full(e)); return }
        switch openMode {
        case .pc: await openOnPC(e)
        case .app: await openInApp(e)
        case .external: await share(e)
        }
    }

    func openOnPC(_ e: FsEntry) async {
        await run("Opening on PC…") { try await $0.openOnPC(self.full(e)) }
        remote?.toast = "Opened \(e.name) on the PC"
    }

    func openInApp(_ e: FsEntry) async {
        switch FileKind.of(e) {
        case .dir: await load(full(e))
        case .image:
            let imgs = entries.filter { FileKind.of($0) == .image }
            sheet = .images(imgs, imgs.firstIndex(of: e) ?? 0, listing?.path ?? path)
        case .video, .audio:
            if Self.playable.contains((e.name as NSString).pathExtension.lowercased()) {
                sheet = .media(full(e))
            } else {
                fallback = .unplayable(e)
            }
        case .text: sheet = .text(full(e))
        case .pdf, .other:
            if e.size > Self.largeFile { fallback = .large(e, share: false); return }
            await preview(e)
        }
    }

    func preview(_ e: FsEntry) async {
        if let url = await fetch(e) { sheet = .preview(url) }
    }

    func share(_ e: FsEntry, confirmed: Bool = false) async {
        if !confirmed && e.size > Self.largeFile { fallback = .large(e, share: true); return }
        if let url = await fetch(e) { sheet = .share(url) }
    }

    private func fetch(_ e: FsEntry) async -> URL? {
        guard let client else { return nil }
        busy = "Downloading \(e.name)…"
        defer { busy = nil }
        do { return try await client.downloadToFile(full(e)) } catch {
            self.error = error.localizedDescription
            return nil
        }
    }

    func run(_ label: String, reload: Bool = false, _ op: @escaping (AgentClient) async throws -> Void) async {
        guard let client else { return }
        busy = label
        defer { busy = nil }
        do {
            try await op(client)
            if reload { await load() }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func upload(_ urls: [URL]) async {
        guard let client else { return }
        let base = listing?.path ?? path
        var taken = Set(listing?.entries.map(\.name) ?? [])
        for u in urls {
            let scoped = u.startAccessingSecurityScopedResource()
            defer { if scoped { u.stopAccessingSecurityScopedResource() } }
            // The agent overwrites without asking; never clobber a PC file.
            let name = Self.freeName(u.lastPathComponent, taken: taken)
            taken.insert(name)
            busy = "Uploading \(name)…"
            do {
                try await client.upload(base == "/" ? "/\(name)" : "\(base)/\(name)", file: u)
            } catch {
                self.error = error.localizedDescription
            }
        }
        busy = nil
        await load()
    }

    /// "a.jpg" → "a (1).jpg" → "a (2).jpg"… until nothing in the folder has it.
    static func freeName(_ name: String, taken: Set<String>) -> String {
        guard taken.contains(name) else { return name }
        let ext = (name as NSString).pathExtension
        let stem = (name as NSString).deletingPathExtension
        var n = 1
        while true {
            let candidate = ext.isEmpty ? "\(stem) (\(n))" : "\(stem) (\(n)).\(ext)"
            if !taken.contains(candidate) { return candidate }
            n += 1
        }
    }
}

struct FilesView: View {
    @EnvironmentObject var remote: RemoteModel
    @StateObject private var fm = FilesModel()
    @State private var prompt: NamePrompt?
    @State private var confirmDelete: FsEntry?
    @State private var importing = false

    var body: some View {
        VStack(spacing: 0) {
            toolbar
            pathBar
            Rectangle().fill(P.border).frame(height: 1)
            ZStack {
                if let err = fm.error, fm.listing == nil {
                    VStack(spacing: 10) {
                        Text(err).foregroundStyle(P.red).multilineTextAlignment(.center)
                        Button("Retry") { Task { await fm.load() } }
                    }.padding()
                } else if fm.grid {
                    gridView
                } else {
                    listView
                }
                if let b = fm.busy {
                    HStack(spacing: 10) { ProgressView(); Text(b).font(.footnote) }
                        .padding(14)
                        .background(P.panel, in: RoundedRectangle(cornerRadius: 10))
                        .frame(maxHeight: .infinity, alignment: .bottom).padding(.bottom, 16)
                }
            }
        }
        .background(P.bg)
        .task {
            fm.remote = remote
            if fm.listing == nil { await fm.load(remote.sysinfo?.home ?? "~") }
        }
        .onChange(of: remote.socketGen) { _, _ in
            if fm.listing == nil { Task { await fm.load(remote.sysinfo?.home ?? "~") } }
        }
        .sheet(item: sheetBinding(fullScreen: false)) { sheet in
            switch sheet {
            case .text(let p): TextFileView(path: p).environmentObject(remote)
            case .preview(let u): QuickLookView(url: u).ignoresSafeArea()
            case .share(let u): ShareSheet(items: [u]).presentationDetents([.medium, .large])
            default: EmptyView()
            }
        }
        // Photos and films want the whole screen, not a card.
        .fullScreenCover(item: sheetBinding(fullScreen: true)) { sheet in
            switch sheet {
            case .images(let list, let i, let dir): ImageViewer(entries: list, index: i, dir: dir).environmentObject(remote)
            case .media(let p): MediaPlayerView(path: p).environmentObject(remote).ignoresSafeArea()
            default: EmptyView()
            }
        }
        .confirmationDialog(fallbackTitle, isPresented: Binding(
            get: { fm.fallback != nil }, set: { if !$0 { fm.fallback = nil } }), titleVisibility: .visible,
            presenting: fm.fallback) { f in
            Button("Open on PC") { Task { await fm.openOnPC(f.entry) } }
            switch f {
            case .unplayable(let e):
                Button("Download & open with… (\(sizeLabel(e.size)))") { Task { await fm.share(e, confirmed: true) } }
            case .large(let e, let share):
                Button("Download anyway (\(sizeLabel(e.size)))") {
                    Task { if share { await fm.share(e, confirmed: true) } else { await fm.preview(e) } }
                }
            }
        } message: { f in
            if case .unplayable = f {
                Text("iPhone can't stream this format. VLC and similar apps can play it once downloaded.")
            }
        }
        .alert(prompt?.title ?? "", isPresented: Binding(get: { prompt != nil }, set: { if !$0 { prompt = nil } })) {
            TextField("Name", text: Binding(get: { prompt?.text ?? "" }, set: { prompt?.text = $0 }))
                .textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("Cancel", role: .cancel) { prompt = nil }
            Button("OK") {
                if let p = prompt, !p.text.isEmpty { Task { await p.commit(p.text) } }
                prompt = nil
            }
        }
        .confirmationDialog("Delete \(confirmDelete?.name ?? "")?", isPresented: Binding(
            get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } }), titleVisibility: .visible) {
            if let e = confirmDelete {
                Button("Move to Trash", role: .destructive) {
                    Task { await fm.run("Deleting…", reload: true) { try await $0.delete(fm.full(e)) } }
                }
                Button("Delete permanently", role: .destructive) {
                    Task { await fm.run("Deleting…", reload: true) { try await $0.delete(fm.full(e), permanent: true) } }
                }
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item], allowsMultipleSelection: true) { r in
            if case .success(let urls) = r { Task { await fm.upload(urls) } }
        }
    }

    private func sheetBinding(fullScreen: Bool) -> Binding<FileSheet?> {
        Binding(
            get: {
                guard let s = fm.sheet else { return nil }
                switch s {
                case .images, .media: return fullScreen ? s : nil
                default: return fullScreen ? nil : s
                }
            },
            set: { if $0 == nil { fm.sheet = nil } }
        )
    }

    private var fallbackTitle: String {
        switch fm.fallback {
        case .unplayable(let e): "Can't play .\((e.name as NSString).pathExtension) on iPhone"
        case .large(let e, _): "\(e.name) is \(sizeLabel(e.size))"
        case nil: ""
        }
    }

    // ── chrome ───────────────────────────────────────────────────────────────
    private var toolbar: some View {
        HStack(spacing: 14) {
            Button { Task { await fm.up() } } label: { Image(systemName: "arrow.up") }
                .disabled(fm.listing?.parent == nil)
            Menu {
                ForEach(fm.places, id: \.self) { p in
                    Button(p.label) { Task { await fm.load(p.path) } }
                }
            } label: { Image(systemName: "star") }
            Button { Task { await fm.load() } } label: { Image(systemName: "arrow.clockwise") }
            Spacer()
            Button {
                fm.openMode = fm.openMode.next
            } label: {
                Text(fm.openMode.label).font(.caption.bold())
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .background(P.glow, in: Capsule())
                    .overlay(Capsule().stroke(P.accentD))
            }
            Menu {
                Button { fm.grid.toggle() } label: {
                    Label(fm.grid ? "List view" : "Grid view", systemImage: fm.grid ? "list.bullet" : "square.grid.2x2")
                }
                Button { fm.showHidden.toggle() } label: {
                    Label(fm.showHidden ? "Hide dotfiles" : "Show dotfiles", systemImage: "eye")
                }
                Divider()
                Button { newItem(folder: true) } label: { Label("New folder", systemImage: "folder.badge.plus") }
                Button { newItem(folder: false) } label: { Label("New file", systemImage: "doc.badge.plus") }
                Button { importing = true } label: { Label("Upload from phone", systemImage: "square.and.arrow.up") }
            } label: { Image(systemName: "ellipsis.circle") }
        }
        .font(.title3)
        .foregroundStyle(P.accent)
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(P.surface)
    }

    private var pathBar: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 2) {
                    let parts = crumbs(fm.listing?.path ?? fm.path)
                    ForEach(parts.indices, id: \.self) { i in
                        Button(parts[i].0) { Task { await fm.load(parts[i].1) } }
                            .font(Fonts.mono(12))
                            .foregroundStyle(i == parts.count - 1 ? P.text : P.sub)
                            .id(i)
                        if i < parts.count - 1 { Text("/").font(Fonts.mono(12)).foregroundStyle(P.mute) }
                    }
                }
                .padding(.horizontal, 16).padding(.vertical, 8)
            }
            .onChange(of: fm.listing?.path) { _, _ in
                proxy.scrollTo(crumbs(fm.listing?.path ?? "").count - 1, anchor: .trailing)
            }
        }
        .background(P.surface)
    }

    private func crumbs(_ path: String) -> [(String, String)] {
        var out: [(String, String)] = [("/", "/")]
        var acc = ""
        for part in path.split(separator: "/") {
            acc += "/\(part)"
            out.append((String(part), acc))
        }
        return out
    }

    // ── listings ─────────────────────────────────────────────────────────────
    private var listView: some View {
        List {
            ForEach(fm.entries) { e in
                Button { Task { await fm.tap(e) } } label: { EntryRow(entry: e, path: fm.full(e)) }
                    .listRowBackground(P.bg)
                    .listRowSeparatorTint(P.border)
                    .contextMenu { actions(e) }
                    .swipeActions {
                        Button(role: .destructive) { confirmDelete = e } label: { Label("Delete", systemImage: "trash") }
                    }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .refreshable { await fm.load() }
    }

    private var gridView: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 10)], spacing: 12) {
                ForEach(fm.entries) { e in
                    Button { Task { await fm.tap(e) } } label: { EntryTile(entry: e, path: fm.full(e)) }
                        .contextMenu { actions(e) }
                }
            }
            .padding(12)
        }
        .refreshable { await fm.load() }
    }

    /// One action list for both layouts, so neither can drift from the other.
    @ViewBuilder
    private func actions(_ e: FsEntry) -> some View {
        if !e.isDir {
            Button { Task { await fm.openOnPC(e) } } label: { Label("Open on PC", systemImage: "desktopcomputer") }
            Button { Task { await fm.openInApp(e) } } label: { Label("Open in app", systemImage: "iphone") }
            Button { Task { await fm.share(e) } } label: { Label("Open with… / Save", systemImage: "square.and.arrow.up") }
        } else {
            Button { Task { await fm.openOnPC(e) } } label: { Label("Open folder on PC", systemImage: "desktopcomputer") }
        }
        Button {
            UIPasteboard.general.string = fm.full(e)
        } label: { Label("Copy path", systemImage: "doc.on.clipboard") }
        Button {
            prompt = NamePrompt(title: "Rename", text: e.name) { name in
                await fm.run("Renaming…", reload: true) { try await $0.rename(fm.full(e), to: name) }
            }
        } label: { Label("Rename", systemImage: "pencil") }
        Button {
            let copyName = copyNameFor(e.name)
            prompt = NamePrompt(title: "Duplicate as", text: copyName) { name in
                let base = fm.listing?.path ?? fm.path
                await fm.run("Copying…", reload: true) {
                    try await $0.copy(fm.full(e), to: base == "/" ? "/\(name)" : "\(base)/\(name)")
                }
            }
        } label: { Label("Duplicate", systemImage: "plus.square.on.square") }
        Button(role: .destructive) { confirmDelete = e } label: { Label("Delete", systemImage: "trash") }
    }

    private func copyNameFor(_ name: String) -> String {
        let ext = (name as NSString).pathExtension
        let stem = (name as NSString).deletingPathExtension
        return ext.isEmpty ? "\(stem) copy" : "\(stem) copy.\(ext)"
    }

    private func newItem(folder: Bool) {
        prompt = NamePrompt(title: folder ? "New folder" : "New file", text: "") { name in
            let base = fm.listing?.path ?? fm.path
            let p = base == "/" ? "/\(name)" : "\(base)/\(name)"
            await fm.run("Creating…", reload: true) { c in
                if folder { try await c.mkdir(p) } else { try await c.create(p) }
            }
        }
    }
}

struct NamePrompt {
    var title: String
    var text: String
    var commit: (String) async -> Void
}

// ── rows and tiles ───────────────────────────────────────────────────────────

private func icon(_ e: FsEntry) -> (String, Color) {
    switch FileKind.of(e) {
    case .dir: ("folder.fill", P.amber)
    case .image: ("photo", P.purple)
    case .video: ("film", P.accent)
    case .audio: ("music.note", P.green)
    case .pdf: ("doc.richtext", P.red)
    case .text: ("doc.text", P.text)
    case .other: ("doc", P.sub)
    }
}

func sizeLabel(_ n: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: n, countStyle: .file)
}

// Built once: a DateFormatter per row is measurable while scrolling a big folder.
private let thisYear: DateFormatter = { let f = DateFormatter(); f.dateFormat = "MMM d HH:mm"; return f }()
private let otherYear: DateFormatter = { let f = DateFormatter(); f.dateFormat = "MMM d yyyy"; return f }()

private func formatDate(_ t: Double) -> String {
    let d = Date(timeIntervalSince1970: t)
    let same = Calendar.current.isDate(d, equalTo: Date(), toGranularity: .year)
    return (same ? thisYear : otherYear).string(from: d)
}

struct Thumb: View {
    @EnvironmentObject var remote: RemoteModel
    let entry: FsEntry
    let path: String
    let size: CGFloat
    @State private var image: UIImage?

    var body: some View {
        let (sym, col) = icon(entry)
        ZStack {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
                    .frame(width: size, height: size).clipped()
                if FileKind.of(entry) == .video {
                    Image(systemName: "play.circle.fill").font(.system(size: size * 0.3))
                        .foregroundStyle(.white.opacity(0.9)).shadow(radius: 3)
                }
            } else {
                Image(systemName: sym).font(.system(size: size * 0.42)).foregroundStyle(col)
            }
        }
        .frame(width: size, height: size)
        .background(P.surface)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .task(id: path) {
            guard FileKind.of(entry).hasThumb, let c = remote.client else { return }
            image = await ImageLoader.shared.thumb(c, path: path, size: 256)
        }
    }
}

private struct EntryRow: View {
    let entry: FsEntry
    let path: String

    var body: some View {
        HStack(spacing: 12) {
            Thumb(entry: entry, path: path, size: 40)
            VStack(alignment: .leading, spacing: 3) {
                Text(entry.name).font(.system(size: 15)).foregroundStyle(entry.isDir ? P.amber : P.text)
                    .lineLimit(1).truncationMode(.middle)
                Text(entry.isDir ? formatDate(entry.mtime) : "\(sizeLabel(entry.size)) · \(formatDate(entry.mtime))")
                    .font(Fonts.mono(11)).foregroundStyle(P.sub)
            }
            Spacer()
            if entry.link { Image(systemName: "link").font(.caption).foregroundStyle(P.sub) }
            if entry.isDir { Image(systemName: "chevron.right").font(.caption).foregroundStyle(P.mute) }
        }
        .padding(.vertical, 2)
        .opacity(entry.readable ? 1 : 0.5)
    }
}

private struct EntryTile: View {
    let entry: FsEntry
    let path: String

    var body: some View {
        VStack(spacing: 6) {
            Thumb(entry: entry, path: path, size: 100)
            Text(entry.name).font(.caption).foregroundStyle(entry.isDir ? P.amber : P.text)
                .lineLimit(2).multilineTextAlignment(.center).truncationMode(.middle)
                .frame(height: 30, alignment: .top)
        }
    }
}
