package com.example.fakelagtool

import java.nio.ByteBuffer

object PacketUtils {

    data class IpInfo(
        val version: Int,
        val headerLength: Int,
        val totalLength: Int,
        val protocol: Int,
        val srcIp: ByteArray,
        val dstIp: ByteArray
    )

    data class UdpInfo(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val length: Int,
        val payload: ByteArray
    )

    data class TcpInfo(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val seq: Long,
        val ack: Long,
        val syn: Boolean,
        val ackFlag: Boolean,
        val rst: Boolean
    )

    /** Parse IPv4 header. Returns null if not valid IPv4. */
    fun parseIpv4(packet: ByteArray): IpInfo? {
        if (packet.size < 20) return null
        val vIhl = packet[0].toInt() and 0xFF
        val version = vIhl shr 4
        if (version != 4) return null
        val ihl = (vIhl and 0x0F) * 4
        if (ihl < 20 || packet.size < ihl) return null
        val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        if (totalLength < ihl || totalLength > packet.size) return null
        val protocol = packet[9].toInt() and 0xFF
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        return IpInfo(version, ihl, totalLength, protocol, srcIp, dstIp)
    }

    /** Parse UDP inside an IPv4 packet. */
    fun parseUdp(ip: IpInfo, packet: ByteArray): UdpInfo? {
        val udpOffset = ip.headerLength
        if (packet.size < udpOffset + 8) return null
        val srcPort = ((packet[udpOffset].toInt() and 0xFF) shl 8) or (packet[udpOffset + 1].toInt() and 0xFF)
        val dstPort = ((packet[udpOffset + 2].toInt() and 0xFF) shl 8) or (packet[udpOffset + 3].toInt() and 0xFF)
        val udpLength = ((packet[udpOffset + 4].toInt() and 0xFF) shl 8) or (packet[udpOffset + 5].toInt() and 0xFF)
        if (udpLength < 8) return null
        val payloadLen = udpLength - 8
        if (payloadLen < 0 || udpOffset + 8 + payloadLen > packet.size) return null
        val payload = packet.copyOfRange(udpOffset + 8, udpOffset + 8 + payloadLen)
        return UdpInfo(ip.srcIp, ip.dstIp, srcPort, dstPort, udpLength, payload)
    }

    /** Parse TCP header (only fields we need for RST). */
    fun parseTcp(ip: IpInfo, packet: ByteArray): TcpInfo? {
        val tcpOffset = ip.headerLength
        if (packet.size < tcpOffset + 20) return null
        val srcPort = ((packet[tcpOffset].toInt() and 0xFF) shl 8) or (packet[tcpOffset + 1].toInt() and 0xFF)
        val dstPort = ((packet[tcpOffset + 2].toInt() and 0xFF) shl 8) or (packet[tcpOffset + 3].toInt() and 0xFF)
        val seq = readU32(packet, tcpOffset + 4)
        val ack = readU32(packet, tcpOffset + 8)
        val flags = packet[tcpOffset + 13].toInt() and 0xFF
        val syn = (flags and 0x02) != 0
        val ackFlag = (flags and 0x10) != 0
        val rst = (flags and 0x04) != 0
        return TcpInfo(ip.srcIp, ip.dstIp, srcPort, dstPort, seq, ack, syn, ackFlag, rst)
    }

    private fun readU32(p: ByteArray, off: Int): Long {
        return ((p[off].toLong() and 0xFF) shl 24) or
                ((p[off + 1].toLong() and 0xFF) shl 16) or
                ((p[off + 2].toLong() and 0xFF) shl 8) or
                (p[off + 3].toLong() and 0xFF)
    }

    /** Build a full IPv4 + UDP packet. */
    fun buildIpv4Udp(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val buf = ByteBuffer.allocate(totalLen)

        // IPv4 header
        buf.put(0x45.toByte())                    // version 4, IHL 5
        buf.put(0)                                // DSCP/ECN
        buf.putShort(totalLen.toShort())          // total length
        buf.putShort(0)                           // identification
        buf.putShort(0)                           // flags + frag offset
        buf.put(64)                               // TTL
        buf.put(17)                               // protocol UDP
        buf.putShort(0)                           // checksum placeholder
        buf.put(srcIp)
        buf.put(dstIp)

        // IP checksum
        val ipHeader = buf.array().copyOfRange(0, 20)
        val ipCsum = checksum(ipHeader, 0, 20)
        buf.putShort(10, ipCsum.toShort())

        // UDP header
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0)                           // checksum placeholder
        buf.put(payload)

        // UDP checksum with pseudo-header
        val udpSegment = buf.array().copyOfRange(20, totalLen)
        val pseudo = ByteBuffer.allocate(12 + udpSegment.size)
        pseudo.put(srcIp)
        pseudo.put(dstIp)
        pseudo.put(0)
        pseudo.put(17)
        pseudo.putShort(udpLen.toShort())
        pseudo.put(udpSegment)
        val udpCsum = checksum(pseudo.array(), 0, pseudo.array().size)
        buf.putShort(26, if (udpCsum == 0) 0xFFFF.toShort() else udpCsum.toShort())

        return buf.array()
    }

    /** Build a TCP RST|ACK to reject a SYN quickly. */
    fun buildTcpRst(ip: IpInfo, tcp: TcpInfo): ByteArray {
        val totalLen = 40
        val buf = ByteBuffer.allocate(totalLen)

        // IPv4 header: swap src/dst
        buf.put(0x45.toByte())
        buf.put(0)
        buf.putShort(totalLen.toShort())
        buf.putShort(0)
        buf.putShort(0)
        buf.put(64)
        buf.put(6)                                // TCP
        buf.putShort(0)
        buf.put(tcp.dstIp)                        // src = original dst
        buf.put(tcp.srcIp)                        // dst = original src

        val ipHeader = buf.array().copyOfRange(0, 20)
        val ipCsum = checksum(ipHeader, 0, 20)
        buf.putShort(10, ipCsum.toShort())

        // TCP header
        buf.putShort(tcp.dstPort.toShort())       // src port = original dst port
        buf.putShort(tcp.srcPort.toShort())       // dst port = original src port
        buf.putInt(0)                             // seq = 0
        buf.putInt((tcp.seq + 1).toInt())         // ack = original seq + 1
        buf.put(0x50.toByte())                    // data offset 5, reserved
        buf.put(0x14.toByte())                    // flags RST|ACK
        buf.putShort(0)                           // window
        buf.putShort(0)                           // checksum placeholder
        buf.putShort(0)                           // urgent

        // TCP checksum with pseudo-header
        val tcpSegment = buf.array().copyOfRange(20, 40)
        val pseudo = ByteBuffer.allocate(12 + 20)
        pseudo.put(tcp.dstIp)
        pseudo.put(tcp.srcIp)
        pseudo.put(0)
        pseudo.put(6)
        pseudo.putShort(20)
        pseudo.put(tcpSegment)
        val tcpCsum = checksum(pseudo.array(), 0, pseudo.array().size)
        buf.putShort(36, tcpCsum.toShort())

        return buf.array()
    }

    /** Standard internet checksum. */
    fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }
}
