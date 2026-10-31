"""Five scoped durable Telegram tables.

Revision ID: 0001_telegram
Revises: none
"""

from alembic import op

revision = "0001_telegram"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE telegram_identities (
            id UUID PRIMARY KEY,
            scope VARCHAR(128) NOT NULL,
            bot_id BIGINT NOT NULL CHECK (bot_id > 0),
            telegram_user_id BIGINT NOT NULL CHECK (telegram_user_id > 0),
            username TEXT,
            first_name TEXT NOT NULL,
            last_name TEXT,
            display_name TEXT NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            UNIQUE (scope, bot_id, telegram_user_id),
            UNIQUE (scope, bot_id, id)
        )
    """)
    op.execute("""
        CREATE TABLE conversation_sessions (
            id UUID PRIMARY KEY,
            scope VARCHAR(128) NOT NULL,
            bot_id BIGINT NOT NULL,
            identity_id UUID NOT NULL,
            chat_id BIGINT NOT NULL,
            status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CLOSED')),
            last_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_sequence >= 0),
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            last_activity_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            closed_at TIMESTAMPTZ,
            FOREIGN KEY (scope, bot_id, identity_id) REFERENCES telegram_identities(scope, bot_id, id),
            UNIQUE (scope, bot_id, identity_id, chat_id, id),
            CHECK ((status='ACTIVE' AND closed_at IS NULL) OR (status='CLOSED' AND closed_at IS NOT NULL))
        )
    """)
    op.execute("""
        CREATE UNIQUE INDEX one_active_session ON conversation_sessions(scope, bot_id, identity_id, chat_id)
        WHERE status='ACTIVE'
    """)
    op.execute("""
        CREATE TABLE telegram_inbox (
            id UUID PRIMARY KEY,
            scope VARCHAR(128) NOT NULL,
            bot_id BIGINT NOT NULL,
            update_id BIGINT NOT NULL,
            identity_id UUID NOT NULL,
            chat_id BIGINT NOT NULL,
            message_id BIGINT NOT NULL,
            received_order BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
            kind VARCHAR(16) NOT NULL CHECK (kind IN ('TEXT','START','NEW','SESSION','INVALID','UNSUPPORTED')),
            content TEXT NOT NULL,
            status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
              CHECK (status IN ('PENDING','PROCESSING','RETRY','READY','DONE','DELIVERY_FAILED')),
            session_id UUID,
            attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
            next_attempt_at TIMESTAMPTZ,
            error_code VARCHAR(64),
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            UNIQUE (scope, bot_id, update_id),
            UNIQUE (scope, bot_id, identity_id, chat_id, id),
            FOREIGN KEY (scope, bot_id, identity_id) REFERENCES telegram_identities(scope, bot_id, id),
            FOREIGN KEY (scope, bot_id, identity_id, chat_id, session_id)
              REFERENCES conversation_sessions(scope, bot_id, identity_id, chat_id, id)
        )
    """)
    op.execute("CREATE INDEX inbox_pending ON telegram_inbox(scope, bot_id, status, next_attempt_at, received_order)")
    op.execute("""
        CREATE TABLE conversation_messages (
            id UUID PRIMARY KEY,
            scope VARCHAR(128) NOT NULL,
            bot_id BIGINT NOT NULL,
            identity_id UUID NOT NULL,
            chat_id BIGINT NOT NULL,
            session_id UUID NOT NULL,
            sequence BIGINT NOT NULL CHECK (sequence > 0),
            role VARCHAR(16) NOT NULL CHECK (role IN ('USER','ASSISTANT')),
            content TEXT NOT NULL,
            inbox_id UUID NOT NULL,
            inbound_update_id BIGINT NOT NULL,
            reply_to_id UUID,
            model VARCHAR(256),
            provider_response_id VARCHAR(256),
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            UNIQUE (scope, bot_id, session_id, sequence),
            UNIQUE (scope, bot_id, identity_id, chat_id, session_id, id),
            FOREIGN KEY (scope, bot_id, identity_id, chat_id, session_id)
              REFERENCES conversation_sessions(scope, bot_id, identity_id, chat_id, id),
            FOREIGN KEY (scope, bot_id, identity_id, chat_id, inbox_id)
              REFERENCES telegram_inbox(scope, bot_id, identity_id, chat_id, id),
            FOREIGN KEY (scope, bot_id, identity_id, chat_id, session_id, reply_to_id)
              REFERENCES conversation_messages(scope, bot_id, identity_id, chat_id, session_id, id),
            CHECK ((role='USER' AND reply_to_id IS NULL AND model IS NULL AND provider_response_id IS NULL)
              OR (role='ASSISTANT' AND reply_to_id IS NOT NULL AND model IS NOT NULL))
        )
    """)
    op.execute("CREATE UNIQUE INDEX one_user_per_inbox ON conversation_messages(scope, bot_id, inbox_id) WHERE role='USER'")
    op.execute("CREATE UNIQUE INDEX one_assistant_per_user ON conversation_messages(scope, bot_id, reply_to_id) WHERE role='ASSISTANT'")
    op.execute("CREATE INDEX message_history ON conversation_messages(scope, bot_id, session_id, sequence)")
    op.execute("""
        CREATE TABLE telegram_outbox (
            id UUID PRIMARY KEY,
            scope VARCHAR(128) NOT NULL,
            bot_id BIGINT NOT NULL,
            identity_id UUID NOT NULL,
            chat_id BIGINT NOT NULL,
            session_id UUID NOT NULL,
            inbox_id UUID NOT NULL,
            assistant_message_id UUID,
            response_kind VARCHAR(16) NOT NULL CHECK (response_kind IN ('AI','COMMAND','ERROR')),
            chunk_number INTEGER NOT NULL CHECK (chunk_number >= 0),
            content TEXT NOT NULL,
            status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
              CHECK (status IN ('PENDING','SENDING','SENT','FAILED','UNKNOWN')),
            telegram_message_id BIGINT,
            attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
            next_attempt_at TIMESTAMPTZ,
            retry_after_seconds INTEGER NOT NULL DEFAULT 0 CHECK (retry_after_seconds >= 0),
            error_code VARCHAR(64),
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            UNIQUE (scope, bot_id, inbox_id, response_kind, chunk_number),
            FOREIGN KEY (scope, bot_id, identity_id, chat_id, session_id)
              REFERENCES conversation_sessions(scope, bot_id, identity_id, chat_id, id),
            FOREIGN KEY (scope, bot_id, identity_id, chat_id, inbox_id)
              REFERENCES telegram_inbox(scope, bot_id, identity_id, chat_id, id),
            FOREIGN KEY (scope, bot_id, identity_id, chat_id, session_id, assistant_message_id)
              REFERENCES conversation_messages(scope, bot_id, identity_id, chat_id, session_id, id),
            CHECK ((response_kind='AI' AND assistant_message_id IS NOT NULL)
              OR (response_kind IN ('COMMAND','ERROR') AND assistant_message_id IS NULL)),
            CHECK (status<>'SENT' OR telegram_message_id IS NOT NULL)
        )
    """)
    op.execute("CREATE INDEX outbox_pending ON telegram_outbox(scope, bot_id, status, next_attempt_at)")


def downgrade() -> None:
    op.execute("DROP TABLE telegram_outbox")
    op.execute("DROP TABLE conversation_messages")
    op.execute("DROP TABLE telegram_inbox")
    op.execute("DROP TABLE conversation_sessions")
    op.execute("DROP TABLE telegram_identities")
