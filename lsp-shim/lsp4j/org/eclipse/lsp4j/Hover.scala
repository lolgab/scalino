package org.eclipse.lsp4j

import org.eclipse.lsp4j.jsonrpc.messages.Either

class Hover() {
  private var _contents: Either[AnyRef, MarkupContent] = null
  private var _range: Range = null

  def this(contents: MarkupContent) = {
    this()
    _contents = Either.forRight(contents)
  }

  def this(contents: MarkupContent, range: Range) = {
    this()
    _contents = Either.forRight(contents)
    _range = range
  }

  // lsp4j types `contents` as `Either<List<Either<String, MarkedString>>, MarkupContent>`. The
  // constructors take only `MarkupContent` (`ScalaHover.toLsp()`), but the getter keeps the Either
  // shape PcLanguageServer reads; `AnyRef` stands in for the never-constructed left branch.
  def getContents(): Either[AnyRef, MarkupContent] = _contents
  def setContents(contents: MarkupContent): Unit = _contents = Either.forRight(contents)
  def getRange(): Range = _range
  def setRange(range: Range): Unit = _range = range
}
