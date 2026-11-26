package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules are the local floor: they answer the common commands instantly
 * and catch malformed or missing cloud classification. This corpus is the
 * guardrail for keyword changes, so a regression is visible rather than a
 * hunch.
 *
 * The last three are deliberate paraphrases the rules miss; the threshold
 * leaves room for them while still failing if the rules get worse.
 */
class IntentRulesTest {

    private data class Case(
        val phrase: String,
        val expected: SpokenIntent,
        val cloud: Boolean? = null,
    )

    private val cases = listOf(
        Case("logged three hours at the Smiths yesterday", SpokenIntent.LogHours),
        Case("four hours for Mrs Jones", SpokenIntent.LogHours),
        Case("put two and a half hours on the Johnsons", SpokenIntent.LogHours),
        Case("I worked six hours at the Browns", SpokenIntent.LogHours),
        Case("how many hours did I do at the Smiths", SpokenIntent.LogHours),

        Case("what's on today", SpokenIntent.CheckSchedule),
        Case("read me my diary", SpokenIntent.CheckSchedule),
        Case("have I got anything booked this afternoon", SpokenIntent.CheckSchedule),
        Case("what have I got on next week", SpokenIntent.CheckSchedule),

        Case("what's next", SpokenIntent.NextJob),
        Case("who's my next job", SpokenIntent.NextJob),
        Case("where's my next job", SpokenIntent.NextJobAddress),

        Case("finished the Jones job", SpokenIntent.MarkJobDone),
        Case("mark the Smiths job done", SpokenIntent.MarkJobDone),
        Case("I've completed the Browns", SpokenIntent.MarkJobDone),
        // A question or a surname must never mutate the diary.
        Case("did you mark off Louisa", SpokenIntent.Other),
        Case("have you finished the Smiths", SpokenIntent.Other),
        Case("Billy Markoff-Lewis are a full day today", SpokenIntent.Other),

        Case("who owes me money", SpokenIntent.CheckDebtors),
        Case("how much is outstanding", SpokenIntent.CheckDebtors),
        Case("any unpaid invoices", SpokenIntent.CheckDebtors),
        Case("how much does the Smiths owe me", SpokenIntent.CheckDebtors),

        Case("what have I earned this week", SpokenIntent.WeeklyEarnings),
        Case("how much have I taken this week", SpokenIntent.WeeklyEarnings),
        Case("how much did I earn last month", SpokenIntent.MonthEarnings),
        Case("how many hours have I worked this week", SpokenIntent.HoursThisWeek),

        Case("what's the Smiths' phone number", SpokenIntent.ClientInfo),
        Case("when did I last visit the Smiths", SpokenIntent.LastVisit),
        Case("who's my best client", SpokenIntent.BestClient),

        Case("note for the Smiths that the gate sticks", SpokenIntent.AddNote),
        Case("make a note about the Browns' dog", SpokenIntent.AddNote),
        Case("what's ready to invoice", SpokenIntent.Unbilled),
        Case("who are my clients", SpokenIntent.ClientList),
        Case("has the Smiths' invoice been paid", SpokenIntent.InvoiceStatus),
        Case("raise an invoice for the Smiths", SpokenIntent.RaiseInvoice),
        Case("draft invoice for Simon", SpokenIntent.RaiseInvoice),
        Case("add £40 for green waste", SpokenIntent.AdjustCharge),
        Case("change the rate to £45 an hour", SpokenIntent.AdjustCharge),
        Case("stick £30 of materials onto the Smiths", SpokenIntent.AddExpense),
        Case("log £25 materials for the pub", SpokenIntent.AddExpense),
        Case("remind me to get compost for the next visit to the Smiths", SpokenIntent.NextVisitNote),
        Case("what's for the Smiths' next visit", SpokenIntent.NextVisitNote),
        Case("clear the next visit note for the Smiths", SpokenIntent.NextVisitNote),
        Case("say again", SpokenIntent.SayAgain),
        Case("repeat that", SpokenIntent.SayAgain),
        Case("what was that", SpokenIntent.SayAgain),
        Case("when is the Smiths next in", SpokenIntent.NextJobForClient),
        Case("what have I taken today", SpokenIntent.TodayEarnings),

        Case("end of day wrap up", SpokenIntent.DayWrapUp),
        Case("run through today's jobs", SpokenIntent.DayWrapUp),
        Case("do my timesheets", SpokenIntent.DayWrapUp),
        Case("What, can you do", SpokenIntent.Help),
        Case("What could you help me with", SpokenIntent.Help),
        Case("How can you help", SpokenIntent.Help),

        Case("do you cover Tring", SpokenIntent.BusinessInfo),
        Case("what do you do", SpokenIntent.BusinessInfo),
        Case("what services do you offer", SpokenIntent.BusinessInfo),
        Case("are you insured", SpokenIntent.BusinessInfo),
        Case("how much are my running costs", SpokenIntent.RunningCosts),
        Case("what's my biggest cost", SpokenIntent.RunningCosts),
        Case("what's my profit this month", SpokenIntent.Profit),
        Case("what's my break-even", SpokenIntent.BreakEven),
        Case("who's my least profitable client", SpokenIntent.LeastProfitable),
        Case("what should I chase first", SpokenIntent.ChaseFirst),
        Case("how's business", SpokenIntent.Health),

        Case("how much for forty square metres of turf", SpokenIntent.Quote, cloud = true),
        Case("price up a hedge trim for Mrs Jones", SpokenIntent.Quote, cloud = true),
        Case("what's the weather tomorrow", SpokenIntent.Other, cloud = true),
        Case("tell me a joke", SpokenIntent.Other),

        // Deliberate misses.
        Case("clock me off at the Browns", SpokenIntent.MarkJobDone),
        Case("what's the damage on the Smith account", SpokenIntent.CheckDebtors),
        Case("how are we doing this week", SpokenIntent.WeeklyEarnings),
    )

    @Test
    fun `rules understand the common commands`() {
        val results = cases.map { case -> case to IntentRules.classify(case.phrase) }
        val failures = results.filter { (case, result) ->
            result.intent != case.expected ||
                (case.cloud != null && result.requiresCloud != case.cloud)
        }
        failures.forEach { (case, result) ->
            println(
                "MISS \"${case.phrase}\" wanted ${case.expected.wire} " +
                    "got ${result.intent.wire} cloud=${result.requiresCloud}",
            )
        }
        val passed = results.size - failures.size
        assertTrue(
            "Rules understood $passed of ${results.size}; see the misses above.",
            passed >= (results.size * 80) / 100,
        )
    }

    @Test
    fun `adding hours to a visit logs hours, adding money adjusts the charge`() {
        assertTrue(IntentRules.classify("can you add 8 hours to louisa and george's visit today").intent == SpokenIntent.LogHours)
        assertTrue(IntentRules.classify("add 2.5 hrs for the smiths").intent == SpokenIntent.LogHours)
        assertTrue(IntentRules.classify("add £40 for green waste").intent == SpokenIntent.AdjustCharge)
        assertTrue(IntentRules.classify("add 40 for green waste").intent == SpokenIntent.AdjustCharge)
        assertTrue(IntentRules.classify("change the rate to £45 an hour").intent == SpokenIntent.AdjustCharge)
    }
}
