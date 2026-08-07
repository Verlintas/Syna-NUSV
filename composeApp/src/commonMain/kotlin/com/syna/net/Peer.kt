/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.syna.net

import kotlinx.serialization.Serializable

@Serializable
data class PeerAddr(val ip: String, val tcpPort: Int, val udpPort: Int = UDP_DATA_PORT)

data class Peer(
    val id: String,
    val username: String,
    val device: String,
    val addr: PeerAddr,
    val version: String,
    val lastSeen: Long,
    val online: Boolean,
)
