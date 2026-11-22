# Scalino for Neovim

Neovim setup for `scalino-lsp`, scalino's language server. The server is a
native binary, so nothing here needs a JVM. What it can do is listed under
[Editor support](../README.md#editor-support) in the main README.

This is not a packaged plugin like the [Zed](../zed-extension/) and
[VS Code](../vscode-extension/) extensions. Neovim has an LSP client built in,
so all it takes is a client config:
[`lua/scalino-lsp.lua`](lua/scalino-lsp.lua), for `vim.lsp` in Neovim 0.11 and
later. Older Neovims have no `vim.lsp.config` or `vim.lsp.enable`; use
[nvim-lspconfig](https://github.com/neovim/nvim-lspconfig) there, as shown
[below](#neovim-before-011).

## Install (Neovim 0.11+)

1. Put `scalino-lsp` on your `PATH`. `install.sh` links it next to `scalino`.
   If you built scalino from source, the binary is `dist/scalino-lsp`. You can
   also pass its path in step 3.
2. In the project root, write the config the server reads at startup:
   ```
   scalino setup-ide <your sources...>
   ```
   This creates `.scalino-build/scalino-lsp.json`.
3. In your `init.lua`:
   ```lua
   require("scalino-lsp").setup({
     -- path = "/absolute/path/to/dist/scalino-lsp", -- only if not on PATH
   })
   ```
   Neovim has to find the module. Any of these works:
   - add this directory to the runtimepath before the `require`:
     `vim.opt.rtp:append("/path/to/scalino/neovim-extension")`
   - copy `lua/scalino-lsp.lua` into your own config's `lua/` directory
   - point a plugin manager at the directory

   With [lazy.nvim](https://github.com/folke/lazy.nvim):
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
   `dir` tells lazy.nvim this is a local plugin with nothing to clone, and
   `ft = "scala"` loads it only for Scala buffers.
4. Open a `.scala` file. `:LspInfo` (or `:checkhealth vim.lsp`) shows whether
   `scalino_lsp` attached.

## Neovim before 0.11

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

Steps 1 and 2 above still apply.

## When something is missing

Read `.scalino-build/scalino-lsp.log` in the project root. The server writes
every request and notification there, with a timestamp.

If Metals (through `nvim-metals`, say) attaches to the same `.scala` file, you
get every diagnostic and hover twice. Disable one of the two for the workspace.

## What it doesn't do

- Debugging. There is no DAP support.
- Download `scalino-lsp`. Install scalino first.
