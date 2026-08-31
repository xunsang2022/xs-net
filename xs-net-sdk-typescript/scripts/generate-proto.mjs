import { mkdir, readdir } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const sdkRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const protoRoot = resolve(sdkRoot, "proto");
const output = resolve(sdkRoot, "generated");
const protoc = process.env.PROTOC ?? join(process.env.USERPROFILE ?? "", ".m2/repository/com/google/protobuf/protoc/3.25.5/protoc-3.25.5-windows-x86_64.exe");
const plugin = resolve(sdkRoot, "node_modules/.bin/protoc-gen-ts_proto" + (process.platform === "win32" ? ".cmd" : ""));
const protoFiles = (await readdir(protoRoot)).filter((file) => file.endsWith(".proto")).sort();

await mkdir(output, { recursive: true });
const args = [
  `--proto_path=${protoRoot}`,
  `--plugin=protoc-gen-ts_proto=${plugin}`,
  `--ts_proto_out=${output}`,
  "--ts_proto_opt=esModuleInterop=true,forceLong=number,useOptionals=messages,outputServices=generic-definitions",
  ...protoFiles
];
const result = spawnSync(protoc, args, { cwd: protoRoot, stdio: "inherit", shell: process.platform === "win32" });
if (result.error) throw new Error(`Unable to run protoc: ${result.error.message}`);
if (result.status !== 0) throw new Error(`protoc exited with code ${result.status}`);
