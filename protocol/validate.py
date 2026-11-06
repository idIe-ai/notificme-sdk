"""Checks the protocol schemas and validates every example against its schema.

Examples are named ``<name>.<schema>.json`` (e.g. ``container-caiu.event.json``
→ ``schemas/event.schema.json``). Cross-schema ``$ref``s are resolved locally.

    pip install jsonschema referencing
    python protocol/validate.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

ROOT = Path(__file__).parent


def main() -> int:
    schemas = {p.name.removesuffix(".schema.json"): json.loads(p.read_text("utf-8"))
               for p in sorted((ROOT / "schemas").glob("*.schema.json"))}
    registry: Registry = Registry()
    for name, schema in schemas.items():
        Draft202012Validator.check_schema(schema)
        registry = registry.with_resource(schema["$id"], Resource.from_contents(schema))
        print(f"ok  schemas/{name}.schema.json")

    failed = 0
    for path in sorted((ROOT / "examples").glob("*.json")):
        kind = path.name.removesuffix(".json").rsplit(".", 1)[-1]
        schema = schemas.get(kind)
        if schema is None:
            print(f"ERR examples/{path.name}: no schema named {kind!r}")
            failed += 1
            continue
        validator = Draft202012Validator(schema, registry=registry)
        errors = sorted(validator.iter_errors(json.loads(path.read_text("utf-8"))), key=str)
        for err in errors:
            print(f"ERR examples/{path.name}: {'/'.join(map(str, err.absolute_path))}: {err.message}")
        failed += bool(errors)
        if not errors:
            print(f"ok  examples/{path.name}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
