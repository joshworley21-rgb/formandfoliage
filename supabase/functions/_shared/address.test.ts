// These test the mapping from each provider's payload to the shape the app
// consumes. They do NOT test that the providers actually return these payloads
// — that can only be established against the live services, which is why the
// first build of this needs checking on a real phone.
//
// The samples below are the documented response shapes for each endpoint.
import { expect, test } from "bun:test";
import {
  formatAddress,
  getAddressDetailToAddress,
  getAddressToSuggestions,
  photonToSuggestions,
} from "./address.ts";

test("formatAddress writes one thing per line and drops the blanks", () => {
  expect(formatAddress(["53 Example Road", "", null, "Tring", undefined, "HP23 4PE"])).toBe(
    "53 Example Road\nTring\nHP23 4PE",
  );
});

test("formatAddress does not repeat a part that appears twice", () => {
  // Photon fills district and city with the same name for a small village.
  expect(formatAddress(["1 The Lane", "Wilstone", "wilstone", "HP23 4PE"])).toBe(
    "1 The Lane\nWilstone\nHP23 4PE",
  );
});

test("photon: a house number and street become the first line", () => {
  const suggestions = photonToSuggestions({
    features: [
      {
        properties: {
          countrycode: "GB",
          housenumber: "53",
          street: "Example Road",
          district: "Wilstone",
          city: "Tring",
          county: "Hertfordshire",
          postcode: "HP23 4PE",
        },
      },
    ],
  });

  expect(suggestions).toEqual([
    {
      id: null,
      label: "53 Example Road, Wilstone, Tring, Hertfordshire, HP23 4PE",
      address: "53 Example Road\nWilstone\nTring\nHertfordshire\nHP23 4PE",
    },
  ]);
});

test("photon: a street with no house number still comes through", () => {
  const [first] = photonToSuggestions({
    features: [{ properties: { countrycode: "GB", street: "Example Road", city: "Tring" } }],
  });
  expect(first?.address).toBe("Example Road\nTring");
});

test("photon: anything outside the UK is dropped", () => {
  const suggestions = photonToSuggestions({
    features: [
      { properties: { countrycode: "FR", street: "Rue Example", city: "Paris" } },
      { properties: { countrycode: "GB", street: "Example Road", city: "Tring" } },
    ],
  });
  expect(suggestions).toHaveLength(1);
  expect(suggestions[0]?.address).toContain("Tring");
});

test("photon: a hit with neither street nor name is not a doorstep", () => {
  expect(
    photonToSuggestions({
      features: [{ properties: { countrycode: "GB", county: "Hertfordshire", state: "England" } }],
    }),
  ).toEqual([]);
});

test("photon: the same address twice is listed once", () => {
  const props = { countrycode: "GB", street: "Example Road", city: "Tring" };
  expect(
    photonToSuggestions({ features: [{ properties: props }, { properties: props }] }),
  ).toHaveLength(1);
});

test("photon: junk does not throw", () => {
  expect(photonToSuggestions(null)).toEqual([]);
  expect(photonToSuggestions({})).toEqual([]);
  expect(photonToSuggestions({ features: "nope" })).toEqual([]);
  expect(photonToSuggestions({ features: [null, 7, {}] })).toEqual([]);
});

test("getAddress: suggestions carry an id to resolve, not an address", () => {
  const suggestions = getAddressToSuggestions({
    suggestions: [
      {
        address: "53 Example Road, Wilstone, Tring, Hertfordshire, HP23 4PE",
        id: "abc123",
        url: "/get/abc123",
      },
    ],
  });

  expect(suggestions).toEqual([
    {
      id: "abc123",
      label: "53 Example Road, Wilstone, Tring, Hertfordshire, HP23 4PE",
      address: null,
    },
  ]);
});

test("getAddress: the id is recovered from the url when the field is absent", () => {
  const [first] = getAddressToSuggestions({
    suggestions: [{ address: "1 The Lane, Tring", url: "/get/xyz789" }],
  });
  expect(first?.id).toBe("xyz789");
});

test("getAddress: junk does not throw", () => {
  expect(getAddressToSuggestions(null)).toEqual([]);
  expect(getAddressToSuggestions({ suggestions: [{ id: "no-label" }] })).toEqual([]);
});

test("getAddress: a resolved record becomes an envelope, with the postcode last", () => {
  expect(
    getAddressDetailToAddress({
      formatted_address: ["53 Example Road", "Wilstone", "", "Tring", "Hertfordshire"],
      postcode: "HP23 4PE",
    }),
  ).toBe("53 Example Road\nWilstone\nTring\nHertfordshire\nHP23 4PE");
});

test("getAddress: falls back to the numbered lines when formatted_address is missing", () => {
  expect(
    getAddressDetailToAddress({
      line_1: "53 Example Road",
      line_2: "Wilstone",
      line_3: "",
      line_4: "",
      town_or_city: "Tring",
      county: "Hertfordshire",
      postcode: "HP23 4PE",
    }),
  ).toBe("53 Example Road\nWilstone\nTring\nHertfordshire\nHP23 4PE");
});

test("getAddress: the searched postcode is used when the record omits it", () => {
  expect(getAddressDetailToAddress({ formatted_address: ["1 The Lane"] }, "HP23 4PE")).toBe(
    "1 The Lane\nHP23 4PE",
  );
});
