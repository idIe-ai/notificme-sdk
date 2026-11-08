// Checks the webhook signature verification against the shared protocol
// test vector (protocol/test-vectors/webhook-signature.json), the same one the
// server and the other SDKs use. Run after the build: npm test.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { test } from 'node:test';

const require = createRequire(import.meta.url);
const { verifySignature, jwksKeys, parseSignatureHeader } = require('../dist/nodes/NotificMe/signature.js');
const { replyToOutput } = require('../dist/nodes/NotificMe/GenericFunctions.js');

const vector = JSON.parse(
	readFileSync(new URL('../../protocol/test-vectors/webhook-signature.json', import.meta.url), 'utf8'),
);
const body = Buffer.from(vector.body, 'utf8');
const keys = jwksKeys(vector.jwks);
const now = vector.timestamp * 1000;

test('accepts the signed vector', () => {
	const r = verifySignature(body, vector.header, keys, { now });
	assert.equal(r.timestamp, vector.timestamp);
	assert.equal(r.keyId, vector.key_id);
});

test('rejects a body changed by one byte', () => {
	const changed = Buffer.from(vector.body.replace('Approve', 'Approvf'), 'utf8');
	assert.throws(() => verifySignature(changed, vector.header, keys, { now }), { reason: 'invalid_signature' });
});

test('rejects an old timestamp', () => {
	assert.throws(() => verifySignature(body, vector.header, keys, { now: now + 301_000 }), {
		reason: 'timestamp_out_of_tolerance',
	});
});

test('rejects an unknown key id', () => {
	const header = vector.header.replace(`kid=${vector.key_id}`, 'kid=other');
	assert.throws(() => verifySignature(body, header, keys, { now }), { reason: 'unknown_key' });
});

test('rejects a missing or malformed header', () => {
	assert.throws(() => verifySignature(body, undefined, keys, { now }), { reason: 'missing_header' });
	assert.throws(() => verifySignature(body, 'sig=abc', keys, { now }), { reason: 'malformed_header' });
	assert.equal(parseSignatureHeader('t=1,sig=x,extra=1').sig, 'x');
});

test('turns the reply into answers by question id', () => {
	const out = replyToOutput(JSON.parse(vector.body));
	assert.equal(out.answered, true);
	assert.deepEqual(out.answers, { action: 'Approve', note: 'Only after 10 pm ✓' });
	assert.deepEqual(out.free_text, { note: 'Only after 10 pm ✓' });
	assert.equal(out.approved, undefined);
	const approval = replyToOutput({
		status: 'timeout',
		question_set_id: 'x',
		answers: [{ question_id: 'approved', value: false }],
	});
	assert.equal(approval.answered, false);
	assert.equal(approval.approved, false);
});
