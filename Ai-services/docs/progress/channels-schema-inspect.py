"""Inspect declared schema metadata without running migrations or connecting a database."""

import ast
import importlib.util
import json
from pathlib import Path
import re
import sys

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql
from sqlalchemy.schema import CreateIndex, CreateTable


root = Path(__file__).resolve().parents[2]
stage = root / "docs/progress" / (sys.argv[1] if len(sys.argv) > 1 else "channels-stage-a")
artifact_prefix = sys.argv[2] if len(sys.argv) > 2 else "channels"
spec = importlib.util.spec_from_file_location("channels_schema_review", stage / "app/chat/entities.py")
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
dialect = postgresql.dialect()
declared = module.Base.metadata
tree = ast.parse((stage / "migrations/versions/0001_channels.py").read_text(encoding="utf-8"))
upgrade = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "upgrade")
environment = {"sa": sa, "UUID": postgresql.UUID(as_uuid=True), "TIMESTAMPTZ": sa.DateTime(timezone=True)}
allowed = {"Column", "ForeignKeyConstraint", "UniqueConstraint", "CheckConstraint", "PrimaryKeyConstraint",
           "BigInteger", "Integer", "String", "Text", "Identity", "text"}


def expression(node: ast.expr) -> object:
    for part in ast.walk(node):
        if isinstance(part, ast.Call):
            if not isinstance(part.func, ast.Attribute) or not isinstance(part.func.value, ast.Name):
                raise ValueError("Unexpected declaration expression")
            if part.func.value.id != "sa" or part.func.attr not in allowed:
                raise ValueError("Unexpected declaration constructor")
        if isinstance(part, ast.Name) and part.id not in environment:
            raise ValueError("Unexpected declaration name")
    return eval(compile(ast.Expression(node), "migration-declaration", "eval"), {"__builtins__": {}}, environment)


snapshot = sa.MetaData()
for statement in upgrade.body:
    call = statement.value if isinstance(statement, ast.Expr) else None
    if not isinstance(call, ast.Call) or not isinstance(call.func, ast.Attribute):
        raise ValueError("Unexpected migration declaration")
    if not isinstance(call.func.value, ast.Name) or call.func.value.id != "op":
        raise ValueError("Unexpected migration operation")
    if call.func.attr == "create_table":
        table_name = ast.literal_eval(call.args[0])
        sa.Table(table_name, snapshot, *(expression(arg) for arg in call.args[1:]))
    elif call.func.attr == "create_index":
        name, table_name, column_names = (ast.literal_eval(arg) for arg in call.args[:3])
        options = {item.arg: expression(item.value) for item in call.keywords}
        sa.Index(name, *(snapshot.tables[table_name].c[column] for column in column_names), **options)
    else:
        raise ValueError("Unexpected schema operation")


def normalized(value: object) -> str:
    return re.sub(r"\s+", "", str(value))


def table_description(table: sa.Table) -> dict[str, object]:
    columns = []
    for column in table.columns:
        default = None if column.server_default is None or isinstance(column.server_default, sa.Identity) else str(column.server_default.arg.compile(dialect=dialect))
        identity = None if column.identity is None else column.identity.always
        columns.append((column.name, str(column.type.compile(dialect=dialect)), column.nullable, column.primary_key, normalized(default), identity))
    checks = sorted(normalized(item.sqltext) for item in table.constraints if isinstance(item, sa.CheckConstraint))
    unique = sorted(tuple(item.columns.keys()) for item in table.constraints if isinstance(item, sa.UniqueConstraint))
    foreign = sorted((tuple(item.columns.keys()), tuple(element.target_fullname for element in item.elements)) for item in table.foreign_key_constraints)
    indexes = sorted((item.name, tuple(column.name for column in item.columns), item.unique, normalized(item.dialect_options["postgresql"].get("where"))) for item in table.indexes)
    return {"columns": columns, "checks": checks, "unique": unique, "foreign": foreign, "indexes": indexes}


differences = []
for name in sorted(set(declared.tables) | set(snapshot.tables)):
    if name not in declared.tables or name not in snapshot.tables:
        differences.append({"table": name, "mismatch": "missing table"})
        continue
    model, frozen = table_description(declared.tables[name]), table_description(snapshot.tables[name])
    for category in model:
        if model[category] != frozen[category]:
            differences.append({"table": name, "mismatch": category, "model": model[category], "migration": frozen[category]})

ddl = []
for table in declared.sorted_tables:
    ddl.append(str(CreateTable(table).compile(dialect=dialect)))
    ddl.extend(str(CreateIndex(index).compile(dialect=dialect)) for index in sorted(table.indexes, key=lambda index: index.name))
(root / f"docs/progress/{artifact_prefix}-schema.sql").write_text("\n\n".join(ddl), encoding="utf-8")
result = {"tables": sorted(declared.tables), "columns": sum(len(table.columns) for table in declared.tables.values()),
          "foreign_keys": sum(len(table.foreign_key_constraints) for table in declared.tables.values()),
          "indexes": sum(len(table.indexes) for table in declared.tables.values()), "schema_differences": differences,
          "migration_execution": False, "database_connections": 0, "tests_executed": False}
(root / f"docs/progress/{artifact_prefix}-schema-evidence.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result))
