// Picking the account the bank feed watches.
//
// The fixture is the shape a real Monzo login returns: personal current account
// first, a rewards pot second, the business account last. Taking accounts[0]
// looked reasonable until that order turned up, which is why this is tested
// rather than assumed. Ids and numbers here are made up.
import { expect, test } from "bun:test";
import { chooseAccount, type MonzoAccount } from "./monzo.ts";

const PERSONAL: MonzoAccount = {
  id: "acc_personal",
  description: "user_000example",
  type: "uk_retail",
  owner_type: "personal",
  closed: false,
};

const REWARDS: MonzoAccount = {
  id: "acc_rewards",
  description: "rewardsoptin_000example",
  type: "uk_rewards",
  owner_type: "personal",
  closed: false,
};

const BUSINESS: MonzoAccount = {
  id: "acc_business",
  description: "A Gardening Business",
  type: "uk_business",
  owner_type: "business",
  closed: false,
};

test("the business account wins, even when it is listed last", () => {
  expect(chooseAccount([PERSONAL, REWARDS, BUSINESS])?.id).toBe("acc_business");
});

test("with no business account, the personal current account is next best", () => {
  expect(chooseAccount([REWARDS, PERSONAL])?.id).toBe("acc_personal");
});

test("a rewards pot is never chosen over something money can be paid into", () => {
  expect(chooseAccount([REWARDS, BUSINESS])?.id).toBe("acc_business");
  expect(chooseAccount([REWARDS, PERSONAL])?.id).toBe("acc_personal");
});

test("a rewards pot is still better than nothing when it is all there is", () => {
  expect(chooseAccount([REWARDS])?.id).toBe("acc_rewards");
});

test("closed accounts are not candidates", () => {
  const shut = { ...BUSINESS, closed: true };
  expect(chooseAccount([shut, PERSONAL])?.id).toBe("acc_personal");
  expect(chooseAccount([shut])).toBeNull();
});

test("no accounts at all is null rather than a guess", () => {
  expect(chooseAccount([])).toBeNull();
});

test("an account Monzo describes only by owner_type still reads as business", () => {
  const sparse: MonzoAccount = { id: "acc_x", owner_type: "business" };
  expect(chooseAccount([PERSONAL, sparse])?.id).toBe("acc_x");
});
