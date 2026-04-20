cmake_minimum_required(VERSION 3.15)
project(arc_sim LANGUAGES CXX)

if(POLICY CMP0144)
  cmake_policy(SET CMP0144 NEW)
endif()

find_package(Python 3.11 COMPONENTS Interpreter Development.Module REQUIRED)

execute_process(
    COMMAND "${Python_EXECUTABLE}" -m nanobind --cmake_dir
    OUTPUT_STRIP_TRAILING_WHITESPACE OUTPUT_VARIABLE nanobind_ROOT
)
list(APPEND CMAKE_PREFIX_PATH "${nanobind_ROOT}")
find_package(nanobind CONFIG REQUIRED)

nanobind_add_module(arc_sim nanobind_wrapper.cpp ArcBorgSimulator.cpp)
add_subdirectory(../common common_build)

target_link_libraries(arc_sim PRIVATE 
    borgsim 
    ${CMAKE_CURRENT_SOURCE_DIR}/arc.o
)

target_include_directories(arc_sim PRIVATE 
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../common
)

target_compile_options(arc_sim PRIVATE -O3 -fno-strict-aliasing)
