"""Static orchestrator evidence only: parse source, compare hashes and write paths."""

import ast
import difflib
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[3]
artifacts = Path(__file__).resolve().parent
python_files = sorted((root / "app").rglob("*.py")) + [root / "main.py"]
for path in python_files:
    ast.parse(path.read_text(encoding="utf-8-sig"), filename=str(path))
launch = json.loads((root / ".vscode/launch.json").read_text(encoding="utf-8-sig"))
baseline = json.loads((artifacts / "baseline.json").read_text(encoding="utf-8-sig"))
protected = json.loads((artifacts / "protected-baseline.json").read_text(encoding="utf-8-sig"))
changed = []
protected_changed = []
for entry in baseline:
    if hashlib.sha256((root / entry["Path"]).read_bytes()).hexdigest().upper() != entry["SHA256"]:
        changed.append(entry["Path"])
for entry in protected:
    if hashlib.sha256((root / entry["Path"]).read_bytes()).hexdigest().upper() != entry["SHA256"]:
        protected_changed.append(entry["Path"])
reserved = {
    "app/main.py", "app/config.py", "app/telegram/service.py", "app/ai/system_prompt.md",
    "main.py", ".env.example", "README.md", ".vscode/launch.json", "reports/TASK-041.md",
}
writes = set()
commands = []
for log in artifacts.glob("TASK-041*events.jsonl"):
    log_bytes = log.read_bytes()
    log_encoding = "utf-16" if log_bytes.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig"
    for line in log_bytes.decode(log_encoding).splitlines():
        event = json.loads(line)
        item = event.get("item", {})
        if item.get("type") == "file_change":
            for change in item.get("changes", []):
                path = Path(change["path"])
                try:
                    writes.add(path.relative_to(root).as_posix())
                except ValueError:
                    writes.add(str(path))
        if event.get("type") == "item.completed" and item.get("type") == "command_execution":
            commands.append(item.get("command", ""))
diffs = []
for name in changed:
    old = artifacts / name.replace("/", "_")
    if old.exists():
        diffs.extend(difflib.unified_diff(
            old.read_text(encoding="utf-8-sig").splitlines(keepends=True),
            (root / name).read_text(encoding="utf-8-sig").splitlines(keepends=True),
            fromfile="baseline/" + name, tofile="current/" + name,
        ))
(artifacts / "reviewed-source.diff").write_text("".join(diffs), encoding="utf-8")
result = {
    "python_files_parsed": len(python_files),
    "launch_configurations": [c["name"] for c in launch["configurations"]],
    "changed_baseline_paths": changed,
    "protected_changed_paths": protected_changed,
    "worker_write_paths": sorted(writes),
    "worker_allowlist_violations": sorted(writes - reserved),
    "worker_command_count": len(commands),
    "runtime_tests": "not run by policy",
}
(artifacts / "static-review.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps(result, indent=2))
