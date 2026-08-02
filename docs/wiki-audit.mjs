// Audits the Falco wiki for the two defect classes that keep surviving review.
//
// Run: node docs/wiki-audit.mjs ../Falco.wiki
//      node docs/wiki-audit.mjs ../Falco.wiki --about Project-Status
//      node docs/wiki-audit.mjs --self-test
//
// The wiki is a separate repository, so its path is an argument rather than a constant.
//
// **links** — dead pages and dead anchors. Mechanical, and a hard failure: exit code 1.
//
// **claims** — sentences that a change elsewhere can silently invalidate. These are NOT failures.
// The audit cannot tell a true count from a false one; it can only find the sentences that carry a
// count, a quantifier or a claim about another page, so that a person reads them. That is the whole
// point: three times now the wiki has been left with sentences that resolve perfectly and say
// something that stopped being true, and each time they were found by someone reading rather than
// by a tool, because nobody knew where they were.
//
// The intended use is `--about <Page>` immediately after splitting, renaming or emptying that page:
// it lists every sentence anywhere in the wiki that asserts something about it.
//
// Self-test: `--self-test` runs the checks against a fixture with known defects injected. A checker
// that has never been made to fail is worth nothing, and this one has caught its own bug once
// already — an early version silently skipped anchors inside absolute wiki URLs.

import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

const NUMBER_WORD = "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve";

// A sentence that counts things a later change can add to or remove from.
const COUNTED = new RegExp(
  `\\b(${NUMBER_WORD}|\\d+)\\s+(?:\\w+\\s+){0,2}` +
    "(repeats?|documents?|investigations?|pages?|classes|commits?|tables?|modules?|sections?|rows?|" +
    "benchmarks?|entries|forks?|defects?|races)\\b",
  "i",
);

// A sentence that claims exclusivity. True until someone adds a second one.
const ABSOLUTE =
  /\b(owns every|every measured|all (?:of )?the|the only|only one|nowhere else|no (?:\w+ ){0,2}exists|the whole of|none of the|always|never)\b/i;

// A sentence that dates itself: true when written, false a week later.
const PERISHABLE =
  /\b(most recent|currently|at present|as of|latest|so far|to date|up to now|today)\b/i;

// A link with no anchor whose sentence asserts what the target page holds.
const OWNERSHIP =
  /\b(owns?|holds?|carries|carry|lives? in|belongs? to|records?|is in|are in|sets? out|is the register|collects?)\b/i;

const read = (dir) =>
  Object.fromEntries(
    readdirSync(dir)
      .filter((f) => f.endsWith(".md"))
      .map((f) => [f.slice(0, -3), readFileSync(join(dir, f), "utf8")]),
  );

// GitHub's heading slug: strip inline code and links, lowercase, drop punctuation, spaces to dashes.
// Duplicate headings get -1, -2, which is why the suffix is tracked rather than assumed away.
const anchorsOf = (text) => {
  const seen = new Map();
  const out = new Set();
  let fenced = false;
  for (const line of text.split("\n")) {
    if (line.trimStart().startsWith("```")) { fenced = !fenced; continue; }
    if (fenced) continue;
    const m = /^#{1,6}\s+(.*)$/.exec(line);
    if (!m) continue;
    const plain = m[1].trim().replace(/\[([^\]]*)\]\([^)]*\)/g, "$1").replace(/[`*]/g, "").replace(/<[^>]+>/g, "");
    // Each remaining space becomes one dash — not each run of them. Dropping "—" or "/" from a
    // heading leaves two adjacent spaces, and GitHub emits two dashes there. Collapsing them was
    // this script's own first bug, and it reported two live anchors as dead.
    const base = plain.toLowerCase().replace(/[^\w\s-]/g, "").trim().replace(/\s/g, "-");
    const n = seen.get(base) ?? 0;
    seen.set(base, n + 1);
    out.add(n === 0 ? base : `${base}-${n}`);
  }
  return out;
};

const WIKI_URL = /^https:\/\/github\.com\/OneLiteFeatherNET\/Falco\/wiki\/([^#)]+)(?:#([^)]+))?$/;

// Every [text](target) outside code fences, with the line it sits on.
function* linksOf(text) {
  let fenced = false;
  const lines = text.split("\n");
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].trimStart().startsWith("```")) { fenced = !fenced; continue; }
    if (fenced) continue;
    for (const m of lines[i].matchAll(/\[([^\]]+)\]\(([^)\s]+)\)/g)) {
      yield { text: m[1], target: m[2], line: i + 1, source: lines[i] };
    }
  }
}

function checkLinks(pages) {
  const anchors = Object.fromEntries(Object.entries(pages).map(([p, t]) => [p, anchorsOf(t)]));
  const bad = [];
  for (const [page, text] of Object.entries(pages)) {
    for (const { target, line } of linksOf(text)) {
      let name, anchor;
      const url = WIKI_URL.exec(target);
      if (url) {
        [, name, anchor] = url;
      } else if (/^https?:/.test(target)) {
        continue; // external, not ours to verify
      } else {
        const hash = target.indexOf("#");
        name = hash === -1 ? target : target.slice(0, hash);
        anchor = hash === -1 ? "" : target.slice(hash + 1);
        name = name || page; // bare #anchor is a self-reference
        if (name.endsWith(".md")) { bad.push({ page, line, target, why: "wiki link carries .md" }); continue; }
        if (name.startsWith("/")) { bad.push({ page, line, target, why: "wiki link has a leading slash" }); continue; }
      }
      if (!(name in pages)) { bad.push({ page, line, target, why: `no page named ${name}` }); continue; }
      if (anchor && !anchors[name].has(anchor)) bad.push({ page, line, target, why: `no anchor #${anchor} on ${name}` });
    }
  }
  return bad;
}

// Split on sentence ends, but keep table rows and list items whole — a table cell is a claim too.
const sentencesOf = (text) => {
  const out = [];
  let fenced = false, comment = false;
  const lines = text.split("\n");
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.trimStart().startsWith("```")) { fenced = !fenced; continue; }
    if (fenced) continue;
    if (line.includes("<!--")) comment = true;
    const wasComment = comment;
    if (line.includes("-->")) comment = false;
    if (wasComment) continue;
    for (const s of line.split(/(?<=[.!?])\s+/)) {
      if (s.trim()) out.push({ text: s.trim(), line: i + 1 });
    }
  }
  return out;
};

function checkClaims(pages, about) {
  const hits = [];
  for (const [page, text] of Object.entries(pages)) {
    const linkLines = new Map();
    for (const { target, line } of linksOf(text)) {
      const url = WIKI_URL.exec(target);
      const name = url ? url[1] : target.split("#")[0];
      const anchored = url ? Boolean(url[2]) : target.includes("#");
      if (name && name in pages && !anchored) linkLines.set(line, name);
    }
    for (const { text: s, line } of sentencesOf(text)) {
      const kinds = [];
      const target = linkLines.get(line);
      if (about) {
        // Only sentences that name the page under review, linked or in prose.
        const names = s.includes(about) || s.includes(about.replace(/-/g, " "));
        if (!names) continue;
        kinds.push("about");
      }
      if (COUNTED.test(s)) kinds.push("count");
      if (ABSOLUTE.test(s)) kinds.push("absolute");
      if (PERISHABLE.test(s)) kinds.push("perishable");
      if (target && OWNERSHIP.test(s)) kinds.push(`asserts-about:${target}`);
      if (kinds.length && !(about && kinds.length === 1)) hits.push({ page, line, kinds, text: s });
    }
  }
  return hits;
}

const FIXTURE = {
  Home: "# Home\n\nFour repeats exist. See [Status](Status) which owns every table.\n",
  // The second heading is the double-dash case: dropping "—" and "/" leaves adjacent spaces.
  Status: "## Only section\n\nThe eleven most recent commits each name a pull request.\n" +
    "#### B3 — dissolve the `Foo` / `Bar` exclusivity\n\ntext\n",
  Broken: "# Broken\n\n[a](Nope) [b](Status.md) [c](/Status) [d](Status#ghost) [e](#absent)\n" +
    "[f](https://github.com/OneLiteFeatherNET/Falco/wiki/Status#ghost)\n[ok](Status#only-section)\n" +
    "[dd](Status#b3--dissolve-the-foo--bar-exclusivity)\n",
};

function selfTest() {
  const links = checkLinks(FIXTURE);
  const expect = ["no page named Nope", "carries .md", "leading slash", "no anchor #ghost on Status",
    "no anchor #absent on Broken", "no anchor #ghost on Status"];
  const missed = expect.filter((e) => !links.some((b) => b.why.includes(e.replace("carries .md", "wiki link carries .md"))));
  const falsePositive = links.some((b) => b.target === "Status#only-section" ||
    b.target === "Status#b3--dissolve-the-foo--bar-exclusivity");
  const claims = checkClaims(FIXTURE);
  const kinds = new Set(claims.flatMap((c) => c.kinds));
  const claimsMissed = ["count", "absolute", "perishable"].filter((k) => !kinds.has(k));
  const ownership = claims.some((c) => c.kinds.some((k) => k.startsWith("asserts-about:Status")));

  const problems = [];
  if (links.length !== 6) problems.push(`expected 6 dead links, found ${links.length}`);
  if (missed.length) problems.push(`missed: ${missed.join("; ")}`);
  if (falsePositive) problems.push("flagged a valid link");
  if (claimsMissed.length) problems.push(`claim kinds missed: ${claimsMissed.join(", ")}`);
  if (!ownership) problems.push("missed an ownership assertion about a linked page");

  if (problems.length) { console.error("SELF-TEST FAILED\n  " + problems.join("\n  ")); process.exit(1); }
  console.log("self-test passed: 6 injected link defects found, 2 valid links left alone,");
  console.log("all four claim kinds detected.");
}

const args = process.argv.slice(2);
if (args.includes("--self-test")) { selfTest(); process.exit(0); }

const dir = args.find((a) => !a.startsWith("--"));
if (!dir) { console.error("usage: node docs/wiki-audit.mjs <wiki-dir> [--about <Page>] [--claims]"); process.exit(2); }
const about = args.includes("--about") ? args[args.indexOf("--about") + 1] : null;
const pages = read(dir);

const dead = checkLinks(pages);
console.log(`${Object.keys(pages).length} pages, ${dead.length} dead links or anchors`);
for (const b of dead) console.log(`  ${b.page}.md:${b.line}  ${b.target}  — ${b.why}`);

if (about || args.includes("--claims")) {
  const hits = checkClaims(pages, about);
  const what = about ? `sentences asserting something about ${about}` : "sentences a change elsewhere can invalidate";
  console.log(`\n${hits.length} ${what}. These are candidates for review, not defects:`);
  for (const h of hits) console.log(`  ${h.page}.md:${h.line}  [${h.kinds.join(",")}]  ${h.text.slice(0, 110)}`);
}

process.exit(dead.length ? 1 : 0);
