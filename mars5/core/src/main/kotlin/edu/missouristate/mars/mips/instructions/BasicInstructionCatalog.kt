package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.mips.instructions.basic.InstrAbsD
import edu.missouristate.mars.mips.instructions.basic.InstrAbsS
import edu.missouristate.mars.mips.instructions.basic.InstrAdd
import edu.missouristate.mars.mips.instructions.basic.InstrAddD
import edu.missouristate.mars.mips.instructions.basic.InstrAddS
import edu.missouristate.mars.mips.instructions.basic.InstrAddi
import edu.missouristate.mars.mips.instructions.basic.InstrAddiu
import edu.missouristate.mars.mips.instructions.basic.InstrAddu
import edu.missouristate.mars.mips.instructions.basic.InstrAnd
import edu.missouristate.mars.mips.instructions.basic.InstrAndi
import edu.missouristate.mars.mips.instructions.basic.InstrBc1f1
import edu.missouristate.mars.mips.instructions.basic.InstrBc1f2
import edu.missouristate.mars.mips.instructions.basic.InstrBc1t1
import edu.missouristate.mars.mips.instructions.basic.InstrBc1t2
import edu.missouristate.mars.mips.instructions.basic.InstrBeq
import edu.missouristate.mars.mips.instructions.basic.InstrBgez
import edu.missouristate.mars.mips.instructions.basic.InstrBgezal
import edu.missouristate.mars.mips.instructions.basic.InstrBgtz
import edu.missouristate.mars.mips.instructions.basic.InstrBlez
import edu.missouristate.mars.mips.instructions.basic.InstrBltz
import edu.missouristate.mars.mips.instructions.basic.InstrBltzal
import edu.missouristate.mars.mips.instructions.basic.InstrBne
import edu.missouristate.mars.mips.instructions.basic.InstrBreak1
import edu.missouristate.mars.mips.instructions.basic.InstrBreak2
import edu.missouristate.mars.mips.instructions.basic.InstrCEqD1
import edu.missouristate.mars.mips.instructions.basic.InstrCEqD2
import edu.missouristate.mars.mips.instructions.basic.InstrCEqS1
import edu.missouristate.mars.mips.instructions.basic.InstrCEqS2
import edu.missouristate.mars.mips.instructions.basic.InstrCLeD1
import edu.missouristate.mars.mips.instructions.basic.InstrCLeD2
import edu.missouristate.mars.mips.instructions.basic.InstrCLeS1
import edu.missouristate.mars.mips.instructions.basic.InstrCLeS2
import edu.missouristate.mars.mips.instructions.basic.InstrCLtD1
import edu.missouristate.mars.mips.instructions.basic.InstrCLtD2
import edu.missouristate.mars.mips.instructions.basic.InstrCLtS1
import edu.missouristate.mars.mips.instructions.basic.InstrCLtS2
import edu.missouristate.mars.mips.instructions.basic.InstrCeilWD
import edu.missouristate.mars.mips.instructions.basic.InstrCeilWS
import edu.missouristate.mars.mips.instructions.basic.InstrClo
import edu.missouristate.mars.mips.instructions.basic.InstrClz
import edu.missouristate.mars.mips.instructions.basic.InstrCvtDS
import edu.missouristate.mars.mips.instructions.basic.InstrCvtDW
import edu.missouristate.mars.mips.instructions.basic.InstrCvtSD
import edu.missouristate.mars.mips.instructions.basic.InstrCvtSW
import edu.missouristate.mars.mips.instructions.basic.InstrCvtWD
import edu.missouristate.mars.mips.instructions.basic.InstrCvtWS
import edu.missouristate.mars.mips.instructions.basic.InstrDiv
import edu.missouristate.mars.mips.instructions.basic.InstrDivD
import edu.missouristate.mars.mips.instructions.basic.InstrDivS
import edu.missouristate.mars.mips.instructions.basic.InstrDivu
import edu.missouristate.mars.mips.instructions.basic.InstrEret
import edu.missouristate.mars.mips.instructions.basic.InstrFloorWD
import edu.missouristate.mars.mips.instructions.basic.InstrFloorWS
import edu.missouristate.mars.mips.instructions.basic.InstrJ
import edu.missouristate.mars.mips.instructions.basic.InstrJal
import edu.missouristate.mars.mips.instructions.basic.InstrJalr1
import edu.missouristate.mars.mips.instructions.basic.InstrJalr2
import edu.missouristate.mars.mips.instructions.basic.InstrJr
import edu.missouristate.mars.mips.instructions.basic.InstrLb
import edu.missouristate.mars.mips.instructions.basic.InstrLbu
import edu.missouristate.mars.mips.instructions.basic.InstrLdc1
import edu.missouristate.mars.mips.instructions.basic.InstrLh
import edu.missouristate.mars.mips.instructions.basic.InstrLhu
import edu.missouristate.mars.mips.instructions.basic.InstrLl
import edu.missouristate.mars.mips.instructions.basic.InstrLui
import edu.missouristate.mars.mips.instructions.basic.InstrLw
import edu.missouristate.mars.mips.instructions.basic.InstrLwc1
import edu.missouristate.mars.mips.instructions.basic.InstrLwl
import edu.missouristate.mars.mips.instructions.basic.InstrLwr
import edu.missouristate.mars.mips.instructions.basic.InstrMadd
import edu.missouristate.mars.mips.instructions.basic.InstrMaddu
import edu.missouristate.mars.mips.instructions.basic.InstrMfc0
import edu.missouristate.mars.mips.instructions.basic.InstrMfc1
import edu.missouristate.mars.mips.instructions.basic.InstrMfhi
import edu.missouristate.mars.mips.instructions.basic.InstrMflo
import edu.missouristate.mars.mips.instructions.basic.InstrMovD
import edu.missouristate.mars.mips.instructions.basic.InstrMovS
import edu.missouristate.mars.mips.instructions.basic.InstrMovf1
import edu.missouristate.mars.mips.instructions.basic.InstrMovf2
import edu.missouristate.mars.mips.instructions.basic.InstrMovfD1
import edu.missouristate.mars.mips.instructions.basic.InstrMovfD2
import edu.missouristate.mars.mips.instructions.basic.InstrMovfS1
import edu.missouristate.mars.mips.instructions.basic.InstrMovfS2
import edu.missouristate.mars.mips.instructions.basic.InstrMovn
import edu.missouristate.mars.mips.instructions.basic.InstrMovnD
import edu.missouristate.mars.mips.instructions.basic.InstrMovnS
import edu.missouristate.mars.mips.instructions.basic.InstrMovt1
import edu.missouristate.mars.mips.instructions.basic.InstrMovt2
import edu.missouristate.mars.mips.instructions.basic.InstrMovtD1
import edu.missouristate.mars.mips.instructions.basic.InstrMovtD2
import edu.missouristate.mars.mips.instructions.basic.InstrMovtS1
import edu.missouristate.mars.mips.instructions.basic.InstrMovtS2
import edu.missouristate.mars.mips.instructions.basic.InstrMovz
import edu.missouristate.mars.mips.instructions.basic.InstrMovzD
import edu.missouristate.mars.mips.instructions.basic.InstrMovzS
import edu.missouristate.mars.mips.instructions.basic.InstrMsub
import edu.missouristate.mars.mips.instructions.basic.InstrMsubu
import edu.missouristate.mars.mips.instructions.basic.InstrMtc0
import edu.missouristate.mars.mips.instructions.basic.InstrMtc1
import edu.missouristate.mars.mips.instructions.basic.InstrMthi
import edu.missouristate.mars.mips.instructions.basic.InstrMtlo
import edu.missouristate.mars.mips.instructions.basic.InstrMul
import edu.missouristate.mars.mips.instructions.basic.InstrMulD
import edu.missouristate.mars.mips.instructions.basic.InstrMulS
import edu.missouristate.mars.mips.instructions.basic.InstrMult
import edu.missouristate.mars.mips.instructions.basic.InstrMultu
import edu.missouristate.mars.mips.instructions.basic.InstrNegD
import edu.missouristate.mars.mips.instructions.basic.InstrNegS
import edu.missouristate.mars.mips.instructions.basic.InstrNop
import edu.missouristate.mars.mips.instructions.basic.InstrNor
import edu.missouristate.mars.mips.instructions.basic.InstrOr
import edu.missouristate.mars.mips.instructions.basic.InstrOri
import edu.missouristate.mars.mips.instructions.basic.InstrRoundWD
import edu.missouristate.mars.mips.instructions.basic.InstrRoundWS
import edu.missouristate.mars.mips.instructions.basic.InstrSb
import edu.missouristate.mars.mips.instructions.basic.InstrSc
import edu.missouristate.mars.mips.instructions.basic.InstrSdc1
import edu.missouristate.mars.mips.instructions.basic.InstrSh
import edu.missouristate.mars.mips.instructions.basic.InstrSll
import edu.missouristate.mars.mips.instructions.basic.InstrSllv
import edu.missouristate.mars.mips.instructions.basic.InstrSlt
import edu.missouristate.mars.mips.instructions.basic.InstrSlti
import edu.missouristate.mars.mips.instructions.basic.InstrSltiu
import edu.missouristate.mars.mips.instructions.basic.InstrSltu
import edu.missouristate.mars.mips.instructions.basic.InstrSqrtD
import edu.missouristate.mars.mips.instructions.basic.InstrSqrtS
import edu.missouristate.mars.mips.instructions.basic.InstrSra
import edu.missouristate.mars.mips.instructions.basic.InstrSrav
import edu.missouristate.mars.mips.instructions.basic.InstrSrl
import edu.missouristate.mars.mips.instructions.basic.InstrSrlv
import edu.missouristate.mars.mips.instructions.basic.InstrSub
import edu.missouristate.mars.mips.instructions.basic.InstrSubD
import edu.missouristate.mars.mips.instructions.basic.InstrSubS
import edu.missouristate.mars.mips.instructions.basic.InstrSubu
import edu.missouristate.mars.mips.instructions.basic.InstrSw
import edu.missouristate.mars.mips.instructions.basic.InstrSwc1
import edu.missouristate.mars.mips.instructions.basic.InstrSwl
import edu.missouristate.mars.mips.instructions.basic.InstrSwr
import edu.missouristate.mars.mips.instructions.basic.InstrSyscall
import edu.missouristate.mars.mips.instructions.basic.InstrTeq
import edu.missouristate.mars.mips.instructions.basic.InstrTeqi
import edu.missouristate.mars.mips.instructions.basic.InstrTge
import edu.missouristate.mars.mips.instructions.basic.InstrTgei
import edu.missouristate.mars.mips.instructions.basic.InstrTgeiu
import edu.missouristate.mars.mips.instructions.basic.InstrTgeu
import edu.missouristate.mars.mips.instructions.basic.InstrTlt
import edu.missouristate.mars.mips.instructions.basic.InstrTlti
import edu.missouristate.mars.mips.instructions.basic.InstrTltiu
import edu.missouristate.mars.mips.instructions.basic.InstrTltu
import edu.missouristate.mars.mips.instructions.basic.InstrTne
import edu.missouristate.mars.mips.instructions.basic.InstrTnei
import edu.missouristate.mars.mips.instructions.basic.InstrTruncWD
import edu.missouristate.mars.mips.instructions.basic.InstrTruncWS
import edu.missouristate.mars.mips.instructions.basic.InstrXor
import edu.missouristate.mars.mips.instructions.basic.InstrXori

/** Basic instruction factories in decoding preference order. */
internal val basicInstructionFactories: List<() -> BasicInstruction> = listOf(
    ::InstrNop,
    ::InstrAdd,
    ::InstrSub,
    ::InstrAddi,
    ::InstrAddu,
    ::InstrSubu,
    ::InstrAddiu,
    ::InstrMult,
    ::InstrMultu,
    ::InstrMul,
    ::InstrMadd,
    ::InstrMaddu,
    ::InstrMsub,
    ::InstrMsubu,
    ::InstrDiv,
    ::InstrDivu,
    ::InstrMfhi,
    ::InstrMflo,
    ::InstrMthi,
    ::InstrMtlo,
    ::InstrAnd,
    ::InstrOr,
    ::InstrAndi,
    ::InstrOri,
    ::InstrNor,
    ::InstrXor,
    ::InstrXori,
    ::InstrSll,
    ::InstrSllv,
    ::InstrSrl,
    ::InstrSra,
    ::InstrSrav,
    ::InstrSrlv,
    ::InstrLw,
    ::InstrLl,
    ::InstrLwl,
    ::InstrLwr,
    ::InstrSw,
    ::InstrSc,
    ::InstrSwl,
    ::InstrSwr,
    ::InstrLui,
    ::InstrBeq,
    ::InstrBne,
    ::InstrBgez,
    ::InstrBgezal,
    ::InstrBgtz,
    ::InstrBlez,
    ::InstrBltz,
    ::InstrBltzal,
    ::InstrSlt,
    ::InstrSltu,
    ::InstrSlti,
    ::InstrSltiu,
    ::InstrMovn,
    ::InstrMovz,
    ::InstrMovf1,
    ::InstrMovf2,
    ::InstrMovt1,
    ::InstrMovt2,
    ::InstrBreak1,
    ::InstrBreak2,
    ::InstrSyscall,
    ::InstrJ,
    ::InstrJr,
    ::InstrJal,
    ::InstrJalr1,
    ::InstrJalr2,
    ::InstrLb,
    ::InstrLh,
    ::InstrLhu,
    ::InstrLbu,
    ::InstrSb,
    ::InstrSh,
    ::InstrClo,
    ::InstrClz,
    ::InstrMfc0,
    ::InstrMtc0,
    ::InstrAddS,
    ::InstrSubS,
    ::InstrMulS,
    ::InstrDivS,
    ::InstrSqrtS,
    ::InstrFloorWS,
    ::InstrCeilWS,
    ::InstrRoundWS,
    ::InstrTruncWS,
    ::InstrAddD,
    ::InstrSubD,
    ::InstrMulD,
    ::InstrDivD,
    ::InstrSqrtD,
    ::InstrFloorWD,
    ::InstrCeilWD,
    ::InstrRoundWD,
    ::InstrTruncWD,
    ::InstrBc1t1,
    ::InstrBc1t2,
    ::InstrBc1f1,
    ::InstrBc1f2,
    ::InstrCEqS1,
    ::InstrCEqS2,
    ::InstrCLeS1,
    ::InstrCLeS2,
    ::InstrCLtS1,
    ::InstrCLtS2,
    ::InstrCEqD1,
    ::InstrCEqD2,
    ::InstrCLeD1,
    ::InstrCLeD2,
    ::InstrCLtD1,
    ::InstrCLtD2,
    ::InstrAbsS,
    ::InstrAbsD,
    ::InstrCvtDS,
    ::InstrCvtDW,
    ::InstrCvtSD,
    ::InstrCvtSW,
    ::InstrCvtWD,
    ::InstrCvtWS,
    ::InstrMovD,
    ::InstrMovfD1,
    ::InstrMovfD2,
    ::InstrMovtD1,
    ::InstrMovtD2,
    ::InstrMovnD,
    ::InstrMovzD,
    ::InstrMovS,
    ::InstrMovfS1,
    ::InstrMovfS2,
    ::InstrMovtS1,
    ::InstrMovtS2,
    ::InstrMovnS,
    ::InstrMovzS,
    ::InstrMfc1,
    ::InstrMtc1,
    ::InstrNegD,
    ::InstrNegS,
    ::InstrLwc1,
    ::InstrLdc1,
    ::InstrSwc1,
    ::InstrSdc1,
    ::InstrTeq,
    ::InstrTeqi,
    ::InstrTne,
    ::InstrTnei,
    ::InstrTge,
    ::InstrTgeu,
    ::InstrTgei,
    ::InstrTgeiu,
    ::InstrTlt,
    ::InstrTltu,
    ::InstrTlti,
    ::InstrTltiu,
    ::InstrEret,
)
