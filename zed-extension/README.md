# Scalino for Zed

A Zed extension for `scalino-lsp`, scalino's language server. The server is a
native binary, so nothing here needs a JVM. What it can do is listed under
[Editor support](../README.md#editor-support) in the main README.

## Why the language is called "Scala (scalino)"

The extension brings its own language definition and grammar
(`languages/scala/`, copied from
[metals-zed](https://github.com/scalameta/metals-zed), see
`languages/scala/NOTICE`). The language is named `Scala (scalino)` and covers
only `.scala` files, not `.sbt`, `.sc` or `.mill`.

It used to be named `Scala`, like metals-zed's. With both extensions installed
Zed then picked one of the two definitions for `.scala` files, and which one
could change after a dev extension reinstall. With a name of its own you decide:
a `file_types` override in `settings.json` (step 2 below) pins `.scala` to
`Scala (scalino)`, and metals-zed's `metals` server, which is bound to `Scala`,
never attaches to those files.

## Install

The extension is submitted to Zed's gallery
([zed-industries/extensions#8480](https://github.com/zed-industries/extensions/pull/8480)).
Until it is merged, install it as a dev extension from a clone of this repo:
`cmd-shift-p`, "zed: install dev extension", pick the `zed-extension/`
directory. Zed builds it with Rust, so `rustup` must be installed.

That gets you the grammar and the wiring. It does not download the server, so:

1. Put `scalino-lsp` on your `PATH`. `install.sh` links it next to `scalino`.
   If you built scalino from source, the binary is `dist/scalino-lsp`.
2. In Zed's `settings.json`, set the binary path if it is not on `PATH`, and
   add the `file_types` override if metals-zed is installed too:
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
3. In the project root, write the config the server reads at startup:
   ```
   scalino setup-ide <your sources...>
   ```
   This creates `.scalino-build/scalino-lsp.json` and touches nothing else.
4. Open the project in Zed.

### As a dev extension

To try a change to the extension itself, install it from this directory. Zed
compiles a dev extension's wasm locally and needs the `wasm32-wasip2` target
for that. A Homebrew `rustc` doesn't ship it, so use `rustup`:

```
rustup target add wasm32-wasip2
```

Then `cmd-shift-p`, "zed: install dev extension", and pick `zed-extension/`.

## When something is missing

Run `cmd-shift-p`, "dev: open language server logs". That is the place to start
if `initialize` fails or a request hangs. The server keeps its own log too, in
`.scalino-build/scalino-lsp.log` in the project root, with a timestamp for
every request and notification.

## Inlay hints

Each kind of hint has its own switch, and all are on by default:
`inferredTypes`, `typeParameters`, `implicitArguments`, `implicitConversions`,
`byNameParameters`, `namedParameters`, `hintsInPatternMatch`.

In Zed they go under `lsp.scalino-lsp.settings`:

```json
{
  "lsp": {
    "scalino-lsp": {
      "settings": { "inlayHints": { "inferredTypes": { "enable": false } } }
    }
  }
}
```

## What it doesn't do

- Debugging. There is no DAP support.
- Download `scalino-lsp`. Install scalino first.

The server has been tested end to end in Zed. The bugs that turned up are
listed in [`../docs/findings.md`](../docs/findings.md), under "JVM-free language
server (LSP)".
