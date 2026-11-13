package com.slot5.mlexp.pro

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.slot5.mlexp.MetricPoint
import com.slot5.mlexp.MlflowClient
import com.slot5.mlexp.MlflowException
import com.slot5.mlexp.MlflowSettingsImpl
import com.slot5.mlexp.Run
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.table.DefaultTableModel

/**
 * **Compare** tab - Pro tier 1 ("compare 2 runs").
 *
 * v0.0.22. The 5th tab of the "ML Experiment Cockpit" tool window. It is
 * **visible by everybody** and **locked without a licence**: it is never an
 * error message, it is a gate (short text + a "Get Pro" button that opens the
 * JetBrains purchase dialog).
 *
 * Content when the gate is open:
 *   - an **A / B** header (name, `run_id`, status, start, duration);
 *   - the **parameter** table side by side;
 *   - the **metric** table side by side (absolute gap + relative gap);
 *   - a metric selector and the **overlaid curve** (A in blue, B in orange);
 *   - the **"Copy (Markdown)"** button: the "I want to export everything"
 *     gesture of the plan - it puts the 2 tables in the clipboard (no file,
 *     no CSV: the CSV/XLSX export is tier 2).
 *
 * The panel is **read-only**: it never writes to the server.
 */
class PanneauComparaison(
    private val hub: ComparaisonHub,
    private val acheteur: AcheteurPro = AcheteurPlateforme()
) {

    /** The Swing component hosted by the platform. */
    val rootComponent: JPanel = JPanel(BorderLayout())

    private val carte = CardLayout()
    /** The panel that HOLDS the CardLayout: it is the one added to the root. */
    private val porte = JPanel(carte)
    private val carteContenu = JPanel(BorderLayout())
    private val carteVerrou = JPanel(BorderLayout())

    private val badge = JBLabel("licence: -").apply {
        foreground = java.awt.Color(0x55, 0x55, 0x55)
    }
    private val boutonGetPro = JButton("Get Pro")
    private val boutonCopier = JButton("Copy (Markdown)").apply { isEnabled = false }
    private val statusLabel = JBLabel(
        "Select a run in the Experiments tab, then right-click it to compare it with another run."
    )

    private val entete = JTable(ModeleNonEditable(arrayOf<Any>("A", "B"), 5))
    private val tableParams = JTable(ModeleNonEditable(arrayOf<Any>("parameter", "A", "B", "state"), 0))
    private val tableMetriques = JTable(
        ModeleNonEditable(arrayOf<Any>("metric", "A", "B", "absolute gap", "relative gap", "state"), 0)
    )
    private val comboMetrique = JComboBox<String>(DefaultComboBoxModel<String>())
    // Jury 11/11 (amelioration 3) : the listener is detached while the list
    // of the metrics is filled, so emptying it and filling it again do not
    // trigger the loading of a history several times.
    private val listenerMetrique = java.awt.event.ActionListener { onMetriqueChoisie() }
    private val comboRuns = JComboBox<String>(DefaultComboBoxModel<String>())
    private val courbe = CourbeDiff()
    private val messageVerrou = JTextArea()

    private var runA: Run? = null
    private var experimentId: String = ""
    private var candidats: List<Run> = emptyList()
    private var comparaison: ResultatComparaison.Comparaison? = null
    private var etatPro: ProState = ProState.INCONNU
    private var enCours = false
    // Jury 11/11 (amelioration 2) : every request for a history gets a
    // number; a response that is not the last one is dropped, so a slow
    // answer cannot overwrite the curve of the metric chosen afterwards.
    private var compteurDemandeHistoire: Long = 0L

    init {
        // ---- NORTH : title, licence state, buttons ----
        val topBar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Compare 2 runs").apply {
                font = com.intellij.util.ui.JBFont.regular()
            })
            add(badge)
            add(boutonGetPro)
            add(boutonCopier)
        }
        rootComponent.add(topBar, BorderLayout.NORTH)

        // ---- CENTER : content or closed gate ----
        rootComponent.add(porte, BorderLayout.CENTER)
        // Jury 11/11 : la ligne d'etat est sous les DEUX cartes, sinon ses
        // messages (achat impossible, comparaison verrouillee) sont invisibles
        // quand la carte « verrou » est affichee.
        rootComponent.add(statusLabel, BorderLayout.SOUTH)

        construireContenu()
        construireVerrou()

        // 10/11/2026 (cycle 158) — CORRECTION D'UN BLOQUANT MESURE DANS UN IDE REEL.
        //
        // MESURE (PyCharm 2026.2.4, ZIP livre installe) : a l'ouverture du tool
        // window, la construction de ce panneau levait
        //   java.lang.IllegalArgumentException: wrong parent for CardLayout
        //       at java.awt.CardLayout.checkLayout(CardLayout.java:413)
        //       at java.awt.CardLayout.show(CardLayout.java:535)
        //       at com.slot5.mlexp.pro.PanneauComparaison.<init>(PanneauComparaison.kt:108)
        // L'onglet « Compare » n'etait donc JAMAIS cree (les 4 onglets gratuits
        // s'affichaient, le 5e manquait, et l'IDE affichait un ballon rouge
        // « IDE error occurred »).
        //
        // CAUSE (lue dans la source du JDK, java.desktop/java/awt/CardLayout.java) :
        //   void checkLayout(Container parent) {
        //       if (parent.getLayout() != this) {
        //           throw new IllegalArgumentException("wrong parent for CardLayout");
        //   public void show(Container parent, String name) { checkLayout(parent); ... }
        // Le 1er argument de `show` est le CONTENEUR qui porte le CardLayout,
        // pas la carte a afficher. Le code passait la CARTE (`carteContenu`,
        // dont le layout est un BorderLayout) : le garde-fou du JDK a vu que
        // `carteContenu.getLayout() != carte` et a leve.
        // De plus, `CardLayout.addLayoutComponent(nom, comp)` enregistre la
        // carte mais ne l'ajoute PAS au conteneur : les cartes n'etaient pas
        // des enfants de `porte`, donc elles n'auraient jamais ete affichees.
        //
        // CORRECTION : `porte.add(carte, "nom")` (Container.add fait les deux
        // choses : il ajoute la carte AU conteneur et l'enregistre dans le
        // CardLayout avec sa cle), puis `carte.show(porte, "contenu")` — on
        // passe bien le conteneur.
        // Contre-epreuve : src/test/kotlin/com/slot5/mlexp/pro/PanneauComparaisonPorteTest.kt
        // (test rouge avant la correction, vert apres).
        porte.add(carteContenu, "contenu")
        porte.add(carteVerrou, "verrou")
        carte.show(porte, "contenu")

        boutonGetPro.addActionListener { ouvrirDialogueAchat() }
        boutonCopier.addActionListener { copierMarkdown() }
        comboMetrique.addActionListener(listenerMetrique)
        comboRuns.addActionListener { onRunBChoisi() }

        hub.ecouter { demande -> traiterDemande(demande) }
        // Jury 11/11 : relire la licence chaque fois que l'onglet redevient
        // visible (apres un achat, ou si la licence n'etait pas lisible au
        // demarrage de l'IDE), au lieu d'attendre une nouvelle comparaison.
        rootComponent.addHierarchyListener { e ->
            if ((e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L &&
                rootComponent.isShowing
            ) {
                rafraichirEtatPro()
            }
        }
        rafraichirEtatPro()
    }

    // ------------------------------------------------------------------
    // Building the interface
    // ------------------------------------------------------------------

    private fun construireContenu() {
        entete.rowHeight = 20
        tableParams.rowHeight = 20
        tableMetriques.rowHeight = 20
        entete.tableHeader.reorderingAllowed = false

        val tableauHaut = JPanel(GridLayout(1, 1)).apply {
            add(JBScrollPane(entete))
        }
        tableauHaut.preferredSize = Dimension(600, 120)

        val splitTableaux = javax.swing.JSplitPane(
            javax.swing.JSplitPane.VERTICAL_SPLIT,
            JBScrollPane(tableParams),
            JBScrollPane(tableMetriques)
        ).apply { dividerLocation = 160 }

        val zoneGraphique = JPanel(BorderLayout()).apply {
            val haut = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JBLabel("Metric: "))
                add(comboMetrique)
            }
            add(haut, BorderLayout.NORTH)
            add(courbe, BorderLayout.CENTER)
        }
        zoneGraphique.preferredSize = Dimension(600, 240)

        val centre = JPanel(BorderLayout()).apply {
            val haut = JPanel(BorderLayout()).apply {
                val ligneRuns = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    add(JBLabel("Compare A with: "))
                    add(comboRuns)
                }
                add(ligneRuns, BorderLayout.NORTH)
                add(tableauHaut, BorderLayout.CENTER)
            }
            add(haut, BorderLayout.NORTH)
            add(splitTableaux, BorderLayout.CENTER)
            add(zoneGraphique, BorderLayout.SOUTH)
        }

        carteContenu.add(centre, BorderLayout.CENTER)
    }

    private fun construireVerrou() {
        messageVerrou.isEditable = false
        messageVerrou.isOpaque = false
        messageVerrou.lineWrap = true
        messageVerrou.wrapStyleWord = true
        messageVerrou.border = javax.swing.BorderFactory.createEmptyBorder(20, 20, 20, 20)
        carteVerrou.add(messageVerrou, BorderLayout.CENTER)
        val bas = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
            add(JLabel("ML Experiment Cockpit Pro - $29/year, free trial."))
        }
        carteVerrou.add(bas, BorderLayout.SOUTH)
    }

    // ------------------------------------------------------------------
    // Pro gate
    // ------------------------------------------------------------------

    /** Reads the licence state again and switches between content and closed gate. */
    private fun rafraichirEtatPro() {
        etatPro = ProGate.etat()
        badge.text = when (etatPro) {
            ProState.PRO -> "licence: active"
            ProState.ESSAI -> "licence: free trial"
            ProState.EXPIRE -> "licence: trial ended"
            ProState.ABSENT -> "licence: none"
            ProState.INCONNU -> "licence: not verifiable"
        }
        if (etatPro.autorise()) {
            messageVerrou.text = ""
            carte.show(porte, "contenu")
        } else {
            messageVerrou.text = ProGate.messageVerrou(etatPro)
            carte.show(porte, "verrou")
        }
    }

    /**
     * Opens the **JetBrains purchase dialog** (it is the one that shows the
     * price and takes the payment). The product code passed is
     * [ProGate.CODE_PRODUIT]: **no amount is written in the code**.
     *
     * The dialog is opened through [AcheteurPro] (the seam): in a real IDE it
     * is the JetBrains purchase dialog, in the test it is a double.
     *
     * **The licence is read again when the dialog closes.** The platform
     * checks the licence "at least once a day" (official documentation), so it
     * can appear *during* the session: without this reading, the customer who
     * has just paid would keep seeing a locked tab.
     */
    private fun ouvrirDialogueAchat() {
        acheteur.ouvrir(ProGate.CODE_PRODUIT, "ML Experiment Cockpit Pro: compare 2 runs.") { erreur ->
            if (erreur != null) status(erreur)
            rafraichirEtatPro()
        }
    }

    // ------------------------------------------------------------------
    // Comparison request
    // ------------------------------------------------------------------

    private fun traiterDemande(d: DemandeComparaison) {
        runA = d.runA
        experimentId = d.experimentId
        comparaison = null
        boutonCopier.isEnabled = false
        rafraichirEtatPro()
        if (!etatPro.autorise()) {
            status("Comparison locked: an ML Experiment Cockpit Pro licence is required.")
            return
        }
        status("Loading the runs of experiment ${d.experimentNom} ...")
        chargerRunsCandidats()
    }

    /** Loads the list of runs of the experiment (candidates for B). */
    private fun chargerRunsCandidats() {
        val a = runA ?: return
        val base = MlflowSettingsImpl.getInstance().getTrackingUri().trimEnd('/')
        if (base.isEmpty()) {
            status("Configure the MLflow URI first (Settings > Tools > ML Experiment Cockpit).")
            return
        }
        // Jury 11/11 (amelioration 5) : the IDE's pooled threads are used
        // instead of a raw `Thread(...)` (the former thread was named
        // "mlexp-compare-runs"). The work is identical, only the thread
        // comes from the platform's pool.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = MlflowClient(base)
                val runs = client.listRuns(experimentId).filter { it.id != a.id }
                ApplicationManager.getApplication().invokeLater {
                    candidats = runs
                    val model = DefaultComboBoxModel<String>()
                    for (r in runs) {
                        model.addElement(if (r.name.isEmpty()) r.id.take(8) else r.name)
                    }
                    comboRuns.model = model
                    if (runs.isEmpty()) {
                        status("No other run in this experiment: nothing to compare.")
                    } else {
                        status("${runs.size} run(s) available for the comparison.")
                        // The comparison is computed as soon as B is chosen.
                        onRunBChoisi()
                    }
                }
            } catch (e: MlflowException) {
                ApplicationManager.getApplication().invokeLater {
                    status("Failed to load the runs: ${e.message}")
                }
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    status("Unexpected error: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Comparison
    // ------------------------------------------------------------------

    /** The run B chosen in the list (null if none). */
    private fun runChoisi(): Run? {
        val i = comboRuns.selectedIndex
        return if (i >= 0 && i < candidats.size) candidats[i] else null
    }

    /** Called when the user changes run B in the list. */
    private fun onRunBChoisi() {
        if (candidats.isEmpty()) return
        if (!etatPro.autorise()) {
            status("Comparison locked: an ML Experiment Cockpit Pro licence is required.")
            return
        }
        calculerComparaison()
    }

    /** Computes and displays the comparison between A and the chosen run B. */
    private fun calculerComparaison() {
        val a = runA ?: return
        val b = runChoisi() ?: return
        if (enCours) return
        enCours = true
        val base = MlflowSettingsImpl.getInstance().getTrackingUri().trimEnd('/')
        if (base.isEmpty()) {
            enCours = false
            status("Configure the MLflow URI first (Settings > Tools > ML Experiment Cockpit).")
            return
        }
        // Jury 11/11 (amelioration 5) : pooled thread of the platform
        // (the former raw thread was named "mlexp-compare-calcul").
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = MlflowClient(base)
                val detailsA = client.getRunDetails(a.id)
                val detailsB = client.getRunDetails(b.id)
                val refA = RunRef(
                    id = detailsA.id,
                    nom = a.name,
                    statut = detailsA.statut,
                    startTimeMs = detailsA.startTimeMs,
                    endTimeMs = detailsA.endTimeMs,
                    origine = base,
                    params = detailsA.params,
                    metriques = detailsA.metriques
                )
                val refB = RunRef(
                    id = detailsB.id,
                    nom = b.name,
                    statut = detailsB.statut,
                    startTimeMs = detailsB.startTimeMs,
                    endTimeMs = detailsB.endTimeMs,
                    origine = base,
                    params = detailsB.params,
                    metriques = detailsB.metriques
                )
                val resultat = ComparaisonRuns.calculer(listOf(refA, refB), ProGate.etat())
                ApplicationManager.getApplication().invokeLater {
                    enCours = false
                    // Jury 11/11 : si l'utilisateur a change B pendant le calcul,
                    // ne pas afficher la comparaison d'un autre run : recalculer.
                    if (runChoisi()?.id != b.id) {
                        calculerComparaison()
                    } else {
                        afficher(resultat)
                    }
                }
            } catch (e: MlflowException) {
                ApplicationManager.getApplication().invokeLater {
                    enCours = false
                    status("Failed to load the runs: ${e.message}")
                }
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    enCours = false
                    status("Unexpected error: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun afficher(resultat: ResultatComparaison) {
        when (resultat) {
            is ResultatComparaison.Refus -> {
                comparaison = null
                boutonCopier.isEnabled = false
                viderTableaux()
                status(resultat.message)
            }
            is ResultatComparaison.Comparaison -> {
                comparaison = resultat
                remplirEntete(resultat)
                remplirTableaux(resultat)
                remplirMetriques(resultat)
                boutonCopier.isEnabled = true
                status("${resultat.nombreEcarts} difference(s) between the 2 runs.")
            }
        }
    }

    private fun remplirEntete(c: ResultatComparaison.Comparaison) {
        val model = entete.model as DefaultTableModel
        model.setValueAt(c.a.libelle(), 0, 0)
        model.setValueAt(c.b.libelle(), 0, 1)
        model.setValueAt(c.a.id, 1, 0)
        model.setValueAt(c.b.id, 1, 1)
        model.setValueAt(c.a.statut, 2, 0)
        model.setValueAt(c.b.statut, 2, 1)
        model.setValueAt(formatInstant(c.a.startTimeMs), 3, 0)
        model.setValueAt(formatInstant(c.b.startTimeMs), 3, 1)
        model.setValueAt(c.dureeA, 4, 0)
        model.setValueAt(c.dureeB, 4, 1)
    }

    private fun remplirTableaux(c: ResultatComparaison.Comparaison) {
        val mp = tableParams.model as DefaultTableModel
        mp.rowCount = 0
        for (l in c.params) {
            mp.addRow(arrayOf(l.cle, l.valeurA ?: "-", l.valeurB ?: "-", l.etat.libelle))
        }
        val mm = tableMetriques.model as DefaultTableModel
        mm.rowCount = 0
        for (l in c.metriques) {
            mm.addRow(
                arrayOf(
                    l.cle,
                    ComparaisonRuns.formatNombre(l.valeurA) ?: "-",
                    ComparaisonRuns.formatNombre(l.valeurB) ?: "-",
                    ComparaisonRuns.formatNombre(l.ecartAbsolu) ?: "-",
                    ComparaisonRuns.formatEcartRelatif(l.ecartRelatifPct) ?: "-",
                    l.etat.libelle
                )
            )
        }
    }

    private fun remplirMetriques(c: ResultatComparaison.Comparaison) {
        val model = comboMetrique.model as DefaultComboBoxModel<String>
        // Jury 11/11 (amelioration 3) : the listener is removed for the time
        // of the filling; emptying the list and adding the metrics fire the
        // action event of the combo box, and each of them started a thread
        // loading the history of 100 000 points.
        comboMetrique.removeActionListener(listenerMetrique)
        try {
            model.removeAllElements()
            for (m in c.metriquesCommunes) model.addElement(m)
            comboMetrique.isEnabled = model.size > 0
            if (model.size > 0) comboMetrique.selectedIndex = 0
        } finally {
            comboMetrique.addActionListener(listenerMetrique)
        }
        // The listener was off during the filling: the history of the first
        // metric is loaded once, on purpose.
        if (model.size > 0) onMetriqueChoisie()
    }

    private fun viderTableaux() {
        (tableParams.model as DefaultTableModel).rowCount = 0
        (tableMetriques.model as DefaultTableModel).rowCount = 0
        (comboMetrique.model as DefaultComboBoxModel<String>).removeAllElements()
        courbe.setSeries(emptyList(), emptyList(), "")
    }

    // ------------------------------------------------------------------
    // Overlaid curve
    // ------------------------------------------------------------------

    private fun onMetriqueChoisie() {
        val metrique = comboMetrique.selectedItem as? String ?: return
        if (metrique.isBlank()) return
        val c = comparaison ?: return
        val base = MlflowSettingsImpl.getInstance().getTrackingUri().trimEnd('/')
        if (base.isEmpty()) return
        // Jury 11/11 (amelioration 2) : the request is numbered; the answer
        // of a request that is not the last one is ignored (it would show the
        // curve of a metric that is not the selected one any more).
        val demande = ++compteurDemandeHistoire
        status("Loading the history of $metrique ...")
        // Jury 11/11 (amelioration 5) : pooled thread of the platform
        // (the former raw thread was named "mlexp-compare-histoire").
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = MlflowClient(base)
                val pageA = client.getMetricHistoryAll(c.a.id, metrique, PLAFOND_POINTS_PRO)
                val pageB = client.getMetricHistoryAll(c.b.id, metrique, PLAFOND_POINTS_PRO)
                ApplicationManager.getApplication().invokeLater {
                    if (demande != compteurDemandeHistoire) return@invokeLater
                    courbe.setSeries(pageA.points, pageB.points, metrique)
                    val tronque = pageA.tronque || pageB.tronque
                    val nA = pageA.points.size
                    val nB = pageB.points.size
                    status(
                        if (tronque)
                            "$metrique: $nA point(s) for A, $nB for B - series truncated at $PLAFOND_POINTS_PRO points."
                        else
                            "$metrique: $nA point(s) for A, $nB for B."
                    )
                }
            } catch (e: MlflowException) {
                ApplicationManager.getApplication().invokeLater {
                    if (demande != compteurDemandeHistoire) return@invokeLater
                    status("Failed to load the history: ${e.message}")
                }
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    if (demande != compteurDemandeHistoire) return@invokeLater
                    status("Unexpected error: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    private fun copierMarkdown() {
        val c = comparaison ?: return
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(c.markdown()), null)
            status("Comparison copied to the clipboard (Markdown).")
        } catch (t: Throwable) {
            status("Copy failed: ${t.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun status(message: String) {
        statusLabel.text = message
    }

    private fun formatInstant(ms: Long): String {
        if (ms <= 0L) return "-"
        return java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))
    }

    companion object {
        /**
         * Ceiling of points per metric for Pro tier 1: 100 000 (the free tier
         * keeps 5 000). Beyond it, the series is **declared truncated** to the
         * user - never a silently incomplete curve.
         */
        const val PLAFOND_POINTS_PRO = 100000
    }
}

/** Non editable table (the interface is read-only). */
private class ModeleNonEditable(columns: Array<Any>, rows: Int) : DefaultTableModel(columns, rows) {
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
}
