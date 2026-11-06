// Copies the repository LICENSE into the package before `npm pack`,
// so the tarball ships the Apache 2.0 text.
import { copyFileSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const src = join(root, "..", "LICENSE");
if (existsSync(src)) copyFileSync(src, join(root, "LICENSE"));
