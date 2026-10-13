export {};

type OrbitVBinary = Blob | ArrayBuffer | ArrayBufferView | Uint8Array | number[];
type OrbitVChunk = string | OrbitVBinary;
type OrbitVWritable = OrbitVChunk | ReadableStream<Uint8Array>;

interface OrbitVFsEntry {
  path: string;
  name: string;
  type: 'file' | 'directory';
  size: number;
  modifiedAt: string;
  mimeType: string;
}

interface OrbitVPickedFile {
  id: string;
  name: string;
  mimeType: string;
  size: number;
  createdAt: string;
  path: string;
}

interface OrbitVFileHandle {
  readonly path: string;
  readonly mode: 'read' | 'write' | 'append' | 'readWrite';
  read(length?: number): Promise<Uint8Array>;
  write(data: OrbitVChunk): Promise<number>;
  seek(position: number): Promise<unknown>;
  truncate(length: number): Promise<unknown>;
  flush(): Promise<void>;
  close(): Promise<boolean>;
}

interface OrbitVFileSystem {
  pick(options?: {
    multiple?: boolean;
    accept?: string[];
  }): Promise<OrbitVPickedFile[]>;
  stat(path: string): Promise<OrbitVFsEntry | null>;
  exists(path: string): Promise<boolean>;
  readdir(path?: string): Promise<OrbitVFsEntry[]>;
  mkdir(path: string, options?: { recursive?: boolean }): Promise<OrbitVFsEntry>;
  rename(
    from: string,
    to: string,
    options?: { overwrite?: boolean },
  ): Promise<OrbitVFsEntry>;
  copy(
    from: string,
    to: string,
    options?: { overwrite?: boolean },
  ): Promise<OrbitVFsEntry>;
  remove(path: string, options?: { recursive?: boolean }): Promise<boolean>;
  truncate(path: string, length: number): Promise<OrbitVFsEntry>;
  open(
    path: string,
    mode?: 'read' | 'write' | 'append' | 'readWrite',
  ): Promise<OrbitVFileHandle>;
  readFile(
    path: string,
    options?: { encoding?: 'base64' },
  ): Promise<Uint8Array | string>;
  readText(path: string): Promise<string>;
  readJson<T = unknown>(path: string): Promise<T>;
  writeFile(path: string, data: OrbitVWritable): Promise<OrbitVFsEntry>;
  appendFile(path: string, data: OrbitVWritable): Promise<OrbitVFsEntry>;
  writeText(path: string, text: string): Promise<OrbitVFsEntry>;
  writeJson(path: string, value: unknown): Promise<OrbitVFsEntry>;
  createReadStream(
    path: string,
    options?: { chunkSize?: number },
  ): ReadableStream<Uint8Array>;
  createWriteStream(
    path: string,
    options?: { append?: boolean },
  ): WritableStream<OrbitVChunk>;
  url(path: string): Promise<string>;
}

interface OrbitVStorage {
  get<T = unknown>(key: string): Promise<T | null>;
  set<T = unknown>(key: string, value: T): Promise<T>;
  has(key: string): Promise<boolean>;
  remove(key: string): Promise<boolean>;
  keys(): Promise<string[]>;
  clear(): Promise<boolean>;
}

type OrbitVFileExportOptions =
  | {
      path: string;
      data?: never;
      name?: string;
      mimeType?: string;
    }
  | {
      path?: never;
      data: OrbitVWritable;
      name?: string;
      mimeType?: string;
    };

interface OrbitVFileExportResult {
  saved: boolean;
  cancelled: boolean;
  name: string;
}

interface OrbitVFileApi {
  choose(options?: {
    multiple?: boolean;
    accept?: string[];
  }): Promise<OrbitVPickedFile[]>;
  list(): Promise<{ files: OrbitVPickedFile[] }>;
  remove(fileId: string): Promise<{ removed: boolean }>;
  /** 打开手机系统保存页面，将内容或扩展程序沙箱文件导出到用户选择的位置。仅限可见页面。 */
  export(options: OrbitVFileExportOptions): Promise<OrbitVFileExportResult>;
}

interface OrbitVServerRequest {
  readonly method: string;
  readonly path: string;
  readonly url: string;
  readonly headers: Headers;
  readonly query: URLSearchParams;
  readonly params: Readonly<Record<string, string>>;
  readonly size: number;
  readonly body: ReadableStream<Uint8Array> | null;
  arrayBuffer(): Promise<ArrayBuffer>;
  text(): Promise<string>;
  json<T = unknown>(): Promise<T>;
  blob(): Promise<Blob>;
  formData(): Promise<FormData>;
}

interface OrbitVWebSocketEventMap {
  open: Event;
  message: MessageEvent<string | ArrayBuffer>;
  close: CloseEvent;
  error: Event;
}

interface OrbitVServerSocket {
  readonly id: string;
  readonly readyState: number;
  onopen: ((event: Event) => void) | null;
  onmessage: ((event: MessageEvent<string | ArrayBuffer>) => void) | null;
  onclose: ((event: CloseEvent) => void) | null;
  onerror: ((event: Event) => void) | null;
  send(data: string | OrbitVBinary): Promise<unknown>;
  close(code?: number, reason?: string): Promise<boolean>;
  addEventListener<K extends keyof OrbitVWebSocketEventMap>(
    type: K,
    listener: (event: OrbitVWebSocketEventMap[K]) => void,
  ): void;
  removeEventListener<K extends keyof OrbitVWebSocketEventMap>(
    type: K,
    listener: (event: OrbitVWebSocketEventMap[K]) => void,
  ): void;
}

interface OrbitVRouteRegistration {
  remove(): void;
}

interface OrbitVFileResponse {
  readonly __orbitvFileResponse: true;
  readonly path: string;
}

type OrbitVRouteResult =
  | Response
  | OrbitVFileResponse
  | string
  | OrbitVBinary
  | Record<string, unknown>
  | null
  | void;

interface OrbitVServer {
  route(
    method: string,
    path: string,
    handler: (request: OrbitVServerRequest) => OrbitVRouteResult | Promise<OrbitVRouteResult>,
  ): OrbitVRouteRegistration;
  get(path: string, handler: (request: OrbitVServerRequest) => OrbitVRouteResult | Promise<OrbitVRouteResult>): OrbitVRouteRegistration;
  post(path: string, handler: (request: OrbitVServerRequest) => OrbitVRouteResult | Promise<OrbitVRouteResult>): OrbitVRouteRegistration;
  put(path: string, handler: (request: OrbitVServerRequest) => OrbitVRouteResult | Promise<OrbitVRouteResult>): OrbitVRouteRegistration;
  patch(path: string, handler: (request: OrbitVServerRequest) => OrbitVRouteResult | Promise<OrbitVRouteResult>): OrbitVRouteRegistration;
  delete(path: string, handler: (request: OrbitVServerRequest) => OrbitVRouteResult | Promise<OrbitVRouteResult>): OrbitVRouteRegistration;
  websocket(
    path: string,
    handler: (
      socket: OrbitVServerSocket,
      request: Omit<OrbitVServerRequest, 'method' | 'size' | 'body' | 'arrayBuffer' | 'text' | 'json' | 'blob' | 'formData'>,
    ) => void | Promise<void>,
  ): OrbitVRouteRegistration;
  connections(): OrbitVServerSocket[];
  broadcast(data: string | OrbitVBinary): Promise<number>;
  file(path: string, init?: { status?: number; headers?: HeadersInit }): OrbitVFileResponse;
  json(value: unknown, init?: ResponseInit): Response;
  ready(): Promise<unknown>;
  url(path: string): Promise<string>;
}

interface OrbitVRuntimeInfo {
  apiVersion: number;
  appId: string;
  name: string;
  mode?: 'service';
  capabilities: string[];
}

interface OrbitVNavigationRegistration {
  remove(): void;
}

type OrbitVNavigationBarTextStyle = 'auto' | 'black' | 'white';

interface OrbitVNavigation {
  /** 通常由模板根据 pages.json 自动调用；同步当前页面的原生顶部栏和系统栏外观。 */
  setPage(options: {
    title?: string;
    navigationStyle?: 'default' | 'custom';
    /** 页面背景色，只接受 #RRGGBB。 */
    backgroundColor?: string;
    /** OrbitV 原生顶部栏背景色，只接受 #RRGGBB。 */
    navigationBarBackgroundColor?: string;
    /** 顶部文字和系统状态栏图标颜色；auto 会根据背景自动选择。 */
    navigationBarTextStyle?: OrbitVNavigationBarTextStyle;
  }): Promise<{
    title: string;
    navigationStyle: 'default' | 'custom';
    backgroundColor: string;
    navigationBarBackgroundColor: string;
    navigationBarTextStyle: OrbitVNavigationBarTextStyle;
  }>;
  /** 注册系统返回处理器。返回 false 表示交给 OrbitV 继续处理。 */
  onBack(
    handler: () => boolean | void | Promise<boolean | void>,
  ): OrbitVNavigationRegistration;
  /** 依次执行页面返回处理器、WebView 历史回退，最后关闭扩展程序。 */
  back(): Promise<boolean>;
  /** 立即关闭当前扩展程序页面。 */
  close(): Promise<boolean>;
  /** 重新加载当前网页。 */
  reload(): void;
}

interface OrbitVUi {
  /** 使用 OrbitV 原生 Toast；仅供可见管理页面调用。 */
  toast(options: {
    message: string;
    title?: string;
    type?: 'success' | 'error' | 'info';
  }): Promise<{ shown: true }>;
  toast(message: string): Promise<{ shown: true }>;
}

type OrbitVWatchBatteryState =
  | 'charging'
  | 'notCharging'
  | 'full'
  | 'noBattery'
  | 'unknown';

interface OrbitVWatchInfo {
  /** 当前是否连接手表。未连接时其余设备字段通常为 null。 */
  connected: boolean;
  /** 手机与手表的业务传输通道是否已经可用。 */
  transportReady: boolean;
  /** 系统报告的手表名称。 */
  name: string | null;
  /** vivo 产品型号 ID。 */
  productId: number | null;
  /** 手表序列号。 */
  serialNumber: string | null;
  /** 首选通信地址：优先经典蓝牙 MAC，其次 BLE MAC。 */
  macAddress: string | null;
  /** 当前 BLE 连接标识；Android 通常为 MAC，Apple 平台可能为 UUID。 */
  bleAddress: string | null;
  bleMacAddress: string | null;
  classicMacAddress: string | null;
  battery: {
    /** 电量百分比，暂未读取到时为 null。 */
    level: number | null;
    state: OrbitVWatchBatteryState;
    charging: boolean | null;
    /** 底层电池状态值，仅用于兼容和诊断。 */
    rawState: number | null;
  };
  storage: {
    totalBytes: number | null;
    freeBytes: number | null;
    usedBytes: number | null;
  };
}

interface OrbitVApi {
  readonly version: string;
  readonly runtime: {
    getInfo(): Promise<OrbitVRuntimeInfo>;
  };
  readonly fs: OrbitVFileSystem;
  readonly storage: OrbitVStorage;
  readonly data: OrbitVStorage;
  /** OrbitV 原生界面反馈；service.js 不应调用。 */
  readonly ui: OrbitVUi;
  /** 仅供可见管理页面使用，service.js 不应调用。 */
  readonly navigation: OrbitVNavigation;
  readonly server: OrbitVServer;
  /** 用户文件选择、已导入文件管理和系统文件导出。需要弹出系统页面的方法仅限可见页面。 */
  readonly file: OrbitVFileApi;
  readonly watch: {
    /** 兼容早期项目的简要连接状态。新项目优先使用 getInfo。 */
    getState(): Promise<{
      connected: boolean;
      transportReady: boolean;
      watchSn: string;
    }>;
    /** 返回 OrbitV 当前连接手表的完整只读快照。页面与 service.js 均可调用。 */
    getInfo(): Promise<OrbitVWatchInfo>;
  };
}

declare global {
  const ov: OrbitVApi;

  interface Window {
    readonly ov: OrbitVApi;
    addEventListener(
      type: 'ovready',
      listener: (event: CustomEvent<void>) => void,
      options?: boolean | AddEventListenerOptions,
    ): void;
    addEventListener(
      type: 'ovdownload',
      listener: (event: CustomEvent<OrbitVFsEntry>) => void,
      options?: boolean | AddEventListenerOptions,
    ): void;
  }
}
