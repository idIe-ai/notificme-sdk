const INSPECT = Symbol.for("nodejs.util.inspect.custom");

/**
 * Free text typed (or dictated) by a human in the notific.me app.
 *
 * It is **untrusted input**: never feed it to a shell, SQL, `eval`, an HTML
 * page or an LLM prompt as instructions. The wrapper makes that explicit in
 * the type system: read the raw string with `.text` on purpose, or use
 * `.quoted()` to embed it as a JSON string literal (e.g. inside a prompt,
 * clearly marked as data).
 *
 * `String(x)` and `JSON.stringify(x)` return the raw text.
 */
export class UntrustedText {
  /** The raw text exactly as the human wrote it. Treat it as data only. */
  readonly text: string;

  constructor(text: string) {
    this.text = text;
    Object.freeze(this);
  }

  /** Length in UTF-16 code units. */
  get length(): number {
    return this.text.length;
  }

  /** The text as a JSON string literal, e.g. `"Only after 10 pm"`. */
  quoted(): string {
    return JSON.stringify(this.text);
  }

  toString(): string {
    return this.text;
  }

  toJSON(): string {
    return this.text;
  }

  [INSPECT](): string {
    return `UntrustedText(${this.quoted()})`;
  }

  /** Type guard. */
  static is(value: unknown): value is UntrustedText {
    return value instanceof UntrustedText;
  }
}
