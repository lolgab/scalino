package org.eclipse.lsp4j.jsonrpc.messages

// Same shape as lsp4j 1.0.0: a protected two-slot union with static `forLeft`/`forRight`
// factories. The PC itself uses `scala.util.Either`, but mtags-interfaces' `ReferencesRequest`
// (`offsetOrSymbol()`, read by `PcReferencesProvider`) calls `.isLeft()`/`.getLeft()`/`.getRight()`,
// so this needs real left/right semantics.
class Either[L, R] protected (left: L, right: R) {
  def getLeft(): L = left
  def getRight(): R = right
  def get(): Object = if (left != null) left.asInstanceOf[Object] else right.asInstanceOf[Object]
  def isLeft(): Boolean = left != null
  def isRight(): Boolean = right != null

  def map[T](mapLeft: java.util.function.Function[? >: L, ? <: T], mapRight: java.util.function.Function[? >: R, ? <: T]): T =
    if (isLeft()) mapLeft.apply(left) else mapRight.apply(right)
}

object Either {
  def forLeft[L, R](left: L): Either[L, R] = new Either(left, null.asInstanceOf[R])
  def forRight[L, R](right: R): Either[L, R] = new Either(null.asInstanceOf[L], right)
}
