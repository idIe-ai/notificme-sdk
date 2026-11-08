import type {
	IDataObject,
	IExecuteFunctions,
	INodeExecutionData,
	INodeType,
	INodeTypeDescription,
	IWebhookDescription,
	IWebhookFunctions,
	IWebhookResponseData,
	JsonObject,
} from 'n8n-workflow';
import {
	NodeApiError,
	NodeConnectionTypes,
	NodeOperationError,
	WAIT_INDEFINITELY,
} from 'n8n-workflow';

import { askFields, askOperations } from './descriptions/AskDescription';
import { eventFields, eventOperations } from './descriptions/EventDescription';
import {
	apiRequest,
	baseUrl,
	buildAskBody,
	buildEventBody,
	randomId,
	replyToOutput,
} from './GenericFunctions';
import {
	jwksKeys,
	JWKS_PATH,
	SIGNATURE_HEADER,
	SignatureError,
	verifySignature,
} from './signature';

/**
 * Webhook that resumes an execution waiting on this node (Send and Wait for
 * Response). It is not a trigger: n8n only listens on it while an execution is
 * waiting, so there is nothing to register on notific.me (the URL goes in the
 * ask's callback_url).
 */
const resumeWebhooks: IWebhookDescription[] = [
	{
		name: 'default',
		httpMethod: 'POST',
		responseMode: 'onReceived',
		responseData: '',
		path: '={{ $nodeId }}',
		restartWebhook: true,
		isFullPath: true,
	},
];

const UNIT_MS: Record<string, number> = { minutes: 60_000, hours: 3_600_000, days: 86_400_000 };

/** Extra wait after the ask timeout, for the timeout reply to arrive. */
const TIMEOUT_MARGIN_MS = 10 * 60_000;

/** URL that resumes this execution at this node (signed by n8n when supported). */
function resumeUrl(ctx: IExecuteFunctions): string {
	try {
		if (typeof ctx.getSignedResumeUrl === 'function') return ctx.getSignedResumeUrl();
	} catch {
		// Older engines: fall back to the unsigned URL below.
	}
	const base = ctx.evaluateExpression('{{ $execution?.resumeUrl }}', 0) as string;
	const nodeId = ctx.evaluateExpression('{{ $nodeId }}', 0) as string;
	return `${base}/${nodeId}`;
}

function waitTill(ctx: IExecuteFunctions, ask: IDataObject): Date {
	const limit = ctx.getNodeParameter('limitWaitTime.values', 0, {}) as IDataObject;
	if (limit.amount) {
		return new Date(
			Date.now() + Number(limit.amount) * (UNIT_MS[String(limit.unit)] ?? UNIT_MS.hours),
		);
	}
	if (typeof ask.timeout_at === 'string') {
		const at = Date.parse(ask.timeout_at);
		if (!Number.isNaN(at)) return new Date(at + TIMEOUT_MARGIN_MS);
	}
	return WAIT_INDEFINITELY;
}

function header(ctx: IWebhookFunctions, name: string): string | undefined {
	const v = ctx.getHeaderData()[name];
	return Array.isArray(v) ? v[0] : (v as string | undefined);
}

export class NotificMe implements INodeType {
	description: INodeTypeDescription = {
		displayName: 'notific.me',
		name: 'notificMe',
		icon: { light: 'file:notificme.svg', dark: 'file:notificme.dark.svg' },
		group: ['output'],
		version: 1,
		subtitle: '={{$parameter["operation"] + ": " + $parameter["resource"]}}',
		description:
			'Ask a human for a decision on their phone and get the signed answer back, or send events',
		defaults: { name: 'notific.me' },
		usableAsTool: true,
		inputs: [NodeConnectionTypes.Main],
		outputs: [NodeConnectionTypes.Main],
		credentials: [{ name: 'notificMeApi', required: true }],
		webhooks: resumeWebhooks,
		properties: [
			{
				displayName: 'Resource',
				name: 'resource',
				type: 'options',
				noDataExpression: true,
				options: [
					{ name: 'Ask', value: 'ask', description: 'A decision a human makes on their phone' },
					{
						name: 'Event',
						value: 'event',
						description: 'Something that happened, to notify people',
					},
				],
				default: 'ask',
			},
			...askOperations,
			...askFields,
			...eventOperations,
			...eventFields,
			{
				displayName:
					'n8n must be reachable from the internet over HTTPS: the answer comes back to this workflow on an n8n webhook. Only the first input item is used.',
				name: 'sendAndWaitNotice',
				type: 'notice',
				default: '',
				displayOptions: { show: { resource: ['ask'], operation: ['sendAndWait'] } },
			},
		],
	};

	/** Receives the signed reply and resumes the execution waiting on this node. */
	async webhook(this: IWebhookFunctions): Promise<IWebhookResponseData> {
		const req = this.getRequestObject();
		const res = this.getResponseObject();
		const reject = (status: number, code: string, message: string): IWebhookResponseData => {
			res.status(status).json({ error: { code, message } });
			return { noWebhookResponse: true };
		};

		const rawBody = (req as unknown as { rawBody?: Buffer }).rawBody;
		if (!rawBody || rawBody.length === 0) {
			return reject(400, 'bad_request', 'Empty body');
		}

		// 1. The body was signed by the notific.me server of this credential.
		try {
			const jwks = await this.helpers.httpRequest({
				method: 'GET',
				url: (await baseUrl(this)) + JWKS_PATH,
				json: true,
			});
			verifySignature(rawBody, header(this, SIGNATURE_HEADER), jwksKeys(jwks));
		} catch (error) {
			if (error instanceof SignatureError) return reject(401, error.reason, error.message);
			throw new NodeApiError(this.getNode(), error as JsonObject);
		}

		const topic = header(this, 'notificme-topic');
		if (topic && topic !== 'reply') {
			// Not an answer: acknowledge and keep waiting.
			res.status(202).json({ status: 'ignored', topic });
			return { noWebhookResponse: true };
		}

		let reply: IDataObject;
		try {
			reply = JSON.parse(rawBody.toString('utf8')) as IDataObject;
		} catch {
			return reject(400, 'bad_request', 'Body is not JSON');
		}
		const askId = reply.question_set_id;
		if (typeof askId !== 'string' || !/^[0-9a-f-]{36}$/i.test(askId)) {
			return reject(400, 'bad_request', 'Missing question_set_id');
		}

		// 2. The ask belongs to the workspace of this credential. A signed reply
		// of another workspace on the same server cannot resume this workflow.
		try {
			await apiRequest(this, 'GET', `/v0/asks/${askId}`);
		} catch {
			return reject(
				403,
				'forbidden',
				'This ask does not belong to the workspace of the credential',
			);
		}

		return { workflowData: [[{ json: replyToOutput(reply) }]] };
	}

	async execute(this: IExecuteFunctions): Promise<INodeExecutionData[][]> {
		const resource = this.getNodeParameter('resource', 0) as string;
		const operation = this.getNodeParameter('operation', 0) as string;

		if (resource === 'ask' && operation === 'sendAndWait') {
			const body = buildAskBody(this, 0);
			body.callback_url = resumeUrl(this);
			const ask = await apiRequest(this, 'POST', '/v0/asks', body, undefined, {
				'Idempotency-Key': randomId(),
			});
			await this.putExecutionToWait(waitTill(this, ask));
			// Output only when the wait ends without an answer.
			return [
				[
					{
						json: {
							answered: false,
							status: 'no_reply',
							question_set_id: ask.id,
							title: ask.title,
							timeout_at: ask.timeout_at,
						},
						pairedItem: { item: 0 },
					},
				],
			];
		}

		const items = this.getInputData();
		const returnData: INodeExecutionData[] = [];
		for (let i = 0; i < items.length; i++) {
			try {
				let json: IDataObject;
				if (resource === 'ask' && operation === 'create') {
					json = await apiRequest(
						this,
						'POST',
						'/v0/asks',
						buildAskBody(this, i),
						undefined,
						{
							'Idempotency-Key': randomId(),
						},
						i,
					);
				} else if (resource === 'ask' && operation === 'get') {
					const id = (this.getNodeParameter('askId', i) as string).trim();
					const wait = this.getNodeParameter('waitSeconds', i, 0) as number;
					const ask = await apiRequest(
						this,
						'GET',
						`/v0/asks/${encodeURIComponent(id)}`,
						undefined,
						wait > 0 ? { wait } : undefined,
						undefined,
						i,
					);
					json = ask.reply
						? { ...replyToOutput(ask.reply as IDataObject), ask }
						: { answered: false, status: ask.status, question_set_id: ask.id, ask };
				} else if (resource === 'event' && operation === 'send') {
					json = await apiRequest(
						this,
						'POST',
						'/v0/events',
						buildEventBody(this, i),
						undefined,
						undefined,
						i,
					);
				} else {
					throw new NodeOperationError(
						this.getNode(),
						`Unsupported operation ${resource}:${operation}`,
						{
							itemIndex: i,
						},
					);
				}
				returnData.push({ json, pairedItem: { item: i } });
			} catch (error) {
				if (this.continueOnFail()) {
					returnData.push({ json: { error: (error as Error).message }, pairedItem: { item: i } });
					continue;
				}
				if (error instanceof NodeApiError) {
					throw new NodeApiError(this.getNode(), error as unknown as JsonObject, { itemIndex: i });
				}
				throw new NodeOperationError(this.getNode(), error as Error, { itemIndex: i });
			}
		}
		return [returnData];
	}
}
