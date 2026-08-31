import { cp, mkdir, readdir, rm } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const target = resolve(root, "xs-net-sdk-typescript/proto");
const sources = [
  resolve(root, "game-server/game-message-action/src/main/proto"),
  resolve(root, "xs-net/xs-net-message/src/main/proto")
];

await mkdir(target, { recursive: true });
for (const entry of await readdir(target)) {
  if (entry.endsWith(".proto")) await rm(resolve(target, entry));
}
for (const source of sources) {
  for (const entry of await readdir(source)) {
    if (entry.endsWith(".proto")) await cp(resolve(source, entry), resolve(target, entry));
  }
}
