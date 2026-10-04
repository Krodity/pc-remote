import SwiftUI
import AVKit
import QuickLook
import UIKit

// ── text ─────────────────────────────────────────────────────────────────────

/// Built-in editor for text files, capped by the agent at 2 MB.
struct TextFileView: View {
    @EnvironmentObject var remote: RemoteModel
    @Environment(\.dismiss) private var dismiss
    let path: String
    @State private var text = ""
    @State private var original = ""
    @State private var note: String?
    @State private var loaded = false
    @State private var readOnly = false

    var body: some View {
        NavigationStack {
            Group {
                if loaded {
                    CodeEditor(text: $text, editable: !readOnly)
                } else {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .background(P.term)
            .safeAreaInset(edge: .bottom) {
                if let note {
                    Text(note).font(.caption).foregroundStyle(P.amber).padding(8)
                        .frame(maxWidth: .infinity).background(P.panel)
                }
            }
            .navigationTitle((path as NSString).lastPathComponent)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }
                        .disabled(readOnly || text == original)
                }
            }
        }
        .task { await load() }
    }

    private func load() async {
        guard let c = remote.client else { return }
        do {
            let r = try await c.read(path)
            text = r.text
            original = r.text
            if r.binary == true {
                readOnly = true
                note = "Binary file — shown read-only"
            } else if r.truncated == true {
                readOnly = true
                note = "Truncated at 2 MB — read-only so the rest is not lost"
            }
        } catch {
            note = error.localizedDescription
            readOnly = true
        }
        loaded = true
    }

    private func save() async {
        guard let c = remote.client else { return }
        do {
            try await c.write(path, text: text)
            original = text
            note = "Saved"
        } catch {
            note = "Save failed: \(error.localizedDescription)"
        }
    }
}

/// A plain UITextView with every "smart" feature off. SwiftUI's TextEditor
/// keeps smart quotes and dashes on, which would silently turn `"` into `“`
/// and `--` into `—` in a config file on save.
private struct CodeEditor: UIViewRepresentable {
    @Binding var text: String
    let editable: Bool

    func makeCoordinator() -> Coordinator { Coordinator(text: $text) }

    func makeUIView(context: Context) -> UITextView {
        let v = UITextView()
        v.delegate = context.coordinator
        v.font = Fonts.uiMono(13)
        v.backgroundColor = UIColor(P.term)
        v.textColor = UIColor(P.text)
        v.tintColor = UIColor(P.accent)
        v.autocapitalizationType = .none
        v.autocorrectionType = .no
        v.spellCheckingType = .no
        v.smartQuotesType = .no
        v.smartDashesType = .no
        v.smartInsertDeleteType = .no
        v.keyboardAppearance = .dark
        v.alwaysBounceVertical = true
        v.keyboardDismissMode = .interactive
        v.text = text
        return v
    }

    func updateUIView(_ v: UITextView, context: Context) {
        v.isEditable = editable
        if v.text != text { v.text = text }
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        let text: Binding<String>
        init(text: Binding<String>) { self.text = text }
        func textViewDidChange(_ v: UITextView) { text.wrappedValue = v.text }
    }
}

// ── images ───────────────────────────────────────────────────────────────────

/// Full-screen pager through the folder's images; pinch or double-tap to zoom.
struct ImageViewer: View {
    @EnvironmentObject var remote: RemoteModel
    @Environment(\.dismiss) private var dismiss
    let entries: [FsEntry]
    @State var index: Int
    let dir: String
    @State private var chrome = true

    init(entries: [FsEntry], index: Int, dir: String) {
        self.entries = entries
        _index = State(initialValue: index)
        self.dir = dir
    }

    private func path(_ e: FsEntry) -> String { dir == "/" ? "/\(e.name)" : "\(dir)/\(e.name)" }

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            TabView(selection: $index) {
                ForEach(entries.indices, id: \.self) { i in
                    ImagePage(entry: entries[i], path: path(entries[i])) { chrome.toggle() }
                        .tag(i)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .ignoresSafeArea()

            if chrome, entries.indices.contains(index) {
                HStack {
                    Button { dismiss() } label: { Image(systemName: "xmark").font(.title3) }
                    VStack(alignment: .leading) {
                        Text(entries[index].name).font(.subheadline).lineLimit(1)
                        Text("\(index + 1) of \(entries.count)").font(.caption).foregroundStyle(P.sub)
                    }
                    Spacer()
                    Button {
                        let p = path(entries[index])
                        Task { try? await remote.client?.openOnPC(p); remote.toast = "Opened on the PC" }
                    } label: { Image(systemName: "desktopcomputer") }
                }
                .foregroundStyle(.white)
                .padding()
                .background(.black.opacity(0.55))
            }
        }
    }
}

private struct ImagePage: View {
    @EnvironmentObject var remote: RemoteModel
    let entry: FsEntry
    let path: String
    let onTap: () -> Void
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        ZStack {
            if let image {
                ZoomableImage(image: image, onTap: onTap)
            } else if failed {
                Text("Couldn't load \(entry.name)").foregroundStyle(P.sub)
            } else {
                ProgressView().tint(.white)
            }
        }
        .task(id: path) {
            guard let c = remote.client else { return }
            image = await ImageLoader.shared.full(c, entry: entry, path: path)
            failed = image == nil
        }
    }
}

/// UIScrollView zoom: it only claims horizontal drags while zoomed in, so
/// swiping between pages keeps working at 1× — the conflict Compose needed a
/// hand-rolled gesture loop to solve.
private struct ZoomableImage: UIViewRepresentable {
    let image: UIImage
    let onTap: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onTap: onTap) }

    func makeUIView(context: Context) -> UIScrollView {
        let sv = UIScrollView()
        sv.delegate = context.coordinator
        sv.maximumZoomScale = 8
        sv.minimumZoomScale = 1
        sv.showsHorizontalScrollIndicator = false
        sv.showsVerticalScrollIndicator = false
        sv.contentInsetAdjustmentBehavior = .never
        sv.backgroundColor = .black
        let iv = UIImageView(image: image)
        iv.contentMode = .scaleAspectFit
        iv.translatesAutoresizingMaskIntoConstraints = false
        sv.addSubview(iv)
        NSLayoutConstraint.activate([
            iv.widthAnchor.constraint(equalTo: sv.frameLayoutGuide.widthAnchor),
            iv.heightAnchor.constraint(equalTo: sv.frameLayoutGuide.heightAnchor),
            iv.leadingAnchor.constraint(equalTo: sv.contentLayoutGuide.leadingAnchor),
            iv.trailingAnchor.constraint(equalTo: sv.contentLayoutGuide.trailingAnchor),
            iv.topAnchor.constraint(equalTo: sv.contentLayoutGuide.topAnchor),
            iv.bottomAnchor.constraint(equalTo: sv.contentLayoutGuide.bottomAnchor),
        ])
        context.coordinator.imageView = iv

        let double = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTap(_:)))
        double.numberOfTapsRequired = 2
        sv.addGestureRecognizer(double)
        let single = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.singleTap))
        single.require(toFail: double)
        sv.addGestureRecognizer(single)
        return sv
    }

    func updateUIView(_ sv: UIScrollView, context: Context) {
        context.coordinator.imageView?.image = image
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        weak var imageView: UIImageView?
        let onTap: () -> Void
        init(onTap: @escaping () -> Void) { self.onTap = onTap }

        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

        @objc func singleTap() { onTap() }

        @objc func doubleTap(_ g: UITapGestureRecognizer) {
            guard let sv = g.view as? UIScrollView else { return }
            if sv.zoomScale > 1 {
                sv.setZoomScale(1, animated: true)
            } else {
                let p = g.location(in: imageView)
                let s: CGFloat = 2.5
                let w = sv.bounds.width / s, h = sv.bounds.height / s
                sv.zoom(to: CGRect(x: p.x - w / 2, y: p.y - h / 2, width: w, height: h), animated: true)
            }
        }
    }
}

// ── video / audio ────────────────────────────────────────────────────────────

/// Streams straight from the agent with HTTP Range. AVFoundation can attach
/// the bearer header itself, so iOS needs none of Android's loopback proxy:
/// the token still never leaves this app.
///
/// AVPlayer only demuxes what Apple supports (MP4/MOV/M4V, MP3/AAC/FLAC/WAV).
/// MKV/WebM/AVI fail here — use "Open on PC" or "Open with…" (e.g. VLC).
struct MediaPlayerView: UIViewControllerRepresentable {
    @EnvironmentObject var remote: RemoteModel
    let path: String

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        guard let c = remote.client else { return vc }
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        let asset = AVURLAsset(url: c.downloadURL(path),
                               options: ["AVURLAssetHTTPHeaderFieldsKey": c.authHeader])
        let player = AVPlayer(playerItem: AVPlayerItem(asset: asset))
        vc.player = player
        player.play()
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {}

    static func dismantleUIViewController(_ vc: AVPlayerViewController, coordinator: ()) {
        vc.player?.pause()
    }
}

// ── everything else ──────────────────────────────────────────────────────────

struct QuickLookView: UIViewControllerRepresentable {
    let url: URL

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }

    func makeUIViewController(context: Context) -> UINavigationController {
        let ql = QLPreviewController()
        ql.dataSource = context.coordinator
        return UINavigationController(rootViewController: ql)
    }

    func updateUIViewController(_ vc: UINavigationController, context: Context) {}

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }
    }
}

/// "Open with…": the system share sheet, which lists every app that can take
/// the file plus Save to Files.
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
