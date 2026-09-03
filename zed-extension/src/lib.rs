// Zed extension for dotty-lsp-native (scala-native-compiler's JVM-free Scala
// LSP -- see /README.md and /docs/findings.md "JVM-free language server
// (LSP)"). Registers a second `dotty-lsp-native` server on the existing
// "Scala" language (grammar/file-association provided by the official Scala
// extension, https://github.com/scalameta/metals-zed, which this crate
// takes its shape from) rather than a JVM-backed `metals`.
//
// No DAP support (the worksheet/decompiler modules that would need one were
// trimmed from the server -- see patches/scala3-0002-trim-language-server.patch)
// and no auto-download: dotty-lsp-native isn't published anywhere generic,
// it's this repo's own dist/ build output (build/08-build-lsp-native.sh, or
// a release tarball). Resolved the same way any other manually-installed
// LSP binary is in Zed: on PATH (`worktree.which`), or pinned explicitly via
// `lsp.dotty-lsp-native.binary.path` in settings.json.

use zed_extension_api::{self as zed, settings::LspSettings, Result};

const SERVER_ID: &str = "dotty-lsp-native";

struct DottyLspNativeExtension;

impl zed::Extension for DottyLspNativeExtension {
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
                    "{SERVER_ID} not found on PATH -- build it (build/08-build-lsp-native.sh, \
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

zed::register_extension!(DottyLspNativeExtension);
