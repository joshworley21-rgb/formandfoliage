package uk.co.jwgardenservices.crm.voice

import kotlin.math.min

/**
 * Background level for the energy half of speech detection, updated once per
 * 100 ms frame.
 *
 * It drops quickly and, while no turn is open and the VAD hears no voice,
 * climbs in a few seconds, so a van engine or a road settles into the floor
 * instead of reading as someone still talking. Inside a turn it only creeps,
 * so a pause between words cannot raise it.
 */
object NoiseFloorTracker {
    const val INITIAL = 0.008f

    /** The floor never rises past this, so loud speech cannot be swallowed. */
    const val MAX_FLOOR = 0.06f

    private const val FALL = 0.2f
    private const val RISE_IDLE = 0.02f
    private const val RISE_IN_TURN = 0.001f

    fun next(floor: Float, frameRms: Float, vadVoiced: Boolean, inTurn: Boolean): Float = when {
        frameRms < floor -> floor * (1f - FALL) + frameRms * FALL
        !vadVoiced && !inTurn ->
            min(floor * (1f - RISE_IDLE) + min(frameRms, MAX_FLOOR) * RISE_IDLE, MAX_FLOOR)
        else -> floor * (1f - RISE_IN_TURN) + frameRms * RISE_IN_TURN
    }
}
