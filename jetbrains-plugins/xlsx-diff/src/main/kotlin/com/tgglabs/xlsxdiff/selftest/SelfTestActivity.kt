package com.tgglabs.xlsxdiff.selftest

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Runs the self-test of the plugin when the IDE is started with
 * `-Dxlsxdiff.selftest=1`.
 *
 * Inert in a normal IDE: without the property nothing happens.
 */
class SelfTestActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (System.getProperty("xlsxdiff.selftest") != "1") return
        val report = try {
            SelfTest.run(project)
        } catch (e: Throwable) {
            "${SelfTest.MARKER} KO ${e.javaClass.simpleName}: ${e.message}"
        }
        Logger.getInstance(SelfTestActivity::class.java).info(report)
        println(report)
    }
}
