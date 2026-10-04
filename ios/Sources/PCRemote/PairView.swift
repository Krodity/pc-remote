import SwiftUI

/// First run: host + token by hand, or a tap on the agent's /pair page.
struct PairView: View {
    @EnvironmentObject var model: RemoteModel
    @State private var host = ""
    @State private var token = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Image(systemName: "desktopcomputer")
                    .font(.system(size: 44)).foregroundStyle(P.accent)
                    .padding(.top, 40)
                Text("Pair PC Remote").font(.title2.bold()).foregroundStyle(P.text)
                Text("Easiest: with Tailscale on, open **http://<your-pc>:8778/pair** in Safari on this phone and tap *Pair this phone*.\n\nOr enter the host and the token from `~/.config/pc-remote/token`.")
                    .font(.callout).foregroundStyle(P.sub)

                field("Host", "my-pc or 100.x.y.z", text: $host)
                field("Token", "paste token", text: $token, secure: true)

                if let error {
                    Text(error).font(.footnote).foregroundStyle(P.red)
                }

                Button {
                    Task { await connect() }
                } label: {
                    HStack {
                        if busy { ProgressView().tint(.black) }
                        Text(busy ? "Checking…" : "Connect").bold()
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 14)
                    .background(P.accent, in: RoundedRectangle(cornerRadius: 10))
                    .foregroundStyle(.black)
                }
                .disabled(busy || host.isEmpty || token.isEmpty)
            }
            .padding(24)
        }
        .background(P.bg.ignoresSafeArea())
    }

    private func field(_ label: String, _ hint: String, text: Binding<String>, secure: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label.uppercased()).font(.caption2.bold()).foregroundStyle(P.sub)
            Group {
                if secure { SecureField(hint, text: text) } else { TextField(hint, text: text) }
            }
            .font(Fonts.mono(15))
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .padding(12)
            .background(P.surface, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(P.border))
            .foregroundStyle(P.text)
        }
    }

    private func connect() async {
        busy = true
        error = nil
        defer { busy = false }
        let p = Pairing(host: host, token: token)
        do {
            _ = try await RemoteModel.verify(p)
            model.pair(p)
        } catch {
            self.error = "Couldn't reach the agent: \(error.localizedDescription). Is Tailscale connected?"
        }
    }
}
