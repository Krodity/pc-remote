import SwiftUI
import UIKit

struct MouseView: View {
    @EnvironmentObject var model: RemoteModel
    @State private var sensitivity = Store.sensitivity
    @State private var flash: String?

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(String(format: "Sensitivity %.1f×", sensitivity))
                    .font(Fonts.mono(12)).foregroundStyle(P.sub)
                Slider(value: $sensitivity, in: 0.5...6, step: 0.1)
                    .onChange(of: sensitivity) { _, v in Store.sensitivity = v }
            }
            .padding(.horizontal, 16).padding(.vertical, 8)
            .background(P.surface)

            ZStack {
                DotGrid()
                Trackpad(model: model, sensitivity: sensitivity) { what in flash = what }
                VStack {
                    Spacer()
                    Text(flash.map { "\($0) click" } ?? "1 finger move · tap click · 2 fingers scroll · 2-finger tap right-click · tap-tap-drag select")
                        .font(.caption2).foregroundStyle(flash == nil ? P.mute : P.accent)
                        .multilineTextAlignment(.center)
                        .padding(10)
                        .allowsHitTesting(false)
                }
            }
            .task(id: flash) {
                guard flash != nil else { return }
                try? await Task.sleep(for: .milliseconds(600))
                flash = nil
            }

            buttons
        }
        .background(P.bg)
    }

    private var buttons: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                MouseButton(label: "Left") { model.click("left"); flash = "left" }
                MouseButton(label: "Middle") { model.click("middle"); flash = "middle" }
                MouseButton(label: "Right") { model.click("right"); flash = "right" }
            }
            HStack(spacing: 8) {
                RepeatButton(system: "chevron.up") { model.scroll(dy: 1) }
                MouseButton(label: "Double") { model.click("left", n: 2); flash = "double" }
                RepeatButton(system: "chevron.down") { model.scroll(dy: -1) }
            }
        }
        .padding(12)
        .background(P.surface)
    }
}

private struct MouseButton: View {
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            action()
        }) {
            Text(label).font(Fonts.mono(13)).foregroundStyle(P.text)
                .frame(maxWidth: .infinity).padding(.vertical, 14)
                .background(P.panel, in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(P.border))
        }
    }
}

/// Fires once on press, then keeps firing while held — a scroll wheel.
struct RepeatButton: View {
    let system: String
    let action: () -> Void
    @State private var timer: Timer?

    var body: some View {
        Image(systemName: system).foregroundStyle(P.accent)
            .frame(maxWidth: .infinity).padding(.vertical, 14)
            .background(P.panel, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(P.border))
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard timer == nil else { return }
                        action()
                        timer = Timer.scheduledTimer(withTimeInterval: 0.09, repeats: true) { _ in
                            Task { @MainActor in action() }
                        }
                    }
                    .onEnded { _ in
                        timer?.invalidate()
                        timer = nil
                    }
            )
    }
}

private struct DotGrid: View {
    var body: some View {
        Canvas { ctx, size in
            let step: CGFloat = 22
            var y: CGFloat = step / 2
            while y < size.height {
                var x: CGFloat = step / 2
                while x < size.width {
                    ctx.fill(Path(ellipseIn: CGRect(x: x - 1, y: y - 1, width: 2, height: 2)),
                             with: .color(P.mute))
                    x += step
                }
                y += step
            }
        }
        .background(P.bg)
    }
}

// ── the pad itself ───────────────────────────────────────────────────────────

private struct Trackpad: UIViewRepresentable {
    let model: RemoteModel
    let sensitivity: Double
    let onFlash: (String) -> Void

    func makeUIView(context: Context) -> TrackpadUIView {
        let v = TrackpadUIView()
        v.model = model
        v.onFlash = onFlash
        return v
    }

    func updateUIView(_ v: TrackpadUIView, context: Context) {
        v.sensitivity = sensitivity
        v.onFlash = onFlash
    }
}

/// Raw touches rather than SwiftUI gestures: the tap / drag / two-finger
/// scroll / two-finger tap / tap-tap-drag grammar needs every pointer's
/// lifetime, which gesture recognisers fight over.
///
/// Thresholds are in *pixels* (points × screen scale) so they match the
/// Android build, where the feel was tuned.
final class TrackpadUIView: UIView {
    weak var model: RemoteModel?
    var sensitivity: Double = 2
    var onFlash: ((String) -> Void)?

    private var downAt = Date.distantPast
    private var lastTapAt = Date.distantPast
    private var moved: CGFloat = 0
    private var maxPointers = 0
    private var scrollAcc: CGFloat = 0
    private var holdDrag = false
    private var dragLatched = false
    private let haptic = UIImpactFeedbackGenerator(style: .light)

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        backgroundColor = .clear
    }

    required init?(coder: NSCoder) { fatalError() }

    private var scale: CGFloat { window?.screen.scale ?? 3 }

    private func active(_ event: UIEvent?) -> [UITouch] {
        (event?.touches(for: self) ?? []).filter { $0.phase != .ended && $0.phase != .cancelled }
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        let count = active(event).count
        if count == touches.count {
            // First finger(s) of a new gesture.
            downAt = Date()
            moved = 0
            maxPointers = 0
            scrollAcc = 0
            // A touch that lands within the double-tap window holds the left
            // button: tap-tap-drag selects, like a laptop pad.
            holdDrag = Date().timeIntervalSince(lastTapAt) < 0.3
            if holdDrag {
                model?.button("left", down: true)
                dragLatched = true
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            }
        }
        maxPointers = max(maxPointers, count)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        let act = active(event)
        maxPointers = max(maxPointers, act.count)
        let s = scale
        if act.count >= 2 {
            // Two fingers: vertical scroll, natural direction, one notch per 40px.
            let dy = act.map { ($0.location(in: self).y - $0.previousLocation(in: self).y) * s }
                .reduce(0, +) / CGFloat(act.count)
            scrollAcc += dy
            while abs(scrollAcc) >= 40 {
                let dir = scrollAcc > 0 ? 1 : -1
                model?.scroll(dy: dir)
                scrollAcc -= CGFloat(dir) * 40
            }
            moved += abs(dy)
        } else if let t = act.first {
            let dx = (t.location(in: self).x - t.previousLocation(in: self).x) * s
            let dy = (t.location(in: self).y - t.previousLocation(in: self).y) * s
            if dx != 0 || dy != 0 {
                model?.move(dx: Double(dx) * sensitivity, dy: Double(dy) * sensitivity)
                moved += abs(dx) + abs(dy)
            }
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard active(event).isEmpty else { return }
        finish(cancelled: false)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard active(event).isEmpty else { return }
        finish(cancelled: true)
    }

    private func finish(cancelled: Bool) {
        if dragLatched {
            model?.button("left", down: false)
            dragLatched = false
        }
        guard !cancelled else { return }
        // A short, still touch is a click. Two fingers = right.
        let quick = Date().timeIntervalSince(downAt) < 0.25
        if quick && moved < 18 && !holdDrag {
            if maxPointers >= 2 {
                model?.click("right")
                onFlash?("right")
            } else {
                model?.click("left")
                onFlash?("left")
                lastTapAt = Date()
            }
            haptic.impactOccurred()
        }
    }
}
