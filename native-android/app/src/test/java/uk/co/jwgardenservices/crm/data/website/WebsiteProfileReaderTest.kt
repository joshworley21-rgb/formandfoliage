package uk.co.jwgardenservices.crm.data.website

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebsiteProfileReaderTest {
    @Test
    fun `free scan keeps services areas credentials and contact`() {
        val profile = WebsiteProfileReader.buildProfile(
            texts = listOf(
                "https://example.co.uk" to """
                    Skip to main content
                    JW Garden Services keeps gardens across Aylesbury and Bierton looking their best.
                    Garden Maintenance
                    Garden maintenance around Aylesbury, weekly or as a one-off. We keep the lawn cut, the hedges neat and the borders weeded.
                    Landscaping & Patios
                    Patios, paths, fencing, decking and new lawns across Aylesbury and Buckinghamshire.
                    Garden Design & Planting
                    New borders, better planting, or a whole new garden.
                    Commercial Maintenance
                    Grounds maintenance for offices, schools, pubs, care homes and managed properties.
                    We cover Bierton, Aylesbury, Tring and HP17 to HP22.
                    City & Guilds qualified. Gardeners Guild accredited. Public liability insured.
                    Phone 07950 636954. Email info@example.co.uk.
                    Opening hours Monday to Friday 8am to 4pm.
                    Find out more about garden maintenance
                    Privacy policy
                """.trimIndent(),
            ),
            title = "Garden Maintenance & Landscaping | Bucks, Beds & Herts",
            headings = listOf(
                "Garden Maintenance",
                "Landscaping & Patios",
                "Garden Design & Planting",
                "Commercial Maintenance",
                "Tell Us About Your Garden",
            ),
        )
        assertTrue(profile != null)
        assertTrue(profile!!.contains("SERVICES:"))
        assertTrue(profile.contains("Garden Maintenance"))
        assertTrue(profile.contains("Landscaping & Patios"))
        assertTrue(profile.contains("lawn", ignoreCase = true))
        assertTrue(profile.contains("AREAS:"))
        assertTrue(profile.contains("Tring"))
        assertTrue(profile.contains("CREDENTIALS:"))
        assertTrue(profile.contains("Guild"))
        assertTrue(profile.contains("HOURS & CONTACT:"))
        assertTrue(profile.contains("07950"))
        assertTrue(!profile.contains("Find out more"))
        assertTrue(!profile.contains("Tell Us About Your Garden"))
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
