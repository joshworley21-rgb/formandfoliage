package uk.co.jwgardenservices.crm.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A small, fixed evaluation set for the assistant's understanding.
 *
 * Runs through the same classifier a call uses, either the 12-phrase quick set
 * or all 35, and keeps its progress in an application-scoped coroutine so
 * leaving Settings (or switching apps briefly) does not throw the run away.
 * The unit test scores [IntentRules] against the same corpus.
 *
 * The last four cases are deliberate paraphrases that the keyword rules do
 * not catch. They are there to show what the model buys, not to fail a build.
 */
object AssistantEval {

    data class Case(
        val phrase: String,
        val expected: QwenIntentEngine.Kind,
        /** null means "either answer is fine". */
        val cloud: Boolean? = null,
    )

    data class Result(
        val case: Case,
        val actual: QwenIntentEngine.Kind,
        val actualCloud: Boolean,
        val millis: Long,
    ) {
        val kindOk: Boolean get() = actual == case.expected
        val cloudOk: Boolean get() = case.cloud == null || actualCloud == case.cloud
        val passed: Boolean get() = kindOk && cloudOk
    }

    sealed interface State {
        data object Idle : State
        data class Running(val done: Int, val total: Int, val label: String) : State
        data class Done(val results: List<Result>) : State
        data class Failed(val message: String) : State
    }

    /** A short run for a quick check; covers every intent at least once. */
    val QUICK: List<Case> = listOf(
        Case("logged three hours at the Smiths yesterday", QwenIntentEngine.Kind.LogHours),
        Case("four hours for Mrs Jones", QwenIntentEngine.Kind.LogHours),
        Case("what's on today", QwenIntentEngine.Kind.CheckSchedule),
        Case("have I got anything booked this afternoon", QwenIntentEngine.Kind.CheckSchedule),
        Case("what's next", QwenIntentEngine.Kind.NextJob),
        Case("who's my next job", QwenIntentEngine.Kind.NextJob),
        Case("finished the Jones job", QwenIntentEngine.Kind.MarkJobDone),
        Case("mark the Smiths job done", QwenIntentEngine.Kind.MarkJobDone),
        Case("who owes me money", QwenIntentEngine.Kind.WhoOwesMe),
        Case("what have I earned this week", QwenIntentEngine.Kind.WeeklyEarnings),
        Case("how much for forty square metres of turf", QwenIntentEngine.Kind.Quote, cloud = true),
        Case("tell me a joke", QwenIntentEngine.Kind.Other),
    )

    val CASES: List<Case> = listOf(
        // Logging time — the most common spoken command.
        Case("logged three hours at the Smiths yesterday", QwenIntentEngine.Kind.LogHours),
        Case("four hours for Mrs Jones", QwenIntentEngine.Kind.LogHours),
        Case("put two and a half hours on the Johnsons", QwenIntentEngine.Kind.LogHours),
        Case("I worked six hours at the Browns", QwenIntentEngine.Kind.LogHours),
        Case("stick four hours down for the Johnsons", QwenIntentEngine.Kind.LogHours),
        Case("how many hours did I do at the Smiths", QwenIntentEngine.Kind.LogHours),

        // The diary.
        Case("what's on today", QwenIntentEngine.Kind.CheckSchedule),
        Case("read me my diary", QwenIntentEngine.Kind.CheckSchedule),
        Case("have I got anything booked this afternoon", QwenIntentEngine.Kind.CheckSchedule),
        Case("is anything booked tomorrow", QwenIntentEngine.Kind.CheckSchedule),

        // The next job.
        Case("what's next", QwenIntentEngine.Kind.NextJob),
        Case("who's my next job", QwenIntentEngine.Kind.NextJob),
        Case("when's my next appointment", QwenIntentEngine.Kind.NextJob),

        // Finishing a job.
        Case("finished the Jones job", QwenIntentEngine.Kind.MarkJobDone),
        Case("mark the Smiths job done", QwenIntentEngine.Kind.MarkJobDone),
        Case("I've completed the Browns", QwenIntentEngine.Kind.MarkJobDone),
        Case("I finished at the Joneses", QwenIntentEngine.Kind.MarkJobDone),

        // Money owed.
        Case("who owes me money", QwenIntentEngine.Kind.WhoOwesMe),
        Case("who still owes me", QwenIntentEngine.Kind.WhoOwesMe),
        Case("how much is outstanding", QwenIntentEngine.Kind.WhoOwesMe),
        Case("any unpaid invoices", QwenIntentEngine.Kind.WhoOwesMe),
        Case("how much do the Smiths owe me", QwenIntentEngine.Kind.WhoOwesMe),

        // What the week has made.
        Case("what have I earned this week", QwenIntentEngine.Kind.WeeklyEarnings),
        Case("what did I make this week", QwenIntentEngine.Kind.WeeklyEarnings),
        Case("how much have I taken this week", QwenIntentEngine.Kind.WeeklyEarnings),

        // Prices and quotes — these are allowed to go online.
        Case("how much for forty square metres of turf", QwenIntentEngine.Kind.Quote, cloud = true),
        Case("price up a hedge trim for Mrs Jones", QwenIntentEngine.Kind.Quote, cloud = true),
        Case("what's the going rate for bark", QwenIntentEngine.Kind.Quote, cloud = true),

        // Small talk and live data.
        Case("what's the weather tomorrow", QwenIntentEngine.Kind.Other, cloud = true),
        Case("tell me a joke", QwenIntentEngine.Kind.Other),
        Case("thanks", QwenIntentEngine.Kind.Other),

        // Paraphrases the rules miss on purpose.
        Case("clock me off at the Browns", QwenIntentEngine.Kind.MarkJobDone),
        Case("what's the damage on the Smith account", QwenIntentEngine.Kind.WhoOwesMe),
        Case("how are we doing this week", QwenIntentEngine.Kind.WeeklyEarnings),
        Case("who haven't I been paid by", QwenIntentEngine.Kind.WhoOwesMe),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    fun start(context: Context, full: Boolean) {
        if (job?.isActive == true) return
        val cases = if (full) CASES else QUICK
        job = scope.launch {
            val engine = QwenEngine.get(context)
            val snapshot = AssistantContext().snapshot(emptyList(), emptyList())
            _state.value = State.Running(0, cases.size, "Loading the model…")
            // Load once, outside the per-phrase timer.
            runCatching { engine.warmUp() }
            val results = ArrayList<Result>(cases.size)
            try {
                cases.forEachIndexed { index, case ->
                    if (!isActive) return@launch
                    _state.value = State.Running(index, cases.size, case.phrase)
                    val started = System.currentTimeMillis()
                    val intent = engine.classify(case.phrase, snapshot)
                    results += Result(
                        case = case,
                        actual = intent.intent,
                        actualCloud = intent.requiresCloud,
                        millis = System.currentTimeMillis() - started,
                    )
                    _state.value = State.Running(index + 1, cases.size, case.phrase)
                }
                _state.value = State.Done(results)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = State.Failed(error.message ?: "The check failed.")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = State.Idle
    }

    fun clear() {
        if (job?.isActive == true) return
        job = null
        _state.value = State.Idle
    }
}
