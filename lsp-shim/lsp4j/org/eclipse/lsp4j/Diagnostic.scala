package org.eclipse.lsp4j

import java.util as ju
import org.eclipse.lsp4j.jsonrpc.messages.Either

class Diagnostic() {
  private var _range: Range = null
  private var _message: Either[String, MarkupContent] = null
  private var _severity: DiagnosticSeverity = null
  private var _source: String = null
  private var _code: Either[String, java.lang.Integer] = null
  private var _tags: ju.List[DiagnosticTag] = null
  private var _data: Object = null

  def this(range: Range, message: String) = {
    this()
    _range = range
    _message = Either.forLeft(message)
  }

  def this(range: Range, message: String, severity: DiagnosticSeverity, source: String) = {
    this(range, message)
    _severity = severity
    _source = source
  }

  def getRange(): Range = _range
  def setRange(range: Range): Unit = _range = range

  // lsp4j types `message` as `Either<String, MarkupContent>`. The constructor/setter take only
  // `String` (as `DiagnosticProvider.scala` uses it), but the getter keeps the Either shape.
  def getMessage(): Either[String, MarkupContent] = _message
  def setMessage(message: String): Unit = _message = Either.forLeft(message)
  def getSeverity(): DiagnosticSeverity = _severity
  def setSeverity(severity: DiagnosticSeverity): Unit = _severity = severity
  def getSource(): String = _source
  def setSource(source: String): Unit = _source = source

  // lsp4j types `code` as `Either<String, Integer>` with setter overloads for Either/String/Integer;
  // getCode() has only the Either signature, which PcLanguageServer unwraps.
  def getCode(): Either[String, java.lang.Integer] = _code
  def setCode(code: java.lang.Integer): Unit = _code = if (code == null) null else Either.forRight(code)
  def setCode(code: String): Unit = _code = if (code == null) null else Either.forLeft(code)

  def getTags(): ju.List[DiagnosticTag] = _tags
  def setTags(tags: ju.List[DiagnosticTag]): Unit = _tags = tags
  def getData(): Object = _data
  def setData(data: Object): Unit = _data = data
}
