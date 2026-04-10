cmake_minimum_required(VERSION 3.15)
project(borg_sim LANGUAGES CXX)

if(POLICY CMP0144)
  cmake_policy(SET CMP0144 NEW)
endif()

# We expect verilator objects to be built already.
# Or better, we can invoke python3 -m nanobind to get include paths and build manually.
# But cmake is typical for nanobind. Fetching nanobind via system (it's in Nix).
find_package(Python 3.11 COMPONENTS Interpreter Development.Module REQUIRED)

execute_process(
    COMMAND "${Python_EXECUTABLE}" -m nanobind --cmake_dir
    OUTPUT_STRIP_TRAILING_WHITESPACE OUTPUT_VARIABLE nanobind_ROOT
)
list(APPEND CMAKE_PREFIX_PATH "${nanobind_ROOT}")
find_package(nanobind CONFIG REQUIRED)

# The verilator model code requires the verilator includes
find_package(verilator HINTS ${VERILATOR_ROOT})

# Add verilator obj_dir output as statically linked sources/objects
# Assuming the user runs `make verilator_sim` before running cmake.
file(GLOB VERILATOR_OBJS "${CMAKE_CURRENT_SOURCE_DIR}/obj_dir/*.o")
# Exclude main.o because it has the main() entrypoint
list(FILTER VERILATOR_OBJS EXCLUDE REGEX "main\\.o$")

nanobind_add_module(borg_sim nanobind_wrapper.cpp)
target_link_libraries(borg_sim PRIVATE ${VERILATOR_OBJS})

# Verilator headers
target_include_directories(borg_sim PRIVATE 
    ${CMAKE_CURRENT_SOURCE_DIR}/obj_dir 
    ${VERILATOR_ROOT}/include
    ${VERILATOR_ROOT}/include/vltstd
)

# Optional optimisation flags
target_compile_options(borg_sim PRIVATE -O3 -fno-strict-aliasing)
