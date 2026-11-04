# scalino-lsp (Neovim)

Wires `dist/scalino-lsp` up as a Neovim LSP client for `.scala` files. No JVM
is needed to run it. See [`../README.md`](../README.md#editor-support) and
`../docs/findings.md` ("JVM-free language server (LSP)").

Unlike [`zed-extension/`](../zed-extension/) and
[`vscode-extension/`](../vscode-extension/), this is not a packaged extension:
[`lua/scalino-lsp.lua`](lua/scalino-lsp.lua) is a client config for Neovim's
built-in `vim.lsp` (0.11+). On Neovim < 0.11, `vim.lsp.config` and
`vim.lsp.enable` do not exist; use
[nvim-lspconfig](https://github.com/neovim/nvim-lspconfig) (see below).

## Install (Neovim 0.11+)

1. Put `scalino-lsp` on your `PATH`. `../install.sh` symlinks it next to
   `scalino`. If you built from source (`../build/08-build-scalino-lsp.sh`,
   output `../dist/scalino-lsp`), put `dist/` on `PATH` or pass `path` in step 3.
   Without `path`, the config looks it up on `PATH` (`vim.fn.executable`).
2. In the project root, generate the config the server reads on `initialize`
   (`DottyLanguageServer.IDE_CONFIG_FILE`):
   ```
   dist/scalino setup-ide <your sources...>
   ```
   This writes `.scalino-build/scalino-lsp.json` (`setup-ide` in
   `../cli/ScalinoCli.scala`).
3. In your `init.lua`:
   ```lua
   require("scalino-lsp").setup({
     -- path = "/absolute/path/to/dist/scalino-lsp", -- only if not on PATH
   })
   ```
   Make the module loadable in one of three ways: append this repo's
   `neovim-extension/` to the runtimepath
   (`vim.opt.rtp:append("/path/to/scalino/neovim-extension")` before the
   `require`), copy `lua/scalino-lsp.lua` into your config's `lua/` directory,
   or point a plugin manager at the directory. With
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
   `dir` marks a local plugin that lazy.nvim should not clone (there is no
   remote to clone from). `ft = "scala"` loads it only for Scala buffers.
4. Open a `.scala` file. `:LspInfo` (or `:checkhealth vim.lsp`) shows whether
   `scalino_lsp` attached. If diagnostics or hover are missing, read
   `.scalino-build/scalino-lsp.log` in the project root, a timestamped trace of
   every request and notification (`Log` in
   `../vendor/scala3/language-server/src/dotty/tools/languageserver/Main.scala`).

## Install (nvim-lspconfig, Neovim < 0.11)

`nvim-lspconfig` has no `scalino_lsp` config, so define one before calling
`setup`:

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

Steps 1-2 above (`PATH` and `setup-ide`) still apply.

## Scope

The server offers what `DottyLanguageServer` implements
(`../vendor/scala3/language-server/`, trimmed by
`../patches/scala3-0002-trim-language-server.patch`): diagnostics, hover,
definition, completion, references, rename, document and workspace symbols,
implementation, signature help.

- No DAP/debug: the worksheet module that would back it was trimmed.
- No auto-download of `scalino-lsp`: it is not published anywhere generic.

If this and Metals (for example `nvim-metals`) attach to the same `.scala` file,
you get duplicate diagnostics and hover; disable one for the workspace.
