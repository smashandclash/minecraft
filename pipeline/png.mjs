// A tiny RGBA canvas and PNG encoder for the textures the pipeline draws itself (the board, the rim).
import { crc32, deflateSync } from 'node:zlib';

export class Canvas {
	constructor(w, h) {
		this.w = w;
		this.h = h;
		this.px = new Float32Array(w * h * 4); // straight RGBA, 0-1
	}

	/** Paint one pixel with coverage a (0-1) of colour [r,g,b,a] (0-255), source-over. */
	blend(x, y, c, a = 1) {
		if (x < 0 || y < 0 || x >= this.w || y >= this.h || a <= 0) return;
		const i = (y * this.w + x) * 4;
		const sa = (c[3] ?? 255) / 255 * Math.min(1, a);
		const da = this.px[i + 3];
		const oa = sa + da * (1 - sa);
		for (let k = 0; k < 3; k++) {
			const s = c[k] / 255;
			this.px[i + k] = oa > 0 ? (s * sa + this.px[i + k] * da * (1 - sa)) / oa : 0;
		}
		this.px[i + 3] = oa;
	}

	/** A rounded rectangle, anti-aliased (fn(x, y) may vary the colour per pixel). */
	roundRect(x0, y0, w, h, r, color) {
		const fn = typeof color === 'function' ? color : () => color;
		for (let y = Math.floor(y0); y < Math.ceil(y0 + h); y++) {
			for (let x = Math.floor(x0); x < Math.ceil(x0 + w); x++) {
				const cx = Math.min(Math.max(x + 0.5, x0 + r), x0 + w - r);
				const cy = Math.min(Math.max(y + 0.5, y0 + r), y0 + h - r);
				const d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
				const cover = Math.max(0, Math.min(1, r - d + 0.5));
				if (cover > 0) this.blend(x, y, fn(x, y), cover);
			}
		}
	}

	png() {
		const raw = Buffer.alloc((this.w * 4 + 1) * this.h);
		for (let y = 0; y < this.h; y++) {
			raw[y * (this.w * 4 + 1)] = 0;
			for (let x = 0; x < this.w; x++) {
				const i = (y * this.w + x) * 4;
				const o = y * (this.w * 4 + 1) + 1 + x * 4;
				for (let k = 0; k < 4; k++) raw[o + k] = Math.round(Math.max(0, Math.min(1, this.px[i + k])) * 255);
			}
		}
		const chunk = (type, data) => {
			const len = Buffer.alloc(4);
			len.writeUInt32BE(data.length);
			const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
			const crc = Buffer.alloc(4);
			crc.writeUInt32BE(crc32(td) >>> 0);
			return Buffer.concat([len, td, crc]);
		};
		const ihdr = Buffer.alloc(13);
		ihdr.writeUInt32BE(this.w, 0);
		ihdr.writeUInt32BE(this.h, 4);
		ihdr[8] = 8; // bit depth
		ihdr[9] = 6; // RGBA
		return Buffer.concat([
			Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
			chunk('IHDR', ihdr),
			chunk('IDAT', deflateSync(raw, { level: 9 })),
			chunk('IEND', Buffer.alloc(0)),
		]);
	}
}

/** A repeatable pseudo-random 0-1 for texture grain. */
export function noise(seed) {
	let a = seed >>> 0;
	return () => {
		a = (a + 0x6d2b79f5) | 0;
		let t = Math.imul(a ^ (a >>> 15), 1 | a);
		t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
}
