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
collision entirely: `scalino setup-ide` (step 5 below) writes a `file_types`
override pinning `.scala` to `Scala (scalino)`, so metals-zed's own
`language_servers.metals` entry (bound to `Scala`) never attaches to those
files, whether or not metals-zed stays installed.

## Install (local dev extension -- not published to Zed's extension gallery)

1. `rustup target add wasm32-wasip2` if you haven't already -- Zed builds
   extensions to a wasm component, and needs that target regardless of which
   extension you're installing. (This repo's own rustc, via Homebrew, does
   not ship it -- use `rustup`.)
2. Build `dist/scalino-lsp` (`../build/08-build-scalino-lsp.sh`, or
   extract a release tarball) and either put `dist/` on your `PATH`, or set
   an explicit path in Zed's `settings.json` (step 4).
3. In Zed: `cmd-shift-p` -> "zed: install dev extension" -> pick this
   directory (`zed-extension/`).
4. Optionally, in Zed's `settings.json`, pin the binary path if it's not on
   `PATH`:
   ```json
   {
     "lsp": {
       "scalino-lsp": {
         "binary": { "path": "/absolute/path/to/dist/scalino-lsp" }
       }
     }
   }
   ```
5. In your Scala project's root, generate the IDE config the server reads on
   `initialize` (`dotty.tools.languageserver.DottyLanguageServer.IDE_CONFIG_FILE`):
   ```
   dist/scalino setup-ide <your sources...>
   ```
   writes `.dotty-ide.json` there, and -- if `.zed/settings.json` doesn't
   already exist -- a `.zed/settings.json` pinning both the LSP binary path
   and the `file_types` override that assigns `.scala` to this extension's
   `Scala (scalino)` language (`../cli/ScalinoCli.scala`'s `setup-ide` command). If
   `.zed/settings.json` already existed, `setup-ide` leaves it alone and
   prints the JSON to add by hand -- merge in both the `lsp` and
   `file_types` keys, or metals-zed (if also installed) keeps claiming
   `.scala` files under the plain `Scala` language.
6. Open the project in Zed. Check `cmd-shift-p` -> "dev: open language
   server logs" if diagnostics/hover don't show up, and this extension's own
   `.scalino-lsp.log` (written to the project root -- see `Log` in
   `vendor/scala3/language-server/src/dotty/tools/languageserver/Main.scala`)
   for a full timestamped trace of every request/notification it handled.

## Scope

Diagnostics, hover, definition, completion, references, rename,
documentSymbol, workspace symbol, implementation, signatureHelp -- whatever
`DottyLanguageServer` itself implements (`vendor/scala3/language-server/`,
trimmed by `patches/scala3-0002-trim-language-server.patch`). No DAP/debug
(the worksheet module that would back it was trimmed as JVM-subprocess-shaped
and out of scope) and no auto-download (`scalino-lsp` isn't published
anywhere generic -- it's this repo's own build output).

Untested against real Zed end-to-end as of this writing -- only verified via
raw stdio process-tree inspection (see `docs/findings.md`). If `initialize`
fails or a request hangs, `dev: open language server logs` is the first
place to look.
