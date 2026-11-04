package org.eclipse.lsp4j

import org.eclipse.lsp4j.jsonrpc.messages.Either

class ParameterInformation() {
  private var _label: Either[String, AnyRef] = null
  private var _documentation: Either[String, MarkupContent] = null

  def this(label: String) = {
    this()
    _label = Either.forLeft(label)
  }

  // lsp4j types `label` as `Either<String, Tuple$Two<Integer, Integer>>`. The constructor takes
  // only `String`, but the getter keeps the Either shape PcLanguageServer reads; `AnyRef` stands in
  // for the never-constructed right branch.
  def getLabel(): Either[String, AnyRef] = _label
  def setLabel(label: String): Unit = _label = Either.forLeft(label)

  // lsp4j types `documentation` as `Either<String, MarkupContent>`. The setter takes only
  // `MarkupContent` (all the PC uses), but the getter keeps the Either shape PcLanguageServer reads.
  def getDocumentation(): Either[String, MarkupContent] = _documentation
  def setDocumentation(documentation: MarkupContent): Unit = _documentation = Either.forRight(documentation)
}
