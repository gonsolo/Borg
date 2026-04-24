cmake_minimum_required(VERSION 4.0)
if(POLICY CMP0074)
  cmake_policy(SET CMP0074 NEW)
endif()
project(BorgSim CXX)

# Set common flags
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fPIC")

add_library(borgsim STATIC
    BorgSimulatorBase.cpp
    texture_loader.cpp
    uart_decoder.cpp
)

target_include_directories(borgsim PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
    $ENV{RDL_C_OUT}
)

