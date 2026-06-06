cmake_minimum_required(VERSION 3.22.1)
project(netscope_tun2socks)

add_library(
    netscope-tun2socks
    SHARED
    tun2socks_bridge.cpp
)

find_library(log-lib log)

target_link_libraries(
    netscope-tun2socks
    ${log-lib}
)
