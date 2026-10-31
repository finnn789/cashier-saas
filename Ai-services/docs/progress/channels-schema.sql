
CREATE TABLE channel_users (
	id UUID NOT NULL, 
	platform VARCHAR(16) NOT NULL, 
	external_user_id VARCHAR(128) NOT NULL, 
	username VARCHAR(256), 
	first_name VARCHAR(256), 
	last_name VARCHAR(256), 
	display_name VARCHAR(512) NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	last_seen_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	PRIMARY KEY (id), 
	CHECK (platform IN ('TELEGRAM','WHATSAPP')), 
	UNIQUE (platform, external_user_id), 
	UNIQUE (platform, id)
)




CREATE TABLE tenants (
	id UUID NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	PRIMARY KEY (id)
)




CREATE TABLE channel_accounts (
	id UUID NOT NULL, 
	tenant_id UUID NOT NULL, 
	platform VARCHAR(16) NOT NULL, 
	external_account_id VARCHAR(128) NOT NULL, 
	username VARCHAR(256), 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	PRIMARY KEY (id), 
	FOREIGN KEY(tenant_id) REFERENCES tenants (id), 
	CHECK (platform IN ('TELEGRAM','WHATSAPP')), 
	UNIQUE (platform, external_account_id), 
	UNIQUE (tenant_id, platform, id)
)




CREATE TABLE tenant_memberships (
	tenant_id UUID NOT NULL, 
	user_id UUID NOT NULL, 
	role VARCHAR(32) NOT NULL, 
	verified_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	PRIMARY KEY (tenant_id, user_id), 
	FOREIGN KEY(tenant_id) REFERENCES tenants (id), 
	FOREIGN KEY(user_id) REFERENCES channel_users (id), 
	CHECK (role IN ('OWNERMERCHANT','CASHIER'))
)




CREATE TABLE conversations (
	id UUID NOT NULL, 
	tenant_id UUID NOT NULL, 
	channel_id UUID NOT NULL, 
	platform VARCHAR(16) NOT NULL, 
	user_id UUID NOT NULL, 
	external_chat_id VARCHAR(128) NOT NULL, 
	status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, 
	summary TEXT, 
	last_sequence BIGINT DEFAULT 0 NOT NULL, 
	last_message_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	closed_at TIMESTAMP WITH TIME ZONE, 
	PRIMARY KEY (id), 
	FOREIGN KEY(tenant_id, platform, channel_id) REFERENCES channel_accounts (tenant_id, platform, id), 
	FOREIGN KEY(platform, user_id) REFERENCES channel_users (platform, id), 
	UNIQUE (tenant_id, channel_id, platform, user_id, external_chat_id, id), 
	CHECK (status IN ('ACTIVE','CLOSED')), 
	CHECK (last_sequence >= 0), 
	CHECK ((status='ACTIVE' AND closed_at IS NULL) OR (status='CLOSED' AND closed_at IS NOT NULL))
)



CREATE UNIQUE INDEX one_active_conversation ON conversations (tenant_id, channel_id, platform, user_id, external_chat_id) WHERE status='ACTIVE'


CREATE TABLE message_inbox (
	id UUID NOT NULL, 
	tenant_id UUID NOT NULL, 
	channel_id UUID NOT NULL, 
	platform VARCHAR(16) NOT NULL, 
	user_id UUID NOT NULL, 
	external_chat_id VARCHAR(128) NOT NULL, 
	external_event_id VARCHAR(512) NOT NULL, 
	external_message_id VARCHAR(512) NOT NULL, 
	received_order BIGINT GENERATED ALWAYS AS IDENTITY, 
	kind VARCHAR(16) NOT NULL, 
	content TEXT NOT NULL, 
	status VARCHAR(24) DEFAULT 'PENDING' NOT NULL, 
	conversation_id UUID, 
	attempts INTEGER DEFAULT 0 NOT NULL, 
	next_attempt_at TIMESTAMP WITH TIME ZONE, 
	error_code VARCHAR(64), 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	PRIMARY KEY (id), 
	FOREIGN KEY(tenant_id, platform, channel_id) REFERENCES channel_accounts (tenant_id, platform, id), 
	FOREIGN KEY(platform, user_id) REFERENCES channel_users (platform, id), 
	FOREIGN KEY(tenant_id, channel_id, platform, user_id, external_chat_id, conversation_id) REFERENCES conversations (tenant_id, channel_id, platform, user_id, external_chat_id, id), 
	UNIQUE (tenant_id, channel_id, external_event_id), 
	UNIQUE (tenant_id, channel_id, platform, user_id, external_chat_id, id), 
	CHECK (kind IN ('TEXT','START','NEW','SESSION','INVALID','UNSUPPORTED')), 
	CHECK (status IN ('PENDING','PROCESSING','RETRY','READY','DONE','DELIVERY_FAILED')), 
	CHECK (attempts >= 0), 
	UNIQUE (received_order)
)



CREATE INDEX inbox_pending ON message_inbox (tenant_id, channel_id, status, next_attempt_at, received_order)


CREATE TABLE messages (
	id BIGINT GENERATED ALWAYS AS IDENTITY, 
	tenant_id UUID NOT NULL, 
	channel_id UUID NOT NULL, 
	platform VARCHAR(16) NOT NULL, 
	user_id UUID NOT NULL, 
	external_chat_id VARCHAR(128) NOT NULL, 
	conversation_id UUID NOT NULL, 
	sequence BIGINT NOT NULL, 
	role VARCHAR(16) NOT NULL, 
	content TEXT NOT NULL, 
	inbox_id UUID NOT NULL, 
	external_message_id VARCHAR(512), 
	token_count INTEGER, 
	reply_to_id BIGINT, 
	model VARCHAR(256), 
	provider_response_id VARCHAR(256), 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	PRIMARY KEY (id), 
	FOREIGN KEY(tenant_id, channel_id, platform, user_id, external_chat_id, conversation_id) REFERENCES conversations (tenant_id, channel_id, platform, user_id, external_chat_id, id), 
	FOREIGN KEY(tenant_id, channel_id, platform, user_id, external_chat_id, inbox_id) REFERENCES message_inbox (tenant_id, channel_id, platform, user_id, external_chat_id, id), 
	FOREIGN KEY(tenant_id, channel_id, platform, user_id, external_chat_id, conversation_id, reply_to_id) REFERENCES messages (tenant_id, channel_id, platform, user_id, external_chat_id, conversation_id, id), 
	UNIQUE (tenant_id, channel_id, conversation_id, sequence), 
	UNIQUE (tenant_id, channel_id, platform, user_id, external_chat_id, conversation_id, id), 
	CHECK (sequence > 0), 
	CHECK (role IN ('USER','ASSISTANT')), 
	CHECK (token_count IS NULL OR token_count >= 0), 
	CHECK ((role='USER' AND reply_to_id IS NULL AND model IS NULL AND provider_response_id IS NULL) OR (role='ASSISTANT' AND reply_to_id IS NOT NULL AND model IS NOT NULL))
)



CREATE UNIQUE INDEX conversation_external_message ON messages (conversation_id, external_message_id) WHERE external_message_id IS NOT NULL

CREATE INDEX message_history ON messages (tenant_id, channel_id, conversation_id, sequence)

CREATE UNIQUE INDEX one_assistant_per_user ON messages (tenant_id, channel_id, reply_to_id) WHERE role='ASSISTANT'

CREATE UNIQUE INDEX one_user_per_inbox ON messages (tenant_id, channel_id, inbox_id) WHERE role='USER'


CREATE TABLE message_outbox (
	id UUID NOT NULL, 
	tenant_id UUID NOT NULL, 
	channel_id UUID NOT NULL, 
	platform VARCHAR(16) NOT NULL, 
	user_id UUID NOT NULL, 
	external_chat_id VARCHAR(128) NOT NULL, 
	conversation_id UUID NOT NULL, 
	inbox_id UUID NOT NULL, 
	assistant_message_id BIGINT, 
	response_kind VARCHAR(16) NOT NULL, 
	chunk_number INTEGER NOT NULL, 
	content TEXT NOT NULL, 
	status VARCHAR(16) DEFAULT 'PENDING' NOT NULL, 
	external_message_id VARCHAR(512), 
	attempts INTEGER DEFAULT 0 NOT NULL, 
	next_attempt_at TIMESTAMP WITH TIME ZONE, 
	retry_after_seconds INTEGER DEFAULT 0 NOT NULL, 
	error_code VARCHAR(64), 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	PRIMARY KEY (id), 
	FOREIGN KEY(tenant_id, channel_id, platform, user_id, external_chat_id, conversation_id) REFERENCES conversations (tenant_id, channel_id, platform, user_id, external_chat_id, id), 
	FOREIGN KEY(tenant_id, channel_id, platform, user_id, external_chat_id, inbox_id) REFERENCES message_inbox (tenant_id, channel_id, platform, user_id, external_chat_id, id), 
	FOREIGN KEY(tenant_id, channel_id, platform, user_id, external_chat_id, conversation_id, assistant_message_id) REFERENCES messages (tenant_id, channel_id, platform, user_id, external_chat_id, conversation_id, id), 
	UNIQUE (tenant_id, channel_id, inbox_id, response_kind, chunk_number), 
	CHECK (response_kind IN ('AI','COMMAND','ERROR')), 
	CHECK (chunk_number >= 0), 
	CHECK (status IN ('PENDING','SENDING','SENT','FAILED','UNKNOWN')), 
	CHECK (attempts >= 0), 
	CHECK (retry_after_seconds >= 0), 
	CHECK ((response_kind='AI' AND assistant_message_id IS NOT NULL) OR (response_kind IN ('COMMAND','ERROR') AND assistant_message_id IS NULL)), 
	CHECK (status<>'SENT' OR external_message_id IS NOT NULL)
)



CREATE INDEX outbox_pending ON message_outbox (tenant_id, channel_id, status, next_attempt_at)