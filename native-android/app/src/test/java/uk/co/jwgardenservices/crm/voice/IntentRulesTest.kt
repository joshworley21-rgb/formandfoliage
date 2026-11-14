package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deterministic rules are the floor: they answer every command when no
 * GGUF is installed, and they catch malformed model output. This scores them
 * against the same corpus the Settings screen uses, so a keyword change is a
 * visible improvement or regression rather than a hunch.
 *
 * The four paraphrases at the end of [AssistantEval.CASES] are expected to
 * miss here; the threshold leaves room for them while still failing if the
 * rules get worse.
 */
class IntentRulesTest {

    @Test
    fun `rules understand the common commands`() {
        val results = AssistantEval.CASES.map { case ->
            case to IntentRules.classify(case.phrase)
        }
        val failures = results.filter { (case, intent) ->
            intent.intent != case.expected ||
                (case.cloud != null && intent.requiresCloud != case.cloud)
        }
        failures.forEach { (case, intent) ->
            println(
                "MISS \"${case.phrase}\" wanted ${case.expected.wire} " +
                    "got ${intent.intent.wire} cloud=${intent.requiresCloud}",
            )
        }
        val passed = results.size - failures.size
        assertTrue(
            "Rules understood $passed of ${results.size}; see the misses above.",
            passed >= (results.size * 80) / 100,
        )
    }
}
