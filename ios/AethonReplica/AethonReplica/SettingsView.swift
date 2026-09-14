import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var appState: AppState
    
    var body: some View {
        NavigationView {
            Form {
                Section("本地代理") {
                    HStack {
                        Text("SOCKS5 监听端口")
                        Spacer()
                        Text("1819")
                            .foregroundColor(.secondary)
                    }
                    HStack {
                        Text("日志级别")
                        Spacer()
                        Text("info")
                            .foregroundColor(.secondary)
                    }
                }
                
                Section("更新") {
                    HStack {
                        Text("当前版本")
                        Spacer()
                        Text("2.1.0")
                            .foregroundColor(.secondary)
                    }
                    Button("检查更新") {
                        appState.addLog("[提示] 检查更新...")
                    }
                }
                
                Section("关于") {
                    HStack {
                        Text("应用")
                        Spacer()
                        Text("环球通 Global Link")
                            .foregroundColor(.secondary)
                    }
                    HStack {
                        Text("核心")
                        Spacer()
                        Text("sing-box 1.14.0")
                            .foregroundColor(.secondary)
                    }
                    Link("Telegram 群组", destination: URL(string: "https://t.me/+tVg48WK48tlkNGVl")!)
                    HStack {
                        Text("数码解码 · 技术支持")
                        Spacer()
                        Image(systemName: "checkmark.shield.fill")
                            .foregroundColor(.green)
                    }
                }
            }
            .navigationTitle("设置")
        }
    }
}
