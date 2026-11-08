import type {
	IAuthenticateGeneric,
	Icon,
	ICredentialTestRequest,
	ICredentialType,
	INodeProperties,
} from 'n8n-workflow';

export class NotificMeApi implements ICredentialType {
	name = 'notificMeApi';

	displayName = 'notific.me API';

	icon: Icon = { light: 'file:notificme.svg', dark: 'file:notificme.dark.svg' };

	documentationUrl = 'https://github.com/idIe-ai/notificme-sdk/tree/main/n8n#credentials';

	properties: INodeProperties[] = [
		{
			displayName: 'Service Token',
			name: 'token',
			type: 'string',
			typeOptions: { password: true },
			required: true,
			default: '',
			description:
				'Service token from the notific.me console (Services → Tokens). It needs the events:write scope to send events, and asks:write and asks:read to ask for decisions.',
		},
		{
			displayName: 'API URL',
			name: 'baseUrl',
			type: 'string',
			required: true,
			default: 'https://api.notific.me',
			description: 'Change only if you run your own notific.me server',
		},
	];

	authenticate: IAuthenticateGeneric = {
		type: 'generic',
		properties: {
			headers: {
				Authorization: '=Bearer {{$credentials.token}}',
			},
		},
	};

	// There is no "who am I" route for service tokens yet. Reading an ask that
	// does not exist answers 404 for a valid token and 401 for an invalid one.
	test: ICredentialTestRequest = {
		request: {
			baseURL: '={{$credentials.baseUrl.replace(/\\/+$/, "")}}',
			url: '/v0/asks/00000000-0000-0000-0000-000000000000',
			method: 'GET',
			ignoreHttpStatusErrors: true,
		},
		rules: [
			{
				type: 'responseSuccessBody',
				properties: {
					key: 'error.code',
					value: 'unauthorized',
					message: 'The token is invalid or was revoked',
				},
			},
		],
	};
}
