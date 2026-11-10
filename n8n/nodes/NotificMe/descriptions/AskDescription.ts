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
	{
		name: '4 – Device Key',
		value: 4,
		description:
			'Level 3 signed with the device key of the notific.me app. It does not set how many people approve: use Decision Rule.',
	},
];

const decisionRules = [
	{ name: 'Any', value: 'any', description: 'The first answer closes the ask' },
	{
		name: 'Each Group (Four Eyes)',
		value: 'each_group',
		description: 'One approval from each entry of Decided By (or of To)',
	},
	{
		name: 'Majority',
		value: 'majority',
		description: 'More than half of the people who may decide',
	},
	{ name: 'Quorum', value: 'quorum', description: 'A number of approvals (Required Approvals)' },
	{ name: 'Unanimous', value: 'unanimous', description: 'Everyone who may decide approves' },
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
		placeholder: '@ana, bob@example.com, #finance>=manager',
		displayOptions: {
			show: { resource: ['ask'], operation: ['sendAndWait', 'create'], askInput: ['fields'] },
		},
		description:
			'Who receives the ask, separated by commas: @handles, e-mails or #groups. #finance>=manager is only the people of #finance with that authority level or above. Leave empty to ask the workspace owner.',
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
				displayName: 'Approving Choices',
				name: 'approveOptions',
				type: 'string',
				typeOptions: { rows: 3 },
				default: '',
				placeholder: 'Approve\nOnly the first 10',
				description:
					'With a Decision Rule and Response Type Choice: the choices that count as approval, one per line',
			},
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
				displayName: 'Decided By',
				name: 'decidedBy',
				type: 'string',
				default: '',
				placeholder: '#board>=director',
				description:
					'Who may decide, separated by commas: #groups (#board>=director for an authority level and above), @handles or e-mails. They also receive the ask; the others in To only see it. Leave empty for everyone in To.',
			},
			{
				displayName: 'Decision Rule',
				name: 'decisionRule',
				type: 'options',
				options: decisionRules,
				default: 'any',
				description:
					'What closes the ask when more than one person decides. One person, one vote; rejections count but do not veto: the ask closes as rejected once approval can no longer be reached. Rules other than Any need the Team plan or above.',
			},
			{
				displayName: 'Escalate After',
				name: 'escalateAfter',
				type: 'string',
				default: '',
				placeholder: '2h',
				description:
					'Time since the ask was sent (30m, 2h, 1d) after which Escalate To also receives it, if still open. Must be shorter than the Timeout. Leave empty for half of the Timeout.',
			},
			{
				displayName: 'Escalate To',
				name: 'escalate',
				type: 'string',
				default: '',
				placeholder: '@backup, #ceo',
				description:
					'Who also receives the ask and may decide if it is still open (see Escalate After), separated by commas: @handles, e-mails or #groups',
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
				displayName: 'Requested By',
				name: 'requestedBy',
				type: 'string',
				default: '',
				placeholder: '@carla',
				description:
					'The person who made the request (@handle or e-mail). They see the ask but never approve their own request.',
			},
			{
				displayName: 'Require Face',
				name: 'requireFace',
				type: 'boolean',
				default: false,
				description:
					'Whether the answer is only accepted after the person verifies their face in the notific.me app, against the face they enrolled in the organization. The answer reports it in face_verification. Business plans and above, with face verification turned on.',
			},
			{
				displayName: 'Require Signature',
				name: 'requireSignature',
				type: 'boolean',
				default: false,
				description:
					'Whether the person must sign the answer with their electronic signature (typed or drawn) and initials, on top of the level. The signed answer carries it in e_signature. Pro plans and above.',
			},
			{
				displayName: 'Required Approvals',
				name: 'requiredApprovals',
				type: 'number',
				typeOptions: { minValue: 1 },
				default: 2,
				description: 'With Decision Rule Quorum: how many approvals close the ask',
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
				displayName: 'Summary Language',
				name: 'locale',
				type: 'options',
				options: [
					{ name: 'English', value: 'en' },
					{ name: 'Portuguese (Brazil)', value: 'pt-BR' },
					{ name: 'Spanish', value: 'es' },
				],
				default: 'en',
				description:
					'Language of as_text, the summary of the answer for this workflow or an AI agent. It does not change what the person sees.',
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
			{
				displayName: 'Veto',
				name: 'veto',
				type: 'boolean',
				default: false,
				description:
					'Whether a single rejection closes the ask as rejected (with a Decision Rule other than Any)',
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
