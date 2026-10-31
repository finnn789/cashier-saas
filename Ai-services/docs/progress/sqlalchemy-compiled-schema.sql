
CREATE TABLE telegram_identities (
	id UUID NOT NULL, 
	scope VARCHAR(128) NOT NULL, 
	bot_id BIGINT NOT NULL, 
	telegram_user_id BIGINT NOT NULL, 
	username TEXT, 
	first_name TEXT NOT NULL, 
	last_name TEXT, 
	display_name TEXT NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	last_seen_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	PRIMARY KEY (id), 
	CHECK (bot_id > 0), 
	CHECK (telegram_user_id > 0), 
	UNIQUE (scope, bot_id, telegram_user_id), 
	UNIQUE (scope, bot_id, id)
)




CREATE TABLE conversation_sessions (
	id UUID NOT NULL, 
	scope VARCHAR(128) NOT NULL, 
	bot_id BIGINT NOT NULL, 
	identity_id UUID NOT NULL, 
	chat_id BIGINT NOT NULL, 
	status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, 
	last_sequence BIGINT DEFAULT 0 NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	last_activity_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	closed_at TIMESTAMP WITH TIME ZONE, 
	PRIMARY KEY (id), 
	FOREIGN KEY(scope, bot_id, identity_id) REFERENCES telegram_identities (scope, bot_id, id), 
	UNIQUE (scope, bot_id, identity_id, chat_id, id), 
	CHECK (status IN ('ACTIVE','CLOSED')), 
	CHECK (last_sequence >= 0), 
	CHECK ((status='ACTIVE' AND closed_at IS NULL) OR (status='CLOSED' AND closed_at IS NOT NULL))
)



CREATE UNIQUE INDEX one_active_session ON conversation_sessions (scope, bot_id, identity_id, chat_id) WHERE status='ACTIVE'


CREATE TABLE telegram_inbox (
	id UUID NOT NULL, 
	scope VARCHAR(128) NOT NULL, 
	bot_id BIGINT NOT NULL, 
	update_id BIGINT NOT NULL, 
	identity_id UUID NOT NULL, 
	chat_id BIGINT NOT NULL, 
	message_id BIGINT NOT NULL, 
	received_order BIGINT GENERATED ALWAYS AS IDENTITY, 
	kind VARCHAR(16) NOT NULL, 
	content TEXT NOT NULL, 
	status VARCHAR(24) DEFAULT 'PENDING' NOT NULL, 
	session_id UUID, 
	attempts INTEGER DEFAULT 0 NOT NULL, 
	next_attempt_at TIMESTAMP WITH TIME ZONE, 
	error_code VARCHAR(64), 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	PRIMARY KEY (id), 
	UNIQUE (scope, bot_id, update_id), 
	UNIQUE (scope, bot_id, identity_id, chat_id, id), 
	FOREIGN KEY(scope, bot_id, identity_id) REFERENCES telegram_identities (scope, bot_id, id), 
	FOREIGN KEY(scope, bot_id, identity_id, chat_id, session_id) REFERENCES conversation_sessions (scope, bot_id, identity_id, chat_id, id), 
	CHECK (kind IN ('TEXT','START','NEW','SESSION','INVALID','UNSUPPORTED')), 
	CHECK (status IN ('PENDING','PROCESSING','RETRY','READY','DONE','DELIVERY_FAILED')), 
	CHECK (attempts >= 0), 
	UNIQUE (received_order)
)



CREATE INDEX inbox_pending ON telegram_inbox (scope, bot_id, status, next_attempt_at, received_order)


CREATE TABLE conversation_messages (
	id UUID NOT NULL, 
	scope VARCHAR(128) NOT NULL, 
	bot_id BIGINT NOT NULL, 
	identity_id UUID NOT NULL, 
	chat_id BIGINT NOT NULL, 
	session_id UUID NOT NULL, 
	sequence BIGINT NOT NULL, 
	role VARCHAR(16) NOT NULL, 
	content TEXT NOT NULL, 
	inbox_id UUID NOT NULL, 
	inbound_update_id BIGINT NOT NULL, 
	reply_to_id UUID, 
	model VARCHAR(256), 
	provider_response_id VARCHAR(256), 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	PRIMARY KEY (id), 
	UNIQUE (scope, bot_id, session_id, sequence), 
	UNIQUE (scope, bot_id, identity_id, chat_id, session_id, id), 
	FOREIGN KEY(scope, bot_id, identity_id, chat_id, session_id) REFERENCES conversation_sessions (scope, bot_id, identity_id, chat_id, id), 
	FOREIGN KEY(scope, bot_id, identity_id, chat_id, inbox_id) REFERENCES telegram_inbox (scope, bot_id, identity_id, chat_id, id), 
	FOREIGN KEY(scope, bot_id, identity_id, chat_id, session_id, reply_to_id) REFERENCES conversation_messages (scope, bot_id, identity_id, chat_id, session_id, id), 
	CHECK (sequence > 0), 
	CHECK (role IN ('USER','ASSISTANT')), 
	CHECK ((role='USER' AND reply_to_id IS NULL AND model IS NULL AND provider_response_id IS NULL) OR (role='ASSISTANT' AND reply_to_id IS NOT NULL AND model IS NOT NULL))
)



CREATE INDEX message_history ON conversation_messages (scope, bot_id, session_id, sequence)

CREATE UNIQUE INDEX one_assistant_per_user ON conversation_messages (scope, bot_id, reply_to_id) WHERE role='ASSISTANT'

CREATE UNIQUE INDEX one_user_per_inbox ON conversation_messages (scope, bot_id, inbox_id) WHERE role='USER'


CREATE TABLE telegram_outbox (
	id UUID NOT NULL, 
	scope VARCHAR(128) NOT NULL, 
	bot_id BIGINT NOT NULL, 
	identity_id UUID NOT NULL, 
	chat_id BIGINT NOT NULL, 
	session_id UUID NOT NULL, 
	inbox_id UUID NOT NULL, 
	assistant_message_id UUID, 
	response_kind VARCHAR(16) NOT NULL, 
	chunk_number INTEGER NOT NULL, 
	content TEXT NOT NULL, 
	status VARCHAR(16) DEFAULT 'PENDING' NOT NULL, 
	telegram_message_id BIGINT, 
	attempts INTEGER DEFAULT 0 NOT NULL, 
	next_attempt_at TIMESTAMP WITH TIME ZONE, 
	retry_after_seconds INTEGER DEFAULT 0 NOT NULL, 
	error_code VARCHAR(64), 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	PRIMARY KEY (id), 
	UNIQUE (scope, bot_id, inbox_id, response_kind, chunk_number), 
	FOREIGN KEY(scope, bot_id, identity_id, chat_id, session_id) REFERENCES conversation_sessions (scope, bot_id, identity_id, chat_id, id), 
	FOREIGN KEY(scope, bot_id, identity_id, chat_id, inbox_id) REFERENCES telegram_inbox (scope, bot_id, identity_id, chat_id, id), 
	FOREIGN KEY(scope, bot_id, identity_id, chat_id, session_id, assistant_message_id) REFERENCES conversation_messages (scope, bot_id, identity_id, chat_id, session_id, id), 
	CHECK (response_kind IN ('AI','COMMAND','ERROR')), 
	CHECK (chunk_number >= 0), 
	CHECK (status IN ('PENDING','SENDING','SENT','FAILED','UNKNOWN')), 
	CHECK (attempts >= 0), 
	CHECK (retry_after_seconds >= 0), 
	CHECK ((response_kind='AI' AND assistant_message_id IS NOT NULL) OR (response_kind IN ('COMMAND','ERROR') AND assistant_message_id IS NULL)), 
	CHECK (status<>'SENT' OR telegram_message_id IS NOT NULL)
)



CREATE INDEX outbox_pending ON telegram_outbox (scope, bot_id, status, next_attempt_at)