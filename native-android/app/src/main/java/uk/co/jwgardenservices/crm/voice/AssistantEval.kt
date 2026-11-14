package uk.co.jwgardenservices.crm.voice

/**
 * A small, fixed evaluation set for the assistant's understanding.
 *
 * Thirty-odd commands a gardener would actually say, with the intent the app
 * should act on. The Settings screen runs them through the same classifier a
 * call uses; the unit test runs them through [IntentRules]. That turns "can we
 * make it smarter?" into a number: how many phrases it understands today, and
 * which ones it misses.
 *
 * The last four cases are deliberate paraphrases that the keyword rules do
 * not catch. They are there to show what the model buys, not to fail the
 * build.
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

    /**
     * Run every case through [classifier]. [onProgress] is called with
     * (done, total) after each phrase so the UI can show a moving counter.
     */
    suspend fun run(
        classifier: suspend (String) -> QwenIntentEngine.Intent,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<Result> {
        val results = ArrayList<Result>(CASES.size)
        CASES.forEachIndexed { index, case ->
            val started = System.currentTimeMillis()
            val intent = classifier(case.phrase)
            results += Result(
                case = case,
                actual = intent.intent,
                actualCloud = intent.requiresCloud,
                millis = System.currentTimeMillis() - started,
            )
            onProgress(index + 1, CASES.size)
        }
        return results
    }
}
