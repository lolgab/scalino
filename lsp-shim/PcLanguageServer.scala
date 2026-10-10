package dotty.tools
package languageserver

import java.io.File
import java.net.URI
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

import dotty.tools.pc.RawScalaPresentationCompiler
import scala.meta.internal.metals.{CompilerInlayHintsParams, CompilerOffsetParams, CompilerRangeParams, CompilerVirtualFileParams}
import scala.meta.internal.pc.{PcReferencesRequest, SemanticTokens => PcSemanticTokens}
import scala.meta.pc.{CodeActionId, OffsetParams, VirtualFileParams}
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import org.eclipse.lsp4j as l
import scala.util.control.NonFatal

import com.github.plokhotnyuk.jsoniter_scala.core._
import Lsp._
import Lsp.given

import scala.scalanative.unsafe.{extern, CInt}

/** Immix GC hook added by patches/scala-native-0071: opts this process into
 *  evacuating (compacting) collections unless `SCALANATIVE_EVAC_ENABLE` is
 *  set explicitly. */
@extern private object GcTuning {
  def scalanative_gc_set_evac_default(enabled: CInt): Unit = extern
}

/** Thin LSP layer over `scala3-presentation-compiler` (Metals' PC engine),
 *  reusing `Main.scala`'s existing JSON-RPC transport and `Lsp.scala`'s
 *  existing jsoniter-scala wire model -- no lsp4j, no Gson, no reflection,
 *  the same "no reflection anywhere" design the old (now removed)
 *  `DottyLanguageServer` backend used. Real lsp4j/mtags-interfaces/
 *  mtags-shared types are satisfied by the in-tree shim at `lsp-shim/` (not
 *  real jars).
 *
 *  Scope: completion/hover/definition/typeDefinition/references/rename/
 *  prepareRename/documentHighlight/signatureHelp/selectionRange/inlayHint/
 *  semanticTokens/codeAction + didOpen/didChange/didClose diagnostics.
 *  presentation-compiler has no documentSymbol/workspaceSymbol/implementation
 *  equivalent (those come from Metals' own BSP-driven indexer, not the PC
 *  itself) -- `Main.scala`'s dispatch just doesn't wire those methods.
 */
class PcLanguageServer(publishDiagnostics: (String, List[Lsp.Diagnostic]) => Unit) { thisServer =>

  /** One entry per project in `.scalino-build/scalino-lsp.json` (e.g. an
   *  app's main and test scopes, later one per platform). Each owns its own
   *  presentation compiler, built lazily on the first request for one of its
   *  files -- a second PC is a second symbol table, so a project nobody
   *  touches costs nothing. */
  private final class Project(
      val id: String,
      val config: ProjectConfig,
      /** Directories whose direct children are this project's own sources. */
      val sourceDirs: Seq[Path],
      /** `sourceDirs` plus those of every project this one (transitively)
       *  depends on: dependencies are typechecked from source, no class
       *  output needed. */
      val sourcePathDirs: Seq[Path]) {
    var pc: RawScalaPresentationCompiler = null
    var factory: () => RawScalaPresentationCompiler = null
    var completionsSincePc = 0
    var pendingRecycle: java.util.concurrent.ScheduledFuture[?] = null
  }
  /** Empty until the config is loaded; never empty afterwards. */
  private var projects: List[Project] = Nil
  private var warmupError: Throwable = null
  /** Set when the workspace has no `.scalino-build/scalino-lsp.json`, i.e. it
   *  never ran `scalino setup-ide`. Not an error: the server stays idle and
   *  answers every request with an empty result, so a project that doesn't
   *  use scalino doesn't get a toast per request. */
  @volatile private var unconfigured = false

  /** Serialized request body; empty result when the workspace is unconfigured. */
  private def guarded[A](empty: => A)(body: => A): A =
    thisServer.synchronized {
      // A request racing the warmup thread must learn whether the workspace
      // is configured before deciding (requirePc waits for the same signal).
      val deadline = System.currentTimeMillis() + 180000
      while (projects.isEmpty && warmupError == null && !unconfigured && System.currentTimeMillis() < deadline)
        thisServer.wait(deadline - System.currentTimeMillis())
      if (unconfigured) empty else body
    }
  private val buffers: mutable.Map[URI, String] = mutable.Map.empty

  /** `didChange` used to recompile+publish synchronously on every keystroke:
   *  typing a few chars queued up that many full compiles behind the LSP's
   *  single lock, so diagnostics shown mid-burst lagged several keystrokes
   *  behind (stale errors for seconds after the fix was already typed), even
   *  though any *individual* compile is fast. Debounce so only the text from
   *  the last edit in a burst gets compiled. */
  private val diagnosticsDebounceMillis = 250L
  private val diagnosticsScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r => {
    val t = new Thread(r, "pc-diagnostics-debounce")
    t.setDaemon(true)
    t
  })
  private val bufferVersions: mutable.Map[URI, Long] = mutable.Map.empty
  private val pendingDiagnostics: mutable.Map[URI, java.util.concurrent.ScheduledFuture[?]] = mutable.Map.empty

  /** Same on-disk config the old `DottyLanguageServer` backend used to read
   *  (`scalino setup-ide`'s output). */
  private def loadConfig(clientRootUri: String): List[ProjectConfig] = {
    val IDE_CONFIG_FILE = ".scalino-build/scalino-lsp.json"
    // Some clients (e.g. neovim with no detected root) send `rootUri: null`.
    val rootUri =
      if (clientRootUri != null) clientRootUri
      else Paths.get("").toAbsolutePath.toUri.toString.stripSuffix("/")
    val configFile = new File(new URI(rootUri + '/' + IDE_CONFIG_FILE))
    if (!configFile.exists)
      throw new java.io.FileNotFoundException(
        s"$IDE_CONFIG_FILE not found at $rootUri -- run `scalino setup-ide <sources...>` in the project root first")
    readFromArray(Files.readAllBytes(configFile.toPath))(using projectConfigListCodec)
  }

  /** Per-kind inlay hint toggles, named like Metals' `inlayHints.*.enable`
   *  settings. All on by default. */
  private val InlayHintSettingKeys = List(
    "inferredTypes", "typeParameters", "implicitArguments", "implicitConversions",
    "byNameParameters", "namedParameters", "hintsInPatternMatch")
  @volatile private var inlayHintToggles: Map[String, Boolean] =
    InlayHintSettingKeys.map(_ -> true).toMap

  /** Applies `inlayHints.<kind>.enable` flags from `initializationOptions` /
   *  `workspace/didChangeConfiguration` (flattened to dotted paths, see
   *  `Lsp.readBooleanPaths`). A path matches when it equals the key or ends
   *  with `.<key>`, so clients may prefix it with their own section name
   *  (`scalino-lsp.`, `metals.`, ...). Returns whether any toggle changed. */
  def updateSettings(flags: Map[String, Boolean]): Boolean = {
    val updated = InlayHintSettingKeys.map { kind =>
      val key = s"inlayHints.$kind.enable"
      val value = flags.collectFirst { case (path, v) if path == key || path.endsWith("." + key) => v }
      kind -> value.getOrElse(inlayHintToggles(kind))
    }.toMap
    val changed = updated != inlayHintToggles
    inlayHintToggles = updated
    changed
  }

  def initialize(rootUri: String): InitializeResult = thisServer.synchronized {
    // The PC's per-completion garbage fragments Immix badly (RSS reached
    // GBs); compacting collections keep the committed heap near the live set.
    GcTuning.scalanative_gc_set_evac_default(1)
    val capabilities = ServerCapabilities(
      textDocumentSync = 1, // Full
      documentHighlightProvider = true,
      documentSymbolProvider = false,
      definitionProvider = true,
      renameProvider = true,
      hoverProvider = true,
      workspaceSymbolProvider = false,
      referencesProvider = true,
      implementationProvider = false,
      completionProvider = CompletionOptions(triggerCharacters = List(".")),
      signatureHelpProvider = SignatureHelpOptions(triggerCharacters = List("(")),
      typeDefinitionProvider = true,
      selectionRangeProvider = true,
      inlayHintProvider = true,
      codeActionProvider = true,
      semanticTokensProvider = SemanticTokensOptions(
        legend = SemanticTokensLegend(
          tokenTypes = PcSemanticTokens.TokenTypes,
          tokenModifiers = PcSemanticTokens.TokenModifiers),
        full = true))

    val warmup = new Thread(() => {
      try {
        val loaded = buildProjects(loadConfig(rootUri))
        thisServer.synchronized {
          projects = loaded
          thisServer.notifyAll()
          // Warm the first project (its PC is what a client's first request
          // most likely needs); the rest are built on demand.
          ensurePc(loaded.head)
          ()
        }
      } catch {
        case ex: java.io.FileNotFoundException if ex.getMessage != null && ex.getMessage.contains(".scalino-build/scalino-lsp.json") =>
          System.err.println(s"scalino-lsp: ${ex.getMessage}; language features disabled for this workspace")
          System.err.flush()
          thisServer.synchronized {
            unconfigured = true
            thisServer.notifyAll()
          }
        case ex: Throwable =>
          System.err.println(s"PC warmup failed: ${ex.getClass.getName}: ${ex.getMessage}")
          ex.printStackTrace()
          System.err.flush()
          // Wake blocked requests so they fail fast with the real cause
          // instead of waiting out the full deadline.
          thisServer.synchronized {
            warmupError = ex
            thisServer.notifyAll()
          }
      }
    })
    warmup.setDaemon(true)
    warmup.start()

    InitializeResult(capabilities)
  }

  /** The config's `sourceDirectories` are the parents of every project
   *  source (`setup-ide` collects them from the expanded file list, which
   *  skips hidden/build dirs). dotc's `-sourcepath` scan is recursive though,
   *  so handing it a dir like the project root also pulls in `.metals/`,
   *  `.scalino-build/.../sources/` (the stdlib's own sources, ->
   *  "duplicate source version import" errors) etc. List each dir's direct
   *  children instead; the PC accepts individual source files as entries. */
  private def sourceFilesIn(dirs: Seq[Path]): Seq[Path] =
    dirs.flatMap { d =>
      Option(d.toFile.listFiles()).toSeq.flatten
        .filter(f => f.isFile && (f.getName.endsWith(".scala") || f.getName.endsWith(".java")))
        .map(_.toPath)
    }.distinct

  private def buildProjects(configs: List[ProjectConfig]): List[Project] = {
    if (configs.isEmpty) throw new IllegalStateException(".scalino-build/scalino-lsp.json lists no projects")
    def absDirs(c: ProjectConfig): Seq[Path] =
      c.sourceDirectories.map(d => Paths.get(d).toAbsolutePath.normalize).distinct
    // Configs written before multi-project support have no `id`.
    val ids = configs.zipWithIndex.map { case (c, i) =>
      Option(c.id).getOrElse(if (configs.size == 1) "main" else s"project-$i")
    }
    val byId = ids.zip(configs).toMap
    def closure(id: String, seen: Set[String]): List[String] =
      if (seen(id)) Nil
      else id :: byId.get(id).toList.flatMap(_.dependsOn).flatMap(closure(_, seen + id))
    ids.zip(configs).map { case (id, c) =>
      val pathDirs = closure(id, Set.empty).distinct.flatMap(i => absDirs(byId(i))).distinct
      new Project(id, c, absDirs(c), pathDirs)
    }
  }

  /** The project a document belongs to: the one listing the file's parent
   *  directory, else the one with the deepest source directory above it
   *  (a file created since `setup-ide` ran), else the project with the
   *  largest classpath (documents with no project of their own, such as
   *  dependency sources opened from a jar -- the broadest classpath is the
   *  one most likely to resolve their imports). */
  private def projectFor(uri: URI): Project = {
    val ps = projects
    val parent: Option[Path] =
      if (uri.getScheme != "file") None
      else try Option(Paths.get(uri).toAbsolutePath.normalize.getParent) catch { case NonFatal(_) => None }
    parent.flatMap { dir =>
      ps.find(_.sourceDirs.contains(dir)).orElse {
        val above = for (p <- ps; d <- p.sourceDirs if dir.startsWith(d)) yield (p, d.getNameCount)
        above.sortBy(-_._2).headOption.map(_._1)
      }
    }.getOrElse(ps.maxBy(_.config.dependencyClasspath.size))
  }

  private def ensurePc(project: Project): RawScalaPresentationCompiler = {
    if (project.pc == null) {
      val cfg = project.config
      // Includes `-javabootclasspath ...` -- without it dotc's own
      // Definitions.init() can't find java.lang.Object at all.
      // `-color:never`: dotc's reporter output reaches editors' plain-text
      // log panes (VS Code Output), which don't render ANSI escapes.
      val factory = () => RawScalaPresentationCompiler(
        buildTargetIdentifier = project.id,
        classpath = cfg.dependencyClasspath.distinct.map(Paths.get(_)),
        options = cfg.compilerArguments.distinct :+ "-color:never",
        // Without this, sourcePathMode defaults to DISABLED and sourcePath
        // below is plumbed through but never used: the InteractiveDriver
        // only ever typechecks files the editor explicitly opens
        // (didOpen/didChange), so a symbol defined in a sibling source file
        // that hasn't been opened yet fails to resolve -- looks exactly
        // like "not all files in the source dirs get compiled".
        config = scala.meta.internal.pc.PresentationCompilerConfigImpl(
          _sourcePathMode = scala.meta.pc.SourcePathMode.FULL
        ),
        sourcePath = () => sourceFilesIn(project.sourcePathDirs).asJava
      )
      project.factory = factory
      project.pc = factory()
    }
    project.pc
  }

  /** Every `complete` call makes the PC build a throwaway `InteractiveDriver`
   *  (see `RawScalaPresentationCompiler.complete`), a few MB of garbage per
   *  call. Scala Native's Immix heap fragments under that churn and its RSS
   *  only ever grows, reaching GBs in a long editing session. Recycling the
   *  PC (fresh driver; the old one becomes garbage together with its symbol
   *  table and caches) bounds it: when the server has been idle for
   *  `pcRecycleIdleMillis` after `pcRecycleMinCompletions` completions, or
   *  hard-capped at `pcRecycleMaxCompletions` regardless of idleness. */
  private val pcRecycleIdleMillis = 15000L
  private val pcRecycleMinCompletions = 50
  private val pcRecycleMaxCompletions = 400

  private def recyclePc(project: Project): Unit = {
    if (project.factory == null || project.pc == null) return
    val fresh = project.factory()
    // Warm the new driver with the project's open buffers so the next request
    // doesn't pay for typechecking them from scratch (results are discarded;
    // diagnostics for unchanged text are already published).
    buffers.foreach { case (uri, text) =>
      if (projectFor(uri) eq project)
        try fresh.didChange(CompilerVirtualFileParams(uri, text))
        catch { case NonFatal(_) => () }
    }
    project.pc = fresh
    project.completionsSincePc = 0
    // Let the GC (evacuating, when enabled) reclaim the old PC's heap now
    // rather than at the next allocation-triggered cycle.
    System.gc()
  }

  private def noteCompletion(uri: URI): Unit = {
    val project = projectFor(uri)
    project.completionsSincePc += 1
    if (project.pendingRecycle != null) project.pendingRecycle.cancel(false)
    if (project.completionsSincePc >= pcRecycleMaxCompletions) recyclePc(project)
    else {
      val task: Runnable = () => thisServer.synchronized {
        if (project.completionsSincePc >= pcRecycleMinCompletions) recyclePc(project)
      }
      project.pendingRecycle =
        diagnosticsScheduler.schedule(task, pcRecycleIdleMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
  }

  /** Warmup (classpath resolution + PC construction) runs on a daemon thread
   *  started from `initialize()` -- requests that land before it finishes
   *  (e.g. an editor's first definition request right after startup) block
   *  here instead of failing outright. 30s was too tight: observed real PC
   *  warmup (classpath scanning over ~20+ real jars) taking anywhere from
   *  ~1s (warm page cache) to several minutes (cold). */
  private def requirePc(uri: URI): RawScalaPresentationCompiler = thisServer.synchronized {
    val deadline = System.currentTimeMillis() + 180000
    while (projects.isEmpty && warmupError == null && System.currentTimeMillis() < deadline)
      thisServer.wait(deadline - System.currentTimeMillis())
    if (projects.isEmpty && warmupError != null)
      throw new IllegalStateException(s"presentation compiler failed to initialize: ${warmupError.getMessage}", warmupError)
    if (projects.isEmpty) throw new IllegalStateException("presentation compiler not yet initialized")
    ensurePc(projectFor(uri))
  }

  /** Convert an `Lsp.Position` (line/character) to a raw text offset --
   *  presentation-compiler's `OffsetParams` always wants a plain `Int`
   *  offset, computed from whatever full text the client last sent (no
   *  driver/SourceFile needed, unlike `DottyLanguageServer.sourcePosition`
   *  -- this is pure text math). */
  private def positionToOffset(text: String, pos: Position): Int = {
    var idx = 0
    var line = 0
    while (line < pos.line) {
      val nl = text.indexOf('\n', idx)
      if (nl < 0) return text.length
      idx = nl + 1
      line += 1
    }
    math.min(idx + pos.character, text.length)
  }

  private def textOf(uri: URI): String =
    buffers.getOrElse(uri, throw new IllegalStateException(s"no open buffer for $uri"))

  private def offsetParams(uri: URI, pos: Position): CompilerOffsetParams = {
    val text = textOf(uri)
    CompilerOffsetParams(uri, text, positionToOffset(text, pos))
  }

  private def toRange(r: l.Range): Range =
    Range(Position(r.getStart().getLine(), r.getStart().getCharacter()), Position(r.getEnd().getLine(), r.getEnd().getCharacter()))

  private def toLocation(l0: l.Location): Location =
    Location(clientUri(l0.getUri()), toRange(l0.getRange()))

  /** The compiler reports dependency sources as
   *  `jar:file:///x-sources.jar!/pkg/X.scala` (read in memory, see
   *  patches/scala3-0014). Clients that can open such URIs (VS Code/Neovim
   *  extensions in this repo set `SCALINO_LSP_JAR_URIS=1`) get them as-is;
   *  for any other client extract the entry to a cache file lazily, only
   *  when a definition result actually points at it. */
  private val jarUris: Boolean = sys.env.get("SCALINO_LSP_JAR_URIS").exists(v => v == "1" || v == "true")
  private val depsSrcRoot: Path =
    Paths.get(System.getProperty("user.home"), ".cache", "scalino", "lsp-deps-src")

  /** Documents the client opened by `jar:` URI (VS Code/Neovim go-to-def
   *  into a dependency, then hover/go-to-def from there). The PC resolves
   *  document URIs through `Paths.get(uri)` in many places, which can't take
   *  `jar:`, so it sees a synthetic never-written `file:` URI instead
   *  (the text always comes from the client's buffer); this maps it back. */
  private val jarDocs = new java.util.concurrent.ConcurrentHashMap[String, String]()

  private def pcUri(raw: String): URI =
    if (!raw.startsWith("jar:")) new URI(raw)
    else {
      // VS Code percent-encodes the opaque part ("jar:file%3A///x.jar%21/..."):
      // normalize to the plain "jar:file:///x.jar!/..." form we hand out.
      val uri = "jar:" + new URI(raw).getSchemeSpecificPart
      val sep = uri.indexOf("!/")
      val entry = if (sep < 0) "Unknown.scala" else new URI("file:///" + uri.substring(sep + 2)).getPath.stripPrefix("/")
      val virtual = depsSrcRoot.resolve("jar-open").resolve(Integer.toHexString(uri.hashCode)).resolve(entry).toUri
      jarDocs.put(virtual.toString, uri)
      virtual
    }

  private def clientUri(uri: String): String =
    if (jarDocs.containsKey(uri)) jarDocs.get(uri)
    else if (jarUris || !uri.startsWith("jar:file:")) uri
    else
      try {
        val sep = uri.indexOf("!/")
        val jar = Paths.get(new URI(uri.substring(4, sep)))
        val entry = new URI("file:///" + uri.substring(sep + 2)).getPath.stripPrefix("/")
        val cached = depsSrcRoot
          .resolve(Integer.toHexString(jar.toString.hashCode))
          .resolve(entry)
        if (!Files.exists(cached)) {
          val zf = new java.util.zip.ZipFile(jar.toFile)
          try {
            val ze = zf.getEntry(entry)
            if (ze == null) return uri
            Files.createDirectories(cached.getParent)
            val tmp = Files.createTempFile(cached.getParent, cached.getFileName.toString, ".tmp")
            try {
              val in = zf.getInputStream(ze)
              try Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
              finally in.close()
              Files.move(tmp, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } finally Files.deleteIfExists(tmp)
          } finally zf.close()
        }
        cached.toUri.toString
      } catch { case NonFatal(_) => uri }

  private def toTextEdit(e: l.TextEdit): TextEdit =
    TextEdit(toRange(e.getRange()), e.getNewText())

  /** Real lsp4j types markup-ish fields as `Either<String, MarkupContent>`
   *  (or similar) since a raw string is also a valid MarkupContent -- unwrap
   *  either branch into a plain `MarkupContent`. */
  private def eitherToMarkupContent(e: JEither[String, l.MarkupContent]): MarkupContent =
    if (e == null) MarkupContent("plaintext", "")
    else if (e.isLeft()) MarkupContent("plaintext", e.getLeft())
    else { val mc = e.getRight(); MarkupContent(mc.getKind(), mc.getValue()) }

  private def eitherToString(e: JEither[String, ?]): String =
    if (e == null) "" else if (e.isLeft()) e.getLeft() else String.valueOf(e.getRight())

  def didOpen(params: DidOpenTextDocumentParams): Unit = guarded(()) {
    val document = params.textDocument
    val uri = pcUri(document.uri)
    buffers(uri) = document.text
    if (!document.uri.startsWith("jar:")) publishDiagnosticsFor(uri, document.uri)
  }

  def didChange(params: DidChangeTextDocumentParams): Unit = guarded(()) {
    val document = params.textDocument
    val uri = pcUri(document.uri)
    val change = params.contentChanges.head
    assert(change.range.isEmpty, "TextDocumentSyncKind.Incremental support is not implemented")
    buffers(uri) = change.text
    val version = bufferVersions.getOrElse(uri, 0L) + 1
    bufferVersions(uri) = version
    pendingDiagnostics.remove(uri).foreach(_.cancel(false))
    val task: Runnable = () => thisServer.synchronized {
      // Drop this compile if a newer edit landed while we were waiting --
      // that edit's own scheduled task will publish for the latest text.
      if (bufferVersions.get(uri).contains(version)) publishDiagnosticsFor(uri, document.uri)
    }
    pendingDiagnostics(uri) =
      diagnosticsScheduler.schedule(task, diagnosticsDebounceMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
  }

  /** dotc's raw diagnostic `code` is just `ErrorMessageID.errorNumber`'s bare
   *  digits (e.g. "50") -- shown by VS Code as `source(code)`, i.e.
   *  "presentation compiler(50)", which tells a user nothing on its own.
   *  Reformat to dotc's own `E###` convention (matches dotc's CLI output,
   *  e.g. `-- [E007] Type Mismatch Error:`) and, since every such ID has a
   *  real published page at
   *  https://docs.scala-lang.org/scala3/reference/error-codes/E###.html
   *  (confirmed live for E050), attach it as `codeDescription` so a client
   *  that supports it (VS Code does) renders the code as a clickable link
   *  straight to the explanation. `-1` (`NoExplanationID`, real Metals'
   *  own special case for "no doc page exists") and anything non-numeric
   *  are left with no code/link at all rather than a broken one. */
  private def formatDiagnosticCode(rawCode: String): Option[(String, String)] =
    rawCode.toIntOption.filter(_ >= 0).map { n =>
      val padded = "E%03d".format(n)
      (padded, s"https://docs.scala-lang.org/scala3/reference/error-codes/$padded.html")
    }

  private def publishDiagnosticsFor(uri: URI, uriString: String): Unit = {
    val diags = requirePc(uri).didChange(CompilerVirtualFileParams(uri, textOf(uri)))
    val lspDiags = diags.asScala.map { d =>
      val severity = Option(d.getSeverity()).map(_.getValue()).getOrElse(1)
      val (code, codeDescription) = Option(d.getCode()).map(eitherToString).flatMap(formatDiagnosticCode) match {
        case Some((c, href)) => (c, Some(href))
        case None => ("", None)
      }
      Diagnostic(toRange(d.getRange()), eitherToString(d.getMessage()), severity, Option(d.getSource()).getOrElse(""), code, codeDescription)
    }.toList
    publishDiagnostics(uriString, lspDiags)
  }

  def didClose(params: DidCloseTextDocumentParams): Unit = guarded(()) {
    val uri = pcUri(params.textDocument.uri)
    buffers.remove(uri)
    bufferVersions.remove(uri)
    pendingDiagnostics.remove(uri).foreach(_.cancel(false))
    Option(projectFor(uri).pc).foreach(_.didClose(uri))
  }

  def completion(params: TextDocumentPositionParams): CompletionList = guarded(CompletionList(false, Nil)) {
    val uri = pcUri(params.textDocument.uri)
    val result = requirePc(uri).complete(offsetParams(uri, params.position), l.CompletionTriggerKind.Invoked)
    noteCompletion(uri)
    CompletionList(
      isIncomplete = result.isIncomplete(),
      items = result.getItems().asScala.map { item =>
        CompletionItem(
          label = item.getLabel(),
          kind = Option(item.getKind()).map(_.getValue()),
          detail = Option(item.getDetail()),
          documentation = Option(item.getDocumentation()).map(eitherToMarkupContent),
          deprecated = Option(item.getDeprecated()).exists(_.booleanValue),
          sortText = Option(item.getSortText()),
          filterText = Option(item.getFilterText()),
          insertText = Option(item.getInsertText()),
          insertTextFormat = Option(item.getInsertTextFormat()).map(_.getValue()),
          textEdit = Option(item.getTextEdit()).map { either =>
            if (either.isLeft()) toTextEdit(either.getLeft())
            else {
              val ire = either.getRight()
              TextEdit(toRange(ire.getInsert()), ire.getNewText())
            }
          },
          additionalTextEdits = Option(item.getAdditionalTextEdits()).map(_.asScala.map(toTextEdit).toList).getOrElse(Nil)
        )
      }.toList
    )
  }

  def definition(params: TextDocumentPositionParams): List[Location] = guarded(Nil) {
    val uri = pcUri(params.textDocument.uri)
    val result = requirePc(uri).definition(offsetParams(uri, params.position))
    result.locations().asScala.map(toLocation).toList
  }

  /** References of the symbol at `pos`, split into those in `uri` itself and
   *  those in other files. presentation-compiler only ever searches the one
   *  file it is handed (Metals drives it from its own index, which this
   *  server doesn't have), so the rest is done here the way Metals does it:
   *  resolve the symbol(s) at the cursor to semanticdb names, then ask for
   *  that name in every other source of the connected projects (those
   *  related through `dependsOn`, in either direction: a rename in `main`
   *  must reach `test`, and a use in `test` must find the `main` definition),
   *  each asked of the compiler of the project owning that file. Files whose
   *  text doesn't mention the identifier are skipped without typechecking
   *  them. File-local symbols never leave their file. */
  private def workspaceReferences(uri: URI, pos: Position, includeDeclaration: Boolean): (List[Location], List[Location]) = {
    val text = textOf(uri)
    val offset = positionToOffset(text, pos)
    val originResults = requirePc(uri).references(PcReferencesRequest(
      CompilerVirtualFileParams(uri, text), includeDeclaration, JEither.forLeft(Integer.valueOf(offset)))).asScala.toList
    val here = originResults.flatMap(_.locations().asScala).map(toLocation)
    val symbols = originResults.map(_.symbol()).distinct.filterNot(_.startsWith("local"))
    if (symbols.isEmpty) return (here, Nil)

    val isIdentChar = (c: Char) => Character.isUnicodeIdentifierPart(c)
    var start = math.min(offset, text.length)
    var end = start
    while (start > 0 && isIdentChar(text.charAt(start - 1))) start -= 1
    while (end < text.length && isIdentChar(text.charAt(end))) end += 1
    val word = text.substring(start, end)

    val owner = projectFor(uri)
    // Connected component of the dependsOn graph, undirected.
    val related = scala.collection.mutable.LinkedHashSet[Project](owner)
    var grew = true
    while (grew) {
      grew = false
      for (a <- projects; b <- related.toList if !related(a) && (a.config.dependsOn.contains(b.id) || b.config.dependsOn.contains(a.id))) {
        related += a
        grew = true
      }
    }
    val candidates = related.toList.flatMap(p => sourceFilesIn(p.sourceDirs)).distinct.filter(_.toUri != uri)
    val others = candidates.flatMap { file =>
      val fileUri = file.toUri
      try {
        val fileText = buffers.getOrElse(fileUri, new String(Files.readAllBytes(file), "UTF-8"))
        if (word.nonEmpty && !fileText.contains(word)) Nil
        else {
          val pcForFile = requirePc(fileUri)
          symbols.flatMap { symbol =>
            pcForFile.references(PcReferencesRequest(
              CompilerVirtualFileParams(fileUri, fileText), includeDeclaration, JEither.forRight(symbol))).asScala
              .flatMap(_.locations().asScala).map(toLocation)
          }
        }
      } catch { case NonFatal(_) => Nil }
    }
    (here, others.distinct)
  }

  def references(params: ReferenceParams): List[Location] = guarded(Nil) {
    val uri = pcUri(params.textDocument.uri)
    val (here, others) = workspaceReferences(uri, params.position, params.context.includeDeclaration)
    here ++ others
  }

  /** For the file the rename was requested in, the PC's own rename is
   *  preferred (it knows about backticks, named arguments, ...), but it only
   *  handles file-local symbols and answers nothing for the rest, so then
   *  that file's references stand in for it. Every other file's occurrences
   *  come from `workspaceReferences`. */
  def rename(params: RenameParams): WorkspaceEdit = guarded(WorkspaceEdit(Map.empty)) {
    val uri = pcUri(params.textDocument.uri)
    val pcEdits = requirePc(uri).rename(offsetParams(uri, params.position), params.newName).asScala.map(toTextEdit).toList
    val (here, others) = workspaceReferences(uri, params.position, includeDeclaration = true)
    def asEdits(locs: List[Location]) = locs.map(loc => TextEdit(loc.range, params.newName))
    val ownEdits = if (pcEdits.nonEmpty) pcEdits else asEdits(here)
    val otherEdits = others.groupBy(_.uri).map { case (u, locs) => u -> asEdits(locs) }
    WorkspaceEdit(otherEdits + (params.textDocument.uri -> ownEdits))
  }

  def documentHighlight(params: TextDocumentPositionParams): List[DocumentHighlight] = guarded(Nil) {
    val uri = pcUri(params.textDocument.uri)
    val result = requirePc(uri).documentHighlight(offsetParams(uri, params.position))
    result.asScala.map(h => DocumentHighlight(toRange(h.getRange()), Option(h.getKind()).map(_.getValue()).getOrElse(1))).toList
  }

  def hover(params: TextDocumentPositionParams): Option[Hover] = guarded(None) {
    val uri = pcUri(params.textDocument.uri)
    val result = requirePc(uri).hover(offsetParams(uri, params.position))
    result.toScala.map { sig =>
      val h = sig.toLsp()
      val contents = h.getContents()
      val markup =
        if (contents == null) MarkupContent("plaintext", "")
        else if (contents.isRight()) { val mc = contents.getRight(); MarkupContent(mc.getKind(), mc.getValue()) }
        else MarkupContent("plaintext", String.valueOf(contents.getLeft()))
      Hover(markup)
    }
  }

  def signatureHelp(params: TextDocumentPositionParams): SignatureHelp = guarded(SignatureHelp(Nil, -1, -1)) {
    val uri = pcUri(params.textDocument.uri)
    val result = requirePc(uri).signatureHelp(offsetParams(uri, params.position))
    SignatureHelp(
      signatures = result.getSignatures().asScala.map { sig =>
        SignatureInformation(
          label = sig.getLabel(),
          documentation = Option(sig.getDocumentation()).map(eitherToMarkupContent),
          parameters = Option(sig.getParameters()).map(_.asScala.map { p =>
            ParameterInformation(eitherToString(p.getLabel()), Option(p.getDocumentation()).map(eitherToMarkupContent))
          }.toList).getOrElse(Nil)
        )
      }.toList,
      activeParameter = Option(result.getActiveParameter()).map(_.intValue).getOrElse(-1),
      activeSignature = Option(result.getActiveSignature()).map(_.intValue).getOrElse(-1)
    )
  }

  // presentation-compiler has no documentSymbol/workspace-symbol/implementation
  // equivalent -- those come from Metals' own BSP-driven indexer, not the PC
  // itself (see this class's doc comment above). Stubbed empty so Main.scala's
  // shared ServerBackend dispatch doesn't need a PC-specific branch for them.
  def documentSymbol(params: DocumentSymbolParams): List[SymbolInformation] = Nil
  def symbol(params: WorkspaceSymbolParams): List[SymbolInformation] = Nil
  def implementation(params: TextDocumentPositionParams): List[Location] = Nil

  def typeDefinition(params: TextDocumentPositionParams): List[Location] = guarded(Nil) {
    val uri = pcUri(params.textDocument.uri)
    val result = requirePc(uri).typeDefinition(offsetParams(uri, params.position))
    result.locations().asScala.map(toLocation).toList
  }

  def prepareRename(params: TextDocumentPositionParams): Option[Range] = guarded(None) {
    val uri = pcUri(params.textDocument.uri)
    requirePc(uri).prepareRename(offsetParams(uri, params.position)).toScala.map(toRange)
  }

  def selectionRange(params: SelectionRangeParams): List[Lsp.SelectionRange] = guarded(Nil) {
    val uri = pcUri(params.textDocument.uri)
    val offsets: List[OffsetParams] = params.positions.map(pos => offsetParams(uri, pos))
    def convert(sr: l.SelectionRange): Lsp.SelectionRange =
      Lsp.SelectionRange(toRange(sr.getRange()), Option(sr.getParent()).map(convert))
    requirePc(uri).selectionRange(offsets.asJava).asScala.map(convert).toList
  }

  def inlayHint(params: InlayHintParams): List[Lsp.InlayHint] = guarded(Nil) {
    val uri = pcUri(params.textDocument.uri)
    val text = textOf(uri)
    val rangeParams = CompilerRangeParams(uri, text, positionToOffset(text, params.range.start), positionToOffset(text, params.range.end))
    val on = inlayHintToggles
    val hintsParams = CompilerInlayHintsParams(
      rangeParams,
      inferredTypes = on("inferredTypes"),
      typeParameters = on("typeParameters"),
      implicitParameters = on("implicitArguments"),
      hintsXRayMode = false,
      byNameParameters = on("byNameParameters"),
      implicitConversions = on("implicitConversions"),
      namedParameters = on("namedParameters"),
      hintsInPatternMatch = on("hintsInPatternMatch"),
      closingLabels = false)
    // presentation-compiler folds each child of the requested range separately
    // and the children overlap (a call and its own arguments), so one hint --
    // `[Int, Int]`, `(using ...)` -- can come back once per enclosing tree;
    // editors render each copy. Identical position + text + kind is one hint.
    val rawHints = requirePc(uri).inlayHints(hintsParams).asScala.toList
    def hintLabel(h: l.InlayHint): String = Option(h.getLabel()) match {
      case None => ""
      case Some(e) if e.isLeft() => e.getLeft()
      case Some(e) => e.getRight().asScala.map(_.getValue()).mkString
    }
    rawHints.distinctBy { h =>
      val p = h.getPosition()
      (p.getLine(), p.getCharacter(), hintLabel(h), Option(h.getKind()).map(_.getValue()))
    }.map { h =>
      val pos = h.getPosition()
      // Real lsp4j 1.0.0 types `label` as `Either<String, List<InlayHintLabelPart>>` --
      // presentation-compiler really does build the `List` shape for composite
      // (e.g. multi-part inferred-type) hints (`InlayHints.makeInlayHint`,
      // mtags-shared), so unlike the other Either-typed fields in this file
      // (which PC only ever sets to `Left`), this one needs its Right branch
      // handled for real: flatten each part's plain text -- this minimal wire
      // model has no per-part hover-link shape to preserve, only a display
      // string. `tooltip` is never actually set by any call site (confirmed
      // via a full grep of presentation-compiler+mtags-shared), so
      // `eitherToString`'s generic Left/Right handling is enough there.
      val label = Option(h.getLabel()) match {
        case None => ""
        case Some(e) if e.isLeft() => e.getLeft()
        case Some(e) => e.getRight().asScala.map(_.getValue()).mkString
      }
      Lsp.InlayHint(
        position = Position(pos.getLine(), pos.getCharacter()),
        label = label,
        kind = Option(h.getKind()).map(_.getValue()),
        paddingLeft = Option(h.getPaddingLeft()).map(_.booleanValue),
        paddingRight = Option(h.getPaddingRight()).map(_.booleanValue),
        tooltip = Option(h.getTooltip()).map(eitherToString))
    }.toList
  }

  /** LSP's semantic-tokens wire format is a flat, *relative*-encoded array:
   *  each token is 5 ints `(deltaLine, deltaStartChar, length, tokenType,
   *  tokenModifiersBitmask)`, delta-encoded against the previous token's
   *  start (`deltaStartChar` is relative to the previous token's start
   *  column ONLY when on the same line, otherwise absolute) -- see the LSP
   *  spec's "SemanticTokens" section. `RawScalaPresentationCompiler.Node`
   *  gives raw text offsets, not line/character, so this does one forward
   *  scan over the buffer converting each node's start offset to
   *  line/character (nodes are sorted by position first since the encoding
   *  requires monotonically increasing positions). */
  def semanticTokens(params: DocumentSymbolParams): Lsp.SemanticTokens = guarded(Lsp.SemanticTokens(Nil)) {
    val uri = pcUri(params.textDocument.uri)
    val text = textOf(uri)
    val nodes = requirePc(uri).semanticTokens(CompilerVirtualFileParams(uri, text)).asScala.toList.sortBy(n => (n.start(), n.end()))
    val data = List.newBuilder[Int]
    var idx = 0
    var line = 0
    var col = 0
    var prevLine = 0
    var prevCol = 0
    for (n <- nodes) {
      while (idx < n.start()) {
        if (text.charAt(idx) == '\n') { line += 1; col = 0 } else col += 1
        idx += 1
      }
      val deltaLine = line - prevLine
      val deltaCol = if (deltaLine == 0) col - prevCol else col
      data += deltaLine
      data += deltaCol
      data += (n.end() - n.start())
      data += n.tokenType()
      data += n.tokenModifier()
      prevLine = line
      prevCol = col
    }
    Lsp.SemanticTokens(data.result())
  }

  /** Only the code actions presentation-compiler can compute from just a
   *  cursor/selection (no extra user-chosen payload) are wired here --
   *  `ConvertToNamedArguments`/`ConvertToNamedLambdaParameters` need a set of
   *  argument indices the user picked, which this minimal client model has
   *  no UI to collect, so they're deliberately left unwired rather than
   *  guessed at (e.g. "convert all arguments", which wouldn't match what a
   *  real editor's own argument-picking UI would send). Each provider is
   *  tried independently and just contributes nothing if it doesn't apply at
   *  this position -- there's no separate "is this applicable here" query on
   *  `RawScalaPresentationCompiler`, only "try it and see what edits (if
   *  any) come back", so a thrown exception is treated the same as an empty
   *  result. */
  def codeAction(params: CodeActionParams): List[Lsp.CodeAction] = guarded(Nil) {
    val uri = pcUri(params.textDocument.uri)
    val text = textOf(uri)
    val startOffset = positionToOffset(text, params.range.start)
    val endOffset = positionToOffset(text, params.range.end)
    val cursorParams = CompilerOffsetParams(uri, text, startOffset)
    val compiler = requirePc(uri)

    def tryEdits(id: String, target: OffsetParams, payload: java.util.Optional[OffsetParams] = java.util.Optional.empty()): List[TextEdit] =
      try compiler.codeAction(target, id, payload).asScala.map(toTextEdit).toList
      catch { case NonFatal(_) => Nil }

    val actions = List.newBuilder[Lsp.CodeAction]
    def addIfNonEmpty(title: String, kind: String, edits: List[TextEdit]): Unit =
      if (edits.nonEmpty) actions += Lsp.CodeAction(title, kind, WorkspaceEdit(Map(params.textDocument.uri -> edits)))

    addIfNonEmpty("Implement abstract members", "quickfix", tryEdits(CodeActionId.ImplementAbstractMembers, cursorParams))
    addIfNonEmpty("Insert inferred type", "refactor.rewrite", tryEdits(CodeActionId.InsertInferredType, cursorParams))
    addIfNonEmpty("Insert inferred method", "refactor.rewrite", tryEdits(CodeActionId.InsertInferredMethod, cursorParams))
    addIfNonEmpty("Inline value", "refactor.inline", tryEdits(CodeActionId.InlineValue, cursorParams))
    if (endOffset > startOffset) {
      val rangeParams = CompilerRangeParams(uri, text, startOffset, endOffset)
      addIfNonEmpty("Extract method", "refactor.extract", tryEdits(CodeActionId.ExtractMethod, rangeParams, java.util.Optional.of(cursorParams)))
    }
    actions.result()
  }
}
