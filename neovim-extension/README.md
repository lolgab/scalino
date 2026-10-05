# scalino-lsp (Neovim)

Wires `dist/scalino-lsp` (`../README.md`, `../docs/findings.md` "JVM-free
language server (LSP)") up as a Neovim LSP client for `.scala` files -- no
JVM required to run it.

Unlike [`zed-extension/`](../zed-extension/) and
[`vscode-extension/`](../vscode-extension/), there's no packaged extension
here: Neovim's own LSP client (`vim.lsp`, 0.11+) is generic enough that
[`lua/scalino-lsp.lua`](lua/scalino-lsp.lua) is just a client config, not
extension code. If you're on Neovim < 0.11, wire it up through
[nvim-lspconfig](https://github.com/neovim/nvim-lspconfig) instead (see
below) -- `vim.lsp.config`/`vim.lsp.enable` aren't available there.

## Install (Neovim 0.11+)

1. Get `scalino-lsp` onto your `PATH`: `../install.sh` symlinks it there
   alongside `scalino`. Building from source instead
   (`../build/08-build-scalino-lsp.sh`, output at `../dist/scalino-lsp`)? Put
   `dist/` on `PATH`, or pass an explicit `path` (step 3) -- this falls back
   to a plain PATH lookup (`vim.fn.executable`) when no explicit path is
   given.
2. In your Scala project's root, generate the IDE config the server reads on
   `initialize` (`dotty.tools.languageserver.DottyLanguageServer.IDE_CONFIG_FILE`):
   ```
   dist/scalino setup-ide <your sources...>
   ```
   writes `.scalino-build/scalino-lsp.json` there (`../cli/ScalinoCli.scala`'s
   `setup-ide` command).
3. In your `init.lua`:
   ```lua
   require("scalino-lsp").setup({
     -- path = "/absolute/path/to/dist/scalino-lsp", -- only if not on PATH
   })
   ```
   Either put this repo's `neovim-extension/` on your `runtimepath` (e.g.
   `vim.opt.rtp:append("/path/to/scalino/neovim-extension")` before the
   `require`), copy `lua/scalino-lsp.lua` into your own config's `lua/`
   directory, or point a plugin manager at this local directory -- with
   [lazy.nvim](https://github.com/folke/lazy.nvim):
   ```lua
   {
     dir = "/path/to/scalino/neovim-extension",
     name = "scalino-lsp",
     ft = "scala",
     config = function()
       require("scalino-lsp").setup({
         -- path = "/absolute/path/to/dist/scalino-lsp", -- only if not on PATH
         -- Inlay hint toggles (all on by default): inferredTypes, typeParameters,
         -- implicitArguments, implicitConversions, byNameParameters,
         -- namedParameters, hintsInPatternMatch
         -- settings = { inlayHints = { inferredTypes = { enable = false } } },
       })
     end,
   }
   ```
   `dir` (not the usual `"user/repo"` string) tells lazy.nvim this is a local
   plugin, not something to clone -- there's nowhere to clone it from, same
   as the caveat in "Scope" below. `ft = "scala"` lazy-loads it only for
   Scala buffers.
4. Open a `.scala` file. `:LspInfo` (or `:checkhealth vim.lsp`) confirms
   `scalino_lsp` attached; this repo's own `.scalino-build/scalino-lsp.log`
   (written to the project root -- see `Log` in
   `../vendor/scala3/language-server/src/dotty/tools/languageserver/Main.scala`)
   has a full timestamped trace of every request/notification it handled if
   diagnostics/hover don't show up.

## Install (nvim-lspconfig, Neovim < 0.11)

`nvim-lspconfig` doesn't ship a `scalino_lsp` config, so define one yourself
before calling `setup`:

```lua
local configs = require("lspconfig.configs")
if not configs.scalino_lsp then
  configs.scalino_lsp = {
    default_config = {
      cmd = { "scalino-lsp", "-stdio" }, -- or an absolute path
      filetypes = { "scala" },
      root_dir = require("lspconfig.util").root_pattern(".scalino-build", "build.sbt", ".git"),
    },
  }
end
require("lspconfig").scalino_lsp.setup({})
```

Steps 1-2 above (PATH + `setup-ide`) still apply.

## Scope

Diagnostics, hover, definition, completion, references, rename,
documentSymbol, workspace symbol, implementation, signatureHelp -- whatever
`DottyLanguageServer` itself implements (`../vendor/scala3/language-server/`,
trimmed by `../patches/scala3-0002-trim-language-server.patch`). No
DAP/debug (the worksheet module that would back it was trimmed as
JVM-subprocess-shaped and out of scope) and no auto-download of
`scalino-lsp` itself -- it isn't published anywhere generic, it's this
repo's own build output.

If both this and Metals attach to the same `.scala` file (e.g. another
`nvim-metals`/`nvim-jdtls`-style setup already in your config), expect
duplicate diagnostics/hover -- disable one for the workspace, same caveat as
the VS Code extension.
