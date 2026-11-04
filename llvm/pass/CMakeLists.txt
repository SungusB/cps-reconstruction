cmake_minimum_required(VERSION 3.20)
project(CoroReconstruct CXX)

find_package(LLVM REQUIRED CONFIG)
message(STATUS "LLVM ${LLVM_PACKAGE_VERSION} from ${LLVM_DIR}")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# Match LLVM's own build: no RTTI, or the plugin won't load into opt.
if(NOT LLVM_ENABLE_RTTI)
  set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-rtti")
endif()

include_directories(SYSTEM ${LLVM_INCLUDE_DIRS})
add_definitions(${LLVM_DEFINITIONS})

add_library(CoroReconstruct MODULE CoroReconstruct.cpp)
set_target_properties(CoroReconstruct PROPERTIES PREFIX "")
