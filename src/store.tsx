import { createContext, useReducer, useContext } from "react";
import type { Dispatch, ReactNode } from "react";
import type { AppInfo } from "./lib/core";
import type {
  ConnectionMode,
  ConnectionSettings,
  ConnectionState,
  GeneralSettings,
  Page,
  Protocol,
  ScanMode,
  ServerProfile,
} from "./types";

export interface PersistedPayload {
  profiles: ServerProfile[];
  selectedProfileId: string | null;
  settings: GeneralSettings;
  mode: ConnectionMode;
  protocol: Protocol;
  scanMode: ScanMode;
}

/** 内置默认节点：真实可用 VLESS 订阅节点，首次启动即加载，用户可随时在“配置”页编辑/替换。 */
const DEFAULT_UUID = "c18b978e-1c4e-415c-8bea-07942b563a64";

const vlessNode = (
  id: string,
  name: string,
  address: string,
  port: number,
): ServerProfile => ({
  id,
  name,
  protocol: "vless",
  address,
  port,
  params: {
    uuid: DEFAULT_UUID,
    security: "tls",
    transport: "ws",
    sni: "shuma.ccwu.cc",
    host: "shuma.ccwu.cc",
    path: "/",
    fp: "chrome",
  },
});

/** 全部内置默认节点（首启时若无持久化配置即加载这组真实节点）。 */
export const DEFAULT_PROFILES: ServerProfile[] = [
  vlessNode("default-hk1", "HK-1 (175.29.23.87)", "175.29.23.87", 443),
  vlessNode("default-hk2", "HK-2 (122.10.119.252)", "122.10.119.252", 443),
  vlessNode("default-hk3", "HK-3 (68.64.178.52)", "68.64.178.52", 443),
  vlessNode("default-jp1", "JP-1 (103.143.81.126)", "103.143.81.126", 8443),
];

/** 兼容旧引用：默认配置即内置节点列表的第一个。 */
export const DEFAULT_PROFILE: ServerProfile = DEFAULT_PROFILES[0];

export interface AppModel {
  page: Page;
  appInfo: AppInfo | null;
  /** false until the persisted state has been loaded from disk */
  hydrated: boolean;
  profiles: ServerProfile[];
  conn: ConnectionSettings;
  status: ConnectionState;
  logs: string[];
  settings: GeneralSettings;
}

export type Action =
  | { type: "set-page"; page: Page }
  | { type: "set-app-info"; info: AppInfo }
  | { type: "hydrate"; state: Partial<PersistedPayload> }
  | { type: "add-profile"; profile: ServerProfile }
  | { type: "update-profile"; profile: ServerProfile }
  | { type: "remove-profile"; id: string }
  | { type: "select-profile"; id: string | null }
  | { type: "set-mode"; mode: ConnectionMode }
  | { type: "set-protocol"; protocol: Protocol }
  | { type: "set-scan-mode"; scanMode: ScanMode }
  | { type: "set-status"; status: ConnectionState }
  | { type: "push-log"; line: string }
  | { type: "clear-logs" }
  | { type: "set-settings"; patch: Partial<GeneralSettings> };

const initialState: AppModel = {
  page: "connect",
  appInfo: null,
  hydrated: false,
  profiles: DEFAULT_PROFILES,
  conn: { profileId: "default-hk2", mode: "socks5", protocol: "auto", scanMode: "quick" },
  status: "disconnected",
  logs: ["[应用] 环球通 Global Link 已就绪"],
  settings: {
    socksPort: 1819,
    logLevel: "info",
    autoUpdate: true,
    autoUpdateHours: 12,
    autoDownload: false,
  },
};

function reducer(state: AppModel, action: Action): AppModel {
  switch (action.type) {
    case "set-page":
      return { ...state, page: action.page };
    case "set-app-info":
      return { ...state, appInfo: action.info };
    case "hydrate":
      // 优先使用已保存的配置；若磁盘上没有任何配置（为空数组），则回退到内置默认配置。
      {
        const savedProfiles =
          action.state.profiles && action.state.profiles.length > 0
            ? action.state.profiles
            : state.profiles;
        const savedId = action.state.selectedProfileId ?? null;
        const profileId =
          savedProfiles.find((p) => p.id === savedId)?.id ?? savedProfiles[0]?.id ?? null;
        return {
          ...state,
          hydrated: true,
          profiles: savedProfiles,
          conn: {
            profileId,
            mode: action.state.mode ?? state.conn.mode,
            protocol: action.state.protocol ?? state.conn.protocol,
            scanMode: action.state.scanMode ?? state.conn.scanMode,
          },
          settings: { ...state.settings, ...action.state.settings },
        };
      }
    case "add-profile":
      return { ...state, profiles: [...state.profiles, action.profile] };
    case "update-profile":
      return {
        ...state,
        profiles: state.profiles.map((p) => (p.id === action.profile.id ? action.profile : p)),
      };
    case "remove-profile":
      return {
        ...state,
        profiles: state.profiles.filter((p) => p.id !== action.id),
        conn:
          state.conn.profileId === action.id
            ? { ...state.conn, profileId: null }
            : state.conn,
      };
    case "select-profile":
      return { ...state, conn: { ...state.conn, profileId: action.id } };
    case "set-mode":
      return { ...state, conn: { ...state.conn, mode: action.mode } };
    case "set-protocol":
      return { ...state, conn: { ...state.conn, protocol: action.protocol } };
    case "set-scan-mode":
      return { ...state, conn: { ...state.conn, scanMode: action.scanMode } };
    case "set-status":
      return { ...state, status: action.status };
    case "push-log":
      return { ...state, logs: [...state.logs, action.line].slice(-500) };
    case "clear-logs":
      return { ...state, logs: [] };
    case "set-settings":
      return { ...state, settings: { ...state.settings, ...action.patch } };
    default:
      return state;
  }
}

interface Ctx {
  state: AppModel;
  dispatch: Dispatch<Action>;
}

const AppContext = createContext<Ctx | null>(null);

export function AppProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, initialState);
  return <AppContext.Provider value={{ state, dispatch }}>{children}</AppContext.Provider>;
}

export function useApp(): Ctx {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error("useApp must be used inside <AppProvider>");
  return ctx;
}