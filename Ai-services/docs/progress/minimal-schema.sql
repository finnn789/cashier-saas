
CREATE TABLE channel_users (
	id UUID NOT NULL, 
	platform VARCHAR(16) NOT NULL, 
	external_user_id VARCHAR(128) NOT NULL, 
	username VARCHAR(256), 
	first_name VARCHAR(256), 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
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
	PRIMARY KEY (id), 
	FOREIGN KEY(tenant_id) REFERENCES tenants (id), 
	CHECK (platform IN ('TELEGRAM','WHATSAPP')), 
	UNIQUE (platform, external_account_id), 
	UNIQUE (platform, id)
)




CREATE TABLE tenant_memberships (
	tenant_id UUID NOT NULL, 
	user_id UUID NOT NULL, 
	role VARCHAR(32) NOT NULL, 
	verified_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	PRIMARY KEY (tenant_id, user_id), 
	FOREIGN KEY(tenant_id) REFERENCES tenants (id), 
	FOREIGN KEY(user_id) REFERENCES channel_users (id), 
	CHECK (role IN ('OWNERMERCHANT','CASHIER'))
)




CREATE TABLE conversations (
	id UUID NOT NULL, 
	channel_id UUID NOT NULL, 
	platform VARCHAR(16) NOT NULL, 
	user_id UUID NOT NULL, 
	external_chat_id VARCHAR(128) NOT NULL, 
	status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	last_message_at TIMESTAMP WITH TIME ZONE, 
	PRIMARY KEY (id), 
	FOREIGN KEY(platform, channel_id) REFERENCES channel_accounts (platform, id), 
	FOREIGN KEY(platform, user_id) REFERENCES channel_users (platform, id), 
	CHECK (status IN ('ACTIVE','CLOSED'))
)



CREATE UNIQUE INDEX one_active_conversation ON conversations (channel_id, platform, user_id, external_chat_id) WHERE status='ACTIVE'


CREATE TABLE messages (
	id BIGINT GENERATED ALWAYS AS IDENTITY, 
	conversation_id UUID NOT NULL, 
	external_message_id VARCHAR(512), 
	role VARCHAR(16) NOT NULL, 
	content TEXT NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	PRIMARY KEY (id), 
	FOREIGN KEY(conversation_id) REFERENCES conversations (id), 
	CHECK (role IN ('USER','ASSISTANT'))
)



CREATE UNIQUE INDEX conversation_external_message ON messages (conversation_id, external_message_id) WHERE external_message_id IS NOT NULL

CREATE INDEX message_history ON messages (conversation_id, id)