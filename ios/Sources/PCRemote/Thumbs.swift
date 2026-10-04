import UIKit

/// Thumbnail and full-image loading, cached by bytes rather than count — a
/// full-screen bitmap is ~20 MB, so counting entries would blow memory at 3.
@MainActor
final class ImageLoader {
    static let shared = ImageLoader()

    private let cache: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.totalCostLimit = 160 * 1024 * 1024
        return c
    }()

    private var inflight: [String: Task<UIImage?, Never>] = [:]

    private func cost(_ img: UIImage) -> Int {
        Int(img.size.width * img.scale * img.size.height * img.scale * 4)
    }

    /// An agent-rendered preview (image, video frame or PDF page).
    func thumb(_ client: AgentClient, path: String, size: Int) async -> UIImage? {
        let key = "t\(size):\(path)"
        if let hit = cache.object(forKey: key as NSString) { return hit }
        if let t = inflight[key] { return await t.value }
        let t = Task<UIImage?, Never> {
            guard let data = try? await client.thumb(path, size: size) else { return nil }
            return await Task.detached { UIImage(data: data)?.preparingForDisplay() }.value
        }
        inflight[key] = t
        let img = await t.value
        inflight[key] = nil
        if let img { cache.setObject(img, forKey: key as NSString, cost: cost(img)) }
        return img
    }

    /// The original when the phone can decode it and it is not huge — that is
    /// what makes zoom sharp. Otherwise a screen-sized agent render (SVG,
    /// TIFF, and the 235 MP upscayl PNGs the phone must not pull).
    func full(_ client: AgentClient, entry: FsEntry, path: String) async -> UIImage? {
        let key = "f:\(path)"
        if let hit = cache.object(forKey: key as NSString) { return hit }
        let ext = (entry.name as NSString).pathExtension.lowercased()
        let native = ["png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "bmp"].contains(ext)
        var img: UIImage?
        if native && entry.size <= 32 * 1024 * 1024,
           let data = try? await client.download(path) {
            img = await Task.detached { () -> UIImage? in
                // Downsample at decode so a 50 MP photo does not become a
                // 200 MB bitmap.
                guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
                let opts: [CFString: Any] = [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: 4096,
                ]
                guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
                return UIImage(cgImage: cg)
            }.value
        }
        if img == nil { img = await thumb(client, path: path, size: 2048) }
        if let img { cache.setObject(img, forKey: key as NSString, cost: cost(img)) }
        return img
    }
}
