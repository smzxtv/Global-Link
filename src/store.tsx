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

/**
 * 构造一个 VLESS + WS + TLS 节点。
 * `overrides` 用于按订阅实际参数逐节点覆盖（不同节点的 uuid / sni / host / path 常常不同），
 * 未覆盖的字段沿用下面的公共默认值。
 */
const vlessNode = (
  id: string,
  name: string,
  address: string,
  port: number,
  overrides: Partial<Record<string, string>> = {},
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
    ...overrides,
  },
});

/** 全部内置默认节点（首启时若无持久化配置即加载这组真实节点）。
 *
 *  顺序即回退优先级：`ConnectPage` 与 `hydrate` 在没有有效选中项时会取列表第一个，
 *  因此第一位必须是实测最稳的节点。这里首位给 HK 而不是延迟更低的 TW：
 *  实测 HK-4 0.46s、JP-2 0.56s，但国内出口到 HK 的路由通常更稳定，故 HK 优先。
 *
 *  数据来源：抓取面板订阅（80 个 VLESS+WS+TLS 节点），用与应用同款的
 *  sing-box 核心起 SOCKS5 入站，以「国内被墙的站点」为判据逐个端到端实测：
 *  2026-09-15 用 `https://www.google.com/generate_204` 必须 204（再用 github / wikipedia
 *  复核 200、cloudflare trace 核对落地国家），实测 **67/80 真正可用**；
 *  下面 6 个按实测延迟择优并覆盖 4 个地区。
 *
 *  ⚠️ 这些是裸 IP 快照，面板更换 IP 池后会整体失效。2026-09-15 复测发现上一版 6 个里
 *  有 3 个（TW-1 / JP-2 / CA-1）在 TCP 层就已不可达，已换成同区域实测可用的新节点。
 *  长期正解是引导用户在「配置」页导入订阅，而不是依赖内置节点。
 */
export const DEFAULT_PROFILES: ServerProfile[] = [
  vlessNode("default-hk4", "HK-4 (45.152.64.16)", "45.152.64.16", 443),
  // 2026-09-15 替换：原 43.213.230.249 已下架（TCP 层无响应）
  vlessNode("default-tw1", "TW-1 (45.207.159.187)", "45.207.159.187", 443),
  // 2026-09-15 替换：原 138.3.212.160 已下架（TCP 层无响应）
  vlessNode("default-jp2", "JP-2 (147.79.20.177)", "147.79.20.177", 443),
  // 2026-09-15 替换：原 137.220.52.250 已下架（TCP 层无响应）
  vlessNode("default-ca1", "CA-1 (167.99.183.13)", "167.99.183.13", 443),
  // 2026-09-15 替换：原 140.235.38.47 可用但极慢（github 10.3s），换同区域更快的
  vlessNode("default-tw2", "TW-2 (140.235.39.19)", "140.235.39.19", 443),
  // 上一版快照节点：已不在当前面板订阅中，但 2026-09-15 实测仍能连通（0.46s），留作兜底。
  vlessNode("default-hk2", "HK-2 (122.10.119.252)", "122.10.119.252", 443),
];

/** 兼容旧引用：默认配置即内置节点列表的第一个（当前为实测最稳的 HK-4）。 */
export const DEFAULT_PROFILE: ServerProfile = DEFAULT_PROFILES[0];

/** 内置节点的 id 前缀，用于和用户自建/导入的节点区分。 */
const BUILTIN_ID_PREFIX = "default-";

/** 把磁盘上的旧 profile 列表与当前版本自带的内置节点对账。
 *
 *  为什么必须做这一步：面板会更换 IP 池，内置节点是「随版本发布的快照」。
 *  持久化时整表快照会被原样读回，于是老用户升级后**看到的仍是上一版的死节点**，
 *  表现就是「列表里有节点，点连接却连不上」——只有全新安装才能拿到新节点。
 *
 *  规则：`default-*` 内置节点一律以当前版本为准（因此用户在内置节点上做的手工
 *  改动、或删掉的内置节点，会在下次启动时被恢复）；非 `default-*` 的用户节点
 *  原样保留并排在后面。若需长期保留自己的节点，请在「配置」页导入订阅，
 *  它会生成 `profile-*` 这样的独立 id，不受此逻辑影响。
 */
export function reconcileProfiles(saved: ServerProfile[]): ServerProfile[] {
  const custom = saved.filter((p) => !p.id.startsWith(BUILTIN_ID_PREFIX));
  return [...DEFAULT_PROFILES, ...custom];
}

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
  // 不写死具体 id：内置列表会随版本换血，写死会在节点下架后指向不存在的配置。
  conn: { profileId: DEFAULT_PROFILE.id, mode: "socks5", protocol: "auto", scanMode: "quick" },
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
      // 保存到磁盘的是一份「快照」，其中可能含上一版本的内置节点，因此读回时必须
      // 与当前版本自带的内置节点对账（详见 reconcileProfiles）。
      {
        const savedProfiles =
          action.state.profiles && action.state.profiles.length > 0
            ? reconcileProfiles(action.state.profiles)
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