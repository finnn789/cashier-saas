"""Static checks of Alembic revision 0003 (no database needed): revision chain,
startup constant, rendered upgrade/downgrade SQL, and agreement with the models."""

import importlib.util
import io
import unittest
from pathlib import Path

from alembic.config import Config
from alembic.migration import MigrationContext
from alembic.operations import Operations
from alembic.script import ScriptDirectory
from sqlalchemy import ForeignKeyConstraint

from app.chat.entities import (
    Base,
    ChannelAccount,
    Conversation,
    TenantMembership,
    TenantSelection,
)
from app.core.database import EXPECTED_ALEMBIC_REVISION

ROOT = Path(__file__).resolve().parents[2]
REVISION = "0003_shared_bot_tenants"


def load_revision(name: str):
    spec = importlib.util.spec_from_file_location(
        f"migration_{name}", ROOT / "migrations" / "versions" / f"{name}.py"
    )
    module = importlib.util.module_from_spec(spec)  # type: ignore[arg-type]
    spec.loader.exec_module(module)  # type: ignore[union-attr]
    return module


def render(direction: str) -> str:
    output = io.StringIO()
    context = MigrationContext.configure(
        dialect_name="postgresql", opts={"as_sql": True, "output_buffer": output}
    )
    with Operations.context(context):
        getattr(load_revision(REVISION), direction)()
    return " ".join(output.getvalue().split())


def foreign_keys(table: object) -> dict[tuple[str, ...], tuple[str, ...]]:
    return {
        tuple(column.name for column in item.columns): tuple(
            element.target_fullname for element in item.elements
        )
        for item in table.constraints  # type: ignore[attr-defined]
        if isinstance(item, ForeignKeyConstraint)
    }


class Migration0003ChainTests(unittest.TestCase):
    def test_revision_follows_0002_and_is_the_single_head(self) -> None:
        revision = load_revision(REVISION)
        self.assertEqual(revision.revision, REVISION)
        self.assertEqual(revision.down_revision, "0002_tenant_members")
        script = ScriptDirectory.from_config(Config(str(ROOT / "alembic.ini")))
        self.assertEqual(script.get_heads(), [REVISION])
        self.assertEqual(
            [item.revision for item in script.walk_revisions()],
            [REVISION, "0002_tenant_members", "0001_channels"],
        )

    def test_service_requires_the_new_head_at_startup(self) -> None:
        self.assertEqual(EXPECTED_ALEMBIC_REVISION, REVISION)

    def test_earlier_revisions_do_not_know_the_new_schema(self) -> None:
        for name in ("0001_channels", "0002_tenant_members"):
            text = (ROOT / "migrations" / "versions" / f"{name}.py").read_text(encoding="utf-8")
            self.assertNotIn("tenant_selections", text)
            self.assertNotIn("conversation_tenant", text)


class Migration0003SqlTests(unittest.TestCase):
    def test_upgrade_backfills_conversation_tenant_before_not_null(self) -> None:
        sql = render("upgrade")
        add = sql.index("ALTER TABLE conversations ADD COLUMN tenant_id UUID;")
        backfill = sql.index(
            "UPDATE conversations SET tenant_id = channel_accounts.tenant_id FROM channel_accounts "
            "WHERE channel_accounts.id = conversations.channel_id "
            "AND channel_accounts.platform = conversations.platform;"
        )
        not_null = sql.index("ALTER TABLE conversations ALTER COLUMN tenant_id SET NOT NULL;")
        self.assertLess(add, backfill)
        self.assertLess(backfill, not_null)
        self.assertIn(
            "ALTER TABLE conversations ADD CONSTRAINT conversation_tenant "
            "FOREIGN KEY(tenant_id) REFERENCES tenants (id);", sql
        )
        # channel tenant is cleared only AFTER conversations got it
        unbind = sql.index("UPDATE channel_accounts SET tenant_id = NULL;")
        self.assertLess(not_null, unbind)
        self.assertLess(
            sql.index("ALTER TABLE channel_accounts ALTER COLUMN tenant_id DROP NOT NULL;"), unbind
        )

    def test_upgrade_active_uniqueness_includes_tenant(self) -> None:
        sql = render("upgrade")
        self.assertIn("DROP INDEX one_active_conversation;", sql)
        self.assertIn(
            "CREATE UNIQUE INDEX one_active_conversation ON conversations "
            "(tenant_id, channel_id, platform, user_id, external_chat_id) WHERE status='ACTIVE';",
            sql,
        )

    def test_upgrade_membership_link_and_selection_table(self) -> None:
        sql = render("upgrade")
        self.assertIn("ALTER TABLE tenant_memberships ADD COLUMN java_user_id UUID;", sql)
        self.assertIn(
            "ALTER TABLE tenant_memberships ADD CONSTRAINT tenant_membership_member FOREIGN "
            "KEY(tenant_id, java_user_id) REFERENCES tenant_members (tenant_id, java_user_id) "
            "ON DELETE CASCADE;", sql
        )
        self.assertIn("CREATE INDEX tenant_membership_user ON tenant_memberships (user_id);", sql)
        self.assertIn("CREATE TABLE tenant_selections", sql)
        self.assertIn("user_id UUID NOT NULL", sql)
        self.assertIn("tenant_id UUID NOT NULL", sql)
        self.assertIn("selected_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL", sql)
        self.assertIn("PRIMARY KEY (user_id)", sql)
        self.assertIn("FOREIGN KEY(user_id) REFERENCES channel_users (id)", sql)
        self.assertIn(
            "FOREIGN KEY(tenant_id, user_id) REFERENCES tenant_memberships (tenant_id, user_id) "
            "ON DELETE CASCADE", sql
        )

    def test_downgrade_refuses_mixed_tenants_and_restores_channel_tenant(self) -> None:
        sql = render("downgrade")
        guard = sql.index("RAISE EXCEPTION 'downgrade 0003 refused")
        restore = sql.index("UPDATE channel_accounts SET tenant_id = (SELECT c.tenant_id FROM conversations c")
        prune = sql.index("DELETE FROM channel_accounts WHERE tenant_id IS NULL;")
        not_null = sql.index("ALTER TABLE channel_accounts ALTER COLUMN tenant_id SET NOT NULL;")
        drop_column = sql.index("ALTER TABLE conversations DROP COLUMN tenant_id;")
        self.assertLess(guard, restore)
        self.assertLess(restore, prune)
        self.assertLess(prune, not_null)
        self.assertLess(restore, drop_column)  # restore reads conversations.tenant_id
        self.assertIn("count(DISTINCT c.tenant_id) > 1", sql)
        self.assertIn(
            "CREATE UNIQUE INDEX one_active_conversation ON conversations "
            "(channel_id, platform, user_id, external_chat_id) WHERE status='ACTIVE';", sql
        )
        self.assertIn("ALTER TABLE conversations DROP CONSTRAINT conversation_tenant;", sql)
        self.assertIn("DROP TABLE tenant_selections;", sql)
        self.assertIn("ALTER TABLE tenant_memberships DROP CONSTRAINT tenant_membership_member;", sql)
        self.assertIn("DROP INDEX tenant_membership_user;", sql)
        self.assertIn("ALTER TABLE tenant_memberships DROP COLUMN java_user_id;", sql)
        self.assertLess(sql.index("DROP TABLE tenant_selections;"),
                        sql.index("ALTER TABLE tenant_memberships DROP COLUMN java_user_id;"))


class Migration0003ModelTests(unittest.TestCase):
    def test_models_match_the_migrated_shape(self) -> None:
        revision = load_revision(REVISION)
        self.assertTrue(ChannelAccount.__table__.c.tenant_id.nullable)
        conversation = Conversation.__table__
        self.assertFalse(conversation.c.tenant_id.nullable)
        self.assertEqual(foreign_keys(conversation)[("tenant_id",)], ("tenants.id",))
        index = next(item for item in conversation.indexes if item.name == "one_active_conversation")
        self.assertTrue(index.unique)
        self.assertEqual([column.name for column in index.columns], revision.NEW_ACTIVE_COLUMNS)
        self.assertEqual(str(index.dialect_options["postgresql"]["where"]), "status='ACTIVE'")

        membership = TenantMembership.__table__
        self.assertTrue(membership.c.java_user_id.nullable)
        self.assertEqual(
            foreign_keys(membership)[("tenant_id", "java_user_id")],
            ("tenant_members.tenant_id", "tenant_members.java_user_id"),
        )
        member_fk = next(
            item for item in membership.constraints
            if isinstance(item, ForeignKeyConstraint) and item.name == "tenant_membership_member"
        )
        self.assertEqual(member_fk.ondelete, "CASCADE")

        selection = TenantSelection.__table__
        self.assertEqual([column.name for column in selection.primary_key.columns], ["user_id"])
        self.assertEqual({column.name for column in selection.columns},
                         {"user_id", "tenant_id", "selected_at"})
        self.assertFalse(selection.c.tenant_id.nullable)
        self.assertEqual(
            foreign_keys(selection)[("tenant_id", "user_id")],
            ("tenant_memberships.tenant_id", "tenant_memberships.user_id"),
        )
        self.assertEqual(foreign_keys(selection)[("user_id",)], ("channel_users.id",))

    def test_every_model_column_is_created_by_some_revision(self) -> None:
        rendered = ""
        for name in ("0001_channels", "0002_tenant_members", REVISION):
            output = io.StringIO()
            context = MigrationContext.configure(
                dialect_name="postgresql", opts={"as_sql": True, "output_buffer": output}
            )
            with Operations.context(context):
                load_revision(name).upgrade()
            rendered += output.getvalue()
        rendered = " ".join(rendered.split())
        for table in Base.metadata.tables.values():
            for column in table.columns:
                with self.subTest(column=f"{table.name}.{column.name}"):
                    created_in_table = f"CREATE TABLE {table.name} (" in rendered and (
                        f" {column.name} " in rendered.split(f"CREATE TABLE {table.name} (", 1)[1]
                        .split(");", 1)[0]
                    )
                    added = f"ALTER TABLE {table.name} ADD COLUMN {column.name} " in rendered
                    self.assertTrue(created_in_table or added)


if __name__ == "__main__":
    unittest.main()
