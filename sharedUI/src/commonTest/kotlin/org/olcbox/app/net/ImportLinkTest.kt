package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportLinkTest {
    private val list = "https://proofkit.org/sub/abc123?transport=auto"

    @Test fun theSchemeLinkCarriesTheListInTheQuery() {
        val link = ImportLink.schemeLink(list)
        assertEquals("proofkit://add?url=https%3A%2F%2Fproofkit.org%2Fsub%2Fabc123%3Ftransport%3Dauto", link)
        assertEquals(list, ImportLink.payloadOf(link))
    }

    @Test fun theWebLinkKeepsTheListInTheFragmentSoNoServerSeesIt() {
        val link = ImportLink.webLink(list)
        assertEquals("https://proofkit.org/add#https%3A%2F%2Fproofkit.org%2Fsub%2Fabc123%3Ftransport%3Dauto", link)
        assertEquals(list, ImportLink.payloadOf(link))
    }

    @Test fun otherShapesPeopleWillTypeStillParse() {
        assertEquals("olcrtc://crypt1/abc", ImportLink.payloadOf("PROOFKIT://add#olcrtc%3A%2F%2Fcrypt1%2Fabc"))
        assertEquals("happ://crypt5/xyz", ImportLink.payloadOf("https://www.proofkit.org/add?url=happ%3A%2F%2Fcrypt5%2Fxyz"))
        assertEquals("olcrtc://crypt1/abc", ImportLink.payloadOf("  proofkit://add?url=olcrtc%3A%2F%2Fcrypt1%2Fabc \n"))
        assertEquals("olcrtc://crypt1/abc", ImportLink.payloadOf("https://proofkit.org/add/#olcrtc%3A%2F%2Fcrypt1%2Fabc"))
    }

    @Test fun aRawListPastedIntoTheFragmentStillComesOut() {
        // Percent-decoding an unencoded payload must not eat it.
        assertEquals("https://proofkit.org/sub/abc?transport=auto", ImportLink.payloadOf("https://proofkit.org/add#https://proofkit.org/sub/abc?transport=auto"))
    }

    @Test fun anythingElseIsNotAnImportLink() {
        assertNull(ImportLink.payloadOf("https://proofkit.org/"))
        assertNull(ImportLink.payloadOf("https://proofkit.org/adder#x"))
        assertNull(ImportLink.payloadOf("https://example.org/add#x"))
        assertNull(ImportLink.payloadOf("proofkit://add"))
        assertNull(ImportLink.payloadOf("proofkit://add?url="))
        assertNull(ImportLink.payloadOf("proofkit://other?url=x"))
        assertNull(ImportLink.payloadOf(""))
    }
}
