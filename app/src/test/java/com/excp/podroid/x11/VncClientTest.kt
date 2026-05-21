/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class VncClientTest {

    @Test
    fun `handshake replies with RFB 003 008 and selects None auth`() {
        // Server greeting: "RFB 003.008\n" then 1 security type + [None=1]
        val serverBytes = byteArrayOf(
            // 12-byte version greeting
            'R'.code.toByte(), 'F'.code.toByte(), 'B'.code.toByte(), ' '.code.toByte(),
            '0'.code.toByte(), '0'.code.toByte(), '3'.code.toByte(), '.'.code.toByte(),
            '0'.code.toByte(), '0'.code.toByte(), '8'.code.toByte(), '\n'.code.toByte(),
            // Security types: count=1, [None=1]
            0x01, 0x01,
            // SecurityResult: OK=0
            0x00, 0x00, 0x00, 0x00,
            // ServerInit: width=1280, height=720, pixel-format (16 bytes), name-len=0
            0x05, 0x00,                                         // width 1280
            0x02, 0xD0.toByte(),                                // height 720
            32, 24, 0, 1,                                       // bpp, depth, big-endian, true-color
            0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),  // max RGB
            16, 8, 0,                                           // shifts (ARGB)
            0, 0, 0,                                            // padding
            0, 0, 0, 0,                                         // name length 0
        )
        val out = ByteArrayOutputStream()

        val info = VncClient.handshake(ByteArrayInputStream(serverBytes), out)

        assertEquals(1280, info.width)
        assertEquals(720, info.height)
        // Client must have sent: version "RFB 003.008\n", then sec-type 1, then shared=1
        val sent = out.toByteArray()
        // First 12 bytes: client version
        assertArrayEquals("RFB 003.008\n".toByteArray(), sent.copyOfRange(0, 12))
        // Byte 12: chosen security type = 1 (None)
        assertEquals(1, sent[12].toInt())
        // Byte 13: ClientInit shared flag = 1
        assertEquals(1, sent[13].toInt())
    }

    @Test
    fun `Raw rectangle update writes pixels into target ARGB buffer`() {
        // Pre-built FramebufferUpdate message:
        //   msg-type=0, padding, num-rects=1
        //   rect: x=0, y=0, w=2, h=1, encoding=0 (Raw)
        //   pixels: 2 BGRA pixels = red, green
        val msg = byteArrayOf(
            0x00, 0x00,             // msg-type, padding
            0x00, 0x01,             // num rects
            0x00, 0x00, 0x00, 0x00, // x=0, y=0
            0x00, 0x02, 0x00, 0x01, // w=2, h=1
            0x00, 0x00, 0x00, 0x00, // encoding = 0 (Raw)
            // Pixel 0: BGRA = (0, 0, 0xFF, 0xFF) -> red
            0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(),
            // Pixel 1: BGRA = (0, 0xFF, 0, 0xFF) -> green
            0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),
        )
        val target = IntArray(2)

        VncClient.readFramebufferUpdate(
            inp = java.io.ByteArrayInputStream(msg),
            targetArgb = target,
            stride = 2,
            zrle = ZrleDecoder(),
        )

        // ARGB packed: 0xAARRGGBB
        assertEquals(0xFFFF0000.toInt(), target[0])  // red
        assertEquals(0xFF00FF00.toInt(), target[1])  // green
    }

    @Test fun `requestDesktopSize serializes type 251 single-screen layout`() {
        val out = java.io.ByteArrayOutputStream()
        VncClient.requestDesktopSize(out, screenId = 7, width = 1920, height = 1080)
        val b = out.toByteArray()
        assertEquals(24, b.size)
        assertEquals(251, b[0].toInt() and 0xFF)          // msg-type
        // width @ offset 2..3, height @ 4..5 (big-endian)
        assertEquals(1920, ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF))
        assertEquals(1080, ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF))
        assertEquals(1, b[6].toInt() and 0xFF)            // number-of-screens
        // screen id @ 8..11
        assertEquals(7, ((b[8].toInt() and 0xFF) shl 24) or ((b[9].toInt() and 0xFF) shl 16) or ((b[10].toInt() and 0xFF) shl 8) or (b[11].toInt() and 0xFF))
    }

    @Test(expected = java.io.IOException::class)
    fun `Raw rectangle exceeding framebuffer bounds throws IOException`() {
        // FramebufferUpdate with a Raw rect at x=0,y=0,w=4,h=1 into a 2-pixel
        // buffer (stride=2). w*h = 4 pixels would overrun the 2-element array →
        // must raise IOException (bounds guard), not ArrayIndexOutOfBoundsException.
        val msg = byteArrayOf(
            0x00, 0x00,             // msg-type, padding
            0x00, 0x01,             // num rects
            0x00, 0x00, 0x00, 0x00, // x=0, y=0
            0x00, 0x04, 0x00, 0x01, // w=4, h=1  (exceeds the 2-pixel buffer)
            0x00, 0x00, 0x00, 0x00, // encoding = 0 (Raw)
            // 4 BGRA pixels of payload so the OOB write is actually reached
            // (without payload, readFully would EOF before any array access).
            0, 0, 0, 0,  0, 0, 0, 0,  0, 0, 0, 0,  0, 0, 0, 0,
        )
        VncClient.readFramebufferUpdate(
            inp = java.io.ByteArrayInputStream(msg),
            targetArgb = IntArray(2),
            stride = 2,
            zrle = ZrleDecoder(),
        )
    }

    @Test(expected = java.io.IOException::class)
    fun `CopyRect with out-of-bounds source throws IOException`() {
        // CopyRect dst x=0,y=0,w=2,h=1 into a 2-pixel buffer; source srcX=10
        // is out of range. Must raise IOException, not IndexOutOfBoundsException.
        val msg = byteArrayOf(
            0x00, 0x00,             // msg-type, padding
            0x00, 0x01,             // num rects
            0x00, 0x00, 0x00, 0x00, // x=0, y=0
            0x00, 0x02, 0x00, 0x01, // w=2, h=1
            0x00, 0x00, 0x00, 0x01, // encoding = 1 (CopyRect)
            0x00, 0x0A, 0x00, 0x00, // srcX=10, srcY=0  (out of bounds)
        )
        VncClient.readFramebufferUpdate(
            inp = java.io.ByteArrayInputStream(msg),
            targetArgb = IntArray(2),
            stride = 2,
            zrle = ZrleDecoder(),
        )
    }

    @Test(expected = Exception::class)
    fun `ServerInit with absurd name length is rejected without OOM`() {
        // Up through ServerInit, then nameLen = 0x7FFFFFFF. Must throw (require/
        // IOException) before allocating ByteArray(nameLen) — no OutOfMemoryError.
        val serverBytes = byteArrayOf(
            'R'.code.toByte(), 'F'.code.toByte(), 'B'.code.toByte(), ' '.code.toByte(),
            '0'.code.toByte(), '0'.code.toByte(), '3'.code.toByte(), '.'.code.toByte(),
            '0'.code.toByte(), '0'.code.toByte(), '8'.code.toByte(), '\n'.code.toByte(),
            0x01, 0x01,                                         // sec types: count=1, None
            0x00, 0x00, 0x00, 0x00,                             // SecurityResult OK
            0x05, 0x00, 0x02, 0xD0.toByte(),                    // width 1280, height 720
            32, 24, 0, 1,                                       // pixel format (16 bytes)
            0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),
            16, 8, 0,
            0, 0, 0,
            0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),  // nameLen = 0x7FFFFFFF
        )
        VncClient.handshake(java.io.ByteArrayInputStream(serverBytes), java.io.ByteArrayOutputStream())
    }

    // Fix 1: ExtendedDesktopSize with w=0 must throw IOException, not ArithmeticException.
    @Test(expected = java.io.IOException::class)
    fun `ExtendedDesktopSize with width zero throws IOException`() {
        // FramebufferUpdate: type=0, pad, numRects=1; rect x=0 y=0 w=0 h=600 enc=-308;
        // body: screens=1 + pad[3]; screen descriptor (16 bytes).
        val bos = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(bos)
        d.writeByte(0); d.writeByte(0); d.writeShort(1)             // msg, pad, numRects
        d.writeShort(0); d.writeShort(0); d.writeShort(0); d.writeShort(600); d.writeInt(-308)  // w=0
        d.writeByte(1); d.writeByte(0); d.writeByte(0); d.writeByte(0)  // screens=1 + pad3
        d.writeInt(1); d.writeShort(0); d.writeShort(0); d.writeShort(0); d.writeShort(600); d.writeInt(0)
        val target = IntArray(1280 * 720)
        VncClient.readFramebufferUpdate(java.io.ByteArrayInputStream(bos.toByteArray()), target, 1280, ZrleDecoder())
    }

    // Fix 2: An unknown encoding type must throw IOException, not IllegalStateException.
    @Test(expected = java.io.IOException::class)
    fun `unsupported encoding throws IOException`() {
        val bos = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(bos)
        d.writeByte(0); d.writeByte(0); d.writeShort(1)          // msg, pad, numRects
        d.writeShort(0); d.writeShort(0); d.writeShort(1); d.writeShort(1)  // x=0,y=0,w=1,h=1
        d.writeInt(0x7FFFFFFF)                                    // unknown encoding
        val target = IntArray(1280 * 720)
        VncClient.readFramebufferUpdate(java.io.ByteArrayInputStream(bos.toByteArray()), target, 1280, ZrleDecoder())
    }

    @Test fun `ExtendedDesktopSize rect reports new size and writes no pixels`() {
        // FramebufferUpdate: type=0, pad, numRects=1; rect x=0 y=0 w=800 h=600 enc=-308;
        // body: screens=1 pad[3]; screen{id=1,x=0,y=0,w=800,h=600,flags=0}
        val bos = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(bos)
        d.writeByte(0); d.writeByte(0); d.writeShort(1)             // msg, pad, numRects
        d.writeShort(0); d.writeShort(0); d.writeShort(800); d.writeShort(600); d.writeInt(-308)
        d.writeByte(1); d.writeByte(0); d.writeByte(0); d.writeByte(0)   // screens=1 + pad3
        d.writeInt(1); d.writeShort(0); d.writeShort(0); d.writeShort(800); d.writeShort(600); d.writeInt(0)
        val target = IntArray(800 * 600)
        val r = VncClient.readFramebufferUpdate(java.io.ByteArrayInputStream(bos.toByteArray()), target, 800, ZrleDecoder())
        assertEquals(VncSize(800, 600), r.newSize)
    }
}
