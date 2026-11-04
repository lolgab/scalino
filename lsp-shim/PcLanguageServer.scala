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

/** Thin LSP layer over `scala3-presentation-compiler` (Metals' PC engine), reusing `Main.scala`'s
 *  JSON-RPC transport and `Lsp.scala`'s jsoniter-scala wire model: no lsp4j, Gson or reflection.
 *  The lsp4j/mtags types the PC expects are satisfied by the in-tree shim at `lsp-shim/`.
 *
 *  Supports completion, hover, definition, typeDefinition, references, rename, prepareRename,
 *  documentHighlight, signatureHelp, selectionRange, inlayHint, semanticTokens, codeAction and
 *  didOpen/didChange/didClose diagnostics. documentSymbol, workspaceSymbol and implementation
 *  come from Metals' BSP-driven indexer, not the PC, so `Main.scala` doesn't wire them.
 */
class PcLanguageServer(publishDiagnostics: (String, List[Lsp.Diagnostic]) => Unit) { thisServer =>

  /** One entry per project in `.scalino-build/scalino-lsp.json` (e.g. an app's main and test
   *  scopes). Each owns a presentation compiler, built lazily on the first request for one of its
   *  files, so an untouched project costs nothing. */
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
  /** Set when the workspace has no `.scalino-build/scalino-lsp.json` (`scalino setup-ide` never
   *  ran). Not an error: the server answers every request with an empty result, so no toast per
   *  request in projects that don't use scalino. */
  @volatile private var unconfigured = false

  /** Serialized request body; empty result when the workspace is unconfigured. */
  private def guarded[A](empty: => A)(body: => A): A =
    thisServer.synchronized {
      // A request racing the warmup thread must wait to learn whether the workspace is configured
      // (requirePc waits for the same signal).
      val deadline = System.currentTimeMillis() + 180000
      while (projects.isEmpty && warmupError == null && !unconfigured && System.currentTimeMillis() < deadline)
        thisServer.wait(deadline - System.currentTimeMillis())
      if (unconfigured) empty else body
    }
  private val buffers: mutable.Map[URI, String] = mutable.Map.empty

  /** Debounces `didChange`: a compile per keystroke queued up behind the LSP's single lock and
   *  made diagnostics lag. Only the text from the last edit in a burst is compiled. */
  private val diagnosticsDebounceMillis = 250L
  private val diagnosticsScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r => {
    val t = new Thread(r, "pc-diagnostics-debounce")
    t.setDaemon(true)
    t
  })
  private val bufferVersions: mutable.Map[URI, Long] = mutable.Map.empty
  private val pendingDiagnostics: mutable.Map[URI, java.util.concurrent.ScheduledFuture[?]] = mutable.Map.empty

  /** `scalino setup-ide`'s output. */
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

  /** Per-kind inlay hint toggles, named like Metals' `inlayHints.*.enable` settings. */
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
          // Warm the first project, the likeliest target of the first request; the rest are built on demand.
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

  /** dotc's `-sourcepath` scan is recursive, so passing a source dir like the project root also
   *  pulls in `.metals/` and `.scalino-build/.../sources/` (the stdlib's sources, giving
   *  "duplicate source version import" errors). List each dir's direct children instead; the PC
   *  accepts individual source files as entries. */
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

  /** The project a document belongs to: the one listing the file's parent directory, else the one
   *  with the deepest source directory above it (a file created since `setup-ide`), else the one
   *  with the largest classpath (e.g. dependency sources opened from a jar: likeliest to resolve
   *  their imports). */
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
        // Without this, sourcePathMode is DISABLED and sourcePath is ignored: symbols in sibling
        // files that haven't been opened fail to resolve.
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

  /** Every `complete` call builds a throwaway `InteractiveDriver` (a few MB of garbage), which
   *  fragments Scala Native's Immix heap until RSS reaches GBs. Recycling the PC bounds it: after
   *  `pcRecycleMinCompletions` completions once idle for `pcRecycleIdleMillis`, or at
   *  `pcRecycleMaxCompletions` regardless. */
  private val pcRecycleIdleMillis = 15000L
  private val pcRecycleMinCompletions = 50
  private val pcRecycleMaxCompletions = 400

  private def recyclePc(project: Project): Unit = {
    if (project.factory == null || project.pc == null) return
    val fresh = project.factory()
    // Warm the new driver with the project's open buffers so the next request doesn't typecheck
    // them from scratch (results are discarded).
    buffers.foreach { case (uri, text) =>
      if (projectFor(uri) eq project)
        try fresh.didChange(CompilerVirtualFileParams(uri, text))
        catch { case NonFatal(_) => () }
    }
    project.pc = fresh
    project.completionsSincePc = 0
    // Reclaim the old PC's heap now rather than at the next allocation-triggered cycle.
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

  /** Warmup (classpath resolution + PC construction) runs on a daemon thread started from
   *  `initialize()`; requests arriving before it finishes block here instead of failing. The
   *  deadline is generous: warmup took from ~1s (warm page cache) to several minutes (cold). */
  private def requirePc(uri: URI): RawScalaPresentationCompiler = thisServer.synchronized {
    val deadline = System.currentTimeMillis() + 180000
    while (projects.isEmpty && warmupError == null && System.currentTimeMillis() < deadline)
      thisServer.wait(deadline - System.currentTimeMillis())
    if (projects.isEmpty && warmupError != null)
      throw new IllegalStateException(s"presentation compiler failed to initialize: ${warmupError.getMessage}", warmupError)
    if (projects.isEmpty) throw new IllegalStateException("presentation compiler not yet initialized")
    ensurePc(projectFor(uri))
  }

  /** Converts an `Lsp.Position` to the plain `Int` offset `OffsetParams` wants, from the full text
   *  the client last sent. */
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

  /** lsp4j types markup fields as `Either<String, MarkupContent>`; unwrap either branch into a
   *  `MarkupContent`. */
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
      // Drop this compile if a newer edit landed: its own task publishes for the latest text.
      if (bufferVersions.get(uri).contains(version)) publishDiagnosticsFor(uri, document.uri)
    }
    pendingDiagnostics(uri) =
      diagnosticsScheduler.schedule(task, diagnosticsDebounceMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
  }

  /** dotc's raw diagnostic `code` is the bare `ErrorMessageID.errorNumber` (e.g. "50"). Reformat
   *  it to dotc's `E###` convention and attach
   *  https://docs.scala-lang.org/scala3/reference/error-codes/E###.html as `codeDescription`.
   *  `-1` (`NoExplanationID`, as in Metals) and non-numeric codes get no code/link. */
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

  /** References of the symbol at `pos`, split into those in `uri` itself and those in other files.
   *  The PC only searches the file it is handed (Metals drives it from an index we lack), so as in
   *  Metals: resolve the symbol(s) at the cursor to semanticdb names, then ask the owning project's
   *  compiler for that name in every other source of the connected projects (related through
   *  `dependsOn` in either direction: a rename in `main` must reach `test` and vice versa). Files
   *  not mentioning the identifier are skipped untypechecked. File-local symbols stay in their file. */
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

  /** In the requested file the PC's own rename is preferred (it knows about backticks, named
   *  arguments, ...), but it only handles file-local symbols, so otherwise that file's references
   *  stand in. Other files' occurrences come from `workspaceReferences`. */
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

  // The PC has no documentSymbol/workspaceSymbol/implementation (see the class doc); stubbed empty
  // so Main.scala's shared ServerBackend dispatch needs no PC-specific branch.
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
    // The PC folds each child of the range separately and children overlap (a call and its
    // arguments), so one hint can come back once per enclosing tree. Dedupe on position + text + kind.
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
      // `label` is `Either<String, List<InlayHintLabelPart>>` and the PC builds the `List` shape for
      // composite hints (`InlayHints.makeInlayHint`), so unlike the other Either fields (always
      // `Left`) the Right branch matters: flatten the parts' text, as the wire model has no
      // per-part links. `tooltip` is never set by the PC, so `eitherToString` suffices there.
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

  /** Encodes nodes in LSP's relative semantic-tokens format: 5 ints per token
   *  `(deltaLine, deltaStartChar, length, tokenType, tokenModifiersBitmask)`, where `deltaStartChar`
   *  is relative to the previous token only on the same line, else absolute. Nodes carry raw
   *  offsets, so they are sorted and converted to line/character in one forward scan. */
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

  /** Only code actions computable from just a cursor/selection are wired.
   *  `ConvertToNamedArguments`/`ConvertToNamedLambdaParameters` need user-picked argument indices,
   *  which this client model can't collect. Each provider is tried independently (there is no
   *  applicability query on `RawScalaPresentationCompiler`), and an exception counts as no edits. */
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
