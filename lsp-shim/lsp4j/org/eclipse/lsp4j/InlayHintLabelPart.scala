package org.eclipse.lsp4j

class InlayHintLabelPart() {
  private var _value: String = null
  private var _tooltip: String = null
  private var _location: Location = null
  private var _command: Command = null

  def this(value: String) = {
    this()
    _value = value
  }

  def getValue(): String = _value
  def setValue(value: String): Unit = _value = value

  // lsp4j types `tooltip` as `Either<String, MarkupContent>`; nothing needs the `MarkupContent`
  // shape, so only the `String` overload is shimmed.
  def getTooltip(): String = _tooltip
  def setTooltip(tooltip: String): Unit = _tooltip = tooltip

  def getLocation(): Location = _location
  def setLocation(location: Location): Unit = _location = location
  def getCommand(): Command = _command
  def setCommand(command: Command): Unit = _command = command
}
