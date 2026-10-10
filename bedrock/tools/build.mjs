// Builds the Smash&Clash Bedrock add-on: a resource pack (art, models, animations, sounds) and a
// behavior pack (the table block, the cards, the entities, and the script - the game itself,
// played on @smashandclash/sdk, bundled in). Art and sound come from the same converted files
// as the Java mod (../java/src/main/resources/assets/smashandclash, made by ../pipeline from
// sc.cards() and sc.assets()).
//
//   node tools/build.mjs            -> packs/RP, packs/BP, dist/smashandclash.mcaddon
//   node tools/build.mjs --deploy   -> also copies both packs into ../bds/server and enables them on its world

import { build } from 'esbuild';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { cp, mkdir, readdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Canvas } from '../../pipeline/png.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const ASSETS = join(ROOT, '..', 'java', 'src', 'main', 'resources', 'assets', 'smashandclash');
const RP = join(ROOT, 'packs', 'RP');
const BP = join(ROOT, 'packs', 'BP');
const NS = 'smashandclash';
const VERSION = [1, 0, 0];
const ENGINE = [1, 26, 50];

/** A stable UUID from a name (packs must keep their ids between builds). */
const uuid = (name) => {
	const h = createHash('sha1').update('smashandclash-bedrock/' + name).digest('hex');
	return `${h.slice(0, 8)}-${h.slice(8, 12)}-5${h.slice(13, 16)}-a${h.slice(17, 20)}-${h.slice(20, 32)}`;
};

async function json(file, data) {
	await mkdir(dirname(file), { recursive: true });
	// Bedrock reads a float property's numbers only when they are written as floats (1.0, not 1)
	await writeFile(file, JSON.stringify(data, null, 1).replace(/"__F(-?[\d.]+)__"/g, '$1'));
}
const F = (n) => `__F${Number.isInteger(n) ? n.toFixed(1) : n}__`;

function ffmpeg(args) {
	execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...args]);
}

async function walk(dir) {
	const out = [];
	for (const e of await readdir(dir, { withFileTypes: true })) {
		const p = join(dir, e.name);
		if (e.isDirectory()) out.push(...(await walk(p)));
		else out.push(p);
	}
	return out;
}

/* ------------------------------ drawn textures ------------------------------ */

// a filled polygon (supersampled), for the chess-piece icons
function poly(cv, pts, color) {
	const ss = 4;
	const ys = pts.map((p) => p[1]);
	for (let y = Math.floor(Math.min(...ys)); y < Math.ceil(Math.max(...ys)); y++) {
		for (let x = 0; x < cv.w; x++) {
			let cover = 0;
			for (let sy = 0; sy < ss; sy++) for (let sx = 0; sx < ss; sx++) {
				const px = x + (sx + 0.5) / ss, py = y + (sy + 0.5) / ss;
				let inside = false;
				for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
					const [xi, yi] = pts[i], [xj, yj] = pts[j];
					if (yi > py !== yj > py && px < ((xj - xi) * (py - yi)) / (yj - yi) + xi) inside = !inside;
				}
				if (inside) cover++;
			}
			if (cover) cv.blend(x, y, color, cover / (ss * ss));
		}
	}
}

const circle = (cv, cx, cy, r, color) => cv.roundRect(cx - r, cy - r, 2 * r, 2 * r, r, color);

/** A chess (operator) tile: the grape tile with its piece, the website's look. */
function drawOperator(piece) {
	const cv = new Canvas(64, 88);
	cv.roundRect(0, 0, 64, 88, 8, [178, 65, 236, 255]);
	cv.roundRect(4, 4, 56, 80, 6, [212, 150, 248, 255]);
	const ink = [91, 37, 214, 255];
	const base = () => cv.roundRect(16, 62, 32, 8, 3, ink);
	if (piece === 'ROOK') {
		cv.roundRect(22, 30, 20, 34, 2, ink);
		for (const x of [18, 28, 38]) cv.roundRect(x, 20, 8, 12, 1, ink);
		cv.roundRect(18, 28, 28, 6, 1, ink);
	} else if (piece === 'BISHOP') {
		poly(cv, [[32, 20], [44, 40], [40, 60], [24, 60], [20, 40]], ink);
		circle(cv, 32, 18, 5, ink);
		cv.roundRect(30, 30, 4, 12, 1, [212, 150, 248, 255]);
	} else if (piece === 'QUEEN') {
		poly(cv, [[16, 30], [24, 44], [32, 24], [40, 44], [48, 30], [44, 60], [20, 60]], ink);
		for (const [x, y] of [[16, 28], [32, 22], [48, 28]]) circle(cv, x, y, 4, ink);
	} else {
		// the knight: a horse's head
		poly(cv, [[22, 60], [24, 44], [18, 40], [20, 32], [30, 22], [34, 16], [38, 24], [46, 34], [44, 46], [42, 60]], ink);
		circle(cv, 32, 30, 2, [212, 150, 248, 255]);
	}
	base();
	return cv.png();
}

// a 5x7 pixel font, enough for "+1" / "+2"
const GLYPHS = {
	'+': ['00000', '00100', '00100', '11111', '00100', '00100', '00000'],
	1: ['00100', '01100', '00100', '00100', '00100', '00100', '01110'],
	2: ['01110', '10001', '00001', '00110', '01000', '10000', '11111'],
};

/** A power tile: the colour's tile with its boost. */
function drawPower(hex, boost) {
	const rgb = [parseInt(hex.slice(1, 3), 16), parseInt(hex.slice(3, 5), 16), parseInt(hex.slice(5, 7), 16)];
	const cv = new Canvas(64, 88);
	cv.roundRect(0, 0, 64, 88, 8, [...rgb, 255]);
	cv.roundRect(4, 4, 56, 80, 6, [...rgb.map((c) => Math.round(c * 0.55 + 255 * 0.45)), 255]);
	const text = '+' + boost;
	const px = 5;
	const w = text.length * 6 * px - px;
	let x0 = (64 - w) / 2;
	for (const ch of text) {
		const g = GLYPHS[ch];
		for (let y = 0; y < 7; y++) for (let x = 0; x < 5; x++) if (g[y][x] === '1') cv.roundRect(x0 + x * px, 26 + y * px, px, px, 0, [...rgb.map((c) => Math.round(c * 0.6)), 255]);
		x0 += 6 * px;
	}
	return cv.png();
}

/** The table's texture: the board top (512 x 416) above a strip of dark oak planks (the tabletop). */
async function drawTableAtlas() {
	const top = join(ASSETS, 'textures', 'block', 'board.png');
	const out = join(RP, 'textures', NS, 'table.png');
	await mkdir(dirname(out), { recursive: true });
	const planks = new Canvas(512, 96);
	for (let y = 0; y < 96; y++) {
		for (let x = 0; x < 512; x++) {
			const plank = Math.floor(y / 24);
			const seam = y % 24 === 0 || (x + plank * 97) % 170 === 0;
			const g = Math.sin(x / 9 + plank * 3) * 6 + Math.sin(x / 31 + y / 5) * 4;
			const base = seam ? [42, 26, 14] : [74 + g, 49 + g * 0.7, 26 + g * 0.4];
			planks.blend(x, y, [...base, 255]);
		}
	}
	const tmp = join(ROOT, 'packs', '.planks.png');
	await writeFile(tmp, planks.png());
	ffmpeg(['-i', top, '-i', tmp, '-filter_complex', '[0][1]vstack=inputs=2', '-pix_fmt', 'rgba', out]);
	await rm(tmp);
}

/* ---------------------------------- geometry ---------------------------------- */

const px = (blocks) => +(blocks * 16).toFixed(3);
// the folding board, at the Java mod's size
const S = 0.55, W = 512 / (165 / S), D = 416 / (165 / S), THICK = 0.07, TOP_H = 0.08;

function tableGeometry() {
	// texture: 512 x 512 (board top 512x416, planks below)
	const w = px(W), d = px(D), t = px(THICK), tw = px(W + 0.9), td = px(D + 0.24), th = px(TOP_H);
	const side = { uv: [2, 2], uv_size: [8, 8] };
	const half = (x0, u0) => ({
		origin: [x0, th, -d / 2],
		size: [w / 2, t, d],
		uv: { up: { uv: [u0, 0], uv_size: [256, 416] }, down: side, north: side, south: side, east: side, west: side },
	});
	return {
		format_version: '1.12.0',
		'minecraft:geometry': [
			{
				description: { identifier: `geometry.${NS}.table`, texture_width: 512, texture_height: 512, visible_bounds_width: 4, visible_bounds_height: 2, visible_bounds_offset: [0, 0.5, 0] },
				bones: [
					{ name: 'root', pivot: [0, 0, 0] },
					{
						name: 'tabletop',
						parent: 'root',
						pivot: [0, 0, 0],
						cubes: [{ origin: [-tw / 2, 0, -td / 2], size: [tw, th, td], uv: { up: { uv: [0, 416], uv_size: [512, 96] }, down: { uv: [0, 416], uv_size: [512, 96] }, north: { uv: [0, 416], uv_size: [512, 8] }, south: { uv: [0, 416], uv_size: [512, 8] }, east: { uv: [0, 416], uv_size: [96, 8] }, west: { uv: [0, 416], uv_size: [96, 8] } } }],
					},
					{ name: 'left', parent: 'root', pivot: [0, th + t, 0], cubes: [half(-w / 2, 0)] },
					// the right half turns on the hinge (the seam, at the board's top): 180 = folded over
					{ name: 'right', parent: 'root', pivot: [0, th + t, 0], cubes: [half(0, 256)] },
				],
			},
		],
	};
}

/** A card lying on the table: the face up, 0.264 x 0.36 blocks; the rim a little bigger under it. */
function cardGeometry() {
	const cw = px(0.48 * S), ch = px(0.655 * S), t = 0.16;
	const rw = px(0.53 * S), rh = px(0.705 * S);
	return {
		format_version: '1.12.0',
		'minecraft:geometry': [
			{
				description: { identifier: `geometry.${NS}.card`, texture_width: 16, texture_height: 16, visible_bounds_width: 2, visible_bounds_height: 2, visible_bounds_offset: [0, 0.5, 0] },
				bones: [
					{ name: 'root', pivot: [0, 0, 0] },
					{ name: 'lift', parent: 'root', pivot: [0, 0, 0] },
					{ name: 'rim', parent: 'lift', pivot: [0, 0, 0], cubes: [{ origin: [-rw / 2, 0, -rh / 2], size: [rw, 0.08, rh], uv: { up: { uv: [0, 0], uv_size: [16, 16] }, down: { uv: [0, 0], uv_size: [16, 16] } } }] },
					{ name: 'card', parent: 'lift', pivot: [0, 0, 0], cubes: [{ origin: [-cw / 2, 0.1, -ch / 2], size: [cw, t, ch], uv: { up: { uv: [16, 16], uv_size: [-16, -16] }, down: { uv: [0, 0], uv_size: [16, 16] } } }] },
					{ name: 'ice', parent: 'lift', pivot: [0, 0, 0], cubes: [{ origin: [-rw / 2, 0.28, -rh / 2], size: [rw, 0.5, rh], uv: { up: { uv: [0, 0], uv_size: [16, 16] }, north: { uv: [0, 0], uv_size: [16, 2] }, south: { uv: [0, 0], uv_size: [16, 2] }, east: { uv: [0, 0], uv_size: [16, 2] }, west: { uv: [0, 0], uv_size: [16, 2] } } }] },
				],
			},
		],
	};
}

/** A flat sprite standing up (the character art, an attack drawing): faces the camera. */
function spriteGeometry() {
	return {
		format_version: '1.12.0',
		'minecraft:geometry': [
			{
				description: { identifier: `geometry.${NS}.sprite`, texture_width: 16, texture_height: 16, visible_bounds_width: 2, visible_bounds_height: 2, visible_bounds_offset: [0, 0.5, 0] },
				bones: [
					{ name: 'root', pivot: [0, 0, 0] },
					{ name: 'sprite', parent: 'root', pivot: [0, 0, 0], billboard: true, cubes: [{ origin: [-8, 0, 0], size: [16, 16, 0], uv: { north: { uv: [0, 0], uv_size: [16, 16] }, south: { uv: [16, 0], uv_size: [-16, 16] } } }] },
				],
			},
		],
	};
}

/** A flat tile marker (chess tile, power tile, a glow ring): the size of a slot, just above the wood. */
function markerGeometry() {
	const mw = px(0.5 * S), mh = px(0.68 * S);
	return {
		format_version: '1.12.0',
		'minecraft:geometry': [
			{
				description: { identifier: `geometry.${NS}.marker`, texture_width: 16, texture_height: 16, visible_bounds_width: 1, visible_bounds_height: 1 },
				bones: [{ name: 'root', pivot: [0, 0, 0], cubes: [{ origin: [-mw / 2, 0, -mh / 2], size: [mw, 0.02, mh], uv: { up: { uv: [16, 16], uv_size: [-16, -16] } } }] }],
			},
		],
	};
}

/* ------------------------------------ main ------------------------------------ */

async function main() {
	const deploy = process.argv.includes('--deploy');
	const cards = JSON.parse(await readFile(join(ASSETS, 'cards.json'), 'utf8'));
	const kit = JSON.parse(await readFile(join(ASSETS, 'kit.json'), 'utf8'));
	await rm(join(ROOT, 'packs'), { recursive: true, force: true });

	/* ---------- resource pack ---------- */
	await json(join(RP, 'manifest.json'), {
		format_version: 2,
		header: { name: 'Smash&Clash', description: 'The official Smash&Clash, in Minecraft: the art, the sounds and the table.', uuid: uuid('rp'), version: VERSION, min_engine_version: ENGINE },
		modules: [{ type: 'resources', uuid: uuid('rp-module'), version: VERSION }],
		dependencies: [{ uuid: uuid('bp'), version: VERSION }],
	});
	await cp(join(ASSETS, 'icon.png'), join(RP, 'pack_icon.png'));

	// the art
	const tex = join(RP, 'textures', NS);
	await mkdir(tex, { recursive: true });
	for (const sub of ['card', 'art', 'attack']) await cp(join(ASSETS, 'textures', 'block', sub), join(tex, sub), { recursive: true });
	await cp(join(ASSETS, 'textures', 'block', 'rim.png'), join(tex, 'rim.png'));
	await cp(join(ASSETS, 'textures', 'block', 'glow.png'), join(tex, 'glow.png'));
	await drawTableAtlas();
	// the rim, in each colour: you blue, them orange (seat colours), and frost for a frozen card
	const tint = (hex, a = 255) => [parseInt(hex.slice(1, 3), 16), parseInt(hex.slice(3, 5), 16), parseInt(hex.slice(5, 7), 16), a];
	for (const [name, color] of [['rim_a', '#2173e8'], ['rim_b', '#f4581f'], ['glow_you', '#5fa6ff'], ['glow_pick', '#ff9a62'], ['glow_turn', '#ffc21a']]) {
		const cv = new Canvas(64, 64);
		if (name.startsWith('rim')) cv.roundRect(0, 0, 64, 64, 10, tint(color));
		else {
			cv.roundRect(0, 0, 64, 64, 12, tint(color));
			for (let y = 6; y < 58; y++) for (let x = 6; x < 58; x++) cv.px[(y * 64 + x) * 4 + 3] = 0.2;
		}
		await writeFile(join(tex, `${name}.png`), cv.png());
	}
	{
		const cv = new Canvas(16, 16);
		cv.roundRect(0, 0, 16, 16, 2, [190, 235, 255, 150]);
		cv.roundRect(2, 2, 6, 3, 1, [255, 255, 255, 200]);
		await writeFile(join(tex, 'ice.png'), cv.png());
	}
	for (const piece of ['KNIGHT', 'BISHOP', 'ROOK', 'QUEEN']) await writeFile(join(tex, `op_${piece.toLowerCase()}.png`), drawOperator(piece));
	for (const [color, hex] of Object.entries(kit.colors)) for (const b of [1, 2]) await writeFile(join(tex, `power_${color}_${b}.png`), drawPower(hex, b));

	// item icons: the card face centred on a square (inventory icons are square)
	const icons = join(RP, 'textures', 'items', NS);
	await mkdir(icons, { recursive: true });
	const itemTextures = {};
	for (const c of cards) {
		ffmpeg(['-i', join(tex, 'card', `${c.id}.png`), '-vf', 'scale=-1:128,pad=128:128:(ow-iw)/2:0:color=0x00000000', '-pix_fmt', 'rgba', join(icons, `card_${c.id}.png`)]);
		itemTextures[`${NS}_card_${c.id}`] = { textures: `textures/items/${NS}/card_${c.id}` };
	}
	ffmpeg(['-i', join(ASSETS, 'icon.png'), '-vf', 'scale=64:64', join(icons, 'table.png')]);
	itemTextures[`${NS}_table`] = { textures: `textures/items/${NS}/table` };
	await json(join(RP, 'textures', 'item_texture.json'), { resource_pack_name: NS, texture_name: 'atlas.items', texture_data: itemTextures });
	await json(join(RP, 'textures', 'terrain_texture.json'), {
		resource_pack_name: NS,
		texture_name: 'atlas.terrain',
		padding: 8,
		num_mip_levels: 4,
		texture_data: { [`${NS}_pedestal`]: { textures: 'textures/blocks/log_big_oak' }, [`${NS}_pedestal_top`]: { textures: 'textures/blocks/planks_big_oak' } },
	});

	// models
	await json(join(RP, 'models', 'entity', 'table.geo.json'), tableGeometry());
	await json(join(RP, 'models', 'entity', 'card.geo.json'), cardGeometry());
	await json(join(RP, 'models', 'entity', 'sprite.geo.json'), spriteGeometry());
	await json(join(RP, 'models', 'entity', 'marker.geo.json'), markerGeometry());
	await json(join(RP, 'models', 'blocks', 'pedestal.geo.json'), {
		format_version: '1.12.0',
		'minecraft:geometry': [
			{
				description: { identifier: `geometry.${NS}.pedestal`, texture_width: 16, texture_height: 16 },
				bones: [
					{
						name: 'pedestal',
						pivot: [0, 0, 0],
						cubes: [
							{ origin: [-7, 0, -7], size: [14, 2, 14], uv: { north: { uv: [0, 0], uv_size: [14, 2] }, south: { uv: [0, 0], uv_size: [14, 2] }, east: { uv: [0, 0], uv_size: [14, 2] }, west: { uv: [0, 0], uv_size: [14, 2] }, up: { uv: [1, 1], uv_size: [14, 14] }, down: { uv: [1, 1], uv_size: [14, 14] } } },
							{ origin: [-4, 2, -4], size: [8, 14, 8], uv: { north: { uv: [4, 2], uv_size: [8, 14] }, south: { uv: [4, 2], uv_size: [8, 14] }, east: { uv: [4, 2], uv_size: [8, 14] }, west: { uv: [4, 2], uv_size: [8, 14] } } },
						],
					},
				],
			},
		],
	});

	// the entities as the client draws them
	const cardTextures = { back: `textures/${NS}/card/back` };
	for (const c of cards) cardTextures[`c${c.id}`] = `textures/${NS}/card/${c.id}`;
	const cardIds = cards.map((c) => c.id);
	await json(join(RP, 'entity', 'card.entity.json'), {
		format_version: '1.10.0',
		'minecraft:client_entity': {
			description: {
				identifier: `${NS}:card`,
				materials: { default: 'entity_alphatest', ice: 'entity_alphablend' },
				textures: { ...cardTextures, rim_a: `textures/${NS}/rim_a`, rim_b: `textures/${NS}/rim_b`, ice: `textures/${NS}/ice` },
				geometry: { default: `geometry.${NS}.card` },
				animations: { pose: `animation.${NS}.card.pose` },
				scripts: { animate: ['pose'] },
				render_controllers: [`controller.render.${NS}.card_face`, `controller.render.${NS}.card_rim`, `controller.render.${NS}.card_ice`],
			},
		},
	});
	await json(join(RP, 'animations', 'card.animation.json'), {
		format_version: '1.10.0',
		animations: {
			[`animation.${NS}.card.pose`]: {
				loop: true,
				bones: {
					lift: {
						position: [0, `query.property('${NS}:lift') * 16`, 0],
						scale: `query.property('${NS}:scale')`,
					},
				},
			},
			[`animation.${NS}.table.pose`]: {
				loop: true,
				bones: { right: { rotation: [0, 0, `query.property('${NS}:hinge')`] } },
			},
			[`animation.${NS}.opponent.pose`]: {
				loop: true,
				bones: {
					rightArm: { rotation: [`-50 * query.property('${NS}:holding') - 90 * query.property('${NS}:swing')`, 0, 0] },
					head: { rotation: [30, 0, 0] },
				},
			},
			[`animation.${NS}.sprite.pose`]: {
				loop: true,
				bones: { sprite: { scale: `query.property('${NS}:size')` } },
			},
		},
	});
	const arr = (name, keys) => ({ [name]: keys.map((k) => `Texture.${k}`) });
	await json(join(RP, 'render_controllers', 'smashandclash.render_controllers.json'), {
		format_version: '1.10.0',
		render_controllers: {
			[`controller.render.${NS}.card_face`]: {
				arrays: { textures: arr('Array.faces', ['back', ...cardIds.map((id) => `c${id}`)]) },
				geometry: 'Geometry.default',
				materials: [{ '*': 'Material.default' }],
				textures: [`Array.faces[query.property('${NS}:face')]`],
				part_visibility: [{ '*': false }, { card: true }],
			},
			[`controller.render.${NS}.card_rim`]: {
				arrays: { textures: arr('Array.rims', ['rim_a', 'rim_b']) },
				geometry: 'Geometry.default',
				materials: [{ '*': 'Material.default' }],
				textures: [`Array.rims[query.property('${NS}:rim') - 1]`],
				part_visibility: [{ '*': false }, { rim: `query.property('${NS}:rim') > 0` }],
			},
			[`controller.render.${NS}.card_ice`]: {
				geometry: 'Geometry.default',
				materials: [{ '*': 'Material.ice' }],
				textures: ['Texture.ice'],
				part_visibility: [{ '*': false }, { ice: `query.property('${NS}:frozen')` }],
			},
			[`controller.render.${NS}.table`]: { geometry: 'Geometry.default', materials: [{ '*': 'Material.default' }], textures: ['Texture.default'] },
			[`controller.render.${NS}.sprite`]: {
				arrays: { textures: arr('Array.sprites', ['none', ...cards.flatMap((c) => (c.art ? [`a${c.id}`, `w${c.id}`, `s${c.id}`] : []))]) },
				geometry: 'Geometry.default',
				materials: [{ '*': 'Material.default' }],
				textures: [`Array.sprites[query.property('${NS}:sprite')]`],
			},
			[`controller.render.${NS}.opponent`]: {
				arrays: { textures: arr('Array.skins', ['steve', 'alex']) },
				geometry: 'Geometry.default',
				materials: [{ '*': 'Material.default' }],
				textures: [`Array.skins[query.property('${NS}:skin')]`],
			},
			[`controller.render.${NS}.marker`]: {
				arrays: { textures: arr('Array.markers', MARKERS(kit)) },
				geometry: 'Geometry.default',
				materials: [{ '*': 'Material.default' }],
				textures: [`Array.markers[query.property('${NS}:marker')]`],
			},
		},
	});
	await json(join(RP, 'entity', 'table.entity.json'), {
		format_version: '1.10.0',
		'minecraft:client_entity': {
			description: {
				identifier: `${NS}:table`,
				materials: { default: 'entity_alphatest' },
				textures: { default: `textures/${NS}/table` },
				geometry: { default: `geometry.${NS}.table` },
				animations: { pose: `animation.${NS}.table.pose` },
				scripts: { animate: ['pose'] },
				render_controllers: [`controller.render.${NS}.table`],
			},
		},
	});
	const spriteTextures = { none: `textures/${NS}/glow` };
	for (const c of cards) {
		if (!c.art) continue;
		spriteTextures[`a${c.id}`] = `textures/${NS}/art/${c.id}`;
		spriteTextures[`w${c.id}`] = c.animation ? `textures/${NS}/attack/${c.id}_windup` : `textures/${NS}/art/${c.id}`;
		spriteTextures[`s${c.id}`] = c.animation ? `textures/${NS}/attack/${c.id}_strike` : `textures/${NS}/art/${c.id}`;
	}
	await json(join(RP, 'entity', 'sprite.entity.json'), {
		format_version: '1.10.0',
		'minecraft:client_entity': {
			description: {
				identifier: `${NS}:sprite`,
				materials: { default: 'entity_alphatest' },
				textures: spriteTextures,
				geometry: { default: `geometry.${NS}.sprite` },
				animations: { pose: `animation.${NS}.sprite.pose` },
				scripts: { animate: ['pose'] },
				render_controllers: [`controller.render.${NS}.sprite`],
			},
		},
	});
	const markerTextures = {};
	for (const m of MARKERS(kit)) markerTextures[m] = m === 'none' ? `textures/${NS}/glow` : `textures/${NS}/${m}`;
	await json(join(RP, 'entity', 'marker.entity.json'), {
		format_version: '1.10.0',
		'minecraft:client_entity': {
			description: {
				identifier: `${NS}:marker`,
				materials: { default: 'entity_alphatest' },
				textures: markerTextures,
				geometry: { default: `geometry.${NS}.marker` },
				render_controllers: [`controller.render.${NS}.marker`],
			},
		},
	});
	// the figure across the board: a player model in a default skin, arm up while it holds a card, swinging when it plays
	await json(join(RP, 'entity', 'opponent.entity.json'), {
		format_version: '1.10.0',
		'minecraft:client_entity': {
			description: {
				identifier: `${NS}:opponent`,
				materials: { default: 'entity_alphatest' },
				textures: { steve: 'textures/entity/steve', alex: 'textures/entity/alex' },
				geometry: { default: 'geometry.humanoid.custom' },
				animations: { pose: `animation.${NS}.opponent.pose` },
				scripts: { animate: ['pose'] },
				render_controllers: [`controller.render.${NS}.opponent`],
			},
		},
	});
	// tile hit boxes and floating words: drawn by the game itself (a hit box has no model; a word is a name tag)
	for (const id of ['tile', 'label']) {
		await json(join(RP, 'entity', `${id}.entity.json`), {
			format_version: '1.10.0',
			'minecraft:client_entity': { description: { identifier: `${NS}:${id}`, materials: { default: 'entity_alphatest' }, textures: {}, geometry: {}, render_controllers: [] } },
		});
	}

	// the particle for a glowing tile (one player sees it): flat on the board
	await json(join(RP, 'particles', 'tile_glow.json'), {
		format_version: '1.10.0',
		particle_effect: {
			description: { identifier: `${NS}:tile_glow`, basic_render_parameters: { material: 'particles_blend', texture: `textures/${NS}/glow` } },
			components: {
				'minecraft:emitter_rate_instant': { num_particles: 1 },
				'minecraft:emitter_lifetime_once': { active_time: 0.05 },
				'minecraft:emitter_shape_point': {},
				'minecraft:particle_lifetime_expression': { max_lifetime: 0.3 },
				'minecraft:particle_appearance_billboard': { size: [px(0.26 * S) / 16 * 0.98, px(0.35 * S) / 16 * 0.98], facing_camera_mode: 'emitter_transform_xz', uv: { texture_width: 64, texture_height: 64, uv: [0, 0], uv_size: [64, 64] } },
				'minecraft:particle_appearance_tinting': { color: ['variable.color.r', 'variable.color.g', 'variable.color.b', 'variable.color.a'] },
			},
		},
	});

	// sounds: the same OGGs as the Java mod
	const soundsJson = JSON.parse(await readFile(join(ASSETS, 'sounds.json'), 'utf8'));
	const defs = {};
	for (const [event, def] of Object.entries(soundsJson)) {
		const music = event.startsWith('music.');
		defs[`${NS}.${event}`] = {
			category: music ? 'music' : event.startsWith('voice.') || event.startsWith('announcer.') || event.startsWith('howto.') ? 'player' : 'block',
			...(music ? {} : { min_distance: 2, max_distance: 24 }),
			sounds: def.sounds.map((s) => {
				const name = (typeof s === 'string' ? s : s.name).replace(`${NS}:`, '');
				return { name: `sounds/${NS}/${name}`, ...(music ? { stream: true, load_on_low_memory: true } : {}) };
			}),
		};
	}
	await json(join(RP, 'sounds', 'sound_definitions.json'), { format_version: '1.20.20', sound_definitions: defs });
	await cp(join(ASSETS, 'sounds'), join(RP, 'sounds', NS), { recursive: true });

	// names
	const lang = [`pack.name=Smash&Clash`, `pack.description=The official Smash&Clash, in Minecraft`, `tile.${NS}:table.name=Smash&Clash Table`, `item.${NS}:table=Smash&Clash Table`];
	for (const c of cards) lang.push(`item.${NS}:card_${c.id}=${c.name}`);
	lang.push(`entity.${NS}:card.name=Smash&Clash card`, `entity.${NS}:table.name=Smash&Clash board`, `action.interact.${NS}.play=Play here`);
	await mkdir(join(RP, 'texts'), { recursive: true });
	await writeFile(join(RP, 'texts', 'en_US.lang'), lang.join('\n') + '\n');
	await json(join(RP, 'texts', 'languages.json'), ['en_US']);

	/* ---------- behavior pack ---------- */
	await json(join(BP, 'manifest.json'), {
		format_version: 2,
		header: { name: 'Smash&Clash', description: 'The official Smash&Clash, in Minecraft: place a table, play anyone on any client.', uuid: uuid('bp'), version: VERSION, min_engine_version: ENGINE },
		modules: [
			{ type: 'data', uuid: uuid('bp-data'), version: VERSION },
			{ type: 'script', language: 'javascript', uuid: uuid('bp-script'), version: VERSION, entry: 'scripts/main.js' },
		],
		dependencies: [
			{ uuid: uuid('rp'), version: VERSION },
			{ module_name: '@minecraft/server', version: '2.4.0' },
			{ module_name: '@minecraft/server-ui', version: '2.0.0' },
			{ module_name: '@minecraft/server-net', version: '1.0.0-beta' },
		],
	});
	await cp(join(ASSETS, 'icon.png'), join(BP, 'pack_icon.png'));
	await json(join(BP, 'blocks', 'table.json'), {
		format_version: '1.21.90',
		'minecraft:block': {
			description: { identifier: `${NS}:table`, menu_category: { category: 'items' } },
			components: {
				// the script's custom component: using the table opens its menu (a block must say it can be used)
				[`${NS}:table`]: {},
				'minecraft:geometry': `geometry.${NS}.pedestal`,
				'minecraft:material_instances': { '*': { texture: `${NS}_pedestal`, render_method: 'opaque' } },
				'minecraft:collision_box': { origin: [-7, 0, -7], size: [14, 16, 14] },
				'minecraft:selection_box': { origin: [-7, 0, -7], size: [14, 16, 14] },
				'minecraft:destructible_by_mining': { seconds_to_destroy: 1 },
				'minecraft:map_color': '#4a2f18',
				'minecraft:light_dampening': 0,
			},
		},
	});
	await json(join(BP, 'recipes', 'table.json'), {
		format_version: '1.21.40',
		'minecraft:recipe_shaped': {
			description: { identifier: `${NS}:table` },
			tags: ['crafting_table'],
			pattern: ['PPP', 'BBB', ' L '],
			key: { P: { item: 'minecraft:paper' }, B: { item: 'minecraft:birch_slab' }, L: { item: 'minecraft:birch_log' } },
			unlock: [{ item: 'minecraft:paper' }],
			result: { item: `${NS}:table` },
		},
	});
	for (const c of cards) {
		await json(join(BP, 'items', `card_${c.id}.json`), {
			format_version: '1.21.40',
			'minecraft:item': {
				description: { identifier: `${NS}:card_${c.id}`, menu_category: { category: 'none' } },
				components: {
					'minecraft:icon': `${NS}_card_${c.id}`,
					'minecraft:max_stack_size': 1,
					'minecraft:display_name': { value: c.name },
					'minecraft:hand_equipped': false,
				},
			},
		});
	}
	// the entities: properties drive the art (face, rim, frost, lift, scale) on every client
	const entity = (id, props, extra = {}) => ({
		format_version: '1.21.40',
		'minecraft:entity': {
			description: { identifier: `${NS}:${id}`, is_spawnable: false, is_summonable: true, ...(Object.keys(props).length ? { properties: props } : {}) },
			components: {
				'minecraft:physics': { has_gravity: false, has_collision: false },
				'minecraft:pushable': { is_pushable: false, is_pushable_by_piston: false },
				'minecraft:damage_sensor': { triggers: [{ cause: 'all', deals_damage: 'no' }] },
				'minecraft:collision_box': { width: 0.01, height: 0.01 },
				'minecraft:type_family': { family: ['smashandclash', 'inanimate'] },
				'minecraft:persistent': {},
				'minecraft:knockback_resistance': { value: 1 },
				...extra,
			},
		},
	});
	const f = (min, max, d) => ({ type: 'float', range: [F(min), F(max)], default: F(d), client_sync: true });
	const i = (min, max, d) => ({ type: 'int', range: [min, max], default: d, client_sync: true });
	await json(join(BP, 'entities', 'card.json'), entity('card', { [`${NS}:face`]: i(0, 255, 0), [`${NS}:rim`]: i(0, 2, 0), [`${NS}:frozen`]: { type: 'bool', default: false, client_sync: true }, [`${NS}:lift`]: f(-2, 4, 0), [`${NS}:scale`]: f(0, 4, 1) }));
	await json(join(BP, 'entities', 'table.json'), entity('table', { [`${NS}:hinge`]: f(-360, 360, 0) }));
	await json(join(BP, 'entities', 'sprite.json'), entity('sprite', { [`${NS}:sprite`]: i(0, 255, 0), [`${NS}:size`]: f(0, 4, 1) }));
	await json(join(BP, 'entities', 'marker.json'), entity('marker', { [`${NS}:marker`]: i(0, 63, 0) }));
	await json(join(BP, 'entities', 'opponent.json'), entity('opponent', { [`${NS}:skin`]: i(0, 1, 0), [`${NS}:holding`]: { type: 'bool', default: false, client_sync: true }, [`${NS}:swing`]: f(0, 1, 0) }, { 'minecraft:nameable': { always_show: true, allow_name_tag_renaming: false }, 'minecraft:collision_box': { width: 0.6, height: 1.8 } }));
	// a tile hit box: something you can use (an entity must say it can be used, or a tap does nothing)
	await json(join(BP, 'entities', 'tile.json'), entity('tile', {}, {
		'minecraft:collision_box': { width: 0.28, height: 0.14 },
		'minecraft:interact': { interactions: [{ interact_text: 'action.interact.smashandclash.play', swing: true, on_interact: { filters: { test: 'is_family', subject: 'other', value: 'player' } } }] },
	}));
	await json(join(BP, 'entities', 'label.json'), entity('label', {}, { 'minecraft:nameable': { always_show: true, allow_name_tag_renaming: false } }));

	// the script: the game, on @smashandclash/sdk
	await build({
		entryPoints: [join(ROOT, 'src', 'main.ts')],
		outfile: join(BP, 'scripts', 'main.js'),
		bundle: true,
		format: 'esm',
		target: 'es2022',
		platform: 'neutral',
		external: ['@minecraft/server', '@minecraft/server-ui', '@minecraft/server-net', '@minecraft/server-admin'],
		define: { CARDS_JSON: JSON.stringify(JSON.stringify(cards)), MARKERS_JSON: JSON.stringify(JSON.stringify(MARKERS(kit))) },
		legalComments: 'none',
	});

	/* ---------- the .mcaddon (both packs, one file) ---------- */
	await mkdir(join(ROOT, 'dist'), { recursive: true });
	const addon = join(ROOT, 'dist', 'smashandclash.mcaddon');
	await rm(addon, { force: true });
	// an .mcaddon is a zip (Windows' own bsdtar writes zips; Git's GNU tar would read "C:" as a host)
	if (process.platform === 'win32') execFileSync('C:/Windows/System32/tar.exe', ['--format', 'zip', '-c', '-f', addon, '-C', join(ROOT, 'packs'), 'RP', 'BP']);
	else execFileSync('zip', ['-qr', addon, 'RP', 'BP'], { cwd: join(ROOT, 'packs') });
	const size = (await stat(addon)).size;
	console.log(`Built packs/RP, packs/BP and dist/smashandclash.mcaddon (${(size / 1e6).toFixed(1)} MB)`);

	if (deploy) await deployToServer();
}

/** Marker textures, by index: none, the four pieces, then every colour's +1 and +2 power tile. */
function MARKERS(kit) {
	return ['none', 'op_knight', 'op_bishop', 'op_rook', 'op_queen', ...Object.keys(kit.colors).flatMap((c) => [`power_${c}_1`, `power_${c}_2`])];
}

/** Copy the packs into the local test server and turn them on for its world (with Beta APIs, which server-net needs). */
async function deployToServer() {
	const server = join(ROOT, '..', 'bds', 'server');
	if (!existsSync(server)) throw new Error('no ../bds/server');
	for (const [src, dir] of [[RP, 'resource_packs'], [BP, 'behavior_packs']]) {
		const dest = join(server, dir, 'smashandclash');
		await rm(dest, { recursive: true, force: true });
		await cp(src, dest, { recursive: true });
	}
	const world = join(server, 'worlds', 'Smash and Clash');
	await mkdir(world, { recursive: true });
	await json(join(world, 'world_behavior_packs.json'), [{ pack_id: uuid('bp'), version: VERSION }]);
	await json(join(world, 'world_resource_packs.json'), [{ pack_id: uuid('rp'), version: VERSION }]);
	console.log(`Deployed to ${relative(ROOT, server)} (world "Smash and Clash")`);
}

main().catch((e) => {
	console.error(e);
	process.exit(1);
});
