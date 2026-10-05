# scalino-lsp (VS Code extension)

Registers `dist/scalino-lsp` (`../README.md`, `../docs/findings.md`
"JVM-free language server (LSP)") as the language server for `.scala`
files -- no JVM required to run it. Attaches by document selector
(`**/*.scala`), not by claiming the `scala` language id exclusively: unlike
Zed (`../zed-extension/README.md`), VS Code merges multiple extensions'
`languages` contributions for the same id instead of arbitrarily picking
one, so there's no naming collision to dodge here even if Metals is also
installed. If both extensions are active on the same workspace, expect
duplicate diagnostics/hover from both servers -- disable one for the
workspace (`cmd/ctrl-shift-p` -> "Extensions: Disable (Workspace)").

## Install

Not yet published to the VS Code Marketplace. Package and install locally:

```
cd vscode-extension
npm install
npm run compile
npx vsce package
code --install-extension scalino-lsp-0.0.1.vsix
```

Either way, `scalino-lsp` itself is still your own build, not
auto-downloaded (see "Scope" below) -- steps 1-3 below are still required.

1. Get `scalino-lsp` onto your `PATH`: `install.sh` symlinks it there
   alongside `scalino` (see `../install.sh`). Building from source instead
   (`../build/08-build-scalino-lsp.sh`, output at `dist/scalino-lsp`)? Put
   `dist/` on `PATH`, or set an explicit path in step 2 -- this extension
   falls back to its own PATH lookup (`findOnPath`, `src/extension.ts`) when
   no explicit path is configured.
2. Optionally, in the workspace's `.vscode/settings.json`, pin the binary
   path if it's not on `PATH`:
   ```json
   {
     "scalino-lsp.path": "/absolute/path/to/dist/scalino-lsp"
   }
   ```
3. In your Scala project's root, generate the IDE config the server reads
   on `initialize`
   (`dotty.tools.languageserver.DottyLanguageServer.IDE_CONFIG_FILE`):
   ```
   dist/scalino setup-ide <your sources...>
   ```
   writes `.scalino-build/scalino-lsp.json` there (`../cli/ScalinoCli.scala`'s
   `setup-ide` command) -- editor config (step 2) is separate and this
   command doesn't touch it.
4. Open the project in VS Code. Check the "Scalino" output channel
   (`View` -> `Output`, pick "Scalino" from the dropdown) if
   diagnostics/hover don't show up -- set `scalino-lsp.trace.server` to
   `"verbose"` first for a full trace of every request/notification, and
   this extension's own `.scalino-build/scalino-lsp.log` (written to the
   project root -- see `Log` in
   `vendor/scala3/language-server/src/dotty/tools/languageserver/Main.scala`).

## Inlay hint toggles

Each hint kind can be switched off individually (all on by default): `inferredTypes`,
`typeParameters`, `implicitArguments`, `implicitConversions`, `byNameParameters`,
`namedParameters`, `hintsInPatternMatch`. The server reads them as
`inlayHints.<kind>.enable` from `initializationOptions` and from
`workspace/didChangeConfiguration`, and asks the client to refresh hints when they change.

In VS Code these are the `scalino-lsp.inlayHints.<kind>.enable` settings
(Settings UI: search "scalino inlay").

## Scope

Diagnostics, hover, definition, completion, references, rename,
documentSymbol, workspace symbol, implementation, signatureHelp -- whatever
`DottyLanguageServer` itself implements (`vendor/scala3/language-server/`,
trimmed by `patches/scala3-0002-trim-language-server.patch`). No DAP/debug
(the worksheet module that would back it was trimmed as JVM-subprocess-shaped
and out of scope) and no auto-download of `scalino-lsp` itself -- unlike the
extension, it isn't published anywhere generic, it's this repo's own build
output. No bundled grammar either: syntax highlighting for `.scala` comes
from whatever TextMate grammar is already installed (e.g. Metals, or a
plain Scala syntax extension) -- this extension only wires up the language
server.
