`default_nettype none
`timescale 1ns/1ps

module Counter#(parameter integer width = 8)(
  input  wire clk,
  input  wire rst,
  input  wire en,
  output reg [width - 1:0] cnt
);
  `include "dfhdl_defs.vh"
  always @(posedge clk)
  begin
    if (rst == 1'b1) cnt <= `dPW(0, 1, width);
    else begin
      if (en) cnt <= cnt + `dPW(1, 1, width);
    end
  end
endmodule
