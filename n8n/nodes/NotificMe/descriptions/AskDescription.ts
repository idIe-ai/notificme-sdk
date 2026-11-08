import type { INodeProperties } from 'n8n-workflow';

const showFor = (operation: string[]) => ({ show: { resource: ['ask'], operation } });

const questionTypes = [
	{ name: 'Yes / No', value: 'yesno' },
	{ name: 'Single Choice', value: 'single' },
	{ name: 'Multiple Choice', value: 'multi' },
	{ name: 'Text', value: 'text' },
	{ name: 'Voice', value: 'voice' },
	{ name: 'Number', value: 'number' },
	{ name: 'Date', value: 'date' },
];

const levels = [
	{ name: '0 – Tap', value: 0, description: 'Any tap on the notification' },
	{ name: '1 – Unlocked Device', value: 1, description: 'The phone must be unlocked' },
	{
		name: '2 – Biometrics',
		value: 2,
		description: 'Signed on the device after biometrics or the device passcode',
	},
	{
		name: '3 – Biometrics, Online, Short Window',
		value: 3,
		description: 'For irreversible actions: deleting data, moving money',
	},
	{ name: '4 – Quorum', value: 4, description: 'Level 3 plus a quorum of people' },
];

export const askOperations: INodeProperties[] = [
	{
		displayName: 'Operation',
		name: 'operation',
		type: 'options',
		noDataExpression: true,
		displayOptions: { show: { resource: ['ask'] } },
		options: [
			{
				name: 'Send and Wait for Response',
				value: 'sendAndWait',
				description:
					'Ask a human and pause the workflow until the signed answer comes back (or the ask times out)',
				action: 'Ask a human and wait for the answer',
			},
			{
				name: 'Create',
				value: 'create',
				description:
					'Create an ask and continue right away; get the answer later or on your own webhook',
				action: 'Create an ask',
			},
			{
				name: 'Get',
				value: 'get',
				description: 'Get the status and the answer of an ask',
				action: 'Get an ask',
			},
		],
		default: 'sendAndWait',
	},
];

export const askFields: INodeProperties[] = [
	// ------------------------------------------------------------------ ask:get
	{
		displayName: 'Ask ID',
		name: 'askId',
		type: 'string',
		required: true,
		default: '',
		displayOptions: showFor(['get']),
		description: 'ID returned when the ask was created (question_set_id)',
	},
	{
		displayName: 'Wait for Change (Seconds)',
		name: 'waitSeconds',
		type: 'number',
		typeOptions: { minValue: 0, maxValue: 60 },
		default: 0,
		displayOptions: showFor(['get']),
		description:
			'Hold the request up to this many seconds while the ask is still open, returning as soon as it is answered',
	},

	// ------------------------------------------------------- ask:create/sendAndWait
	{
		displayName: 'Define Ask',
		name: 'askInput',
		type: 'options',
		noDataExpression: true,
		displayOptions: showFor(['sendAndWait', 'create']),
		options: [
			{ name: 'Using Fields Below', value: 'fields' },
			{
				name: 'Using JSON',
				value: 'json',
				description:
					'The full ask, as defined by the question set schema of the notific.me protocol',
			},
		],
		default: 'fields',
	},
	{
		displayName: 'Ask (JSON)',
		name: 'askJson',
		type: 'json',
		required: true,
		default:
			'{\n  "to": ["@handle"],\n  "title": "Approve the refund?",\n  "questions": [\n    { "id": "approved", "type": "yesno", "text": "Approve?" }\n  ]\n}',
		displayOptions: {
			show: { resource: ['ask'], operation: ['sendAndWait', 'create'], askInput: ['json'] },
		},
		description:
			'Title, questions, recipients, level, timeout, card… callback_url is set by this node when it waits for the answer',
	},
	{
		displayName: 'To',
		name: 'to',
		type: 'string',
		default: '',
		placeholder: '@ana, bob@example.com, #finance',
		displayOptions: {
			show: { resource: ['ask'], operation: ['sendAndWait', 'create'], askInput: ['fields'] },
		},
		description:
			'Who decides: @handles, e-mails or #groups, separated by commas. Leave empty to ask the workspace owner.',
	},
	{
		displayName: 'Title',
		name: 'title',
		type: 'string',
		required: true,
		default: '',
		placeholder: 'Approve the $1,200 refund for order #4411?',
		displayOptions: {
			show: { resource: ['ask'], operation: ['sendAndWait', 'create'], askInput: ['fields'] },
		},
		description: 'What the person reads first on the notification',
	},
	{
		displayName: 'Context',
		name: 'context',
		type: 'string',
		typeOptions: { rows: 3 },
		default: '',
		displayOptions: {
			show: { resource: ['ask'], operation: ['sendAndWait', 'create'], askInput: ['fields'] },
		},
		description: 'Why they are being asked: the details they need to decide',
	},
	{
		displayName: 'Response Type',
		name: 'responseType',
		type: 'options',
		noDataExpression: true,
		displayOptions: {
			show: { resource: ['ask'], operation: ['sendAndWait', 'create'], askInput: ['fields'] },
		},
		options: [
			{
				name: 'Approval',
				value: 'approval',
				description: 'Yes or no. The output has approved: true or false.',
			},
			{ name: 'Choice', value: 'choice', description: 'Pick one (or several) of your options' },
			{ name: 'Free Text', value: 'freeText', description: 'The person writes the answer' },
			{
				name: 'Custom Questions',
				value: 'custom',
				description: 'Up to 20 questions of any type in one ask',
			},
		],
		default: 'approval',
	},
	{
		displayName: 'Question',
		name: 'question',
		type: 'string',
		default: '',
		placeholder: 'Approve?',
		displayOptions: {
			show: {
				resource: ['ask'],
				operation: ['sendAndWait', 'create'],
				askInput: ['fields'],
				responseType: ['approval', 'choice', 'freeText'],
			},
		},
		description: 'Text of the question. Leave empty to repeat the title.',
	},
	{
		displayName: 'Choices',
		name: 'choices',
		type: 'string',
		typeOptions: { rows: 4 },
		required: true,
		default: '',
		placeholder: 'Approve\nOnly the first 10\nDeny',
		displayOptions: {
			show: {
				resource: ['ask'],
				operation: ['sendAndWait', 'create'],
				askInput: ['fields'],
				responseType: ['choice'],
			},
		},
		description: 'One option per line (2 to 12)',
	},
	{
		displayName: 'Allow Multiple Choices',
		name: 'multipleChoices',
		type: 'boolean',
		default: false,
		displayOptions: {
			show: {
				resource: ['ask'],
				operation: ['sendAndWait', 'create'],
				askInput: ['fields'],
				responseType: ['choice'],
			},
		},
	},
	{
		displayName: 'Allow Other Answer',
		name: 'allowOther',
		type: 'boolean',
		default: false,
		displayOptions: {
			show: {
				resource: ['ask'],
				operation: ['sendAndWait', 'create'],
				askInput: ['fields'],
				responseType: ['choice'],
			},
		},
		description: 'Whether the person can write an answer that is not one of the choices',
	},
	{
		displayName: 'Questions',
		name: 'questions',
		type: 'fixedCollection',
		typeOptions: { multipleValues: true, sortable: true },
		placeholder: 'Add Question',
		default: {},
		displayOptions: {
			show: {
				resource: ['ask'],
				operation: ['sendAndWait', 'create'],
				askInput: ['fields'],
				responseType: ['custom'],
			},
		},
		options: [
			{
				displayName: 'Question',
				name: 'question',
				values: [
					{
						displayName: 'Allow Other Answer',
						name: 'allowOther',
						type: 'boolean',
						default: false,
						description: 'Whether free text is accepted besides the choices',
					},
					{
						displayName: 'Choices',
						name: 'options',
						type: 'string',
						typeOptions: { rows: 3 },
						default: '',
						placeholder: 'Approve\nDeny',
						description: 'For Single and Multiple Choice: one option per line (2 to 12)',
					},
					{
						displayName: 'ID',
						name: 'id',
						type: 'string',
						required: true,
						default: '',
						placeholder: 'action',
						description:
							'Key of this answer in the output (letters, digits, _ and -). Must be unique in the ask.',
					},
					{
						displayName: 'Optional',
						name: 'optional',
						type: 'boolean',
						default: false,
						description: 'Whether the person can skip this question',
					},
					{
						displayName: 'Text',
						name: 'text',
						type: 'string',
						required: true,
						default: '',
						placeholder: 'What should we do?',
					},
					{
						displayName: 'Type',
						name: 'type',
						type: 'options',
						options: questionTypes,
						default: 'yesno',
					},
				],
			},
		],
	},
	{
		displayName: 'Options',
		name: 'options',
		type: 'collection',
		placeholder: 'Add Option',
		default: {},
		displayOptions: {
			show: { resource: ['ask'], operation: ['sendAndWait', 'create'], askInput: ['fields'] },
		},
		options: [
			{
				displayName: 'Callback URL',
				name: 'callbackUrl',
				type: 'string',
				default: '',
				placeholder: 'https://example.com/hooks/notificme',
				displayOptions: { show: { '/operation': ['create'] } },
				description:
					'Your webhook that receives the signed answer. With Send and Wait for Response this node sets it.',
			},
			{
				displayName: 'Escalate To',
				name: 'escalate',
				type: 'string',
				default: '',
				placeholder: '@backup, partner@example.com',
				description: 'Who is asked next when nobody answers in time, separated by commas',
			},
			{
				displayName: 'Level',
				name: 'level',
				type: 'options',
				options: levels,
				default: 1,
				description:
					'Minimum verification the person must pass to answer. The workspace may require more.',
			},
			{
				displayName: 'Offline Answers',
				name: 'offline',
				type: 'options',
				options: [
					{ name: 'Allowed', value: 'allowed' },
					{ name: 'Denied', value: 'denied' },
				],
				default: 'allowed',
				description: 'Whether an answer given without connection (sent later) is accepted',
			},
			{
				displayName: 'On Timeout (JSON)',
				name: 'onTimeout',
				type: 'json',
				default: '{}',
				placeholder: '{ "approved": false }',
				description:
					'Answers applied when nobody answers in time, by question ID. With Approval the question ID is "approved", with Choice "choice" and with Free Text "text".',
			},
			{
				displayName: 'Severity',
				name: 'severity',
				type: 'options',
				options: [
					{
						name: 'Critical',
						value: 'critical',
						description: 'The app may take the full screen with an alarm until they decide',
					},
					{ name: 'High', value: 'high' },
					{ name: 'Info', value: 'info' },
					{ name: 'Low', value: 'low' },
					{ name: 'Medium', value: 'medium' },
				],
				default: 'medium',
			},
			{
				displayName: 'Timeout',
				name: 'timeout',
				type: 'string',
				default: '',
				placeholder: '30m',
				description:
					'How long the ask stays open: 30s, 30m, 2h, 3d. When it ends, the On Timeout answers are applied and sent back.',
			},
		],
	},
	{
		displayName: 'Limit Wait Time',
		name: 'limitWaitTime',
		type: 'fixedCollection',
		default: {},
		placeholder: 'Add Limit',
		displayOptions: showFor(['sendAndWait']),
		description:
			'Resume the workflow without an answer after this time (output has answered: false). Without a limit, the workflow waits for the ask timeout, or forever when the ask has none.',
		options: [
			{
				displayName: 'Limit',
				name: 'values',
				values: [
					{
						displayName: 'Amount',
						name: 'amount',
						type: 'number',
						typeOptions: { minValue: 1 },
						default: 1,
					},
					{
						displayName: 'Unit',
						name: 'unit',
						type: 'options',
						options: [
							{ name: 'Minutes', value: 'minutes' },
							{ name: 'Hours', value: 'hours' },
							{ name: 'Days', value: 'days' },
						],
						default: 'hours',
					},
				],
			},
		],
	},
];
