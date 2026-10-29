package com.slot5.mlexp

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.text.DecimalFormat
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JPanel

/**
 * Time-series chart for one metric of a single run. v0.0.3.
 *
 * Layout (BorderLayout):
 *   NORTH : combo box (metric name) + small "metric label" badge
 *   CENTER: [LineChartCanvas] — custom-drawn line chart using Graphics2D.
 *
 * Network I/O runs on a background thread (mlexp-tw-history-<metric>); the
 * canvas is repainted on the EDT via [refreshCanvas].
 *
 * Why hand-rolled instead of pulling JFreeChart: JFreeChart is NOT in the
 * IntelliJ Platform runtime (it's used internally by some features but not
 * exposed to plugin classloaders), and adding a 1.2 MB dependency for a
 * 30-point line chart is overkill. A 200-line Graphics2D canvas does the job
 * and keeps the plugin ZIP small.
 */
class MetricsChartPanel(
    private val runId: String,
    private val getBaseUri: () -> String,
    /** Called on the EDT when the user picks a different metric. Used to
     *  surface a status message in the parent panel's status bar. */
    private val onStatus: (String) -> Unit = {},
    /** Called on the EDT when the user picks a different metric. The parent
     *  panel uses it to keep the metrics label in sync. */
    private val onMetricPicked: (String) -> Unit = {}
) {

    val rootComponent: JPanel = JPanel(BorderLayout())

    private val comboModel = DefaultComboBoxModel<String>()
    private val combo: JComboBox<String> = JComboBox(comboModel).apply {
        isEnabled = false
        font = JBFont.regular()
    }
    private val pointsLabel = JBLabel("no metric selected").apply {
        foreground = Color(0x55, 0x55, 0x55)
        // 0.0.16 : le libelle "N point(s) for <metric>" est colle au bord droit
        // du panneau (il y touchait le bord de la fenetre de l'IDE). 8 px de
        // marge evitent qu'il soit rogne.
        border = javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 8)
    }

    private val canvas = LineChartCanvas()

    private var points: List<MetricPoint> = emptyList()
    private var currentMetric: String = ""

    init {
        // ---- NORTH : metric selector + label ----
        val topBar = JPanel(BorderLayout()).apply {
            val left = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JBLabel("Metric: ").apply { font = JBFont.regular() })
                add(combo)
            }
            add(left, BorderLayout.WEST)
            add(pointsLabel, BorderLayout.EAST)
        }
        rootComponent.add(topBar, BorderLayout.NORTH)
        rootComponent.add(canvas, BorderLayout.CENTER)

        rootComponent.preferredSize = Dimension(380, 240)

        combo.addActionListener { onComboChanged() }
    }

    /**
     * Populate the combo box with the metric names from the run's last-values
     * map. Called from the parent panel once a run is selected.
     */
    fun setAvailableMetrics(metricNames: List<String>) {
        comboModel.removeAllElements()
        if (metricNames.isEmpty()) {
            comboModel.addElement("(no metrics)")
            combo.isEnabled = false
            pointsLabel.text = "no metrics on this run"
            canvas.setData(emptyList(), "")
            return
        }
        for (n in metricNames.sorted()) comboModel.addElement(n)
        combo.isEnabled = true
        // Pick the first metric by default and load its history.
        combo.selectedIndex = 0
        // The action listener will fire on `selectedIndex = 0`, which will
        // call `loadHistory` for us. No need to invoke it here.
    }

    private fun onComboChanged() {
        val metric = combo.selectedItem as? String ?: return
        if (metric.isBlank() || metric == "(no metrics)") return
        if (metric == currentMetric) return
        currentMetric = metric
        onMetricPicked(metric)
        loadHistory(metric)
    }

    private fun loadHistory(metric: String) {
        val base = getBaseUri().trimEnd('/')
        if (base.isEmpty()) {
            onStatus("Configure the tracking URI first (Settings → Tools → ML Experiment Cockpit).")
            return
        }
        onStatus("Loading history for $metric ...")
        pointsLabel.text = "loading $metric ..."
        canvas.setData(emptyList(), metric)

        Thread({
            try {
                val client = MlflowClient(base)
                val data = client.getMetricHistory(runId, metric)
                // Defensive sort: MLflow may return points in any order across
                // paginated responses. We want a strictly increasing X axis.
                val sorted = data.sortedBy { it.step }
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    points = sorted
                    canvas.setData(sorted, metric)
                    pointsLabel.text = "${sorted.size} point(s) for $metric"
                    onStatus("Loaded ${sorted.size} history point(s) for $metric.")
                }
            } catch (e: MlflowException) {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    pointsLabel.text = "error: ${e.message ?: ""}"
                    canvas.setData(emptyList(), metric)
                    onStatus("Failed to load history for $metric: ${e.message}")
                }
            } catch (t: Throwable) {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    pointsLabel.text = "unexpected: ${t.javaClass.simpleName}"
                    canvas.setData(emptyList(), metric)
                    onStatus("Unexpected error: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }, "mlexp-tw-history-$metric").start()
    }
}

/**
 * Tiny line-chart canvas. No external deps; uses [Graphics2D] only.
 *
 * The chart is "smart" in the sense that:
 *  - The X axis is the MLflow step (0, 1, 2, ...).
 *  - The Y axis auto-scales to the data range with a 5 % headroom on each side.
 *  - If a single point is provided, we draw a dot rather than a line.
 *  - If `points` is empty, we show a centered "no data" placeholder.
 *  - NaN / Inf values are filtered out.
 *  - Long Y values (>= 1e4 or < 1e-2 with non-zero magnitude) are formatted
 *    in scientific notation to keep the axis labels readable.
 */
class LineChartCanvas : javax.swing.JComponent() {

    private var points: List<MetricPoint> = emptyList()
    private var title: String = ""

    // 0.0.16 : marge gauche. 56 px suffisaient pour "-0.0346" (7 caracteres
    // monospace) mais pas pour une valeur en notation scientifique comme
    // "-1.234e-05" (9 caracteres, ~59 px), qui chevauchait alors la courbe.
    private val padding = 68
    private val smallFont: Font = Font(Font.MONOSPACED, Font.PLAIN, 11)
    private val titleFont: Font = Font(Font.SANS_SERIF, Font.BOLD, 12)
    private val gridColor = Color(0xE5, 0xE5, 0xE5)
    private val axisColor = Color(0x88, 0x88, 0x88)
    private val lineColor = Color(0x1E, 0x88, 0xE5)
    private val pointColor = Color(0x0F, 0x5A, 0x9E)
    private val placeholderColor = Color(0x99, 0x99, 0x99)
    private val labelColor = Color(0x44, 0x44, 0x44)
    private val yFormatter = DecimalFormat("#.####")

    // -------------------------------------------------------------------------
    // Vertical budget of the top band (0.0.15).
    //
    // The band above the plot used to hold three texts stacked on top of each
    // other: the chart title (baseline 18), the "value" caption (baseline
    // plotY0-4 = 24) and the highest Y-axis tick label (baseline plotY0+4 =
    // 32). With an 11 px font a line of text reaches ~10 px above its
    // baseline, so the caption and the top tick label overlapped — on screen
    // they printed on top of each other ("value" over "2.5199").
    //
    // The three bands are now given one row each:
    //   title  : baseline 17
    //   caption: baseline 34
    //   plot   : starts at 52, so the top tick label (baseline 56) cannot
    //            reach the caption (which ends at ~37).
    // -------------------------------------------------------------------------
    private val titleBaseline = 17f
    private val captionBaseline = 34f
    private val plotTop = 52f
    private val plotBottomMargin = 26f

    fun setData(points: List<MetricPoint>, title: String) {
        this.points = points.filter { it.value.isFinite() }
        this.title = title
        repaint()
    }

    override fun getPreferredSize(): Dimension = Dimension(380, 220)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val w = width.toFloat()
        val h = height.toFloat()
        g2.color = Color.WHITE
        g2.fillRect(0, 0, width, height)

        // Title
        g2.font = titleFont
        g2.color = labelColor
        val titleText = if (title.isEmpty()) "(no metric)" else title
        g2.drawString(titleText, padding.toFloat(), titleBaseline)

        if (points.isEmpty()) {
            g2.font = smallFont
            g2.color = placeholderColor
            val msg = "no history points"
            val fm = g2.fontMetrics
            val tw = fm.stringWidth(msg)
            g2.drawString(msg, (width - tw) / 2f, height / 2f)
            return
        }

        // Compute extents.
        val xMin = points.minOf { it.step }
        val xMax = points.maxOf { it.step }
        val yMin = points.minOf { it.value }
        val yMax = points.maxOf { it.value }

        // ySpan = 0 means a flat line; pad it.
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

        // 0.0.16 : les etiquettes de l'axe X sont CALCULEES avant la geometrie
        // du graphique, parce que leur largeur determine la marge droite.
        //
        // Le defaut visible : l'etiquette du dernier pas etait dessinee a
        // (sx - 20), une constante supposee etre la moitie de la largeur du
        // texte. "step 39" (7 caracteres monospace, ~46 px) debordait donc du
        // canvas, et Swing rognait le dessin : a l'ecran on lisait "step" et
        // le "39" disparaitait. Le meme decalage de 20 px decalait de fait
        // toutes les etiquettes vers la gauche.
        //
        // Ici : le texte est mesure (fontMetrics), la marge droite vaut la
        // moitie de la plus large etiquette + 6 px, et chaque etiquette est
        // centree sur son pas puis bornee dans le canvas (2 px de marge).
        val xTicks = 4
        val xTickSteps = (0..xTicks).map { i -> (xLo + (xHi - xLo) * i / xTicks).toLong() }
        val xTickLabels = xTickSteps.map { "step $it" }
        g2.font = smallFont
        val xLabelWidth = xTickLabels.maxOf { g2.fontMetrics.stringWidth(it) }
        val rightMargin = maxOf(12, xLabelWidth / 2 + 6)

        val plotW = w - padding - rightMargin
        val plotH = h - plotTop - plotBottomMargin

        // Map a data point to screen coords.
        fun toScreenX(step: Long): Float {
            val t = (step - xLo) / (xHi - xLo)
            return plotX0 + (t * plotW).toFloat()
        }
        fun toScreenY(value: Double): Float {
            val t = (value - yLo) / (yHi - yLo)
            // y axis grows downward; invert.
            return plotY0 + plotH - (t * plotH).toFloat()
        }

        // Grid + Y labels.
        g2.font = smallFont
        g2.color = gridColor
        g2.stroke = BasicStroke(0.5f)
        val yTicks = 4
        // A tick label is drawn 4 px below its grid line. The highest one sits
        // on the top grid line, so its baseline is plotTop+4; the guard below
        // keeps it clear of the "value" caption whatever the font metrics are.
        val minLabelBaseline = captionBaseline + g2.fontMetrics.height + 2f
        for (i in 0..yTicks) {
            val v = yLo + (yHi - yLo) * i / yTicks
            val sy = toScreenY(v)
            g2.drawLine(plotX0.toInt(), sy.toInt(), (plotX0 + plotW).toInt(), sy.toInt())
            g2.color = labelColor
            g2.drawString(formatY(v), 4f, maxOf(sy + 4f, minLabelBaseline))
            g2.color = gridColor
        }

        // X axis line + X labels.
        g2.color = axisColor
        g2.drawLine(plotX0.toInt(), (plotY0 + plotH).toInt(), (plotX0 + plotW).toInt(), (plotY0 + plotH).toInt())
        g2.color = labelColor
        for (i in 0..xTicks) {
            val sx = toScreenX(xTickSteps[i])
            val text = xTickLabels[i]
            val tw = g2.fontMetrics.stringWidth(text)
            // centree sur le pas, puis bornee dans le canvas : plus aucune
            // etiquette ne peut plus etre rognee par le bord droit.
            val lx = (sx - tw / 2f).coerceIn(2f, maxOf(2f, w - tw - 2f))
            g2.drawString(text, lx, (plotY0 + plotH + 16))
        }

        // The data — either a line or a single dot.
        g2.color = lineColor
        g2.stroke = BasicStroke(1.6f)
        if (points.size == 1) {
            val p = points[0]
            val sx = toScreenX(p.step)
            val sy = toScreenY(p.value)
            g2.fillOval((sx - 4).toInt(), (sy - 4).toInt(), 8, 8)
        } else {
            var prevX: Float? = null
            var prevY: Float? = null
            for (p in points) {
                val sx = toScreenX(p.step)
                val sy = toScreenY(p.value)
                if (prevX != null && prevY != null) {
                    g2.drawLine(prevX.toInt(), prevY.toInt(), sx.toInt(), sy.toInt())
                }
                prevX = sx
                prevY = sy
            }
            g2.color = pointColor
            for (p in points) {
                val sx = toScreenX(p.step)
                val sy = toScreenY(p.value)
                g2.fillOval((sx - 2).toInt(), (sy - 2).toInt(), 4, 4)
            }
        }

        // Axis labels (Y axis caption + last point readout).
        g2.font = smallFont
        g2.color = labelColor
        val caption = "value"
        g2.drawString(caption, 4f, captionBaseline)
        val last = points.last()
        g2.drawString(
            "last = ${formatY(last.value)} @ step ${last.step}",
            plotX0 + 6f, plotY0 + 14f
        )
    }

    private fun formatY(v: Double): String {
        if (v == 0.0) return "0"
        val abs = kotlin.math.abs(v)
        return if (abs >= 1e4 || abs < 1e-2) "%.3e".format(v) else yFormatter.format(v)
    }
}
