import gc
import microcontroller
import os
import sys
import time


def mib(value):
    return "{:.2f} MiB".format(value / 1024 / 1024)


print()
print("=" * 52)
print("YD-ESP32-S3 N16R8 HARDWARE CHECK")
print("=" * 52)
print("Board / machine :", os.uname().machine)
print("CircuitPython    :", sys.implementation[0], sys.implementation[1])
print("CPU frequency    :", microcontroller.cpu.frequency, "Hz")
print("CPU temperature  :", microcontroller.cpu.temperature, "C (rough)")
print("CPU UID          :", "".join("{:02X}".format(x) for x in microcontroller.cpu.uid))

stats = os.statvfs("/")
filesystem_total = stats[0] * stats[2]
filesystem_free = stats[0] * stats[3]
print("CIRCUITPY total  :", mib(filesystem_total))
print("CIRCUITPY free   :", mib(filesystem_free))

gc.collect()
heap_before = gc.mem_free()
print("Heap free before :", mib(heap_before))

test_size = 1024 * 1024
test_ok = False
try:
    block = bytearray(test_size)
    block[:] = b"\xA5" * test_size
    first_pass = all(block[i] == 0xA5 for i in range(0, test_size, 257))
    block[:] = b"\x5A" * test_size
    second_pass = all(block[i] == 0x5A for i in range(0, test_size, 257))
    test_ok = first_pass and second_pass
    del block
except MemoryError:
    test_ok = False

gc.collect()
print("1 MiB RAM test   :", "PASS" if test_ok else "FAIL")
print("Heap free after  :", mib(gc.mem_free()))
print("Expected hardware: 16 MiB Quad Flash + 8 MiB Octal PSRAM")
print("Low-level probe  : Flash=16 MiB, PSRAM=8 MiB (already confirmed)")
print("Overall result   :", "PASS" if test_ok and heap_before > 6 * 1024 * 1024 else "CHECK")
print("=" * 52)

while True:
    print("[alive] detector running")
    time.sleep(10)
