package org.eclipse.lsp4j

import java.util as ju
import org.eclipse.lsp4j.jsonrpc.messages.Either

class SignatureInformation() {
  private var _label: String = null
  private var _documentation: Either[String, MarkupContent] = null
  private var _parameters: ju.List[ParameterInformation] = null

  def this(label: String) = {
    this()
    _label = label
  }

  def getLabel(): String = _label
  def setLabel(label: String): Unit = _label = label

  // lsp4j types `documentation` as `Either<String, MarkupContent>`. The setter takes only
  // `MarkupContent` (all the PC uses), but the getter keeps the Either shape PcLanguageServer reads.
  def getDocumentation(): Either[String, MarkupContent] = _documentation
  def setDocumentation(documentation: MarkupContent): Unit = _documentation = Either.forRight(documentation)

  def getParameters(): ju.List[ParameterInformation] = _parameters
  def setParameters(parameters: ju.List[ParameterInformation]): Unit = _parameters = parameters
}
