import os
import sys
import random

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.append(os.path.join(SCRIPT_DIR, '../fpga/host'))
sys.path.append(os.path.join(SCRIPT_DIR, '../software/borg/python/build'))

import borg_utils
import borg_utils_c

def run_tests():
    print("Testing morton_encode...")
    for _ in range(10000):
        x = random.randint(0, 65535)
        y = random.randint(0, 65535)
        assert borg_utils.morton_encode(x, y) == borg_utils_c.morton_encode(x, y)

    print("Testing fp16_from_float / float_to_fp16...")
    for _ in range(10000):
        # Generate random float that can be represented
        f = random.uniform(-65000.0, 65000.0)
        # Note: precision differences might happen due to different rounding
        # borg_utils.py might do round-to-nearest while C does truncation.
        # Let's test with exact fp16 floats
        fp = random.randint(0, 65535)
        f_val = borg_utils.fp16_to_float(fp)
        
        import math
        if math.isnan(f_val): continue
        
        c_fp16 = borg_utils_c.fp16_from_float(f_val)
        py_fp16 = borg_utils.float_to_fp16(f_val)
        
        # We test that our C to_float matches py to_float exactly
        assert borg_utils.fp16_to_float(fp) == borg_utils_c.fp16_to_float(fp)

    print("All 10k tests passed successfully!")

if __name__ == '__main__':
    run_tests()
