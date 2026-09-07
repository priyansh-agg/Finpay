const TOKEN_KEY = "finpay.token";
const KEY_KEY = "finpay.apikey";

export const auth = {
  get token() { return localStorage.getItem(TOKEN_KEY); },
  set token(v: string | null) { v ? localStorage.setItem(TOKEN_KEY, v) : localStorage.removeItem(TOKEN_KEY); },
  get apiKey() { return localStorage.getItem(KEY_KEY); },
  set apiKey(v: string | null) { v ? localStorage.setItem(KEY_KEY, v) : localStorage.removeItem(KEY_KEY); },
  get role() { return localStorage.getItem("finpay.role"); },
  set role(v: string | null) { v ? localStorage.setItem("finpay.role", v) : localStorage.removeItem("finpay.role"); },
  clear() { this.token = null; this.apiKey = null; this.role = null; },
};

type Opts = { method?: string; body?: unknown; asMerchant?: boolean; headers?: Record<string, string> };

export async function api<T = any>(path: string, opts: Opts = {}): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json", ...(opts.headers ?? {}) };
  if (opts.asMerchant && auth.apiKey) headers["X-API-Key"] = auth.apiKey;
  else if (auth.token) headers["Authorization"] = `Bearer ${auth.token}`;

  const res = await fetch(path, {
    method: opts.method ?? "GET",
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (res.status === 401 && auth.token) {
    // token expired or gateway restarted — drop it and bounce to the login screen
    auth.clear();
    location.reload();
  }
  if (!res.ok) throw new Error(data?.message ?? `HTTP ${res.status}`);
  return data as T;
}

export function login(email: string, password: string) {
  return api<{ token: string; role: string; email: string }>("/v1/auth/login", {
    method: "POST",
    body: { email, password },
  });
}
