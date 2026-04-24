cmake_minimum_required(VERSION 3.15)
project(borg_python LANGUAGES CXX)

if(POLICY CMP0144)
  cmake_policy(SET CMP0144 NEW)
endif()

set(CMAKE_CXX_STANDARD 17)

find_package(Python 3.11 COMPONENTS Interpreter Development.Module REQUIRED)
execute_process(
    COMMAND "${Python_EXECUTABLE}" -m nanobind --cmake_dir
    OUTPUT_STRIP_TRAILING_WHITESPACE OUTPUT_VARIABLE nanobind_ROOT
)
list(APPEND CMAKE_PREFIX_PATH "${nanobind_ROOT}")
find_package(nanobind CONFIG REQUIRED)

nanobind_add_module(borg_utils_c borg_math_bindings.cpp)
# Include the parent dir (software/borg/) for borg_math.h
target_include_directories(borg_utils_c PRIVATE ..)
