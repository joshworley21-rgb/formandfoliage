import { expect, mock, test } from "bun:test";
import { mailtoUrl, openReply, outlookUrl, replyToEnquiry } from "./compose-email";

mock.module("@/lib/native", () => ({ isNativeApp: () => nativeNow }));
let nativeNow = true;

test("mailto carries the address, subject and body", () => {
  const url = mailtoUrl({ to: "a@b.co.uk", subject: "Your garden", body: "Hi there" });
  expect(url).toStartWith("mailto:a%40b.co.uk?");
  expect(url).toContain("subject=Your+garden");
  expect(url).toContain("body=Hi+there");
});

test("mailto with nothing but an address has no trailing question mark", () => {
  expect(mailtoUrl({ to: "a@b.co.uk" })).toBe("mailto:a%40b.co.uk");
});

test("a plus in an address survives, because it is a real address character", () => {
  // URLSearchParams would turn this into a space and misdeliver the reply.
  expect(mailtoUrl({ to: "josh+garden@b.co.uk" })).toBe("mailto:josh%2Bgarden%40b.co.uk");
});

test("the Outlook link puts the address in a parameter, not the path", () => {
  const url = outlookUrl({ to: "a@b.co.uk", subject: "Your garden" });
  expect(url).toStartWith("ms-outlook://compose?");
  expect(url).toContain("to=a%40b.co.uk");
  expect(url).toContain("subject=Your+garden");
});

test("the reply opens with their first name and signs off as the business", () => {
  const body = replyToEnquiry("Sarah Cooper", "JW Garden Services");
  expect(body).toStartWith("Hi Sarah,");
  expect(body).toContain("JW Garden Services");
});

test("no business name means no dangling sign-off", () => {
  expect(replyToEnquiry("Sarah", null)).not.toContain("Many thanks");
});

test("an empty name still greets somebody", () => {
  expect(replyToEnquiry("   ", null)).toStartWith("Hi there,");
});

// --------------------------------------------------------------- routing ---

function launcher({ has, opens = true }: { has: boolean; opens?: boolean }) {
  const opened: string[] = [];
  return {
    opened,
    api: {
      canOpenUrl: async () => ({ value: has }),
      openUrl: async ({ url }: { url: string }) => {
        opened.push(url);
        return { completed: opens };
      },
    },
  };
}

const compose = { to: "a@b.co.uk", subject: "Your garden" };

/** Records where the fallback sent the browser, instead of navigating. */
function recorder() {
  const went: string[] = [];
  return { went, go: (url: string) => void went.push(url) };
}

test("Outlook installed: the reply opens there", async () => {
  nativeNow = true;
  const l = launcher({ has: true });
  const nav = recorder();
  expect(await openReply(compose, async () => l.api, nav.go)).toBe("outlook");
  expect(l.opened[0]).toStartWith("ms-outlook://compose?");
  expect(nav.went).toHaveLength(0);
});

test("Outlook not installed: falls back rather than doing nothing", async () => {
  nativeNow = true;
  const l = launcher({ has: false });
  const nav = recorder();
  expect(await openReply(compose, async () => l.api, nav.go)).toBe("mailto");
  expect(l.opened).toHaveLength(0);
  expect(nav.went[0]).toStartWith("mailto:");
});

test("Outlook there but refuses to open: still falls back", async () => {
  nativeNow = true;
  const l = launcher({ has: true, opens: false });
  const nav = recorder();
  expect(await openReply(compose, async () => l.api, nav.go)).toBe("mailto");
  expect(nav.went[0]).toStartWith("mailto:");
});

test("the plugin itself failing is not a dead button", async () => {
  nativeNow = true;
  const boom = async () => {
    throw new Error("plugin missing");
  };
  const nav = recorder();
  expect(await openReply(compose, boom, nav.go)).toBe("mailto");
  expect(nav.went[0]).toStartWith("mailto:");
});

test("on the website Outlook is never asked about", async () => {
  nativeNow = false;
  const l = launcher({ has: true });
  const nav = recorder();
  expect(await openReply(compose, async () => l.api, nav.go)).toBe("mailto");
  expect(l.opened).toHaveLength(0);
});
