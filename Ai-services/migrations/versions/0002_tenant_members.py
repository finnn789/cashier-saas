"""Staff members pushed by the Java backend and tenant display names.

Revision ID: 0002_tenant_members
Revises: 0001_channels
"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0002_tenant_members"
down_revision = "0001_channels"
branch_labels = None
depends_on = None

UUID = postgresql.UUID(as_uuid=True)
TIMESTAMPTZ = sa.DateTime(timezone=True)


def upgrade() -> None:
    op.add_column("tenants", sa.Column("name", sa.String(160), nullable=True))
    op.create_table(
        "tenant_members",
        sa.Column("tenant_id", UUID, primary_key=True),
        sa.Column("java_user_id", UUID, primary_key=True),
        sa.Column("full_name", sa.String(160), nullable=False),
        sa.Column("phone_normalized", sa.String(15), nullable=False),
        sa.Column("role", sa.String(32), nullable=False),
        sa.Column("status", sa.String(16), nullable=False, server_default=sa.text("'ACTIVE'")),
        sa.Column("created_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.Column("updated_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.ForeignKeyConstraint(["tenant_id"], ["tenants.id"]),
        sa.CheckConstraint("role IN ('OWNERMERCHANT','CASHIER')"),
        sa.CheckConstraint("status IN ('ACTIVE','DISABLED')"),
    )
    op.create_index(
        "tenant_member_active_phone", "tenant_members", ["phone_normalized"],
        postgresql_where=sa.text("status='ACTIVE'"),
    )


def downgrade() -> None:
    op.drop_index("tenant_member_active_phone", table_name="tenant_members")
    op.drop_table("tenant_members")
    op.drop_column("tenants", "name")
