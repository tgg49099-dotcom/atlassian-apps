package com.slot5.mlexp.pro

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState

/**
 * The purchase dialog of the Pro tier - **the seam**.
 *
 * The JetBrains purchase dialog is opened through the `RegisterPlugins` action
 * (or `Register` in a commercial distribution) with a `DataContext` that
 * carries the product descriptor code, exactly like the official sample
 * (`CheckLicense.java`, "Add license verification calls to the plugin code").
 *
 * **Why an interface.** That call needs a running IDE: it cannot be exercised
 * in a bare JVM (`./gradlew test`). The panel therefore talks to this
 * interface, and the test replaces it with a double - **no secret, no
 * licence, no token** - so the whole "the customer buys, the tab opens"
 * path can be proven without an IDE and without a licence.
 *
 * **What the callback is for.** The platform checks the licence "at least once
 * a day", so the licence can appear *during* the session. The panel re-reads
 * it when the dialog closes (see `PanneauComparaison.ouvrirDialogueAchat`).
 */
interface AcheteurPro {

    /**
     * Opens the purchase dialog of the product [codeProduit].
     *
     * @param surFermeture called when the dialog is closed. It carries an
     *        error message, or `null` when everything went well.
     */
    fun ouvrir(codeProduit: String, message: String, surFermeture: (String?) -> Unit)
}

/**
 * The real one: the JetBrains purchase dialog of the running IDE.
 *
 * The product code is **passed in** and never written here: it lives in one
 * single place in the code (`ProGate.CODE_PRODUIT`).
 */
class AcheteurPlateforme : AcheteurPro {

    override fun ouvrir(codeProduit: String, message: String, surFermeture: (String?) -> Unit) {
        try {
            ApplicationManager.getApplication().invokeLater({
                var erreur: String? = null
                try {
                    val am = ActionManager.getInstance()
                    var action = am.getAction("RegisterPlugins")
                    if (action == null) action = am.getAction("Register")
                    if (action == null) {
                        erreur = "The JetBrains purchase dialog is not available in this IDE."
                    } else {
                        val contexte = DataContext { id ->
                            when (id) {
                                "register.product-descriptor.code" -> codeProduit
                                "register.message" -> message
                                else -> null
                            }
                        }
                        action.actionPerformed(
                            AnActionEvent.createFromDataContext("", Presentation(), contexte)
                        )
                    }
                } catch (t: Throwable) {
                    erreur = "Could not open the purchase dialog: ${t.javaClass.simpleName}"
                } finally {
                    surFermeture(erreur)
                }
            }, ModalityState.NON_MODAL)
        } catch (t: Throwable) {
            // No IDE: the panel must not break, it simply says so.
            surFermeture("Could not open the purchase dialog: ${t.javaClass.simpleName}")
        }
    }
}
