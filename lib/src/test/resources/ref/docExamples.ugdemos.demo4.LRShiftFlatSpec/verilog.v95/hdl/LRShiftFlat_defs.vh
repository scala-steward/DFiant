`ifndef LRSHIFTFLAT_DEFS
`define LRSHIFTFLAT_DEFS
`endif
`ifndef LRSHIFTFLAT_DEFS_MODULE
`define LRSHIFTFLAT_DEFS_MODULE
`else
`define ShiftDir_Left 0
`define ShiftDir_Right 1

function [8*14:1] ShiftDir_to_string;
  /* verilator lint_off UNUSEDSIGNAL */
  input [0:0] value;
  case (value)
    `ShiftDir_Left: ShiftDir_to_string = "ShiftDir_Left";
    `ShiftDir_Right: ShiftDir_to_string = "ShiftDir_Right";
    default: ShiftDir_to_string = "?";
  endcase
  /* verilator lint_on UNUSEDSIGNAL */
endfunction
`undef LRSHIFTFLAT_DEFS_MODULE
`endif
