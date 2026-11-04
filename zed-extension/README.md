# scalino-lsp (Zed extension)

Registers `dist/scalino-lsp` as the language server for its own `Scala
(scalino)` language. No JVM is needed to run it. See
[`../README.md`](../README.md#editor-support) and `../docs/findings.md`
("JVM-free language server (LSP)").

The extension defines the language and grammar itself (`languages/scala/`,
copied from [metals-zed](https://github.com/scalameta/metals-zed), see
`languages/scala/NOTICE`). The language has a different name from metals-zed's
`Scala` and covers only `.scala` files (not `.sbt`/`.sc`/`.mill`). It used to
reuse the name `Scala`, but if metals-zed is also installed, Zed picks one of
the two language definitions for `.scala` files and the choice changes after a
dev extension reinstall. A distinct name avoids that: you pin `.scala` to
`Scala (scalino)` with a `file_types` override in `settings.json` (step 2), so
metals-zed's `language_servers.metals` entry (bound to `Scala`) never attaches to
those files.

## Install

The extension is in Zed's gallery as **Scalino**: `cmd-shift-p` -> "zed:
extensions" -> search "Scalino" -> install. This installs only the grammar and
language server wiring. `scalino-lsp` itself is not downloaded (see "Scope"), so
steps 1-4 are still required.

To install as a local dev extension instead (for example to test a change to the
extension): run `rustup target add wasm32-wasip2` (Zed builds the dev extension's
wasm locally and needs that target; a Homebrew rustc does not ship it, use
`rustup`), then in Zed run `cmd-shift-p` -> "zed: install dev extension" and
pick `zed-extension/`.

1. Put `scalino-lsp` on your `PATH`. `install.sh` symlinks it next to `scalino`.
   If you built from source (`../build/08-build-scalino-lsp.sh`, output
   `dist/scalino-lsp`), put `dist/` on `PATH` or set the path in step 2. With no
   path configured the extension uses Zed's `worktree.which` (`src/lib.rs`).
2. In Zed's `settings.json`, set the binary path if it is not on `PATH` and, if
   metals-zed is also installed, the `file_types` override that routes `.scala`
   to `Scala (scalino)`:
   ```json
   {
     "lsp": {
       "scalino-lsp": {
         "binary": { "path": "/absolute/path/to/dist/scalino-lsp" }
       }
     },
     "file_types": {
       "Scala (scalino)": ["scala"]
     }
   }
   ```
3. In the project root, generate the config the server reads on `initialize`
   (`DottyLanguageServer.IDE_CONFIG_FILE`):
   ```
   dist/scalino setup-ide <your sources...>
   ```
   This writes `.scalino-build/scalino-lsp.json` (`setup-ide` in
   `../cli/ScalinoCli.scala`). It does not touch editor settings.
4. Open the project in Zed. If diagnostics or hover are missing, run
   `cmd-shift-p` -> "dev: open language server logs". The server also writes
   `.scalino-build/scalino-lsp.log` in the project root, a timestamped trace of
   every request and notification (`Log` in
   `vendor/scala3/language-server/src/dotty/tools/languageserver/Main.scala`).

## Inlay hint toggles

Each hint kind can be switched off individually (all on by default):
`inferredTypes`, `typeParameters`, `implicitArguments`, `implicitConversions`,
`byNameParameters`, `namedParameters`, `hintsInPatternMatch`. The server reads
them as `inlayHints.<kind>.enable` from `initializationOptions` and
`workspace/didChangeConfiguration`, and asks the client to refresh hints when
they change.

In Zed, set them under `lsp.scalino-lsp.settings`:
```json
{
  "lsp": {
    "scalino-lsp": {
      "settings": { "inlayHints": { "inferredTypes": { "enable": false } } }
    }
  }
}
```

## Scope

The extension offers what `DottyLanguageServer` implements
(`vendor/scala3/language-server/`, trimmed by
`patches/scala3-0002-trim-language-server.patch`): diagnostics, hover,
definition, completion, references, rename, document and workspace symbols,
implementation, signature help.

- No DAP/debug: the worksheet module that would back it was trimmed.
- No auto-download of `scalino-lsp`: it is not published anywhere generic.

It has been tested end to end in Zed; `docs/findings.md` ("JVM-free language
server (LSP)") lists the bugs found that way. If `initialize` fails or a request
hangs, start with `dev: open language server logs`.
