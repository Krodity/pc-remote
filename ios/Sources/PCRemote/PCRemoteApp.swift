import SwiftUI

@main
struct PCRemoteApp: App {
    @StateObject private var model = RemoteModel()
    @Environment(\.scenePhase) private var phase

    init() {
        Fonts.register()
        // Share-sheet and QuickLook downloads are only needed while shown.
        try? FileManager.default.removeItem(at: AgentClient.sharedDir)
        let bar = UITabBarAppearance()
        bar.configureWithOpaqueBackground()
        bar.backgroundColor = UIColor(P.nav)
        UITabBar.appearance().standardAppearance = bar
        UITabBar.appearance().scrollEdgeAppearance = bar
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .preferredColorScheme(.dark)
                .tint(P.accent)
                // pcremote://pair?host=…&token=… from the agent's /pair page.
                .onOpenURL { url in
                    if let p = Pairing(url: url) { model.pair(p) }
                }
        }
        .onChange(of: phase) { _, now in
            if now == .active { model.resume() }
        }
    }
}

struct RootView: View {
    @EnvironmentObject var model: RemoteModel

    var body: some View {
        if model.pairing.isSet {
            MainTabs()
        } else {
            PairView()
        }
    }
}

enum Tab: Hashable { case mouse, keys, files, shell }

struct MainTabs: View {
    @EnvironmentObject var model: RemoteModel
    @State private var tab: Tab = .mouse

    var body: some View {
        VStack(spacing: 0) {
            TopBar()
            TabView(selection: $tab) {
                MouseView().tabItem { Label("Mouse", systemImage: "cursorarrow.motionlines") }.tag(Tab.mouse)
                KeysView().tabItem { Label("Keys", systemImage: "keyboard") }.tag(Tab.keys)
                FilesView().tabItem { Label("Files", systemImage: "folder") }.tag(Tab.files)
                ShellView().tabItem { Label("Shell", systemImage: "terminal") }.tag(Tab.shell)
            }
        }
        .background(P.bg)
        .overlay(alignment: .bottom) {
            if let t = model.toast {
                Text(t)
                    .font(.footnote)
                    .foregroundStyle(P.text)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(P.panel, in: RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(P.border))
                    .padding(.bottom, 70)
                    .onTapGesture { model.toast = nil }
                    .task(id: t) {
                        try? await Task.sleep(for: .seconds(3))
                        if model.toast == t { model.toast = nil }
                    }
            }
        }
    }
}

/// Connection dot, host, latency — and the way back to the pair screen.
struct TopBar: View {
    @EnvironmentObject var model: RemoteModel
    @State private var confirmUnpair = false

    private var dot: Color {
        switch model.link {
        case .online: P.green
        case .connecting: P.amber
        case .offline: P.red
        }
    }

    var body: some View {
        HStack(spacing: 10) {
            Circle().fill(dot).frame(width: 8, height: 8)
                .shadow(color: dot.opacity(0.7), radius: 4)
            VStack(alignment: .leading, spacing: 1) {
                Text(model.sysinfo?.host ?? model.pairing.host)
                    .font(Fonts.mono(14)).foregroundStyle(P.text)
                Text(subtitle).font(.caption2).foregroundStyle(P.sub)
            }
            Spacer()
            if let ms = model.latency {
                Text("\(ms) ms").font(Fonts.mono(12)).foregroundStyle(ms < 60 ? P.green : P.amber)
            }
            Menu {
                if let s = model.sysinfo {
                    Text("\(s.user ?? "?")@\(s.host ?? "?")")
                    if let os = s.os { Text(os) }
                    if let k = s.kernel { Text("Kernel \(k)") }
                }
                Button("Reconnect") { model.start() }
                Button("Unpair", role: .destructive) { confirmUnpair = true }
            } label: {
                Image(systemName: "ellipsis.circle").foregroundStyle(P.sub).font(.title3)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(P.nav)
        .overlay(alignment: .bottom) { Rectangle().fill(P.border).frame(height: 1) }
        .confirmationDialog("Forget this PC?", isPresented: $confirmUnpair, titleVisibility: .visible) {
            Button("Unpair", role: .destructive) { model.unpair() }
        }
    }

    private var subtitle: String {
        switch model.link {
        case .online:
            if let s = model.sysinfo, let cpu = s.cpu, let mem = s.mem?.percent {
                return String(format: "CPU %.0f%% · RAM %.0f%%", cpu, mem)
            }
            return "connected"
        case .connecting: return "connecting…"
        case .offline: return model.lastError ?? "offline"
        }
    }
}
