package com.slot5.mlexp.pro

import com.slot5.mlexp.MetricPoint
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.text.DecimalFormat
import javax.swing.JComponent

/**
 * **Overlaid** curve of 2 runs - Pro tier 1.
 *
 * v0.0.22. This file is **new**: `MetricsChartPanel.kt` (the chart of the
 * free tier) is **not** modified - it keeps drawing a single series with its
 * own code. Tier 1 therefore has its own drawing engine, as the plan
 * requires ("do not modify `MetricsChartPanel.kt`").
 *
 * Two series, two colours:
 *   - **A** in blue (`#1E88E5`), the same shade as the curve of the free tier;
 *   - **B** in orange (`#FB8C00`).
 *
 * Both series share **the same scale** (same Y range, same X range): that is
 * the whole point of an overlay. The non-finite points (NaN / Infinity) are
 * ignored, as in the chart of the free tier.
 */
class CourbeDiff : JComponent() {

    private var serieA: List<MetricPoint> = emptyList()
    private var serieB: List<MetricPoint> = emptyList()
    private var titre: String = ""

    private val smallFont: Font = Font(Font.MONOSPACED, Font.PLAIN, 11)
    private val titleFont: Font = Font(Font.SANS_SERIF, Font.BOLD, 12)
    private val gridColor = Color(0xE5, 0xE5, 0xE5)
    private val axisColor = Color(0x88, 0x88, 0x88)
    private val colorA = Color(0x1E, 0x88, 0xE5)
    private val colorB = Color(0xFB, 0x8C, 0x00)
    private val placeholderColor = Color(0x99, 0x99, 0x99)
    private val labelColor = Color(0x44, 0x44, 0x44)
    private val yFormatter = DecimalFormat("#.####")

    private val padding = 68
    private val titleBaseline = 17f
    private val captionBaseline = 34f
    private val plotTop = 52f
    private val plotBottomMargin = 26f

    /** Replaces the 2 series. An empty series = absent from the comparison. */
    fun setSeries(a: List<MetricPoint>, b: List<MetricPoint>, titre: String) {
        serieA = a.filter { it.value.isFinite() }
        serieB = b.filter { it.value.isFinite() }
        this.titre = titre
        repaint()
    }

    override fun getPreferredSize(): Dimension = Dimension(520, 240)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val w = width.toFloat()
        val h = height.toFloat()
        g2.color = Color.WHITE
        g2.fillRect(0, 0, width, height)

        g2.font = titleFont
        g2.color = labelColor
        g2.drawString(if (titre.isEmpty()) "(no metric)" else titre, padding.toFloat(), titleBaseline)

        val tous = serieA + serieB
        if (tous.isEmpty()) {
            g2.font = smallFont
            g2.color = placeholderColor
            val msg = "no history points"
            val tw = g2.fontMetrics.stringWidth(msg)
            g2.drawString(msg, (width - tw) / 2f, height / 2f)
            return
        }

        // One common scale for the 2 series.
        val xMin = tous.minOf { it.step }
        val xMax = tous.maxOf { it.step }
        val yMin = tous.minOf { it.value }
        val yMax = tous.maxOf { it.value }

        val yLo: Double
        val yHi: Double
        if (yMax - yMin < 1e-12) {
            val pad = if (yMax == 0.0) 1.0 else kotlin.math.abs(yMax) * 0.1
            yLo = yMin - pad
            yHi = yMax + pad
        } else {
            val pad = (yMax - yMin) * 0.05
            yLo = yMin - pad
            yHi = yMax + pad
        }

        val xLo = if (xMax - xMin < 1) xMin.toDouble() - 0.5 else xMin.toDouble()
        val xHi = if (xMax - xMin < 1) xMax.toDouble() + 0.5 else xMax.toDouble()

        val plotX0 = padding.toFloat()
        val plotY0 = plotTop

        val xTicks = 4
        val xTickSteps = (0..xTicks).map { i -> (xLo + (xHi - xLo) * i / xTicks).toLong() }
        val xTickLabels = xTickSteps.map { "step $it" }
        g2.font = smallFont
        val xLabelWidth = xTickLabels.maxOf { g2.fontMetrics.stringWidth(it) }
        val rightMargin = maxOf(12, xLabelWidth / 2 + 6)

        val plotW = w - padding - rightMargin
        val plotH = h - plotTop - plotBottomMargin

        fun toScreenX(step: Long): Float {
            val t = (step - xLo) / (xHi - xLo)
            return plotX0 + (t * plotW).toFloat()
        }
        fun toScreenY(value: Double): Float {
            val t = (value - yLo) / (yHi - yLo)
            return plotY0 + plotH - (t * plotH).toFloat()
        }

        // Grid + Y ticks.
        g2.font = smallFont
        g2.color = gridColor
        g2.stroke = BasicStroke(0.5f)
        val yTicks = 4
        val minLabelBaseline = captionBaseline + g2.fontMetrics.height + 2f
        for (i in 0..yTicks) {
            val v = yLo + (yHi - yLo) * i / yTicks
            val sy = toScreenY(v)
            g2.drawLine(plotX0.toInt(), sy.toInt(), (plotX0 + plotW).toInt(), sy.toInt())
            g2.color = labelColor
            g2.drawString(formatY(v), 4f, maxOf(sy + 4f, minLabelBaseline))
            g2.color = gridColor
        }

        // X axis + ticks.
        g2.color = axisColor
        g2.drawLine(plotX0.toInt(), (plotY0 + plotH).toInt(), (plotX0 + plotW).toInt(), (plotY0 + plotH).toInt())
        g2.color = labelColor
        for (i in 0..xTicks) {
            val sx = toScreenX(xTickSteps[i])
            val text = xTickLabels[i]
            val tw = g2.fontMetrics.stringWidth(text)
            val lx = (sx - tw / 2f).coerceIn(2f, maxOf(2f, w - tw - 2f))
            g2.drawString(text, lx, (plotY0 + plotH + 16))
        }

        // Legend: A in blue, B in orange.
        g2.font = smallFont
        g2.color = labelColor
        g2.drawString("A", plotX0 + 6f, plotY0 + 14f)
        g2.color = colorA
        g2.fillRect(plotX0.toInt() + 18, plotY0.toInt() + 4, 8, 8)
        g2.color = labelColor
        g2.drawString("B", plotX0 + 32f, plotY0 + 14f)
        g2.color = colorB
        g2.fillRect(plotX0.toInt() + 44, plotY0.toInt() + 4, 8, 8)

        dessinerSerie(g2, serieA, colorA, ::toScreenX, ::toScreenY)
        dessinerSerie(g2, serieB, colorB, ::toScreenX, ::toScreenY)

        g2.font = smallFont
        g2.color = labelColor
        g2.drawString("value", 4f, captionBaseline)
    }

    private fun dessinerSerie(
        g2: Graphics2D,
        serie: List<MetricPoint>,
        couleur: Color,
        toScreenX: (Long) -> Float,
        toScreenY: (Double) -> Float
    ) {
        if (serie.isEmpty()) return
        g2.color = couleur
        g2.stroke = BasicStroke(1.6f)
        if (serie.size == 1) {
            val p = serie[0]
            val sx = toScreenX(p.step)
            val sy = toScreenY(p.value)
            g2.fillOval((sx - 4).toInt(), (sy - 4).toInt(), 8, 8)
            return
        }
        var prevX: Float? = null
        var prevY: Float? = null
        for (p in serie) {
            val sx = toScreenX(p.step)
            val sy = toScreenY(p.value)
            if (prevX != null && prevY != null) {
                g2.drawLine(prevX.toInt(), prevY.toInt(), sx.toInt(), sy.toInt())
            }
            prevX = sx
            prevY = sy
        }
        g2.color = couleur.darker()
        for (p in serie) {
            val sx = toScreenX(p.step)
            val sy = toScreenY(p.value)
            g2.fillOval((sx - 2).toInt(), (sy - 2).toInt(), 4, 4)
        }
    }

    private fun formatY(v: Double): String {
        if (v == 0.0) return "0"
        val abs = kotlin.math.abs(v)
        return if (abs >= 1e4 || abs < 1e-2) "%.3e".format(v) else yFormatter.format(v)
    }
}
