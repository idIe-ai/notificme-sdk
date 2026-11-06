import { isPlainObject } from "./util";

/** `dedupKey` → `dedup_key`. Keys already in snake_case are kept. */
export function toSnakeKey(key: string): string {
  return key.replace(/([a-z0-9])([A-Z])/g, "$1_$2").replace(/([A-Z])([A-Z][a-z])/g, "$1_$2").toLowerCase();
}

/** `dedup_key` → `dedupKey`. */
export function toCamelKey(key: string): string {
  return key.replace(/_([a-z0-9])/g, (_, c: string) => c.toUpperCase());
}

/**
 * Converts object keys recursively. Values under opaque keys (free-form user
 * data such as `payload`) are copied untouched.
 */
function convertKeys(value: unknown, convert: (k: string) => string, opaque: ReadonlySet<string>): unknown {
  if (Array.isArray(value)) return value.map((v) => convertKeys(v, convert, opaque));
  if (!isPlainObject(value)) return value;
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(value)) {
    if (v === undefined) continue;
    const nk = convert(k);
    out[nk] = opaque.has(k) || opaque.has(nk) ? v : convertKeys(v, convert, opaque);
  }
  return out;
}

/** Keys whose values are user data and are never converted. */
const OPAQUE = new Set(["payload", "onTimeout", "on_timeout", "value", "details"]);

/** camelCase → snake_case (deep), leaving user data (`payload`, `value`…) as is. */
export function toSnake<T = unknown>(value: unknown): T {
  return convertKeys(value, toSnakeKey, OPAQUE) as T;
}

/** snake_case → camelCase (deep), leaving user data (`payload`, `value`…) as is. */
export function toCamel<T = unknown>(value: unknown): T {
  return convertKeys(value, toCamelKey, OPAQUE) as T;
}
