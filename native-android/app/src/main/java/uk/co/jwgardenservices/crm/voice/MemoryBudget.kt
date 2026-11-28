package uk.co.jwgardenservices.crm.voice

/**
 * The phone's memory is shared by the live recogniser, the voice, and several
 * optional models. These small rules keep the optional ones from crowding out
 * the essential ones, which is what ended calls with "low memory kill".
 */
object MemoryBudget {
    /** Always left free after a load, so the system does not reach for this app. */
    const val RESERVE_MB = 300L

    // Android's ComponentCallbacks2 trim levels while the app is in the foreground.
    private const val RUNNING_MODERATE = 5
    private const val RUNNING_LOW = 10
    private const val RUNNING_CRITICAL = 15

    /** True when a model needing [needMb] can load and still leave [reserveMb] free. */
    fun canLoad(freeMb: Long, needMb: Long, reserveMb: Long = RESERVE_MB): Boolean =
        freeMb >= needMb + reserveMb

    /** What to let go of, cumulatively, as the system asks for memory back. */
    enum class Shed { Nothing, SecondLook, Router, AllOptional }

    /** The live recogniser and the voice are never shed; everything optional goes in this order. */
    fun shedFor(trimLevel: Int): Shed = when {
        trimLevel == RUNNING_CRITICAL -> Shed.AllOptional
        trimLevel == RUNNING_LOW -> Shed.Router
        trimLevel == RUNNING_MODERATE -> Shed.SecondLook
        else -> Shed.Nothing
    }
}

/**
 * Watches one turn from the moment the owner stops talking: if no reply has
 * started speaking, say what is going on, and eventually give up out loud
 * rather than leaving the line quiet.
 */
object TurnWatch {
    const val STATUS_AT_MS = 4_000L
    const val GIVE_UP_AT_MS = 15_000L

    enum class Step { Wait, Status, GiveUp }

    fun next(elapsedMs: Long, replyStarted: Boolean, statusSpoken: Boolean): Step = when {
        replyStarted -> Step.Wait
        elapsedMs >= GIVE_UP_AT_MS -> Step.GiveUp
        elapsedMs >= STATUS_AT_MS && !statusSpoken -> Step.Status
        else -> Step.Wait
    }
}
