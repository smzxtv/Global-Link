// Shared domain types for the Global Link (环球通) client.

export type Page = "connect" | "configurations" | "settings";

export type ConnectionMode = "vpn" | "socks5";

export type ConnectionState = "disconnected" | "connecting" | "connected" | "error";

export type Protocol =
  | "auto"
  | "shadowsocks"
  | "vmess"
  | "vless"
  | "trojan"
  | "hysteria2"
  | "tuic"
  | "http";

export type ScanMode = "disabled" | "quick" | "full";

export type LogLevel = "error" | "warn" | "info" | "debug" | "trace";

export interface ServerProfile {
  id: string;
  name: string;
  protocol: Protocol;
  address: string;
  port: number;
  /** Protocol-specific settings, e.g. password, method, uuid, security, sni … */
  params: Record<string, string>;
}

export interface ConnectionSettings {
  profileId: string | null;
  mode: ConnectionMode;
  protocol: Protocol;
  scanMode: ScanMode;
}

export interface GeneralSettings {
  socksPort: number;
  logLevel: LogLevel;
  autoUpdate: boolean;
  autoUpdateHours: number;
  autoDownload: boolean;
}