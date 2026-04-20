#include <nanobind/nanobind.h>
#include "../../software/borg/borg_math.h"
#include "../../software/borg/borg_math.c"

namespace nb = nanobind;

NB_MODULE(borg_utils_c, m) {
    m.doc() = "Borg math utilities (C implementation)";
    
    m.def("morton_interleave", &morton_interleave, "Interleave bits for Morton encoding");
    m.def("morton_encode", &morton_encode, "Morton encode 2D coordinates");
    m.def("fp16_from_float", &fp16_from_float, "Convert a float to an FP16 bit pattern");
    m.def("fp16_to_float", &fp16_to_float, "Convert an FP16 bit pattern to float");
    m.def("fp16_to_fixed", &fp16_to_fixed, "Convert FP16 to signed fixed-point Q16");
    m.def("fixed_to_fp16", &fixed_to_fp16, "Convert signed fixed-point Q16 to FP16");
    m.def("fp16_neg", &fp16_neg, "Negate FP16 value");
    m.def("fp16_add", &fp16_add, "FP16 addition");
    m.def("fp16_sub", &fp16_sub, "FP16 subtraction");
    m.def("fp16_recip", &fp16_recip, "FP16 reciprocal");
    m.def("fp16_sin", &fp16_sin, "FP16 sine");
    m.def("fp16_cos", &fp16_cos, "FP16 cosine");
}
