package dfhdl.internals

import scala.annotation.Annotation

final case class Position(
    file: String,
    lineStart: Int,
    columnStart: Int,
    lineEnd: Int,
    columnEnd: Int
) derives CanEqual:
  override def toString: String = s"$file:$lineStart:$columnStart - $lineEnd:$columnEnd"

object Position:
  val unknown = Position("", 0, 0, 0, 0)

trait MetaContext:
  def setMeta(
      nameOpt: Option[String],
      position: Position,
      doc: Option[String],
      annotations: List[Annotation]
  ): this.type

  def setName(name: String): this.type

  def anonymize: this.type

  val nameOpt: Option[String]
  val position: Position
  final val isAnonymous: Boolean = nameOpt.isEmpty
  final val name: String =
    nameOpt.getOrElse(s"anon${this.hashString}")
end MetaContext

class metaContextDelegate extends scala.annotation.StaticAnnotation
