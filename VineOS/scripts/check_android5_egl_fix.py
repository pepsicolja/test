#!/usr/bin/env python3
"""Execute the original/fixed Thumb return sequence (requires Unicorn)."""
from unicorn import Uc, UC_ARCH_ARM, UC_MODE_THUMB
from unicorn.arm_const import UC_ARM_REG_LR, UC_ARM_REG_R0


def execute(code, count):
    vm = Uc(UC_ARCH_ARM, UC_MODE_THUMB)
    vm.mem_map(0x1000, 0x1000)
    vm.mem_write(0x1000, bytes.fromhex(code))
    vm.reg_write(UC_ARM_REG_R0, count & 0xFFFFFFFF)
    vm.reg_write(UC_ARM_REG_LR, 0x1101)
    vm.emu_start(0x1001, 0x1100, count=8)
    return vm.reg_read(UC_ARM_REG_R0)


if __name__ == "__main__":
    # CMP r0,0; ITE condition; conditional MOV 0/1; BX lr.
    original = "0028d4bf002001207047"
    fixed = "0028b4bf002001207047"
    for value in (-2147483648, -1, 0, 1, 17, 2147483647):
        assert execute(original, value) == int(value > 0)
        assert execute(fixed, value) == int(value >= 0)
    print("12 Thumb execution checks passed: zero becomes success; negative/positive behavior retained")
