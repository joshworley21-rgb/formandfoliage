package uk.co.jwgardenservices.crm.domain

import java.text.Normalizer

/*
 * Filtering a list by what someone typed into a box.
 *
 * Deliberately forgiving: case, accents, punctuation and spacing are ignored,
 * and each word is matched separately — so "smith hp22" finds Mrs Smith at
 * HP22 7DG however her record happens to be punctuated.
 */

private val combiningMarks = Regex("[\\u0300-\\u036f]")
private val nonAlphanumeric = Regex("[^a-z0-9]+")

fun normalise(value: String): String =
    Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(combiningMarks, "")
        .replace(nonAlphanumeric, " ")
        .trim()

/** The words to look for. Empty when the box is empty or only punctuation. */
fun searchTerms(query: String): List<String> {
    val cleaned = normalise(query)
    return if (cleaned.isEmpty()) emptyList() else cleaned.split(' ')
}

/**
 * True when every word typed appears somewhere in the record. Fields are joined
 * before matching, so "smith hp22" matches a name and a postcode together. A
 * term also matches with the spaces removed, so "hp227dg" finds "HP22 7DG".
 */
fun matches(fields: List<String?>, terms: List<String>): Boolean {
    if (terms.isEmpty()) return true
    val spaced = normalise(fields.filterNotNull().joinToString(" "))
    val squashed = spaced.replace(" ", "")
    return terms.all { term ->
        spaced.contains(term) || squashed.contains(term.replace(" ", ""))
    }
}

/** Convenience for the common case of one query against one list. */
fun <T> filterBy(items: List<T>, query: String, fieldsOf: (T) -> List<String?>): List<T> {
    val terms = searchTerms(query)
    if (terms.isEmpty()) return items
    return items.filter { matches(fieldsOf(it), terms) }
}
