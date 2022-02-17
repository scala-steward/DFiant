package DFiant.compiler.stages.vhdl
import DFiant.compiler.printing.*
import DFiant.compiler.ir.*
import DFiant.compiler.analysis.*
import DFiant.internals.*
import DFVal.*

protected trait RTTokenPrinter extends AbstractTokenPrinter:
  def csDFBitsData(dfType: DFBits, data: (BitVector, BitVector)): String =
    val valueBits: BitVector = data._1
    val bubbleBits: BitVector = data._2
    val width = dfType.width
    def binZip(v: BitVector, b: BitVector, bubbleChar: Char): String =
      v.toBin
        .zip(b.toBin)
        .map {
          case (_, '1')       => bubbleChar
          case (zeroOrOne, _) => zeroOrOne
        }
        .mkString
    end binZip
    def hexZip(
        v: BitVector,
        b: BitVector,
        bubbleChar: Char,
        allowBinMode: Boolean
    ): Option[String] =
      Some(
        v.toHex
          .zip(b.toHex)
          .flatMap {
            case (_, 'F' | 'f') => s"$bubbleChar"
            case (h, '0')       => s"$h"
            case (h, b) if allowBinMode =>
              s"{${binZip(BitVector(h), BitVector(b), bubbleChar)}}"
            case _ => return None
          }
          .mkString
      )
    end hexZip
    def toBinString(bubbleChar: Char): String =
      binZip(valueBits, bubbleBits, bubbleChar)
    def toHexString(bubbleChar: Char, allowBinMode: Boolean): Option[String] =
      if (width % 4 == 0) hexZip(valueBits, bubbleBits, bubbleChar, allowBinMode)
      else
        val headWidth = width % 4
        val (headValue, theRestValue) = valueBits.splitAt(headWidth)
        val (headBubble, theRestBubble) = bubbleBits.splitAt(headWidth)

        val headOption =
          if (headBubble === BitVector.high(headWidth)) Some(s"$bubbleChar")
          else
            hexZip(
              headValue.resize(4),
              headBubble.resize(4),
              bubbleChar,
              allowBinMode
            )
        val theRestOption =
          hexZip(theRestValue, theRestBubble, bubbleChar, allowBinMode)
        for (h <- headOption; tr <- theRestOption) yield h + tr
    end toHexString
    val binRep = toBinString('?')
    val hexRep = s"${width}'${toHexString('?', allowBinMode = true).get}"
    // choosing the shorter representation for readability
    if (binRep.length <= hexRep.length) s"""b"$binRep""""
    else s"""h"$hexRep""""
  end csDFBitsData

  def csDFBoolOrBitData(dfType: DFBoolOrBit, data: Option[Boolean]): String =
    data match
      case Some(value) =>
        dfType match
          case DFBool => value.toString
          case DFBit  => if (value) "1" else "0"
      case None => "?"
  def csDFDecimalData(dfType: DFDecimal, data: Option[BigInt]): String =
    data match
      case Some(value) =>
        if (dfType.fractionWidth == 0) // DFXInt
          val interpStr = if (dfType.signed) "sd" else "d"
          s"""$interpStr"${dfType.width}'$value""""
        else ??? // DFXFix
      case None => "?"
  def csDFEnumData(dfType: DFEnum, data: Option[BigInt]): String =
    data match
      case Some(value) =>
        val entryName = dfType.entries.find(_._2 == value).get._1
        s"${dfType.getName}.${entryName}"
      case None => "?"
  def csDFVectorData(dfType: DFVector, data: Vector[Any]): String =
    s"Vector${data.map(x => csDFToken(DFToken.forced(dfType.cellType, x))).mkStringBrackets}"
  def csDFOpaqueData(dfType: DFOpaque, data: Any): String =
    s"${csDFToken(DFToken.forced(dfType.actualType, data)).applyBrackets()}.as(${dfType.getName})"
  def csDFStructData(dfType: DFStruct, data: List[Any]): String =
    if (dfType.getName.isEmpty)
      csDFTupleData(dfType.fieldMap.values.toList, data)
    else
      dfType.getName + dfType.fieldMap
        .lazyZip(data)
        .map { case ((n, t), d) =>
          s"$n = ${csDFToken(DFToken.forced(t, d))}"
        }
        .mkStringBrackets
  def csDFTupleData(dfTypes: List[DFType], data: List[Any]): String =
    (dfTypes lazyZip data)
      .map((t, d) => csDFToken(DFToken.forced(t, d)))
      .mkStringBrackets
  def csDFTokenSeq(tokenSeq: Seq[DFTokenAny]): String =
    tokenSeq.map(csDFToken).mkStringBrackets
end RTTokenPrinter