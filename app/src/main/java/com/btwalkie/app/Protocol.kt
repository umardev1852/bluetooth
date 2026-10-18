package com.btwalkie.app

import android.bluetooth.BluetoothAdapter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * The bytes two phones send each other. Used by the call service and by the "long range"
 * screen code.
 *
 * Direct call:        caller sends HELLO, the other phone answers ACCEPT / REJECT / BUSY.
 * Call through phones in the middle (a "route"): the caller connects to the first phone and sends
 *   ROUTE + the phones still to visit + its own name. Each phone in the middle connects to the next
 *   phone and then simply passes every byte along in both directions. The last phone sees an empty
 *   route and treats it as a normal incoming call.
 * LIST: "tell me which phones you are paired with" (also passed along a route).
 */
object Protocol {
    // Both phones use the same id. Any random UUID works.
    val APP_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")

    const val MSG_HELLO = 1
    const val MSG_ACCEPT = 2
    const val MSG_REJECT = 3
    const val MSG_BUSY = 4
    const val MSG_ROUTE = 5
    const val MSG_LIST = 6
    const val MSG_UNREACHABLE = 7 // a phone in the middle could not reach the next phone

    const val RATE_16K = 0
    const val RATE_8K = 1

    /** Most phones on one route, not counting the caller: up to 3 in the middle plus the target. */
    const val MAX_ROUTE = 4

    private const val ADDRESS_BYTES = 17 // "AA:BB:CC:DD:EE:FF"

    class Header(val rate: Int, val rest: List<String>, val name: String)

    class Peer(val name: String, val address: String)

    /** type, rate, number of phones still to visit, their addresses, name length, name. */
    fun frame(type: Int, rate: Int, rest: List<String>, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(type)
        out.write(rate)
        out.write(rest.size)
        for (address in rest) out.write(address.toByteArray(Charsets.US_ASCII))
        val nameBytes = name.take(40).toByteArray(Charsets.UTF_8)
        out.write(nameBytes.size)
        out.write(nameBytes)
        return out.toByteArray()
    }

    /** Reads exactly n bytes, or returns null if the connection ends first. */
    fun readBytes(inp: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val got = inp.read(buf, offset, n - offset)
            if (got < 0) return null
            offset += got
        }
        return buf
    }

    /** Reads what follows the type byte of a ROUTE or LIST message. Null if it is damaged. */
    fun readHeader(inp: InputStream): Header? {
        val rate = inp.read()
        val count = inp.read()
        if (rate < 0 || count < 0 || count >= MAX_ROUTE) return null
        val rest = ArrayList<String>()
        for (i in 0 until count) {
            val raw = readBytes(inp, ADDRESS_BYTES) ?: return null
            val address = String(raw, Charsets.US_ASCII)
            if (!BluetoothAdapter.checkBluetoothAddress(address)) return null
            rest.add(address)
        }
        val nameLength = inp.read()
        if (nameLength < 0) return null
        val nameBytes = readBytes(inp, nameLength) ?: return null
        return Header(rate, rest, String(nameBytes, Charsets.UTF_8))
    }

    /** The answer to LIST: how many phones, then address, name length and name for each. */
    fun peersFrame(peers: List<Peer>): ByteArray {
        val out = ByteArrayOutputStream()
        val list = peers.take(20)
        out.write(list.size)
        for (p in list) {
            out.write(p.address.toByteArray(Charsets.US_ASCII))
            val nameBytes = p.name.take(40).toByteArray(Charsets.UTF_8)
            out.write(nameBytes.size)
            out.write(nameBytes)
        }
        return out.toByteArray()
    }

    fun readPeers(inp: InputStream): List<Peer>? {
        val count = inp.read()
        if (count < 0) return null
        val result = ArrayList<Peer>()
        for (i in 0 until count) {
            val raw = readBytes(inp, ADDRESS_BYTES) ?: return null
            val address = String(raw, Charsets.US_ASCII)
            val nameLength = inp.read()
            if (nameLength < 0) return null
            val nameBytes = readBytes(inp, nameLength) ?: return null
            if (BluetoothAdapter.checkBluetoothAddress(address)) {
                result.add(Peer(String(nameBytes, Charsets.UTF_8), address))
            }
        }
        return result
    }
}
