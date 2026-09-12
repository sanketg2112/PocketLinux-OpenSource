cmake_minimum_required(VERSION 3.22.1)

project("terminal")

include(ExternalProject)

ExternalProject_Add(
    termux_proot
    GIT_REPOSITORY https://github.com/termux/proot.git
    GIT_TAG master
    PREFIX ${CMAKE_CURRENT_BINARY_DIR}/termux-proot
    CONFIGURE_COMMAND ""
    BUILD_COMMAND make -C <SOURCE_DIR>/src CC=${CMAKE_C_COMPILER} CFLAGS=${CMAKE_C_FLAGS} LDFLAGS=${CMAKE_SHARED_LINKER_FLAGS} V=1
    INSTALL_COMMAND cp <SOURCE_DIR>/src/proot ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libproot.so
)



add_library(terminal SHARED terminal.cpp)
find_library(log-lib log)
target_link_libraries(terminal ${log-lib} android)
