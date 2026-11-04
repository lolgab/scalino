// Zed extension for scalino-lsp (scalino's JVM-free Scala LSP; see /README.md and /docs/findings.md
// "JVM-free language server (LSP)"). Defines its own "Scala (scalino)" language/grammar
// (languages/scala/, copied from https://github.com/scalameta/metals-zed, see
// languages/scala/NOTICE), named differently from metals-zed's "Scala" and scoped to `.scala`
// only (not `.sbt`/`.sc`/`.mill`) so the two never collide; see extension.toml. Registers
// `scalino-lsp` as its only language server.
//
// No DAP support (the worksheet/decompiler modules that would need one were trimmed from the
// server, patches/scala3-0002-trim-language-server.patch) and no auto-download: scalino-lsp is
// this repo's dist/ build output (build/08-build-scalino-lsp.sh, or a release tarball). Resolved
// like any manually installed LSP binary in Zed: on PATH (`worktree.which`), or via
// `lsp.scalino-lsp.binary.path` in settings.json.

use zed_extension_api::{self as zed, settings::LspSettings, Result};

const SERVER_ID: &str = "scalino-lsp";

struct ScalinoLspExtension;

impl zed::Extension for ScalinoLspExtension {
    fn new() -> Self {
        Self
    }

    fn language_server_command(
        &mut self,
        _language_server_id: &zed::LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<zed::Command> {
        let binary_settings =
            LspSettings::for_worktree(SERVER_ID, worktree).ok().and_then(|s| s.binary);

        let path = binary_settings
            .as_ref()
            .and_then(|b| b.path.clone())
            .or_else(|| worktree.which(SERVER_ID))
            .ok_or_else(|| {
                format!(
                    "{SERVER_ID} not found on PATH -- build it (build/08-build-scalino-lsp.sh, \
                     or use a release tarball's dist/{SERVER_ID}) and either add dist/ to PATH \
                     or set `lsp.{SERVER_ID}.binary.path` in settings.json"
                )
            })?;

        let args = binary_settings
            .as_ref()
            .and_then(|b| b.arguments.clone())
            .unwrap_or_else(|| vec!["-stdio".to_string()]);

        let mut env = worktree.shell_env();
        if let Some(extra_env) = binary_settings.and_then(|b| b.env) {
            env.extend(extra_env);
        }

        Ok(zed::Command {
            command: path,
            args,
            env,
        })
    }

    fn language_server_initialization_options(
        &mut self,
        _language_server_id: &zed::LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<Option<zed::serde_json::Value>> {
        Ok(LspSettings::for_worktree(SERVER_ID, worktree)
            .ok()
            .and_then(|s| s.initialization_options))
    }
}

zed::register_extension!(ScalinoLspExtension);
