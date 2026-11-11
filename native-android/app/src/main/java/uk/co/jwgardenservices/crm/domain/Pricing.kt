package uk.co.jwgardenservices.crm.domain

/** The parts of a client that decide what a visit is worth. */
data class BillingSource(
    val billingMode: String,
    val hourlyRate: Double,
    val fixedPrice: Double,
)

/**
 * Price a visit from the client's billing setup.
 *
 * Mirrors `priceVisit` in the web app: fixed-price clients are charged the fixed
 * price regardless of hours; everyone else is rate × hours. Materials are added
 * on top in both cases.
 */
fun priceVisit(client: BillingSource, hours: Double, materialsCost: Double): Double {
    val labour = if (client.billingMode == "fixed") {
        client.fixedPrice
    } else {
        client.hourlyRate * if (hours.isFinite()) hours else 0.0
    }
    val materials = if (materialsCost.isFinite()) materialsCost else 0.0
    return round2(labour + materials)
}
