-- Neovim wiring for scalino-lsp (scalino's JVM-free Scala LSP -- see
-- ../README.md and ../docs/findings.md "JVM-free language server (LSP)").
--
-- Unlike the vscode-extension/ and zed-extension/ directories, this isn't a
-- packaged plugin -- Neovim's own LSP client (vim.lsp, 0.11+) is generic
-- enough that no extension code is needed, just a client config. Source
-- this file from your init.lua, or copy it into your config and adjust.
--
-- No auto-download of `scalino-lsp` itself, same as the other two: it isn't
-- published anywhere generic, it's this repo's own dist/ build output
-- (build/08-build-scalino-lsp.sh, or a release tarball).

local M = {}

local function is_executable(path)
  return vim.fn.executable(path) == 1
end

-- Mirrors vscode-extension/src/extension.ts's resolveServerPath: an explicit
-- path wins, otherwise fall back to a PATH lookup.
local function resolve_cmd(opts)
  opts = opts or {}
  if opts.path and opts.path ~= "" then
    if not is_executable(opts.path) then
      vim.notify(
        "scalino-lsp: configured path is not executable: " .. opts.path,
        vim.log.levels.ERROR
      )
    end
    return opts.path
  end

  if is_executable("scalino-lsp") then
    return "scalino-lsp"
  end

  vim.notify(
    "scalino-lsp not found on PATH -- build it (build/08-build-scalino-lsp.sh, "
      .. "or use a release tarball's dist/scalino-lsp) and either add dist/ to "
      .. "PATH or pass { path = \"/absolute/path/to/scalino-lsp\" } to setup()",
    vim.log.levels.ERROR
  )
  return "scalino-lsp"
end

-- scalino-lsp exits itself after SCALINO_LSP_IDLE_TIMEOUT_MINUTES of
-- inactivity (default 30) to avoid piling up hanging processes -- see
-- vendor/scala3/language-server/src/dotty/tools/languageserver/Main.scala.
-- That's a clean exit (code 0), which Neovim's client treats as a
-- deliberate stop rather than a crash, so it won't reattach on its own the
-- way it would after a crash-restart. Re-`start` the client for every
-- still-open scala buffer so editing resumes without needing to close and
-- reopen the buffer.
local function reattach_open_buffers(cfg)
  vim.schedule(function()
    for _, buf in ipairs(vim.api.nvim_list_bufs()) do
      if vim.api.nvim_buf_is_loaded(buf) and vim.bo[buf].filetype == "scala" then
        vim.lsp.start(cfg, { bufnr = buf })
      end
    end
  end)
end

-- scalino-lsp reports dependency sources as
-- `jar:file:///abs/x-sources.jar!/pkg/X.scala` (see SCALINO_LSP_JAR_URIS
-- below) so they're read straight from the jar instead of an extracted copy.
-- Neovim has no handler for that scheme: read the entry with `unzip -p`
-- into a read-only scratch buffer.
local function setup_jar_reader()
  local group = vim.api.nvim_create_augroup("scalino_lsp_jar", { clear = true })
  vim.api.nvim_create_autocmd("BufReadCmd", {
    group = group,
    pattern = "jar:*",
    callback = function(args)
      local name = args.match
      local sep = name:find("!/", 1, true)
      if not sep then
        return
      end
      local jar = vim.uri_to_fname(name:sub(5, sep - 1))
      local entry = vim.uri_decode(name:sub(sep + 2))
      if vim.fn.executable("unzip") ~= 1 then
        vim.notify("scalino-lsp: `unzip` not found on PATH, cannot open " .. name, vim.log.levels.ERROR)
        return
      end
      local res = vim.system({ "unzip", "-p", jar, entry }, { text = true }):wait()
      if res.code ~= 0 then
        vim.notify(
          "scalino-lsp: cannot read " .. entry .. " from " .. jar .. ": " .. (res.stderr or ""),
          vim.log.levels.ERROR
        )
        return
      end
      local buf = args.buf
      vim.bo[buf].buftype = "nofile"
      vim.api.nvim_buf_set_lines(buf, 0, -1, false, vim.split(res.stdout or "", "\n", { plain = true }))
      vim.bo[buf].modifiable = false
      vim.bo[buf].modified = false
      vim.bo[buf].filetype = "scala"
    end,
  })
end

-- scalino-lsp can't build a compiler without `.scalino-build/scalino-lsp.json`.
-- When a scala buffer's root lacks it, offer to run `scalino setup-ide .`
-- there and restart the client afterwards. Also exposed as :ScalinoSetupIde.
local IDE_CONFIG_FILE = ".scalino-build/scalino-lsp.json"

local function resolve_scalino(server_cmd)
  local sibling = vim.fs.joinpath(vim.fs.dirname(server_cmd), "scalino")
  if server_cmd:find("/", 1, true) and is_executable(sibling) then
    return sibling
  end
  if is_executable("scalino") then
    return "scalino"
  end
end

local function run_setup_ide(server_cmd, root, cfg)
  local scalino = resolve_scalino(server_cmd)
  if not scalino then
    vim.notify("scalino not found next to scalino-lsp or on PATH", vim.log.levels.ERROR)
    return
  end
  vim.notify("scalino setup-ide: running in " .. root)
  vim.system({ scalino, "setup-ide", "." }, { cwd = root, text = true }, function(res)
    vim.schedule(function()
      if res.code ~= 0 then
        vim.notify("scalino setup-ide failed: " .. (res.stderr or ""), vim.log.levels.ERROR)
        return
      end
      vim.notify("scalino setup-ide: done, restarting scalino_lsp")
      for _, c in ipairs(vim.lsp.get_clients({ name = "scalino_lsp" })) do
        c:stop()
      end
      -- on_exit re-attaches open scala buffers; cover the no-client case.
      reattach_open_buffers(cfg)
    end)
  end)
end

local function setup_ide_prompt(server_cmd, cfg)
  local group = vim.api.nvim_create_augroup("scalino_lsp_setup_ide", { clear = true })
  local asked = {}
  local function check(bufnr, force)
    local name = vim.api.nvim_buf_get_name(bufnr)
    if name == "" or name:find("^jar:") then
      return
    end
    local root = vim.fs.root(bufnr, { ".scalino-build", "build.sbt", ".git" })
    if not root or vim.uv.fs_stat(vim.fs.joinpath(root, IDE_CONFIG_FILE)) then
      return
    end
    if asked[root] and not force then
      return
    end
    asked[root] = true
    vim.ui.select(
      { "Run scalino setup-ide", "Not now" },
      { prompt = "scalino-lsp: no " .. IDE_CONFIG_FILE .. " in " .. root },
      function(choice)
        if choice == "Run scalino setup-ide" then
          run_setup_ide(server_cmd, root, cfg)
        end
      end
    )
  end
  vim.api.nvim_create_autocmd("FileType", {
    group = group,
    pattern = "scala",
    callback = function(args)
      check(args.buf, false)
    end,
  })
  vim.api.nvim_create_user_command("ScalinoSetupIde", function()
    check(vim.api.nvim_get_current_buf(), true)
  end, {})
end

--- @param opts table|nil { path?: string, args?: string[], env?: table }
function M.setup(opts)
  opts = opts or {}
  local cmd = { resolve_cmd(opts), unpack(opts.args or { "-stdio" }) }

  local cfg = {
    cmd = cmd,
    filetypes = { "scala" },
    root_dir = function(bufnr, on_dir)
      if vim.api.nvim_buf_get_name(bufnr):find("^jar:") then
        -- Read-only dependency source (above): reuse the running client's
        -- root so hover/go-to-definition work from inside it. No client
        -- yet => no project to attach to.
        local c = vim.lsp.get_clients({ name = "scalino_lsp" })[1]
        if c and c.root_dir then
          on_dir(c.root_dir)
        end
        return
      end
      local root = vim.fs.root(bufnr, { ".scalino-build", "build.sbt", ".git" })
      if root then
        on_dir(root)
      end
    end,
    cmd_env = vim.tbl_extend("force", { SCALINO_LSP_JAR_URIS = "1" }, opts.env or {}),
  }
  setup_jar_reader()
  cfg.on_exit = function(_, _, _)
    reattach_open_buffers(cfg)
  end

  setup_ide_prompt(cmd[1], cfg)

  vim.lsp.config("scalino_lsp", cfg)
  vim.lsp.enable("scalino_lsp")
end

return M
