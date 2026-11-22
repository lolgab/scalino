# Scalino for VS Code

A VS Code client for `scalino-lsp`, scalino's language server. The server is a
native binary, so nothing here needs a JVM. What it can do is listed under
[Editor support](../README.md#editor-support) in the main README.

The extension attaches to `**/*.scala` by document selector and does not claim
the `scala` language id. VS Code merges `languages` contributions from several
extensions, so there is no fight with Metals over who owns Scala files (Zed is
different, see [`../zed-extension/`](../zed-extension/)). If both are active in
a workspace you get every diagnostic and hover twice. Turn one of them off with
"Extensions: Disable (Workspace)" from the command palette.

## Install

The extension is not on the Marketplace. Package it and install the `.vsix`:

```
cd vscode-extension
npm install
npm run compile
npx vsce package
code --install-extension scalino-lsp-0.0.7.vsix
```

It does not download the server, so:

1. Put `scalino-lsp` on your `PATH`. `install.sh` links it next to `scalino`.
   If you built scalino from source, the binary is `dist/scalino-lsp`. To use
   one that is not on `PATH`, point the extension at it in
   `.vscode/settings.json`:
   ```json
   {
     "scalino-lsp.path": "/absolute/path/to/dist/scalino-lsp"
   }
   ```
2. Open your project. The server reads `.scalino-build/scalino-lsp.json`, and
   when a workspace folder has none the extension offers to run
   `scalino setup-ide .` for you. You can also run it yourself:
   ```
   scalino setup-ide <your sources...>
   ```
   It only writes that file. Your editor settings are not touched.

## When something is missing

Look at the "Scalino" output channel (`View` -> `Output`). Setting
`scalino-lsp.trace.server` to `"verbose"` traces every request and
notification. The server keeps its own log too, in
`.scalino-build/scalino-lsp.log` in the project root.

## Inlay hints

Each kind of hint has its own switch, and all are on by default:
`inferredTypes`, `typeParameters`, `implicitArguments`, `implicitConversions`,
`byNameParameters`, `namedParameters`, `hintsInPatternMatch`.

In VS Code they are the `scalino-lsp.inlayHints.<kind>.enable` settings. Search
for "scalino inlay" in the Settings UI. Changes apply without a restart.

## What it doesn't do

- Debugging. There is no DAP support.
- Download `scalino-lsp`. Install scalino first.
- Syntax highlighting. There is no bundled grammar, so colours come from
  whatever TextMate grammar you have installed: Metals, or a plain Scala syntax
  extension.
