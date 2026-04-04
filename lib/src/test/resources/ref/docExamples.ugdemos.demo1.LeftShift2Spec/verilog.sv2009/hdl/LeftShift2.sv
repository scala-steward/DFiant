/* A two-bits left shifter */
`default_nettype none
`timescale 1ns/1ps

module LeftShift2(
  /* bits input */
  input  wire logic [7:0] iBits,
  /* bits output */
  output      logic [7:0] oBits
);
  `include "dfhdl_defs.svh"
  assign oBits = iBits << 2;
endmodule
