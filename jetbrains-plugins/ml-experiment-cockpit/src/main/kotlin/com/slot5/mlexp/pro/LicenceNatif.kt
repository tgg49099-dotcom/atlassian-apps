package com.slot5.mlexp.pro

import com.intellij.ui.LicensingFacade

/**
 * Reading of the **native** licence of the IntelliJ platform.
 *
 * v0.0.22. This is the only place in the code that talks to `LicensingFacade`.
 *
 * What the platform does, and what we do not redo (official documentation,
 * "Add license verification calls to the plugin code"):
 *   - the licence is checked by the IDE at startup, then at least once a day;
 *   - the stamp is handed to us **signed** by the platform - "there are no
 *     private keys in the platform";
 *   - our only job is to note the presence of a stamp for OUR product code.
 *
 * Two precise return cases, never a vague exception:
 *   - `LicensingFacade.getInstance() == null`: the facade is not initialised
 *     yet (IDE startup) -> [ProState.INCONNU];
 *   - the class cannot be read (IDE owner of a version that removed it)
 *     -> [ProState.INCONNU] with the exact reason.
 *
 * In both cases **nothing is unlocked**: fail-closed.
 */
class LicenceNatif : SourceLicence {

    override fun lire(): LectureLicence = try {
        val facade = LicensingFacade.getInstance()
        if (facade == null) {
            LectureLicence(
                facadeInitialisee = false,
                motif = "LicensingFacade not initialised (IDE startup)"
            )
        } else {
            LectureLicence(
                facadeInitialisee = true,
                sceau = facade.getConfirmationStamp(ProGate.CODE_PRODUIT)
            )
        }
    } catch (t: Throwable) {
        // The class may disappear from a version of the IDE: we do not break
        // the IDE, we politely refuse the paid part.
        LectureLicence(
            facadeInitialisee = false,
            motif = "licence read failed: ${t.javaClass.simpleName}"
        )
    }
}
