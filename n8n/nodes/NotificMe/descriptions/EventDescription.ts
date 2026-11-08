import type { INodeProperties } from 'n8n-workflow';

const showFor = (operation: string[]) => ({ show: { resource: ['event'], operation } });

export const eventOperations: INodeProperties[] = [
	{
		displayName: 'Operation',
		name: 'operation',
		type: 'options',
		noDataExpression: true,
		displayOptions: { show: { resource: ['event'] } },
		options: [
			{
				name: 'Send',
				value: 'send',
				description: 'Send an event; notific.me decides who gets notified and how',
				action: 'Send an event',
			},
		],
		default: 'send',
	},
];

export const eventFields: INodeProperties[] = [
	{
		displayName: 'Type',
		name: 'type',
		type: 'string',
		required: true,
		default: '',
		placeholder: 'order.refund_requested',
		displayOptions: showFor(['send']),
		description: 'What happened, as a dotted name. Rules and subscriptions match on it.',
	},
	{
		displayName: 'Summary',
		name: 'summary',
		type: 'string',
		required: true,
		default: '',
		placeholder: 'Refund of $1,200 requested for order #4411',
		displayOptions: showFor(['send']),
		description: 'Short text for the push, the watch and simple channels (up to 240 characters)',
	},
	{
		displayName: 'Severity',
		name: 'severity',
		type: 'options',
		options: [
			{ name: 'Critical', value: 'critical' },
			{ name: 'High', value: 'high' },
			{ name: 'Info', value: 'info' },
			{ name: 'Low', value: 'low' },
			{ name: 'Medium', value: 'medium' },
		],
		default: 'info',
		displayOptions: showFor(['send']),
	},
	{
		displayName: 'To',
		name: 'to',
		type: 'string',
		default: '',
		placeholder: '@ana, #ops',
		displayOptions: showFor(['send']),
		description:
			'@handles, e-mails or #groups, separated by commas. Leave empty to let the workspace subscriptions decide.',
	},
	{
		displayName: 'Additional Fields',
		name: 'additionalFields',
		type: 'collection',
		placeholder: 'Add Field',
		default: {},
		displayOptions: showFor(['send']),
		options: [
			{
				displayName: 'Card (JSON)',
				name: 'card',
				type: 'json',
				default: '{\n  "title": "",\n  "blocks": []\n}',
				description:
					'Rich card with metrics, tables, charts and buttons, as defined by the card schema of the notific.me protocol',
			},
			{
				displayName: 'Card ID',
				name: 'cardId',
				type: 'string',
				default: '',
				description:
					'Send again with the same card ID to update the same live card instead of creating another',
			},
			{
				displayName: 'Dedup Key',
				name: 'dedupKey',
				type: 'string',
				default: '',
				description: 'Events with the same key are grouped instead of notifying again',
			},
			{
				displayName: 'Event ID',
				name: 'id',
				type: 'string',
				default: '',
				description: 'Your own ID for this event. Sending the same ID twice is accepted only once.',
			},
			{
				displayName: 'Expires When',
				name: 'expiresWhen',
				type: 'string',
				default: '',
				placeholder: 'order.refunded',
				description: 'Event type that closes this notification',
			},
			{
				displayName: 'Payload (JSON)',
				name: 'payload',
				type: 'json',
				default: '{}',
				description: 'Free data of the event, used by rules and shown in the audit trail',
			},
			{
				displayName: 'Retention',
				name: 'retention',
				type: 'options',
				options: [
					{ name: 'Ephemeral', value: 'ephemeral' },
					{ name: 'Standard', value: 'standard' },
					{ name: 'Regulated', value: 'regulated' },
				],
				default: 'standard',
			},
			{
				displayName: 'Source',
				name: 'source',
				type: 'string',
				default: 'n8n',
				description: 'Who sent the event',
			},
			{
				displayName: 'TTL',
				name: 'ttl',
				type: 'string',
				default: '',
				placeholder: '2h',
				description: 'How long the notification stays relevant: 30m, 2h, 3d',
			},
		],
	},
];
