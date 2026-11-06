// Builds the SDK tarball into dist-pack/ (npm pack), plus dist-pack/latest.json
// with the file name and version. The notific.me web app serves this tarball
// as a download in the customer area.
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const out = join(root, "dist-pack");
rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });

// Uses the npm running this script (npm_execpath), without a shell.
const npmCli = process.env.npm_execpath;
const stdout = npmCli
  ? execFileSync(process.execPath, [npmCli, "pack", "--json", "--pack-destination", out], { cwd: root, encoding: "utf8" })
  : execFileSync(process.platform === "win32" ? "npm.cmd" : "npm", ["pack", "--json", "--pack-destination", out], {
      cwd: root,
      encoding: "utf8",
      shell: process.platform === "win32",
    });
const [info] = JSON.parse(stdout);
const pkg = JSON.parse(readFileSync(join(root, "package.json"), "utf8"));
const manifest = {
  name: pkg.name,
  version: pkg.version,
  filename: info.filename,
  size: info.size,
  integrity: info.integrity,
  shasum: info.shasum,
};
writeFileSync(join(out, "latest.json"), JSON.stringify(manifest, null, 2) + "\n");
console.log(`dist-pack/${info.filename} (${info.size} bytes)`);
