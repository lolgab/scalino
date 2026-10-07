import scala.quoted.*

def nameOf[t: Type](using Quotes): String =
  quotes.reflect.TypeRepr.of[t].typeSymbol.name

inline def describe[T]: String = ${ describeImpl[T] }

def describeImpl[T: Type](using Quotes): Expr[String] =
  Type.of[T] match
    case '[Int] => Expr("Int")
    case '[List[Int]] => Expr("List[Int]")
    case '[Option[Option[t]]] => Expr("Option[Option[" + nameOf[t] + "]]")
    case '[List[t]] => Expr("List of " + nameOf[t])
    case '[Map[k, v]] => Expr("Map " + nameOf[k] + " -> " + nameOf[v])
    case '[(a, b)] => Expr("Pair " + nameOf[a] + " , " + nameOf[b])
    case '[Array[t]] => Expr("Array of " + nameOf[t])
    case '[Either[l, List[r]]] => Expr("Either " + nameOf[l] + " | List of " + nameOf[r])
    case '[Seq[t]] => Expr("Seq of " + nameOf[t])
    case '[type t <: AnyVal; t] => Expr("AnyVal " + nameOf[t])
    case _ => Expr("other")

/** Uses the pattern-bound type inside a generated quote. */
inline def emptyOf[T]: Any = ${ emptyOfImpl[T] }

def emptyOfImpl[T: Type](using Quotes): Expr[Any] =
  Type.of[T] match
    case '[List[t]] => '{ List.empty[t] }
    case '[Option[t]] => '{ Option.empty[t] }
    case _ => '{ null }

/** Recursive structural match on `*:` (what jsoniter-scala's `genericTupleTypeArgs` does). */
inline def tupleNames[T <: Tuple]: String = ${ tupleNamesImpl[T] }

def tupleNamesImpl[T <: Tuple: Type](using Quotes): Expr[String] =
  def go(tpe: Type[?]): List[String] = tpe match
    case '[EmptyTuple] => Nil
    case '[h *: tl] => nameOf[h] :: go(Type.of[tl])
  Expr(go(Type.of[T]).mkString(","))

/** Same variable twice, and a pattern that only the first of two cases satisfies. */
inline def sameTwice[T]: String = ${ sameTwiceImpl[T] }

def sameTwiceImpl[T: Type](using Quotes): Expr[String] =
  Type.of[T] match
    case '[(a, a)] => Expr("same " + nameOf[a])
    case '[(a, b)] => Expr("different " + nameOf[a] + "/" + nameOf[b])
    case _ => Expr("not a pair")
