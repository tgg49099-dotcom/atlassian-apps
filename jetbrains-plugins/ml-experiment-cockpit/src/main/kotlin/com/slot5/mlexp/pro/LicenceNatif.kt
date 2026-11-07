package com.slot5.mlexp.pro

import com.intellij.ui.LicensingFacade

/**
 * Lecture de la licence **native** de la plateforme IntelliJ.
 *
 * v0.0.22. C'est le seul endroit du code qui parle à `LicensingFacade`.
 *
 * Ce que fait la plateforme, et que nous ne refaisons pas (doc officielle
 * « Add license verification calls to the plugin code ») :
 *   - la licence est vérifiée par l'IDE au démarrage, puis au moins une fois
 *     par jour ;
 *   - le sceau nous est fourni **signé** par la plateforme — « there are no
 *     private keys in the platform » ;
 *   - notre rôle se limite à constater la présence d'un sceau pour NOTRE
 *     code produit.
 *
 * Deux cas de retour précis, jamais une exception vague :
 *   - `LicensingFacade.getInstance() == null` : le facade n'est pas encore
 *     initialisé (démarrage de l'IDE) → [ProState.INCONNU] ;
 *   - la classe n'est pas lisible (IDE propriétaire d'une version qui l'a
 *     retirée) → [ProState.INCONNU] avec le motif exact.
 *
 * Dans les deux cas, **rien n'est ouvert** : fail-closed.
 */
class LicenceNatif : SourceLicence {

    override fun lire(): LectureLicence = try {
        val facade = LicensingFacade.getInstance()
        if (facade == null) {
            LectureLicence(
                facadeInitialisee = false,
                motif = "LicensingFacade non initialise (demarrage de l'IDE)"
            )
        } else {
            LectureLicence(
                facadeInitialisee = true,
                sceau = facade.getConfirmationStamp(ProGate.CODE_PRODUIT)
            )
        }
    } catch (t: Throwable) {
        // La classe peut disparaitre d'une version d'IDE : on ne casse pas
        // l'IDE, on refuse poliment la partie payante.
        LectureLicence(
            facadeInitialisee = false,
            motif = "lecture de licence impossible : ${t.javaClass.simpleName}"
        )
    }
}
