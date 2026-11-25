package uk.co.jwgardenservices.crm.data.website

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebsiteProfileReaderTest {
    @Test
    fun `free scan keeps services areas credentials and contact`() {
        val profile = WebsiteProfileReader.buildProfile(
            listOf(
                "https://example.co.uk" to """
                    Skip to main content
                    JW Garden Services
                    Garden maintenance and landscaping across Aylesbury since 2017.
                    Weekly lawn cuts, hedge trimming, border weeding and winter tidy.
                    Patios, paths, fencing, decking and new turf.
                    We cover Bierton, Aylesbury, Tring and HP17 to HP22.
                    City & Guilds qualified. Gardeners Guild accredited. Public liability insured.
                    Phone 07950 636954. Email info@example.co.uk.
                    Opening hours Monday to Friday 8am to 4pm.
                    Privacy policy
                """.trimIndent(),
            ),
        )
        assertTrue(profile != null)
        assertTrue(profile!!.contains("SERVICES:"))
        assertTrue(profile.contains("lawn", ignoreCase = true))
        assertTrue(profile.contains("AREAS:"))
        assertTrue(profile.contains("Tring"))
        assertTrue(profile.contains("CREDENTIALS:"))
        assertTrue(profile.contains("Guild"))
        assertTrue(profile.contains("HOURS & CONTACT:"))
        assertTrue(profile.contains("07950"))
    }

    @Test
    fun `nothing readable means no profile rather than a guess`() {
        assertNull(WebsiteProfileReader.buildProfile(emptyList()))
        assertNull(
            WebsiteProfileReader.buildProfile(
                listOf("https://example.co.uk" to "Menu\nPrivacy policy\n© 2026"),
            ),
        )
    }
}
