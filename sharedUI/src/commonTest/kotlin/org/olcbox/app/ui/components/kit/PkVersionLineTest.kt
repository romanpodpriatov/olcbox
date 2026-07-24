package org.olcbox.app.ui.components.kit

import org.olcbox.app.AppInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class PkVersionLineTest {
    @Test
    fun formatsBrandVersionAndCore() {
        assertEquals(
            "PROOFKIT · v1.0.209 · OLCBOX CORE",
            pkVersionLine(AppInfo(name = "olcbox", version = "1.0.209"))
        )
    }

    @Test
    fun stripsLeadingVIfAlreadyPresent() {
        assertEquals(
            "PROOFKIT · v2.0.0 · OLCBOX CORE",
            pkVersionLine(AppInfo(name = "olcbox", version = "v2.0.0"))
        )
    }
}
