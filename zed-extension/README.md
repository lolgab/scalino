# dotty-lsp-native (Zed extension)

Registers `dist/dotty-lsp-native` (`../README.md`, `../docs/findings.md`
"JVM-free language server (LSP)") as the language server for Zed's `Scala`
language -- no JVM required to run it. This extension defines the `Scala`
language/grammar itself (`languages/scala/`, copied from
[metals-zed](https://github.com/scalameta/metals-zed) -- see
`languages/scala/NOTICE`), so **installing only this extension is enough**:
no metals-zed, no JVM-backed Metals, nothing to disable. Do not also install
metals-zed -- both extensions would define the same `Scala` language for the
same file extensions, which Zed doesn't support cleanly.

## Install (local dev extension -- not published to Zed's extension gallery)

1. `rustup target add wasm32-wasip2` if you haven't already -- Zed builds
   extensions to a wasm component, and needs that target regardless of which
   extension you're installing. (This repo's own rustc, via Homebrew, does
   not ship it -- use `rustup`.)
2. Build `dist/dotty-lsp-native` (`../build/08-build-lsp-native.sh`, or
   extract a release tarball) and either put `dist/` on your `PATH`, or set
   an explicit path in Zed's `settings.json` (step 4).
3. In Zed: `cmd-shift-p` -> "zed: install dev extension" -> pick this
   directory (`zed-extension/`).
4. Optionally, in Zed's `settings.json`, pin the binary path if it's not on
   `PATH`:
   ```json
   {
     "lsp": {
       "dotty-lsp-native": {
         "binary": { "path": "/absolute/path/to/dist/dotty-lsp-native" }
       }
     }
   }
   ```
5. In your Scala project's root, generate the IDE config the server reads on
   `initialize` (`dotty.tools.languageserver.DottyLanguageServer.IDE_CONFIG_FILE`):
   ```
   dist/scli setup-ide <your sources...>
   ```
   writes `.dotty-ide.json` there (`../cli/Scli.scala`'s `setup-ide` command).
6. Open the project in Zed. Check `cmd-shift-p` -> "dev: open language
   server logs" if diagnostics/hover don't show up.

## Scope

Diagnostics, hover, definition, completion, references, rename,
documentSymbol, workspace symbol, implementation, signatureHelp -- whatever
`DottyLanguageServer` itself implements (`vendor/scala3/language-server/`,
trimmed by `patches/scala3-0002-trim-language-server.patch`). No DAP/debug
(the worksheet module that would back it was trimmed as JVM-subprocess-shaped
and out of scope) and no auto-download (`dotty-lsp-native` isn't published
anywhere generic -- it's this repo's own build output).

Untested against real Zed end-to-end as of this writing -- only verified via
raw stdio process-tree inspection (see `docs/findings.md`). If `initialize`
fails or a request hangs, `dev: open language server logs` is the first
place to look.
