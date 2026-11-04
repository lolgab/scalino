package org.eclipse.lsp4j

import java.util as ju
import org.eclipse.lsp4j.jsonrpc.messages.Either

// lsp4j types `label` as `Either<String, List<InlayHintLabelPart>>` and `tooltip` as
// `Either<String, MarkupContent>`, each with 3 setter overloads (Either/String/right branch, like
// `Diagnostic.code`). `InlayHints.makeInlayHint` (mtags-shared) calls `hint.setLabel(label.asJava)`
// with a `java.util.List[InlayHintLabelPart]` for composite labels, so the List overload is needed.
class InlayHint() {
  private var _position: Position = null
  private var _label: Either[String, ju.List[InlayHintLabelPart]] = null
  private var _kind: InlayHintKind = null
  private var _textEdits: ju.List[TextEdit] = null
  private var _tooltip: Either[String, MarkupContent] = null
  private var _paddingLeft: java.lang.Boolean = null
  private var _paddingRight: java.lang.Boolean = null
  private var _data: Object = null

  def this(position: Position, label: String) = {
    this()
    _position = position
    _label = Either.forLeft(label)
  }

  def getPosition(): Position = _position
  def setPosition(position: Position): Unit = _position = position
  def getLabel(): Either[String, ju.List[InlayHintLabelPart]] = _label
  def setLabel(label: String): Unit = _label = if (label == null) null else Either.forLeft(label)
  def setLabel(label: ju.List[InlayHintLabelPart]): Unit = _label = if (label == null) null else Either.forRight(label)
  def getKind(): InlayHintKind = _kind
  def setKind(kind: InlayHintKind): Unit = _kind = kind
  def getTextEdits(): ju.List[TextEdit] = _textEdits
  def setTextEdits(textEdits: ju.List[TextEdit]): Unit = _textEdits = textEdits
  def getTooltip(): Either[String, MarkupContent] = _tooltip
  def setTooltip(tooltip: String): Unit = _tooltip = if (tooltip == null) null else Either.forLeft(tooltip)
  def setTooltip(tooltip: MarkupContent): Unit = _tooltip = if (tooltip == null) null else Either.forRight(tooltip)
  def getPaddingLeft(): java.lang.Boolean = _paddingLeft
  def setPaddingLeft(paddingLeft: java.lang.Boolean): Unit = _paddingLeft = paddingLeft
  def getPaddingRight(): java.lang.Boolean = _paddingRight
  def setPaddingRight(paddingRight: java.lang.Boolean): Unit = _paddingRight = paddingRight
  def getData(): Object = _data
  def setData(data: Object): Unit = _data = data
}
