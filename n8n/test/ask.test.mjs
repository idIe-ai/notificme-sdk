// Checks the body of POST /v0/asks built from the node fields (groups,
// decision rules, escalation) and the output of a reply closed by a rule.
// Run after the build: npm test.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { test } from 'node:test';

const require = createRequire(import.meta.url);
const { buildAskBody, replyToOutput } = require('../dist/nodes/NotificMe/GenericFunctions.js');

/** A minimal IExecuteFunctions: node parameters by name, dotted paths included. */
function ctx(params) {
	return {
		getNodeParameter(name, _i, fallback) {
			const value = name.split('.').reduce((o, k) => (o == null ? undefined : o[k]), params);
			return value === undefined ? fallback : value;
		},
		getNode: () => ({ name: 'notific.me', type: 'notificMe', typeVersion: 1 }),
	};
}

const approval = (options, extra = {}) =>
	ctx({
		askInput: 'fields',
		title: 'Pay invoice 8812?',
		responseType: 'approval',
		options,
		...extra,
	});

test('sends #group>=level and the decision rule', () => {
	const body = buildAskBody(
		approval(
			{
				decisionRule: 'each_group',
				requestedBy: '@carla',
				escalate: '#ceo',
				escalateAfter: '2h',
				timeout: '8h',
			},
			{ to: '#finance>=manager, #board' },
		),
		0,
	);
	assert.deepEqual(body.to, ['#finance>=manager', '#board']);
	assert.deepEqual(body.decision, { rule: 'each_group', requested_by: '@carla' });
	assert.deepEqual(body.escalate, [{ after: '2h', to: ['#ceo'] }]);
	assert.equal(body.timeout, '8h');
	assert.deepEqual(body.questions, [{ id: 'approved', type: 'yesno', text: 'Pay invoice 8812?' }]);
});

test('quorum sends who decides and the required approvals', () => {
	const body = buildAskBody(
		approval(
			{ decidedBy: '#board>=director', decisionRule: 'quorum', requiredApprovals: 2, veto: true },
			{ to: '#legal, #finance' },
		),
		0,
	);
	assert.deepEqual(body.decision, {
		by: ['#board>=director'],
		rule: 'quorum',
		required: 2,
		veto: true,
	});
});

test('required approvals only go with quorum', () => {
	const body = buildAskBody(approval({ decisionRule: 'majority', requiredApprovals: 3 }), 0);
	assert.deepEqual(body.decision, { rule: 'majority' });
});

test('a choice vote sends the approving choices', () => {
	const body = buildAskBody(
		ctx({
			askInput: 'fields',
			title: 'Refund order #881?',
			responseType: 'choice',
			choices: 'Refund\nPartial refund\nDeny',
			options: { decisionRule: 'unanimous', approveOptions: 'Refund\nPartial refund' },
		}),
		0,
	);
	assert.deepEqual(body.decision, {
		rule: 'unanimous',
		approve_options: ['Refund', 'Partial refund'],
	});
});

test('without decision options the ask is unchanged', () => {
	const body = buildAskBody(
		approval({ decisionRule: 'any', escalate: '@backup, partner@example.com' }),
		0,
	);
	assert.equal(body.decision, undefined);
	// Without Escalate After: the plain list, escalated at half of the timeout.
	assert.deepEqual(body.escalate, ['@backup', 'partner@example.com']);
	assert.equal(buildAskBody(approval({}), 0).escalate, undefined);
});

test('the rule outcome decides approved, with the votes', () => {
	const votes = [
		{
			identity: 'ana@example.com',
			handle: 'ana',
			approve: true,
			answered_at: '2026-11-09T14:32:00Z',
			level_used: 2,
			group: '#board',
			authority_level: 'director',
		},
		{
			identity: 'bruno@example.com',
			handle: 'bruno',
			approve: false,
			answered_at: '2026-11-09T14:40:00Z',
			level_used: 2,
			group: '#board',
			authority_level: 'director',
		},
	];
	const decision = {
		rule: 'unanimous',
		eligible: 2,
		approvals: 1,
		rejections: 1,
		outcome: 'rejected',
	};
	const out = replyToOutput({
		status: 'rejected',
		question_set_id: 'x',
		answers: [{ question_id: 'approved', value: false }],
		decision,
		votes,
	});
	assert.equal(out.answered, false);
	assert.equal(out.status, 'rejected');
	assert.equal(out.approved, false);
	assert.deepEqual(out.decision, decision);
	assert.deepEqual(out.votes, votes);

	// Approved by the rule even when the closing reply is a single choice.
	const ok = replyToOutput({
		status: 'answered',
		question_set_id: 'y',
		answers: [{ question_id: 'choice', value: 'Refund' }],
		decision: {
			rule: 'quorum',
			required: 2,
			eligible: 3,
			approvals: 2,
			rejections: 0,
			outcome: 'approved',
		},
	});
	assert.equal(ok.answered, true);
	assert.equal(ok.approved, true);
	assert.equal(ok.votes, undefined);
});
