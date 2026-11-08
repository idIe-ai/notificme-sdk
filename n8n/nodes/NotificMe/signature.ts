/**
 * Verification of the webhooks notific.me sends (the signed reply of an ask).
 *
 *   Notificme-Signature: t=<unix>,kid=<key id>,sig=<base64url(Ed25519(t + "." + body))>
 *
 * The public keys are published as a JWKS at <API URL>/.well-known/notificme-keys.json.
 * Same algorithm as verifyWebhook() in @notificme/sdk, with node:crypto only
 * (verified n8n community nodes cannot have runtime dependencies).
 */

import { createPublicKey, verify } from 'node:crypto';

export const SIGNATURE_HEADER = 'notificme-signature';
export const JWKS_PATH = '/.well-known/notificme-keys.json';
export const DEFAULT_TOLERANCE_SECONDS = 300;

export interface Ed25519Jwk {
	kty: string;
	crv: string;
	x: string;
	kid?: string;
}

export interface ParsedSignature {
	t: string;
	kid?: string;
	sig: string;
}

export class SignatureError extends Error {
	constructor(
		readonly reason:
			| 'missing_header'
			| 'malformed_header'
			| 'timestamp_out_of_tolerance'
			| 'unknown_key'
			| 'invalid_signature',
		message: string,
	) {
		super(message);
		this.name = 'SignatureError';
	}
}

/** Parses `t=…,kid=…,sig=…` (unknown parts are ignored). */
export function parseSignatureHeader(header: string): ParsedSignature | undefined {
	let t: string | undefined;
	let kid: string | undefined;
	let sig: string | undefined;
	for (const part of header.split(',')) {
		const p = part.trim();
		const i = p.indexOf('=');
		if (i < 0) continue;
		const k = p.slice(0, i);
		const v = p.slice(i + 1);
		if (k === 't') t = v;
		else if (k === 'kid') kid = v;
		else if (k === 'sig') sig = v;
	}
	if (!t || !sig) return undefined;
	return { t, kid, sig };
}

/** Ed25519 keys of a JWKS document (other key types are ignored). */
export function jwksKeys(doc: unknown): Ed25519Jwk[] {
	const keys = (doc as { keys?: unknown })?.keys;
	if (!Array.isArray(keys)) return [];
	return keys.filter(
		(k): k is Ed25519Jwk =>
			typeof k === 'object' &&
			k !== null &&
			(k as Ed25519Jwk).kty === 'OKP' &&
			(k as Ed25519Jwk).crv === 'Ed25519' &&
			typeof (k as Ed25519Jwk).x === 'string',
	);
}

function pickKey(keys: Ed25519Jwk[], kid: string | undefined): Ed25519Jwk | undefined {
	if (!kid) return keys[0];
	const exact = keys.find((k) => k.kid === kid);
	if (exact) return exact;
	// A key published without kid matches any header.
	return keys.length === 1 && !keys[0].kid ? keys[0] : undefined;
}

/**
 * Checks the signature header against the exact raw body. Throws
 * SignatureError when anything does not match.
 */
export function verifySignature(
	rawBody: Buffer,
	header: string | undefined,
	keys: Ed25519Jwk[],
	options: { toleranceSeconds?: number; now?: number } = {},
): { timestamp: number; keyId?: string } {
	if (!header) throw new SignatureError('missing_header', 'Missing Notificme-Signature header');
	const parsed = parseSignatureHeader(header);
	if (!parsed || !/^-?\d+$/.test(parsed.t)) {
		throw new SignatureError('malformed_header', 'Malformed Notificme-Signature header');
	}
	const timestamp = Number(parsed.t);
	const tolerance = options.toleranceSeconds ?? DEFAULT_TOLERANCE_SECONDS;
	if (tolerance > 0) {
		const skew = (options.now ?? Date.now()) / 1000 - timestamp;
		if (skew > tolerance || skew < -tolerance) {
			throw new SignatureError(
				'timestamp_out_of_tolerance',
				'Signature timestamp is outside the tolerance window',
			);
		}
	}
	const jwk = pickKey(keys, parsed.kid);
	if (!jwk) {
		throw new SignatureError('unknown_key', `No public key with kid ${JSON.stringify(parsed.kid)}`);
	}
	const sig = Buffer.from(parsed.sig, 'base64url');
	if (sig.length !== 64) {
		throw new SignatureError('invalid_signature', 'Signature is not an Ed25519 signature');
	}
	let ok = false;
	try {
		const key = createPublicKey({
			key: { kty: 'OKP', crv: 'Ed25519', x: jwk.x },
			format: 'jwk',
		});
		ok = verify(null, Buffer.concat([Buffer.from(parsed.t + '.'), rawBody]), key, sig);
	} catch {
		ok = false;
	}
	if (!ok) throw new SignatureError('invalid_signature', 'Signature does not match the body');
	return { timestamp, keyId: parsed.kid };
}
