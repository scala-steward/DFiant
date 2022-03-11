package DFiant.core
import DFiant.internals.*
import DFiant.compiler.ir

class DFOwner[+T <: ir.DFOwner](val value: T | DFError) extends DFMember[T] //AnyVal with
object DFOwner:
  extension [T <: ir.DFOwner](owner: T) def asFE: DFOwner[T] = DFOwner(owner)

type DFOwnerAny = DFOwner[ir.DFOwner]
