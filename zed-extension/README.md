# scalino-lsp (Zed extension)

Registers `dist/scalino-lsp` (`../README.md`, `../docs/findings.md`
"JVM-free language server (LSP)") as the language server for its own
`Scala (scalino)` language -- no JVM required to run it. This extension
defines that language/grammar itself (`languages/scala/`, copied from
[metals-zed](https://github.com/scalameta/metals-zed) -- see
`languages/scala/NOTICE`), under a name deliberately *different* from
metals-zed's own `Scala` language, and scoped to only `.scala` files (not
`.sbt`/`.sc`/`.mill`, unlike metals-zed's own language). Earlier this reused
the name `Scala` directly, on the theory that installing only this
extension would be enough -- in practice, if metals-zed is *also* installed
(even just left over from before), Zed has to arbitrarily pick one
extension's language definition for `.scala` files, and that pick isn't
stable across a dev extension reinstall. A distinct name sidesteps the
collision entirely: pin `.scala` to `Scala (scalino)` yourself via a
`file_types` override in Zed's `settings.json` (step 2 below), so
metals-zed's own `language_servers.metals` entry (bound to `Scala`) never
attaches to those files, whether or not metals-zed stays installed.

## Install

Published to Zed's extension gallery as **Scalino**: `cmd-shift-p` ->
"zed: extensions" -> search "Scalino" -> install. This only installs the
extension (grammar + language server wiring) -- `scalino-lsp` itself is
still your own build, not auto-downloaded (see "Scope" below), so steps 1-4
below are still required either way.

To instead install as a local dev extension (e.g. to test a change to this
extension itself before it lands in the gallery): `rustup target add
wasm32-wasip2` if you haven't already -- Zed builds a dev extension's wasm
locally, and needs that target regardless of which extension you're
installing (this repo's own rustc, via Homebrew, does not ship it -- use
`rustup`) -- then in Zed: `cmd-shift-p` -> "zed: install dev extension" ->
pick this directory (`zed-extension/`).

1. Get `scalino-lsp` onto your `PATH`: `install.sh` symlinks it there
   alongside `scalino` (see `../install.sh`). Building from source instead
   (`../build/08-build-scalino-lsp.sh`, output at `dist/scalino-lsp`)? Put
   `dist/` on `PATH`, or set an explicit path in Zed's `settings.json` (step
   2) -- this extension falls back to Zed's own PATH lookup
   (`worktree.which`, `src/lib.rs`) when no explicit path is configured.
2. In Zed's `settings.json`, pin the binary path if it's not on `PATH`, and
   -- if metals-zed is also installed -- the `file_types` override that
   routes `.scala` to this extension's `Scala (scalino)` language instead of
   metals-zed's `Scala`:
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
3. In your Scala project's root, generate the IDE config the server reads on
   `initialize` (`dotty.tools.languageserver.DottyLanguageServer.IDE_CONFIG_FILE`):
   ```
   dist/scalino setup-ide <your sources...>
   ```
   writes `.scalino-build/scalino-lsp.json` there (`../cli/ScalinoCli.scala`'s
   `setup-ide` command) -- editor config (step 2) is separate and this
   command doesn't touch it.
4. Open the project in Zed. Check `cmd-shift-p` -> "dev: open language
   server logs" if diagnostics/hover don't show up, and this extension's own
   `.scalino-build/scalino-lsp.log` (written to the project root -- see `Log` in
   `vendor/scala3/language-server/src/dotty/tools/languageserver/Main.scala`)
   for a full timestamped trace of every request/notification it handled.

## Scope

Diagnostics, hover, definition, completion, references, rename,
documentSymbol, workspace symbol, implementation, signatureHelp -- whatever
`DottyLanguageServer` itself implements (`vendor/scala3/language-server/`,
trimmed by `patches/scala3-0002-trim-language-server.patch`). No DAP/debug
(the worksheet module that would back it was trimmed as JVM-subprocess-shaped
and out of scope) and no auto-download of `scalino-lsp` itself -- unlike the extension, it
isn't published anywhere generic, it's this repo's own build output.

Verified against real Zed end-to-end (see `docs/findings.md`'s "JVM-free
language server (LSP)" section for the bugs that surfaced this way and how
they were fixed). If `initialize` fails or a request hangs, `dev: open
language server logs` is the first place to look.
