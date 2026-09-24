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

  private var pc: RawScalaPresentationCompiler = null
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
  private def loadConfig(rootUri: String): List[ProjectConfig] = {
    val IDE_CONFIG_FILE = ".scalino-build/scalino-lsp.json"
    val configFile = new File(new URI(rootUri + '/' + IDE_CONFIG_FILE))
    if (!configFile.exists)
      throw new java.io.FileNotFoundException(
        s"$IDE_CONFIG_FILE not found at $rootUri -- run `scalino setup-ide <sources...>` in the project root first")
    readFromArray(Files.readAllBytes(configFile.toPath))(using projectConfigListCodec)
  }

  def initialize(rootUri: String): InitializeResult = thisServer.synchronized {
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
        val configs = loadConfig(rootUri)
        val classpath: Seq[Path] =
          configs.flatMap(c => c.classDirectory +: c.dependencyClasspath).distinct.map(Paths.get(_))
        val sourceDirs: Seq[Path] =
          configs.flatMap(_.sourceDirectories).distinct.map(Paths.get(_))
        // Includes `-javabootclasspath ...` -- without it dotc's own
        // Definitions.init() can't find java.lang.Object at all (same
        // config field DottyLanguageServer already threads through, see
        // its own `config.compilerArguments` usage).
        val compilerArgs: List[String] =
          configs.flatMap(_.compilerArguments).distinct
        val built = RawScalaPresentationCompiler(
          buildTargetIdentifier = "scalino",
          classpath = classpath,
          options = compilerArgs,
          // Without this, sourcePathMode defaults to DISABLED and sourceDirs
          // below is plumbed through but never used: the InteractiveDriver
          // only ever typechecks files the editor explicitly opens
          // (didOpen/didChange), so a symbol defined in a sibling source file
          // that hasn't been opened yet fails to resolve -- looks exactly
          // like "not all files in the source dirs get compiled".
          config = scala.meta.internal.pc.PresentationCompilerConfigImpl(
            _sourcePathMode = scala.meta.pc.SourcePathMode.FULL
          ),
          sourcePath = () => sourceDirs.asJava
        )
        thisServer.synchronized {
          pc = built
          thisServer.notifyAll()
        }
      } catch {
        case ex: Throwable =>
          System.err.println(s"PC warmup failed: ${ex.getClass.getName}: ${ex.getMessage}")
          ex.printStackTrace()
          System.err.flush()
      }
    })
    warmup.setDaemon(true)
    warmup.start()

    InitializeResult(capabilities)
  }

  /** Warmup (classpath resolution + PC construction) runs on a daemon thread
   *  started from `initialize()` -- requests that land before it finishes
   *  (e.g. an editor's first definition request right after startup) block
   *  here instead of failing outright. 30s was too tight: observed real PC
   *  warmup (classpath scanning over ~20+ real jars) taking anywhere from
   *  ~1s (warm page cache) to several minutes (cold). */
  private def requirePc(): RawScalaPresentationCompiler = thisServer.synchronized {
    val deadline = System.currentTimeMillis() + 180000
    while (pc == null && System.currentTimeMillis() < deadline)
      thisServer.wait(deadline - System.currentTimeMillis())
    if (pc == null) throw new IllegalStateException("presentation compiler not yet initialized")
    pc
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
    Location(l0.getUri(), toRange(l0.getRange()))

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

  def didOpen(params: DidOpenTextDocumentParams): Unit = thisServer.synchronized {
    val document = params.textDocument
    val uri = new URI(document.uri)
    buffers(uri) = document.text
    publishDiagnosticsFor(uri, document.uri)
  }

  def didChange(params: DidChangeTextDocumentParams): Unit = thisServer.synchronized {
    val document = params.textDocument
    val uri = new URI(document.uri)
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
    val diags = requirePc().didChange(CompilerVirtualFileParams(uri, textOf(uri)))
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

  def didClose(params: DidCloseTextDocumentParams): Unit = thisServer.synchronized {
    val uri = new URI(params.textDocument.uri)
    buffers.remove(uri)
    bufferVersions.remove(uri)
    pendingDiagnostics.remove(uri).foreach(_.cancel(false))
    requirePc().didClose(uri)
  }

  def completion(params: TextDocumentPositionParams): CompletionList = thisServer.synchronized {
    val uri = new URI(params.textDocument.uri)
    val result = requirePc().complete(offsetParams(uri, params.position), l.CompletionTriggerKind.Invoked)
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

  def definition(params: TextDocumentPositionParams): List[Location] = thisServer.synchronized {
    val uri = new URI(params.textDocument.uri)
    val result = requirePc().definition(offsetParams(uri, params.position))
    result.locations().asScala.map(toLocation).toList
  }

  def references(params: ReferenceParams): List[Location] = thisServer.synchronized {
    val uri = new URI(params.textDocument.uri)
    val text = textOf(uri)
    val offset = positionToOffset(text, params.position)
    val request = PcReferencesRequest(
      CompilerVirtualFileParams(uri, text),
      params.context.includeDeclaration,
      JEither.forLeft(Integer.valueOf(offset))
    )
    val results = requirePc().references(request)
    results.asScala.flatMap(_.locations().asScala).map(toLocation).toList
  }

  def rename(params: RenameParams): WorkspaceEdit = thisServer.synchronized {
    val uri = new URI(params.textDocument.uri)
    val edits = requirePc().rename(offsetParams(uri, params.position), params.newName)
    WorkspaceEdit(Map(params.textDocument.uri -> edits.asScala.map(toTextEdit).toList))
  }

  def documentHighlight(params: TextDocumentPositionParams): List[DocumentHighlight] = thisServer.synchronized {
    val uri = new URI(params.textDocument.uri)
    val result = requirePc().documentHighlight(offsetParams(uri, params.position))
    result.asScala.map(h => DocumentHighlight(toRange(h.getRange()), Option(h.getKind()).map(_.getValue()).getOrElse(1))).toList
  }

  def hover(params: TextDocumentPositionParams): Option[Hover] = thisServer.synchronized {
    val uri = new URI(params.textDocument.uri)
    val result = requirePc().hover(offsetParams(uri, params.position))
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

  def signatureHelp(params: TextDocumentPositionParams): SignatureHelp = thisServer.synchronized {
    val uri = new URI(params.textDocument.uri)
    val result = requirePc().signatureHelp(offsetParams(uri, params.position))
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

  def typeDefinition(params: TextDocumentPositionParams): List[Location] = thisServer.synchronized {
    val uri = new URI(params.textDocument.uri)
    val result = requirePc().typeDefinition(offsetParams(uri, params.position))
    result.locations().asScala.map(toLocation).toList
  }

  def prepareRename(params: TextDocumentPositionParams): Option[Range] = thisServer.synchronized {
    val uri = new URI(params.textDocument.uri)
    requirePc().prepareRename(offsetParams(uri, params.position)).toScala.map(toRange)
  }

  def selectionRange(params: SelectionRangeParams): List[Lsp.SelectionRange] = thisServer.synchronized {
    val uri = new URI(params.textDocument.uri)
    val offsets: List[OffsetParams] = params.positions.map(pos => offsetParams(uri, pos))
    def convert(sr: l.SelectionRange): Lsp.SelectionRange =
      Lsp.SelectionRange(toRange(sr.getRange()), Option(sr.getParent()).map(convert))
    requirePc().selectionRange(offsets.asJava).asScala.map(convert).toList
  }

  def inlayHint(params: InlayHintParams): List[Lsp.InlayHint] = thisServer.synchronized {
    val uri = new URI(params.textDocument.uri)
    val text = textOf(uri)
    val rangeParams = CompilerRangeParams(uri, text, positionToOffset(text, params.range.start), positionToOffset(text, params.range.end))
    // Enable every hint kind PC supports -- there's no separate LSP-level
    // per-kind toggle in this minimal client model (real Metals exposes these
    // as user settings; this server always asks for everything and lets the
    // editor's own inlay-hint UI settings decide what's actually shown).
    val hintsParams = CompilerInlayHintsParams(
      rangeParams,
      inferredTypes = true,
      typeParameters = true,
      implicitParameters = true,
      hintsXRayMode = false,
      byNameParameters = true,
      implicitConversions = true,
      namedParameters = true,
      hintsInPatternMatch = true,
      closingLabels = false)
    requirePc().inlayHints(hintsParams).asScala.map { h =>
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
  def semanticTokens(params: DocumentSymbolParams): Lsp.SemanticTokens = thisServer.synchronized {
    val uri = new URI(params.textDocument.uri)
    val text = textOf(uri)
    val nodes = requirePc().semanticTokens(CompilerVirtualFileParams(uri, text)).asScala.toList.sortBy(n => (n.start(), n.end()))
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
  def codeAction(params: CodeActionParams): List[Lsp.CodeAction] = thisServer.synchronized {
    val uri = new URI(params.textDocument.uri)
    val text = textOf(uri)
    val startOffset = positionToOffset(text, params.range.start)
    val endOffset = positionToOffset(text, params.range.end)
    val cursorParams = CompilerOffsetParams(uri, text, startOffset)
    val compiler = requirePc()

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
