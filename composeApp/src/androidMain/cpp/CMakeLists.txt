# Syna — LAN instant messenger (GPL-3.0)
# Copyright (C) 2026 Verlintas
# SPDX-License-Identifier: GPL-3.0-only

cmake_minimum_required(VERSION 3.22.1)
project(syna_shield C)

add_library(syna_shield SHARED syna_shield.c)
target_link_libraries(syna_shield log)
