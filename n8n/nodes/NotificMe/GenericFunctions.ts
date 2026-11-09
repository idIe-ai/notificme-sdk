import type {
	IDataObject,
	IExecuteFunctions,
	IHttpRequestMethods,
	IHttpRequestOptions,
	IWebhookFunctions,
	JsonObject,
} from 'n8n-workflow';
import { NodeApiError, NodeOperationError } from 'n8n-workflow';
import { randomUUID } from 'node:crypto';

export const CREDENTIAL = 'notificMeApi';

/** Fixed question IDs of the simple response types. */
export const QUESTION_IDS = { approval: 'approved', choice: 'choice', freeText: 'text' } as const;

type Ctx = IExecuteFunctions | IWebhookFunctions;

export async function baseUrl(ctx: Ctx): Promise<string> {
	const credentials = await ctx.getCredentials(CREDENTIAL);
	return String(credentials.baseUrl ?? 'https://api.notific.me').replace(/\/+$/, '');
}

export async function apiRequest(
	ctx: Ctx,
	method: IHttpRequestMethods,
	path: string,
	body?: IDataObject,
	qs?: IDataObject,
	headers?: Record<string, string>,
	itemIndex?: number,
): Promise<IDataObject> {
	const options: IHttpRequestOptions = {
		method,
		url: (await baseUrl(ctx)) + path,
		json: true,
		headers: { Accept: 'application/json', ...headers },
	};
	if (body) options.body = body;
	if (qs) options.qs = qs;
	try {
		return (await ctx.helpers.httpRequestWithAuthentication.call(
			ctx,
			CREDENTIAL,
			options,
		)) as IDataObject;
	} catch (error) {
		const apiError = (error as { response?: { data?: { error?: IDataObject } } }).response?.data
			?.error;
		const details = apiError?.details;
		throw new NodeApiError(ctx.getNode(), error as JsonObject, {
			itemIndex,
			message: typeof apiError?.message === 'string' ? apiError.message : undefined,
			description: details ? JSON.stringify(details) : undefined,
		});
	}
}

/** "a, b\nc" → ["a", "b", "c"]. */
export function splitList(value: unknown): string[] {
	if (Array.isArray(value))
		return value
			.map(String)
			.map((s) => s.trim())
			.filter(Boolean);
	if (typeof value !== 'string') return [];
	return value
		.split(/[,\n]/)
		.map((s) => s.trim())
		.filter(Boolean);
}

/** One option per line. */
export function splitLines(value: unknown): string[] {
	if (Array.isArray(value))
		return value
			.map(String)
			.map((s) => s.trim())
			.filter(Boolean);
	if (typeof value !== 'string') return [];
	return value
		.split('\n')
		.map((s) => s.trim())
		.filter(Boolean);
}

export function parseJson(
	ctx: IExecuteFunctions,
	value: unknown,
	field: string,
	i: number,
): IDataObject {
	if (value === undefined || value === null || value === '') return {};
	if (typeof value === 'object') return value as IDataObject;
	try {
		const parsed = JSON.parse(String(value)) as unknown;
		if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) throw new Error();
		return parsed as IDataObject;
	} catch {
		throw new NodeOperationError(ctx.getNode(), `${field} must be a JSON object`, { itemIndex: i });
	}
}

function compact(obj: IDataObject): IDataObject {
	for (const k of Object.keys(obj)) {
		const v = obj[k];
		if (v === undefined || v === '' || (Array.isArray(v) && v.length === 0)) delete obj[k];
	}
	return obj;
}

/** Builds the body of POST /v0/asks from the node parameters. */
export function buildAskBody(ctx: IExecuteFunctions, i: number): IDataObject {
	const input = ctx.getNodeParameter('askInput', i) as string;
	if (input === 'json') {
		const body = parseJson(ctx, ctx.getNodeParameter('askJson', i), 'Ask (JSON)', i);
		return { version: '0', ...body };
	}

	const title = ctx.getNodeParameter('title', i) as string;
	const responseType = ctx.getNodeParameter('responseType', i) as string;
	const questions: IDataObject[] = [];

	if (responseType === 'custom') {
		const raw = ctx.getNodeParameter('questions.question', i, []) as IDataObject[];
		for (const q of raw) {
			const type = String(q.type);
			const question: IDataObject = { id: String(q.id).trim(), type, text: q.text };
			if (type === 'single' || type === 'multi') question.options = splitLines(q.options);
			if (q.allowOther) question.allow_other = true;
			if (q.optional) question.optional = true;
			questions.push(question);
		}
		if (questions.length === 0) {
			throw new NodeOperationError(ctx.getNode(), 'Add at least one question', { itemIndex: i });
		}
	} else {
		const text = (ctx.getNodeParameter('question', i, '') as string) || title;
		if (responseType === 'approval') {
			questions.push({ id: QUESTION_IDS.approval, type: 'yesno', text });
		} else if (responseType === 'freeText') {
			questions.push({ id: QUESTION_IDS.freeText, type: 'text', text });
		} else {
			const multiple = ctx.getNodeParameter('multipleChoices', i, false) as boolean;
			const question: IDataObject = {
				id: QUESTION_IDS.choice,
				type: multiple ? 'multi' : 'single',
				text,
				options: splitLines(ctx.getNodeParameter('choices', i)),
			};
			if (ctx.getNodeParameter('allowOther', i, false)) question.allow_other = true;
			questions.push(question);
		}
	}

	const options = ctx.getNodeParameter('options', i, {}) as IDataObject;
	const onTimeout =
		options.onTimeout !== undefined
			? parseJson(ctx, options.onTimeout, 'On Timeout (JSON)', i)
			: undefined;

	return compact({
		version: '0',
		to: splitList(ctx.getNodeParameter('to', i, '')),
		title,
		context: ctx.getNodeParameter('context', i, '') as string,
		questions,
		level: options.level as number | undefined,
		severity: options.severity as string | undefined,
		offline: options.offline as string | undefined,
		timeout: options.timeout as string | undefined,
		on_timeout: onTimeout && Object.keys(onTimeout).length > 0 ? onTimeout : undefined,
		decision: buildDecision(options),
		escalate: buildEscalation(options),
		callback_url: options.callbackUrl as string | undefined,
		require_signature: options.requireSignature === true ? true : undefined,
	});
}

/**
 * The `decision` block (who may decide and which rule closes the ask), or
 * undefined when none of its options is set: the first answer closes it.
 */
export function buildDecision(options: IDataObject): IDataObject | undefined {
	const rule = String(options.decisionRule ?? '') || 'any';
	const decision = compact({
		by: splitList(options.decidedBy),
		rule: rule === 'any' ? undefined : rule,
		required: rule === 'quorum' ? Number(options.requiredApprovals ?? 2) : undefined,
		veto: options.veto === true ? true : undefined,
		requested_by: String(options.requestedBy ?? '').trim(),
		approve_options: splitLines(options.approveOptions),
	});
	return Object.keys(decision).length > 0 ? decision : undefined;
}

/**
 * Escalation: with Escalate After, one step `{ after, to }`; without it, the
 * plain list (the server escalates at half of the timeout).
 */
export function buildEscalation(options: IDataObject): IDataObject[] | string[] | undefined {
	const to = splitList(options.escalate);
	if (to.length === 0) return undefined;
	const after = String(options.escalateAfter ?? '').trim();
	return after ? [{ after, to }] : to;
}

/** Builds the body of POST /v0/events from the node parameters. */
export function buildEventBody(ctx: IExecuteFunctions, i: number): IDataObject {
	const extra = ctx.getNodeParameter('additionalFields', i, {}) as IDataObject;
	const content: IDataObject = { summary: ctx.getNodeParameter('summary', i) as string };
	if (extra.card !== undefined) {
		const card = parseJson(ctx, extra.card, 'Card (JSON)', i);
		if (Object.keys(card).length > 0) content.card = card;
	}
	const payload =
		extra.payload !== undefined ? parseJson(ctx, extra.payload, 'Payload (JSON)', i) : undefined;

	return compact({
		version: '0',
		id: (extra.id as string) || randomId(),
		source: (extra.source as string) || 'n8n',
		type: ctx.getNodeParameter('type', i) as string,
		severity: ctx.getNodeParameter('severity', i) as string,
		occurred_at: new Date().toISOString(),
		to: splitList(ctx.getNodeParameter('to', i, '')),
		payload: payload && Object.keys(payload).length > 0 ? payload : undefined,
		dedup_key: extra.dedupKey as string | undefined,
		ttl: extra.ttl as string | undefined,
		expires_when: extra.expiresWhen as string | undefined,
		card_id: extra.cardId as string | undefined,
		retention: extra.retention as string | undefined,
		content,
	});
}

export function randomId(): string {
	return randomUUID();
}

/**
 * Turns a reply (webhook body or the `reply` of GET /v0/asks/{id}) into the
 * node output: answers by question ID plus the raw signed reply.
 */
export function replyToOutput(reply: IDataObject): IDataObject {
	const answers: IDataObject = {};
	const freeText: IDataObject = {};
	for (const a of (reply.answers as IDataObject[] | undefined) ?? []) {
		const id = String(a.question_id);
		const hasValue = a.value !== undefined && a.value !== null;
		answers[id] = hasValue ? a.value : (a.free_text ?? null);
		if (a.free_text !== undefined && a.free_text !== null) freeText[id] = a.free_text;
	}
	const out: IDataObject = {
		answered: reply.status === 'answered',
		status: reply.status,
		question_set_id: reply.question_set_id,
		answers,
	};
	if (QUESTION_IDS.approval in answers) out.approved = answers[QUESTION_IDS.approval] === true;
	// With a decision rule, the outcome of the rule decides, not the last vote.
	const decision = reply.decision as IDataObject | undefined;
	if (decision && typeof decision.outcome === 'string') {
		out.approved = decision.outcome === 'approved';
	}
	if (Object.keys(freeText).length > 0) out.free_text = freeText;
	out.as_text = reply.as_text;
	out.answered_by = reply.answered_by;
	out.answered_at = reply.answered_at;
	if (decision) out.decision = decision;
	if (Array.isArray(reply.votes)) out.votes = reply.votes;
	// Electronic signature (Require Signature): name, method, PNG images and hashes.
	if (reply.e_signature) out.e_signature = reply.e_signature;
	out.reply = reply;
	return out;
}
