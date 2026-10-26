package com.marco.photosphere

import android.content.Context
import android.net.Uri

/** Metadati Google Photo Sphere (XMP GPano): scrittura e lettura. */
data class PanoInfo(
    val fullW: Int,
    val fullH: Int,
    val cropW: Int,
    val cropH: Int,
    val left: Int,
    val top: Int
)

object Gpano {

    fun xmp(w: Int, h: Int): String {
        return "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"PhotoSphere\">\n" +
            " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
            "  <rdf:Description rdf:about=\"\" xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\">\n" +
            "   <GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>\n" +
            "   <GPano:ProjectionType>equirectangular</GPano:ProjectionType>\n" +
            "   <GPano:CaptureSoftware>PhotoSphere</GPano:CaptureSoftware>\n" +
            "   <GPano:StitchingSoftware>PhotoSphere</GPano:StitchingSoftware>\n" +
            "   <GPano:PoseHeadingDegrees>0.0</GPano:PoseHeadingDegrees>\n" +
            "   <GPano:InitialViewHeadingDegrees>0</GPano:InitialViewHeadingDegrees>\n" +
            "   <GPano:InitialViewPitchDegrees>0</GPano:InitialViewPitchDegrees>\n" +
            "   <GPano:InitialViewRollDegrees>0</GPano:InitialViewRollDegrees>\n" +
            "   <GPano:InitialHorizontalFOVDegrees>75.0</GPano:InitialHorizontalFOVDegrees>\n" +
            "   <GPano:CroppedAreaLeftPixels>0</GPano:CroppedAreaLeftPixels>\n" +
            "   <GPano:CroppedAreaTopPixels>0</GPano:CroppedAreaTopPixels>\n" +
            "   <GPano:CroppedAreaImageWidthPixels>$w</GPano:CroppedAreaImageWidthPixels>\n" +
            "   <GPano:CroppedAreaImageHeightPixels>$h</GPano:CroppedAreaImageHeightPixels>\n" +
            "   <GPano:FullPanoWidthPixels>$w</GPano:FullPanoWidthPixels>\n" +
            "   <GPano:FullPanoHeightPixels>$h</GPano:FullPanoHeightPixels>\n" +
            "  </rdf:Description>\n" +
            " </rdf:RDF>\n" +
            "</x:xmpmeta>\n" +
            "<?xpacket end=\"w\"?>"
    }

    /** Inserisce il segmento APP1 XMP subito dopo gli eventuali APP0/APP1 iniziali. */
    fun inject(jpeg: ByteArray, xmp: String): ByteArray {
        if (jpeg.size < 4 || (jpeg[0].toInt() and 0xFF) != 0xFF || (jpeg[1].toInt() and 0xFF) != 0xD8) return jpeg
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            val m0 = jpeg[pos].toInt() and 0xFF
            val m1 = jpeg[pos + 1].toInt() and 0xFF
            if (m0 == 0xFF && (m1 == 0xE0 || m1 == 0xE1)) {
                val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
                pos += 2 + len
            } else break
        }
        val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        val body = xmp.toByteArray(Charsets.UTF_8)
        val segLen = 2 + header.size + body.size
        val out = ByteArray(jpeg.size + 2 + segLen)
        System.arraycopy(jpeg, 0, out, 0, pos)
        var o = pos
        out[o++] = 0xFF.toByte()
        out[o++] = 0xE1.toByte()
        out[o++] = ((segLen shr 8) and 0xFF).toByte()
        out[o++] = (segLen and 0xFF).toByte()
        System.arraycopy(header, 0, out, o, header.size); o += header.size
        System.arraycopy(body, 0, out, o, body.size); o += body.size
        System.arraycopy(jpeg, pos, out, o, jpeg.size - pos)
        return out
    }

    /** Legge i primi 256 KB del file (dove sta l'XMP). */
    fun readHeader(ctx: Context, uri: Uri): String? {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                val buf = ByteArray(256 * 1024)
                var n = 0
                while (n < buf.size) {
                    val r = s.read(buf, n, buf.size - n)
                    if (r <= 0) break
                    n += r
                }
                String(buf, 0, n, Charsets.ISO_8859_1)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun isPano(header: String): Boolean =
        header.contains("GPano:") && header.contains("equirectangular", ignoreCase = true)

    fun parse(header: String): PanoInfo? {
        if (!isPano(header)) return null
        val fw = attr(header, "FullPanoWidthPixels") ?: return null
        val fh = attr(header, "FullPanoHeightPixels") ?: return null
        if (fw <= 0 || fh <= 0) return null
        val cw = attr(header, "CroppedAreaImageWidthPixels") ?: fw
        val ch = attr(header, "CroppedAreaImageHeightPixels") ?: fh
        val l = attr(header, "CroppedAreaLeftPixels") ?: 0
        val t = attr(header, "CroppedAreaTopPixels") ?: 0
        return PanoInfo(fw, fh, cw, ch, l, t)
    }

    private fun attr(h: String, name: String): Int? {
        val a = Regex("GPano:$name\\s*=\\s*\"(-?\\d+)\"").find(h)?.groupValues?.get(1)
        if (a != null) return a.toIntOrNull()
        val b = Regex("<GPano:$name>\\s*(-?\\d+)\\s*</GPano:$name>").find(h)?.groupValues?.get(1)
        return b?.toIntOrNull()
    }
}
