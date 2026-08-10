cmake_minimum_required(VERSION 3.22.1)
project(syna_shield C)

add_library(syna_shield SHARED syna_shield.c)
target_link_libraries(syna_shield log)
