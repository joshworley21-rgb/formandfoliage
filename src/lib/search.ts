// Filtering a list by what someone typed into a box.
//
// Deliberately forgiving, because the alternative is a list that says "nothing
// found" about a client who is plainly there. Case is ignored, accents are
// ignored, punctuation and spacing are ignored, and each word is matched
// separately — so "smith hp22" finds Mrs Smith at HP22 7DG however her record
// happens to be punctuated, and typing a postcode with or without its space
// works either way.

/**
 * Lower case, accents stripped, and everything that is not a letter or number
 * collapsed to a single space. "HP22 7DG" and "hp227dg" both end up close
 * enough to match, and "O'Brien" survives being typed as "obrien".
 */
function normalise(value: string): string {
  return (
    value
      .toLowerCase()
      .normalize("NFD")
      // The combining accent marks NFD just split off, written as escapes rather
      // than as the characters themselves — invisible marks in source are the
      // kind of thing an editor eats without anyone noticing.
      .replace(/[\u0300-\u036f]/g, "")
      .replace(/[^a-z0-9]+/g, " ")
      .trim()
  );
}

/** The words to look for. Empty when the box is empty or only punctuation. */
export function searchTerms(query: string): string[] {
  const cleaned = normalise(query);
  return cleaned ? cleaned.split(" ") : [];
}

/**
 * True when every word typed appears somewhere in the record.
 *
 * Every word rather than any word: adding a word should narrow the list, which
 * is what people expect when the first attempt returns too much. The fields are
 * joined before matching, so "smith hp22" matches a name and a postcode
 * together without either field having to contain both.
 *
 * A term also matches with the spaces removed, so a postcode typed as "hp227dg"
 * still finds "HP22 7DG".
 */
export function matches(fields: Array<string | null | undefined>, terms: string[]): boolean {
  if (terms.length === 0) return true;
  const spaced = normalise(fields.filter(Boolean).join(" "));
  const squashed = spaced.replace(/ /g, "");
  return terms.every((term) => spaced.includes(term) || squashed.includes(term.replace(/ /g, "")));
}

/** Convenience for the common case of one query against one list. */
export function filterBy<T>(
  items: T[],
  query: string,
  fieldsOf: (item: T) => Array<string | null | undefined>,
): T[] {
  const terms = searchTerms(query);
  if (terms.length === 0) return items;
  return items.filter((item) => matches(fieldsOf(item), terms));
}
