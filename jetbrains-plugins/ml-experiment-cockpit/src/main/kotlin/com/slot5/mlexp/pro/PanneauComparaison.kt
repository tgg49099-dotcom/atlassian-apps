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
 * Onglet **Compare** — palier Pro n°1 (« comparer 2 runs »).
 *
 * v0.0.22. Le 5ᵉ onglet du tool window « ML Experiment Cockpit ». Il est
 * **visible par tout le monde** et **verrouillé sans licence** : ce n'est
 * jamais un message d'erreur, c'est une porte (texte court + bouton
 * « Get Pro » qui ouvre le dialogue d'achat JetBrains).
 *
 * Contenu quand la porte est ouverte :
 *   - un en-tête **A / B** (nom, `run_id`, statut, début, durée) ;
 *   - le tableau des **paramètres** côte à côte ;
 *   - le tableau des **métriques** côte à côte (écart absolu + écart relatif) ;
 *   - un sélecteur de métrique et la **courbe superposée** (A en bleu, B en
 *     orange) ;
 *   - le bouton **« Copy (Markdown) »** : le geste « je veux tout exporter »
 *     du plan — il met les 2 tableaux dans le presse-papier (pas de fichier,
 *     pas de CSV : l'export CSV/XLSX est le palier n°2).
 *
 * Le panneau est **lecture seule** : il n'écrit jamais sur le serveur.
 */
class PanneauComparaison(private val hub: ComparaisonHub) {

    /** Le composant Swing que la plateforme héberge. */
    val rootComponent: JPanel = JPanel(BorderLayout())

    private val carte = CardLayout()
    /** Le panneau qui PORTE le CardLayout : c'est lui qu'on ajoute à la racine. */
    private val porte = JPanel(carte)
    private val carteContenu = JPanel(BorderLayout())
    private val carteVerrou = JPanel(BorderLayout())

    private val badge = JBLabel("licence : —").apply {
        foreground = java.awt.Color(0x55, 0x55, 0x55)
    }
    private val boutonGetPro = JButton("Get Pro")
    private val boutonCopier = JButton("Copy (Markdown)").apply { isEnabled = false }
    private val statusLabel = JBLabel("Select a run in the Experiments tab, then right-click > Compare with…")

    private val entete = JTable(ModeleNonEditable(arrayOf<Any>("A", "B"), 5))
    private val tableParams = JTable(ModeleNonEditable(arrayOf<Any>("paramètre", "A", "B", "état"), 0))
    private val tableMetriques = JTable(
        ModeleNonEditable(arrayOf<Any>("métrique", "A", "B", "écart absolu", "écart relatif", "état"), 0)
    )
    private val comboMetrique = JComboBox<String>(DefaultComboBoxModel<String>())
    private val comboRuns = JComboBox<String>(DefaultComboBoxModel<String>())
    private val courbe = CourbeDiff()
    private val messageVerrou = JTextArea()

    private var runA: Run? = null
    private var experimentId: String = ""
    private var candidats: List<Run> = emptyList()
    private var comparaison: ResultatComparaison.Comparaison? = null
    private var etatPro: ProState = ProState.INCONNU
    private var enCours = false

    init {
        // ---- NORTH : titre, état de la licence, boutons ----
        val topBar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Compare 2 runs").apply {
                font = com.intellij.util.ui.JBFont.regular()
            })
            add(badge)
            add(boutonGetPro)
            add(boutonCopier)
        }
        rootComponent.add(topBar, BorderLayout.NORTH)

        // ---- CENTER : contenu ou porte fermée ----
        rootComponent.add(porte, BorderLayout.CENTER)

        construireContenu()
        construireVerrou()

        carte.addLayoutComponent("contenu", carteContenu)
        carte.addLayoutComponent("verrou", carteVerrou)
        carte.show(carteContenu, "contenu")

        boutonGetPro.addActionListener { ouvrirDialogueAchat() }
        boutonCopier.addActionListener { copierMarkdown() }
        comboMetrique.addActionListener { onMetriqueChoisie() }
        comboRuns.addActionListener { onRunBChoisi() }

        hub.ecouter { demande -> traiterDemande(demande) }
        rafraichirEtatPro()
    }

    // ------------------------------------------------------------------
    // Construction de l'interface
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
        carteContenu.add(statusLabel, BorderLayout.SOUTH)
    }

    private fun construireVerrou() {
        messageVerrou.isEditable = false
        messageVerrou.isOpaque = false
        messageVerrou.lineWrap = true
        messageVerrou.wrapStyleWord = true
        messageVerrou.border = javax.swing.BorderFactory.createEmptyBorder(20, 20, 20, 20)
        carteVerrou.add(messageVerrou, BorderLayout.CENTER)
        val bas = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
            add(JLabel("ML Experiment Cockpit Pro — 29 $/an, essai gratuit 14 jours."))
        }
        carteVerrou.add(bas, BorderLayout.SOUTH)
    }

    // ------------------------------------------------------------------
    // Porte Pro
    // ------------------------------------------------------------------

    /** Relit l'état de la licence et bascule entre contenu et porte fermée. */
    private fun rafraichirEtatPro() {
        etatPro = ProGate.etat()
        badge.text = when (etatPro) {
            ProState.PRO -> "licence : active"
            ProState.ESSAI -> "licence : essai gratuit"
            ProState.EXPIRE -> "licence : essai terminé"
            ProState.ABSENT -> "licence : aucune"
            ProState.INCONNU -> "licence : non vérifiable"
        }
        if (etatPro.autorise()) {
            messageVerrou.text = ""
            carte.show(carteContenu, "contenu")
        } else {
            messageVerrou.text = ProGate.messageVerrou(etatPro)
            carte.show(carteVerrou, "verrou")
        }
    }

    /**
     * Ouvre le **dialogue d'achat JetBrains** (c'est lui qui affiche le prix
     * et encaisse) via l'action `RegisterPlugins` (ou `Register` dans une
     * distribution commerciale). Le code produit transmis est
     * [ProGate.CODE_PRODUIT] : **aucun montant n'est écrit dans le code**.
     */
    private fun ouvrirDialogueAchat() {
        ApplicationManager.getApplication().invokeLater({
            try {
                val am = ActionManager.getInstance()
                var action = am.getAction("RegisterPlugins")
                if (action == null) action = am.getAction("Register")
                if (action == null) {
                    status("Le dialogue d'achat JetBrains n'est pas disponible dans cet IDE.")
                    return@invokeLater
                }
                val contexte = DataContext { id ->
                    when (id) {
                        "register.product-descriptor.code" -> ProGate.CODE_PRODUIT
                        "register.message" -> "ML Experiment Cockpit Pro : comparaison de 2 runs."
                        else -> null
                    }
                }
                action.actionPerformed(
                    AnActionEvent.createFromDataContext("", Presentation(), contexte)
                )
            } catch (t: Throwable) {
                status("Ouverture du dialogue d'achat impossible : ${t.javaClass.simpleName}")
            }
        }, ModalityState.NON_MODAL)
    }

    // ------------------------------------------------------------------
    // Demande de comparaison
    // ------------------------------------------------------------------

    private fun traiterDemande(d: DemandeComparaison) {
        runA = d.runA
        experimentId = d.experimentId
        comparaison = null
        boutonCopier.isEnabled = false
        rafraichirEtatPro()
        if (!etatPro.autorise()) {
            status("Comparaison verrouillee : licence ML Experiment Cockpit Pro requise.")
            return
        }
        status("Chargement des runs de l'experience ${d.experimentNom} ...")
        chargerRunsCandidats()
    }

    /** Charge la liste des runs de l'expérience (candidats pour B). */
    private fun chargerRunsCandidats() {
        val a = runA ?: return
        val base = MlflowSettingsImpl.getInstance().getTrackingUri().trimEnd('/')
        if (base.isEmpty()) {
            status("Configurez d'abord l'URI MLflow (Settings > Tools > ML Experiment Cockpit).")
            return
        }
        Thread({
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
                        status("Aucun autre run dans cette experience : rien a comparer.")
                    } else {
                        status("${runs.size} run(s) disponible(s) pour la comparaison.")
                        // La comparaison est calculée dès que B est choisi.
                        onRunBChoisi()
                    }
                }
            } catch (e: MlflowException) {
                ApplicationManager.getApplication().invokeLater {
                    status("Echec du chargement des runs : ${e.message}")
                }
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    status("Erreur inattendue : ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }, "mlexp-compare-runs").start()
    }

    // ------------------------------------------------------------------
    // Comparaison
    // ------------------------------------------------------------------

    /** Le run B choisi dans la liste (null si aucun). */
    private fun runChoisi(): Run? {
        val i = comboRuns.selectedIndex
        return if (i >= 0 && i < candidats.size) candidats[i] else null
    }

    /** Appelé quand l'utilisateur change le run B dans la liste. */
    private fun onRunBChoisi() {
        if (candidats.isEmpty()) return
        if (!etatPro.autorise()) {
            status("Comparaison verrouillee : licence ML Experiment Cockpit Pro requise.")
            return
        }
        calculerComparaison()
    }

    /** Calcule et affiche la comparaison entre A et le run B choisi. */
    private fun calculerComparaison() {
        val a = runA ?: return
        val b = runChoisi() ?: return
        if (enCours) return
        enCours = true
        val base = MlflowSettingsImpl.getInstance().getTrackingUri().trimEnd('/')
        if (base.isEmpty()) {
            enCours = false
            status("Configurez d'abord l'URI MLflow (Settings > Tools > ML Experiment Cockpit).")
            return
        }
        Thread({
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
                    afficher(resultat)
                }
            } catch (e: MlflowException) {
                ApplicationManager.getApplication().invokeLater {
                    enCours = false
                    status("Echec du chargement des runs : ${e.message}")
                }
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    enCours = false
                    status("Erreur inattendue : ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }, "mlexp-compare-calcul").start()
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
                status("${resultat.nombreEcarts} ecart(s) entre les 2 runs.")
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
            mp.addRow(arrayOf(l.cle, l.valeurA ?: "—", l.valeurB ?: "—", l.etat.libelle))
        }
        val mm = tableMetriques.model as DefaultTableModel
        mm.rowCount = 0
        for (l in c.metriques) {
            mm.addRow(
                arrayOf(
                    l.cle,
                    ComparaisonRuns.formatNombre(l.valeurA) ?: "—",
                    ComparaisonRuns.formatNombre(l.valeurB) ?: "—",
                    ComparaisonRuns.formatNombre(l.ecartAbsolu) ?: "—",
                    ComparaisonRuns.formatEcartRelatif(l.ecartRelatifPct) ?: "—",
                    l.etat.libelle
                )
            )
        }
    }

    private fun remplirMetriques(c: ResultatComparaison.Comparaison) {
        val model = comboMetrique.model as DefaultComboBoxModel<String>
        model.removeAllElements()
        for (m in c.metriquesCommunes) model.addElement(m)
        comboMetrique.isEnabled = model.size > 0
        if (model.size > 0) comboMetrique.selectedIndex = 0
    }

    private fun viderTableaux() {
        (tableParams.model as DefaultTableModel).rowCount = 0
        (tableMetriques.model as DefaultTableModel).rowCount = 0
        (comboMetrique.model as DefaultComboBoxModel<String>).removeAllElements()
        courbe.setSeries(emptyList(), emptyList(), "")
    }

    // ------------------------------------------------------------------
    // Courbe superposée
    // ------------------------------------------------------------------

    private fun onMetriqueChoisie() {
        val metrique = comboMetrique.selectedItem as? String ?: return
        if (metrique.isBlank()) return
        val c = comparaison ?: return
        val base = MlflowSettingsImpl.getInstance().getTrackingUri().trimEnd('/')
        if (base.isEmpty()) return
        status("Chargement de l'historique de $metrique ...")
        Thread({
            try {
                val client = MlflowClient(base)
                val pageA = client.getMetricHistoryAll(c.a.id, metrique, PLAFOND_POINTS_PRO)
                val pageB = client.getMetricHistoryAll(c.b.id, metrique, PLAFOND_POINTS_PRO)
                ApplicationManager.getApplication().invokeLater {
                    courbe.setSeries(pageA.points, pageB.points, metrique)
                    val tronque = pageA.tronque || pageB.tronque
                    val nA = pageA.points.size
                    val nB = pageB.points.size
                    status(
                        if (tronque)
                            "$metrique : $nA point(s) pour A, $nB pour B — serie tronquee a $PLAFOND_POINTS_PRO points."
                        else
                            "$metrique : $nA point(s) pour A, $nB pour B."
                    )
                }
            } catch (e: MlflowException) {
                ApplicationManager.getApplication().invokeLater {
                    status("Echec du chargement de l'historique : ${e.message}")
                }
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    status("Erreur inattendue : ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }, "mlexp-compare-histoire").start()
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    private fun copierMarkdown() {
        val c = comparaison ?: return
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(c.markdown()), null)
            status("Comparaison copiee dans le presse-papier (Markdown).")
        } catch (t: Throwable) {
            status("Copie impossible : ${t.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------
    // Aides
    // ------------------------------------------------------------------

    private fun status(message: String) {
        statusLabel.text = message
    }

    private fun formatInstant(ms: Long): String {
        if (ms <= 0L) return "—"
        return java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))
    }

    companion object {
        /**
         * Plafond de points par métrique pour le palier Pro n°1 : 100 000
         * (le gratuit garde 5 000). Au-delà, la série est **déclarée tronquée**
         * à l'utilisateur — jamais une courbe silencieusement incomplète.
         */
        const val PLAFOND_POINTS_PRO = 100000
    }
}

/** Tableau non éditable (l'interface est en lecture seule). */
private class ModeleNonEditable(columns: Array<Any>, rows: Int) : DefaultTableModel(columns, rows) {
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
}
