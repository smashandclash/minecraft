// @smashandclash/sdk inside Minecraft Bedrock. The SDK needs fetch, a sleep and URL; a
// behavior pack's script runtime has none of them, so they are given here: fetch over
// @minecraft/server-net (HTTP, which Bedrock allows on a dedicated server with Beta APIs on),
// sleep over system.runTimeout, and a small URL for the parts the SDK uses.

import { system } from '@minecraft/server';
import { http, HttpHeader, HttpRequest, HttpRequestMethod } from '@minecraft/server-net';

/* ------------------------------------- URL ------------------------------------- */

class Params {
	private list: Array<[string, string]> = [];
	constructor(q = '') {
		for (const part of q.replace(/^\?/, '').split('&')) {
			if (!part) continue;
			const eq = part.indexOf('=');
			const k = decodeURIComponent((eq < 0 ? part : part.slice(0, eq)).replace(/\+/g, ' '));
			const v = eq < 0 ? '' : decodeURIComponent(part.slice(eq + 1).replace(/\+/g, ' '));
			this.list.push([k, v]);
		}
	}
	get(k: string): string | null {
		return this.list.find((e) => e[0] === k)?.[1] ?? null;
	}
	set(k: string, v: string): void {
		this.list = this.list.filter((e) => e[0] !== k);
		this.list.push([k, String(v)]);
	}
	has(k: string): boolean {
		return this.list.some((e) => e[0] === k);
	}
	toString(): string {
		return this.list.map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
	}
}

class MiniURL {
	protocol: string;
	host: string;
	pathname: string;
	hash: string;
	searchParams: Params;
	constructor(input: string, base?: string) {
		let s = String(input);
		if (!/^[a-z][\w+.-]*:\/\//i.test(s) && base) s = base.replace(/\/+$/, '') + '/' + s.replace(/^\/+/, '');
		const m = /^([a-z][\w+.-]*:)\/\/([^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$/i.exec(s);
		if (!m) throw new TypeError('Invalid URL: ' + input);
		this.protocol = m[1]!;
		this.host = m[2]!;
		this.pathname = m[3] || '/';
		this.searchParams = new Params(m[4] ?? '');
		this.hash = m[5] ?? '';
	}
	get search(): string {
		const q = this.searchParams.toString();
		return q ? '?' + q : '';
	}
	get origin(): string {
		return `${this.protocol}//${this.host}`;
	}
	get href(): string {
		return this.toString();
	}
	toString(): string {
		return `${this.protocol}//${this.host}${this.pathname}${this.search}${this.hash}`;
	}
}

const g = globalThis as Record<string, unknown>;
if (typeof g.URL !== 'function') g.URL = MiniURL;
if (typeof g.setTimeout !== 'function') g.setTimeout = (fn: () => void, ms: number) => system.runTimeout(fn, Math.max(1, Math.round(ms / 50)));

/* ------------------------------------ fetch ------------------------------------ */

const METHODS: Record<string, HttpRequestMethod> = {
	GET: HttpRequestMethod.Get,
	POST: HttpRequestMethod.Post,
	PUT: HttpRequestMethod.Put,
	DELETE: HttpRequestMethod.Delete,
	HEAD: HttpRequestMethod.Head,
};

/** fetch, as much of it as the SDK uses, over @minecraft/server-net. */
export async function bedrockFetch(url: string, init: { method?: string; headers?: Record<string, string>; body?: string } = {}): Promise<unknown> {
	const req = new HttpRequest(url);
	req.setMethod(METHODS[(init.method ?? 'GET').toUpperCase()] ?? HttpRequestMethod.Get);
	// a long-poll waits up to 20 s on the server: give it room
	req.setTimeout(45);
	const headers: HttpHeader[] = [];
	for (const [k, v] of Object.entries(init.headers ?? {})) headers.push(new HttpHeader(k, v));
	req.setHeaders(headers);
	if (init.body !== undefined) req.setBody(init.body);
	let res;
	try {
		res = await http.request(req);
	} catch (e) {
		console.warn(`[Smash&Clash] HTTP ${init.method ?? 'GET'} ${url}: ${String(e)}`);
		throw new TypeError('fetch failed: ' + String(e));
	}
	const h = new Map<string, string>();
	// the response's headers are a native list: index it (it is not iterable in this runtime)
	const list = (res.headers ?? []) as unknown as ArrayLike<HttpHeader>;
	for (let i = 0; i < (list.length ?? 0); i++) {
		const x = list[i];
		if (x) h.set(String(x.key).toLowerCase(), typeof x.value === 'string' ? x.value : String(x.value));
	}
	return {
		ok: res.status >= 200 && res.status < 300,
		status: res.status,
		headers: { get: (name: string) => h.get(name.toLowerCase()) ?? null },
		text: async () => res.body,
		json: async () => JSON.parse(res.body),
	};
}

/** A sleep on the game's own clock (ticks of 50 ms). */
export function bedrockSleep(ms: number): Promise<void> {
	return new Promise((r) => system.runTimeout(() => r(), Math.max(1, Math.round(ms / 50))));
}
