package uk.co.jwgardenservices.crm.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The percentage of each net invoice to set aside for tax. Stored on this
 * device only — it is a personal rule of thumb, not business data, and tax is
 * charged on profit, so the knob absorbs the user's own running costs.
 */
object TaxReservePrefs {
    private const val PREFS = "app-tax"
    private const val KEY_RATE = "tax_reserve_percent"
    const val DEFAULT_RATE = 30.0

    private val rateState = MutableStateFlow(DEFAULT_RATE)

    val ratePercent: StateFlow<Double> = rateState.asStateFlow()

    fun load(context: Context) {
        rateState.value = read(context)
    }

    fun read(context: Context): Double =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_RATE, DEFAULT_RATE.toFloat()).toDouble()

    fun set(context: Context, value: Double) {
        val clamped = value.coerceIn(0.0, 90.0)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_RATE, clamped.toFloat()).apply()
        rateState.value = clamped
    }
}
