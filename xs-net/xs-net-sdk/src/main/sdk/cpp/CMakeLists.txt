cmake_minimum_required(VERSION 3.20)
project(xs_net_sdk_cpp VERSION 0.0.1 LANGUAGES CXX)

option(XSNET_BUILD_TESTS "Build XS Net C++ SDK tests" ON)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(Protobuf 25.5 CONFIG REQUIRED)
find_path(ASIO_INCLUDE_DIR asio.hpp REQUIRED)

file(GLOB XSNET_GENERATED_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/generated/*.cc")
if(NOT XSNET_GENERATED_SOURCES)
    message(FATAL_ERROR "Generated protobuf sources are missing. Run .\\scripts\\Generate-Sdks.ps1 -Only Cpp from the repository root first.")
endif()

add_library(xs_net_sdk_cpp
    src/client.cpp
    ${XSNET_GENERATED_SOURCES}
)
target_include_directories(xs_net_sdk_cpp
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/generated
        ${ASIO_INCLUDE_DIR}
)
target_link_libraries(xs_net_sdk_cpp PUBLIC protobuf::libprotobuf)
target_compile_definitions(xs_net_sdk_cpp PUBLIC ASIO_STANDALONE ASIO_NO_DEPRECATED)

if(XSNET_BUILD_TESTS)
    enable_testing()
    add_executable(xs_net_sdk_cpp_frame_test tests/frame_test.cpp)
    target_link_libraries(xs_net_sdk_cpp_frame_test PRIVATE xs_net_sdk_cpp)
    add_test(NAME xs_net_sdk_cpp_frame_test COMMAND xs_net_sdk_cpp_frame_test)

    add_executable(xs_net_sdk_cpp_kcp_test tests/kcp_test.cpp)
    target_link_libraries(xs_net_sdk_cpp_kcp_test PRIVATE xs_net_sdk_cpp)
    add_test(NAME xs_net_sdk_cpp_kcp_test COMMAND xs_net_sdk_cpp_kcp_test)
endif()
