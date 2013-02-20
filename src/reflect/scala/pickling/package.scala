/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala

import scala.language.experimental.macros

package object pickling {

  import scala.reflect.macros.Context
  import ir._

  // TOGGLE DEBUGGING
  var debugEnabled: Boolean = true
  def debug(output: => String) = if (debugEnabled) println(output)

  implicit class PickleOps[T](x: T) {
    def pickle(implicit pickler: Pickler[T], format: PickleFormat): Pickle = {
      pickler.pickle(x)
      //format.write(ir)
    }
  }

  implicit def genPickler[T]: Pickler[T] = macro genPicklerImpl[T]

  def genPicklerImpl[T: c.WeakTypeTag](c: Context): c.Expr[Pickler[T]] = {
    import c.universe._
    val irs = new IRs[c.type](c)
    import irs._

    val tt = weakTypeTag[T]
    val tpe = tt.tpe
    try {
      val pickleFormatTree: Tree = c.inferImplicitValue(typeOf[PickleFormat]) match {
        case EmptyTree => c.abort(c.enclosingPosition, "Couldn't find implicit PickleFormat")
        case tree => tree
      }

      // get instance of PickleFormat
      val pickleFormat = c.eval(c.Expr[PickleFormat](c.resetAllAttrs(pickleFormatTree)))

      // get all declared fields (and not accessor methods)
      val fields = tpe.declarations.filter(!_.isMethod)

      // this is unneeded now, but it's useful for debugging
      //--from here
      val implicitPicklers = fields.map{ field =>
        c.inferImplicitValue(
          typeRef(NoPrefix, typeOf[Pickler[_]].typeSymbol, List(field.typeSignatureIn(tpe)))
        )
      }
      debug("Implicit values found per field: " + implicitPicklers)
      //--to here

      // build IR
      debug("The tpe just before IR creation is: " + tpe)
      val oir = compose(ObjectIR(tpe, null, List()))
      val fieldIR2Pickler = oir.fields.map(field =>
        // infer implicit pickler, if not found, try to generate pickler for field
        c.inferImplicitValue(
          typeRef(NoPrefix, typeOf[Pickler[_]].typeSymbol, List(field.tpe))
        ) match {
          case EmptyTree =>
            // EmptyTree essentially means that no pickler could be generated, so abort with error msg
            c.abort(c.enclosingPosition, "Couldn't generate implicit Pickler[" + field.tpe + "]")
          case tree =>
            field -> tree
        }
      ).toMap

      val chunked: (List[Any], List[FieldIR]) = pickleFormat.genObjectTemplate(irs)(flatten(oir))
      val chunks = chunked._1
      val holes  = chunked._2
      debug("chunks: "+chunks.mkString("]["))
      debug("chunks.size:" + chunks.size)
      debug("holes.size:" + holes.size)

      // fill in holes
      def genFieldAccess(fir: FieldIR): c.Tree = {
        // obj.fieldName
        debug("selecting member [" + fir.name + "]")
        fieldIR2Pickler.get(fir) match {
          case None =>
            Select(Select(Select(Ident("obj"), fir.name), "pickle"), "value")
          case Some(picklerTree) =>
            Select(Apply(Select(picklerTree, "pickle"), List(Select(Ident("obj"), fir.name))), "value")
            //Select(Ident("obj"), ir.name)
        }
      }

      def genChunkLiteral(chunk: Any): c.Tree =
        Literal(Constant(chunk))

      // assemble the pickle from the template
      val cs = chunks.init.zipWithIndex
      val pickleChunks: List[c.Tree] = for (c <- cs) yield {
        Apply(Select(pickleFormatTree, "concatChunked"), List(genChunkLiteral(c._1), genFieldAccess(holes(c._2))))
      }
      val concatAllTree = (pickleChunks :+ genChunkLiteral(chunks.last)) reduceLeft { (left: c.Tree, right: c.Tree) =>
        Apply(Select(pickleFormatTree, "concatChunked"), List(left, right))
      }

      val castAndAssignTree: c.Tree =
        ValDef(Modifiers(), "obj", TypeTree(tpe),
          TypeApply(Select(Ident("raw"), "asInstanceOf"), List(TypeTree(tpe)))
        )

      // pass the assembled pickle into the generated runtime code
      reify {
        new Pickler[T] {
          def pickle(raw: Any): Pickle = {
            c.Expr[Unit](castAndAssignTree).splice //akin to: val obj = raw.asInstanceOf[<tpe>]
            new Pickle {
              val value = {
                c.Expr[Any](concatAllTree).splice
              }
            }
          }
        }
      }

    } catch {
      case t: Throwable => t.printStackTrace(); throw t
    }
  }
}

package pickling {
  import scala.reflect.macros.Context

  trait Pickler[T] {
    def pickle(obj: Any): Pickle
    //def unpickle(p: Pickle): T
  }

  trait Pickle {
    val value: Any
  }

  trait HasPicklerDispatch {
    def dispatchTo: Pickler[_]
  }

  // PickleFormat is intended to be used at compile time
  // to generate a pickle template which is to be inlined
  trait PickleFormat {
    import ir._

    def genTypeTemplate(c: Context)(tpe: c.universe.Type): Any
    def genObjectTemplate[C <: Context with Singleton](irs: IRs[C])(ir: irs.ObjectIR): (List[Any], List[irs.FieldIR])
    def genFieldTemplate[C <: Context with Singleton](irs: IRs[C])(ir: irs.FieldIR): (List[Any], List[irs.FieldIR])

    def concatChunked(c1: Any, c2: Any): Any
  }
}

