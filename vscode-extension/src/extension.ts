// VS Code extension for scalino-lsp (scalino's JVM-free Scala LSP -- see
// /README.md and /docs/findings.md "JVM-free language server (LSP)").
// Attaches to `.scala` files by document selector, independent of whatever
// grammar/language contribution owns the "scala" language id -- unlike Zed
// (see ../zed-extension/README.md), VS Code merges multiple extensions'
// `languages` contributions for the same id instead of picking one, so
// there's no collision to dodge if Metals is also installed. If both are
// installed and both attach, expect duplicate diagnostics/hover -- disable
// one for the workspace.
//
// No DAP support and no auto-download of `scalino-lsp` itself, same as the
// Zed extension: it isn't published anywhere generic, it's this repo's own
// dist/ build output (build/08-build-scalino-lsp.sh, or a release tarball).
// Resolved via `scalino-lsp.path` setting, falling back to a PATH lookup.

import * as fs from "fs";
import * as path from "path";
import * as zlib from "zlib";
import * as vscode from "vscode";
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
} from "vscode-languageclient/node";

const SERVER_ID = "scalino-lsp";

let client: LanguageClient | undefined;

function isExecutable(file: string): boolean {
  try {
    fs.accessSync(file, fs.constants.X_OK);
    return true;
  } catch {
    return false;
  }
}

function findOnPath(name: string): string | undefined {
  const pathEnv = process.env.PATH ?? "";
  const exts = process.platform === "win32" ? [".exe", ".cmd", ".bat", ""] : [""];
  for (const dir of pathEnv.split(path.delimiter)) {
    if (!dir) continue;
    for (const ext of exts) {
      const candidate = path.join(dir, name + ext);
      if (isExecutable(candidate)) return candidate;
    }
  }
  return undefined;
}

function resolveServerPath(config: vscode.WorkspaceConfiguration): string {
  const configured = config.get<string>("path", "").trim();
  if (configured) return configured;

  const onPath = findOnPath(SERVER_ID);
  if (onPath) return onPath;

  throw new Error(
    `${SERVER_ID} not found on PATH -- build it (build/08-build-scalino-lsp.sh, ` +
      `or use a release tarball's dist/${SERVER_ID}) and either add dist/ to PATH ` +
      `or set "scalino-lsp.path" in settings.json`,
  );
}

// Minimal read-only zip reader (central directory + raw inflate, no zip64)
// so `jar:file:///x-sources.jar!/pkg/X.scala` locations returned by
// scalino-lsp open straight from the jar -- no extracted copies on disk.
interface ZipEntry {
  method: number;
  compressedSize: number;
  localHeaderOffset: number;
}
interface ZipIndex {
  mtimeMs: number;
  buf: Buffer;
  entries: Map<string, ZipEntry>;
}
const zipCache = new Map<string, ZipIndex>();

function openZip(file: string): ZipIndex {
  const mtimeMs = fs.statSync(file).mtimeMs;
  const hit = zipCache.get(file);
  if (hit && hit.mtimeMs === mtimeMs) return hit;
  const buf = fs.readFileSync(file);
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 22 - 0xffff); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new Error(`not a zip file: ${file}`);
  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  const entries = new Map<string, ZipEntry>();
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) break;
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    entries.set(buf.toString("utf8", p + 46, p + 46 + nameLen), {
      method: buf.readUInt16LE(p + 10),
      compressedSize: buf.readUInt32LE(p + 20),
      localHeaderOffset: buf.readUInt32LE(p + 42),
    });
    p += 46 + nameLen + extraLen + commentLen;
  }
  const index = { mtimeMs, buf, entries };
  zipCache.set(file, index);
  return index;
}

function readZipEntry(file: string, name: string): string {
  const { buf, entries } = openZip(file);
  const e = entries.get(name);
  if (!e) throw new Error(`${name} not found in ${file}`);
  const h = e.localHeaderOffset;
  const start = h + 30 + buf.readUInt16LE(h + 26) + buf.readUInt16LE(h + 28);
  const data = buf.subarray(start, start + e.compressedSize);
  return (e.method === 0 ? data : zlib.inflateRawSync(data)).toString("utf8");
}

class JarContentProvider implements vscode.TextDocumentContentProvider {
  provideTextDocumentContent(uri: vscode.Uri): string {
    // uri.path is "file:///abs/x-sources.jar!/pkg/X.scala" (already decoded).
    const sep = uri.path.indexOf("!/");
    if (sep < 0) return "";
    let jar = uri.path.slice(0, sep).replace(/^file:\/\//, "");
    if (/^\/[A-Za-z]:/.test(jar)) jar = jar.slice(1);
    try {
      return readZipEntry(jar, uri.path.slice(sep + 2));
    } catch (err) {
      return `// scalino-lsp: cannot read ${uri.toString()}: ${(err as Error).message}\n`;
    }
  }
}

export function activate(context: vscode.ExtensionContext): void {
  context.subscriptions.push(
    vscode.workspace.registerTextDocumentContentProvider("jar", new JarContentProvider()),
  );
  const config = vscode.workspace.getConfiguration(SERVER_ID);

  let command: string;
  try {
    command = resolveServerPath(config);
  } catch (err) {
    vscode.window.showErrorMessage((err as Error).message);
    return;
  }

  const args = config.get<string[]>("args", ["-stdio"]);
  const extraEnv = config.get<Record<string, string>>("env", {});

  const serverOptions: ServerOptions = {
    command,
    args,
    options: { env: { ...process.env, SCALINO_LSP_JAR_URIS: "1", ...extraEnv } },
  };

  const clientOptions: LanguageClientOptions = {
    documentSelector: [
      { scheme: "file", pattern: "**/*.scala" },
      // Dependency sources opened via go-to-definition: hover, further
      // go-to-definition, etc. keep working from inside them.
      { scheme: "jar", pattern: "**/*.scala" },
    ],
    initializationOptions: config.get("initializationOptions"),
    synchronize: {
      fileEvents: vscode.workspace.createFileSystemWatcher("**/*.scala"),
    },
  };

  client = new LanguageClient(SERVER_ID, "Scalino LSP", serverOptions, clientOptions);
  client.start();
}

export function deactivate(): Thenable<void> | undefined {
  return client?.stop();
}
