# scalino-lsp (VS Code extension)

Registers `dist/scalino-lsp` as the language server for `.scala` files. No JVM
is needed to run it. See [`../README.md`](../README.md#editor-support) and
`../docs/findings.md` ("JVM-free language server (LSP)").

The extension attaches by document selector (`**/*.scala`) and does not claim
the `scala` language id. VS Code merges `languages` contributions from several
extensions, so unlike Zed ([`../zed-extension/`](../zed-extension/)) there is no
collision with Metals. If both are active in a workspace you get duplicate
diagnostics and hover; disable one with `cmd/ctrl-shift-p` -> "Extensions:
Disable (Workspace)".

## Install

The extension is not on the VS Code Marketplace. Package and install it locally:

```
cd vscode-extension
npm install
npm run compile
npx vsce package
code --install-extension scalino-lsp-0.0.7.vsix
```

`scalino-lsp` itself is not downloaded by the extension (see "Scope"), so steps
1-3 are still required.

1. Put `scalino-lsp` on your `PATH`. `install.sh` symlinks it next to `scalino`.
   If you built from source (`../build/08-build-scalino-lsp.sh`, output
   `dist/scalino-lsp`), put `dist/` on `PATH` or set the path in step 2. With no
   path configured the extension looks it up on `PATH` (`findOnPath` in
   `src/extension.ts`).
2. Optional: if it is not on `PATH`, set the path in `.vscode/settings.json`:
   ```json
   {
     "scalino-lsp.path": "/absolute/path/to/dist/scalino-lsp"
   }
   ```
3. In the project root, generate the config the server reads on `initialize`
   (`DottyLanguageServer.IDE_CONFIG_FILE`):
   ```
   dist/scalino setup-ide <your sources...>
   ```
   This writes `.scalino-build/scalino-lsp.json` (`setup-ide` in
   `../cli/ScalinoCli.scala`). It does not touch editor settings.
4. Open the project in VS Code. If diagnostics or hover are missing, check the
   "Scalino" output channel (`View` -> `Output`). Set `scalino-lsp.trace.server`
   to `"verbose"` to trace every request and notification. The server also
   writes `.scalino-build/scalino-lsp.log` in the project root (`Log` in
   `vendor/scala3/language-server/src/dotty/tools/languageserver/Main.scala`).

## Inlay hint toggles

Each hint kind can be switched off individually (all on by default):
`inferredTypes`, `typeParameters`, `implicitArguments`, `implicitConversions`,
`byNameParameters`, `namedParameters`, `hintsInPatternMatch`. The server reads
them as `inlayHints.<kind>.enable` from `initializationOptions` and
`workspace/didChangeConfiguration`, and asks the client to refresh hints when
they change.

In VS Code these are the `scalino-lsp.inlayHints.<kind>.enable` settings
(Settings UI: search "scalino inlay").

## Scope

The extension offers what `DottyLanguageServer` implements
(`vendor/scala3/language-server/`, trimmed by
`patches/scala3-0002-trim-language-server.patch`): diagnostics, hover,
definition, completion, references, rename, document and workspace symbols,
implementation, signature help.

- No DAP/debug: the worksheet module that would back it was trimmed.
- No auto-download of `scalino-lsp`: it is not published anywhere generic.
- No bundled grammar: syntax highlighting comes from whatever TextMate grammar
  is installed (Metals, or a plain Scala syntax extension).
