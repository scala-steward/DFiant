package DFiant.compiler.stages

import DFiant.compiler.analysis.*
import DFiant.compiler.ir.*
import DFiant.compiler.patching.*
import DFiant.internals.*

private class ViaConnection(db: DB) extends Stage(db):
  override def transform: DB =
    val patchList: List[(DFMember, Patch)] = designDB.designMemberList.flatMap {
      case (ib, members) if !ib.isTop =>
        // getting only ports that are not already connected to variables unless these are clock variables
        val (ports, nets): (List[DFVal], List[DFNet]) =
          members.foldRight((List.empty[DFVal], List.empty[DFNet])) {
            case (p @ DclOut(), (ports, nets)) =>
              val conns = p.getConnectionsFrom
              conns.headOption match
                case Some(n) if n.hasLateConstruction =>
                  (ports, nets) // already has via connections
                case Some(n @ DFNet.Connection(DclVar(), _)) if conns.size == 1 =>
                  (ports, n :: nets)
                case _ => (p :: ports, nets)
            case (p @ DclIn(), (ports, nets)) =>
              p.getConnectionTo match
                // we have a single net that is assigned not more than once
                // (otherwise, for RTL purposes we require another value so an internal multi-assignment rtl variable/reg
                // can be assigned into a signal/wire)
                case Some(n) if n.hasLateConstruction =>
                  (ports, nets) // already has via connections
                case Some(n @ DFNet.Connection(_, v @ DclVar())) if v.getAssignmentsTo.isEmpty =>
                  (ports, n :: nets)
                case _ => (p :: ports, nets)
            case (_, x) => x
          }
        // Meta design to construct the variables to be connected to the ports
        val addVarsDsn = new MetaDesign:
          val portsToVars: List[(DFVal, DFVal)] = ports.map { p =>
            p -> p.asValAny.genNewVar(using dfc.setName(s"${ib.name}_${p.name}")).asIR
          }
        // Meta design for connections between ports and the added variables
        val connectDsn = new MetaDesign:
          val thisDFC: DFC = dfc
          val refPatches: List[(DFMember, Patch)] = addVarsDsn.portsToVars.map { case (p, v) =>
            given DFC = thisDFC.setLateConstruction(true)
            p match
              case _ @DclOut() => v.asVarAny.<>(p.asValAny)
              case _ @DclIn()  => p.asVarAny.<>(v.asValAny)
              case _           => ???
            (
              p,
              Patch.Replace(
                v,
                Patch.Replace.Config.ChangeRefOnly,
                Patch.Replace.RefFilter.Outside(ib)
              )
            )
          }
          val movedNets: List[(DFMember, Patch)] = nets.map { n =>
            plantMember(
              n.setMeta(_.setLateConstruction(true))
            ) // planet the net with a
            (n -> Patch.Remove)
          }
        (ib -> Patch.Add(addVarsDsn, Patch.Add.Config.Before)) ::
          (ib -> Patch.Add(
            connectDsn,
            Patch.Add.Config.InsideLast
          )) :: connectDsn.refPatches ++ connectDsn.movedNets
      case _ => Nil
    }
    designDB.patch(patchList)
  end transform
end ViaConnection

extension [T: HasDB](t: T) def viaConnection: DB = new ViaConnection(t.db).transform