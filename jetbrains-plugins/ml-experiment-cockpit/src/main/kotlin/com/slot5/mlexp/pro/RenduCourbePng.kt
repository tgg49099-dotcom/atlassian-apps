package com.slot5.mlexp.pro

import com.slot5.mlexp.MetricPoint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * The metric curve of the **standalone HTML report** — Pro tier 2.
 *
 * 2026.1.8 (consigne of the mother, 19/11/2026). The report must carry the
 * 2 curves (A and B) **inside the HTML file**, as a PNG in base64: the reader
 * gets 1 file that opens offline, with no external image to download.
 *
 * **The PNG is not a copy of the drawing code: it is the drawing code.** The
 * very same [CourbeDiff] component that the Compare tab displays is painted
 * into an off-screen image (`printAll` on a `BufferedImage`). Measured on
 * 19/11/2026 in a headless JVM: `printAll` really calls `paintComponent`
 * (1 907 blue pixels + 199 red pixels on the probe image), so the curve of
 * the report is **the curve of the screen** - same colours (A blue, B orange),
 * same common scale, same axes.
 *
 * **Nothing leaves the machine**: the PNG is built in memory and embedded in
 * the HTML as a `data:` URI. No file is written here, no network call.
 */
object RenduCourbePng {

    /** Width of the embedded PNG (2x the on-screen curve: 520x240). */
    const val LARGEUR = 1200

    /** Height of the embedded PNG. */
    const val HAUTEUR = 560

    /** The PNG bytes of the 2 curves (A in blue, B in orange, same scale). */
    fun png(pointsA: List<MetricPoint>, pointsB: List<MetricPoint>, titre: String): ByteArray {
        val courbe = CourbeDiff()
        courbe.setSeries(pointsA, pointsB, titre)
        courbe.setSize(LARGEUR, HAUTEUR)
        val image = BufferedImage(LARGEUR, HAUTEUR, BufferedImage.TYPE_INT_RGB)
        val g2 = image.createGraphics()
        try {
            // Measured headless on 19/11/2026: printAll calls paintComponent.
            courbe.printAll(g2)
        } finally {
            g2.dispose()
        }
        val out = ByteArrayOutputStream()
        if (!ImageIO.write(image, "png", out)) {
            throw IllegalStateException("no PNG writer available")
        }
        return out.toByteArray()
    }

    /** The PNG as a base64 string — what the HTML carries. */
    fun base64(pointsA: List<MetricPoint>, pointsB: List<MetricPoint>, titre: String): String =
        Base64.getEncoder().encodeToString(png(pointsA, pointsB, titre))
}
