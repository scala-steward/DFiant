///*
// *     This file is part of DFiant.
// *
// *     DFiant is free software: you can redistribute it and/or modify
// *     it under the terms of the GNU Lesser General Public License as published by
// *     the Free Software Foundation, either version 3 of the License, or
// *     any later version.
// *
// *     DFiant is distributed in the hope that it will be useful,
// *     but WITHOUT ANY WARRANTY; without even the implied warranty of
// *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// *     GNU Lesser General Public License for more details.
// *
// *     You should have received a copy of the GNU Lesser General Public License
// *     along with DFiant.  If not, see <https://www.gnu.org/licenses/>.
// */
//
//import DFiant._
//
//class FoldRTx2(width : Int)(implicit ctx : RTComponent.Context) extends RTComponent {
//  final val I = DFUInt(width) <> IN
//  final val O = DFUInt(width) <> OUT
//  final override protected val blackBoxFunctions = Map(O -> BlackBoxFunction(O)(I, I)((l, r) => l + r))
//}
//
//abstract class FoldComp(implicit ctx : DFComponent.Context[FoldComp]) extends DFComponent[FoldComp] {
//  val i = DFUInt(8) <> IN
//  val o = DFUInt(8) <> OUT
//  final override protected val blackBoxFunctions = Map(o -> BlackBoxFunction(o)(i, i)((l, r) => l + r))
//}
//object FoldComp {
//  implicit val ev : FoldComp => Unit = ifc => {
//    import ifc._
//    if (i.isConstant) o := 0
//    else {
//      RTOp2.+(o, i, i)
////      val rt = new FoldRTx2(8)
////      rt.I <> i
////      rt.O <> o
//    }
//  }
//}
//
//trait FoldTest extends DFDesign {
//  val i = DFUInt(8) <> IN
//  val o = DFUInt(8) <> OUT
//
//  val io = new FoldComp {}
////  io.i <> 0
//  i <> io.i
//  o <> io.o
//}
//
//object FoldApp extends DFApp {
//  import internals._
//  val foldtest = new FoldTest {}
//  foldtest.printCodeString
//  println("------------------------------")
//  foldtest.io.unfold
//  foldtest.printCodeString
//  println("------------------------------")
//  foldtest.io.fold
//  foldtest.printCodeString
//}
//
