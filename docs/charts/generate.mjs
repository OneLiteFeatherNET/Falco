// Generates the SVG charts used by the README.
//
// Run: node docs/charts/generate.mjs
//
// Every number in DATA below is a measurement, copied from STATUS.md together with the benchmark
// that produced it. Nothing here computes or estimates a figure — if a measurement changes, edit it
// in one place and regenerate, so the chart and the document cannot drift apart.
//
// Two files are written per chart, one per colour scheme, because a README image is embedded as
// <img> and CSS inside it does not react to the reader's theme. The <picture> element in the README
// picks between them. The palette is the reference categorical order, slots 1 and 2, validated for
// both surfaces with the dataviz validator: contrast >= 3:1, CVD separation dE 24.7 light / 26.8
// dark against a 8.0 target.

import { writeFileSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));

const THEMES = {
  light: {
    suffix: "light",
    surface: "#fcfcfb",
    textPrimary: "#1a1a19",
    textSecondary: "#5c5b55",
    grid: "#e4e3df",
    series: ["#2a78d6", "#eb6834"],
  },
  dark: {
    suffix: "dark",
    surface: "#0d1117",
    textPrimary: "#f0f6fc",
    textSecondary: "#9198a1",
    grid: "#272c35",
    series: ["#3987e5", "#d95926"],
  },
};

const FONT = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif";

const esc = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

// A measured 62.0 arrives here as the number 62 and would print as "62" next to "44.5", claiming a
// precision the measurement does not have. Each chart therefore states how many decimals its source
// table carries, and every value is printed with exactly that many.
const fmt = (v, decimals) => v.toFixed(decimals);

/**
 * Horizontal grouped bars. Horizontal because the category labels are sentences, and grouped
 * because the whole point is one pair per scenario. Values are labelled directly at the end of
 * each bar, so the chart needs no value axis and no gridline behind every bar.
 */
function groupedBars({ title, subtitle, series, categories, rows, footnote, decimals }, theme) {
  const padL = 150;
  const padR = 96;
  const padT = subtitle ? 78 : 60;
  const padB = footnote ? 44 : 20;
  const barH = 17;
  const barGap = 2;          // the 2px surface gap between adjacent bars
  const groupGap = 18;
  const groupH = barH * series.length + barGap * (series.length - 1);
  const plotW = 470;
  const width = padL + plotW + padR;
  const height = padT + categories.length * (groupH + groupGap) - groupGap + padB;

  const max = Math.max(...rows.flat());
  const scale = (v) => (v / max) * plotW;

  const parts = [];
  parts.push(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" font-family="${FONT}">`
  );
  parts.push(`<rect width="${width}" height="${height}" fill="${theme.surface}"/>`);
  parts.push(
    `<text x="20" y="27" font-size="14" font-weight="600" fill="${theme.textPrimary}">${esc(title)}</text>`
  );
  if (subtitle) {
    parts.push(
      `<text x="20" y="46" font-size="11.5" fill="${theme.textSecondary}">${esc(subtitle)}</text>`
    );
  }

  // Legend: always present for two or more series, so identity is never carried by colour alone.
  const legendY = subtitle ? 64 : 46;
  let lx = 20;
  series.forEach((name, i) => {
    parts.push(`<rect x="${lx}" y="${legendY - 8}" width="9" height="9" rx="2" fill="${theme.series[i]}"/>`);
    parts.push(
      `<text x="${lx + 14}" y="${legendY}" font-size="11.5" fill="${theme.textSecondary}">${esc(name)}</text>`
    );
    lx += 14 + name.length * 6.6 + 18;
  });

  categories.forEach((cat, ci) => {
    const gy = padT + ci * (groupH + groupGap);
    parts.push(
      `<text x="${padL - 12}" y="${gy + groupH / 2 + 4}" font-size="11.5" text-anchor="end" fill="${theme.textPrimary}">${esc(cat)}</text>`
    );

    rows[ci].forEach((value, si) => {
      const y = gy + si * (barH + barGap);
      const w = Math.max(scale(value), 2);
      parts.push(
        `<rect x="${padL}" y="${y}" width="${w.toFixed(1)}" height="${barH}" rx="4" fill="${theme.series[si]}"/>`
      );
      parts.push(
        `<text x="${(padL + w + 8).toFixed(1)}" y="${y + barH - 4}" font-size="11" fill="${theme.textSecondary}">${esc(fmt(value, decimals))}</text>`
      );
    });
  });

  if (footnote) {
    parts.push(
      `<text x="20" y="${height - 16}" font-size="10.5" fill="${theme.textSecondary}">${esc(footnote)}</text>`
    );
  }
  parts.push("</svg>");
  return parts.join("\n");
}

/**
 * One stacked bar showing what a whole operation is made of, split by whether a lock is held.
 * The job of this chart is a single proportion, so it is one bar and not four.
 */
function stackedShare({ title, subtitle, segments, footnote }, theme) {
  const width = 716;
  const padL = 20;
  const plotW = width - 40;
  const barY = 96;
  const barH = 40;
  const height = 232;
  const total = segments.reduce((sum, s) => sum + s.value, 0);

  const parts = [];
  parts.push(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" font-family="${FONT}">`
  );
  parts.push(`<rect width="${width}" height="${height}" fill="${theme.surface}"/>`);
  parts.push(
    `<text x="${padL}" y="27" font-size="14" font-weight="600" fill="${theme.textPrimary}">${esc(title)}</text>`
  );
  parts.push(
    `<text x="${padL}" y="46" font-size="11.5" fill="${theme.textSecondary}">${esc(subtitle)}</text>`
  );

  const legend = [
    { name: "no lock held", colour: theme.series[0] },
    { name: "a lock held", colour: theme.series[1] },
  ];
  let lx = padL;
  legend.forEach((entry) => {
    parts.push(`<rect x="${lx}" y="${64 - 8}" width="9" height="9" rx="2" fill="${entry.colour}"/>`);
    parts.push(
      `<text x="${lx + 14}" y="64" font-size="11.5" fill="${theme.textSecondary}">${esc(entry.name)}</text>`
    );
    lx += 14 + entry.name.length * 6.6 + 18;
  });

  let x = padL;
  segments.forEach((segment) => {
    const w = (segment.value / total) * plotW;
    const colour = segment.locked ? theme.series[1] : theme.series[0];
    // The 2px gap between segments is drawn by insetting the fill, not by a stroke, so the
    // proportions stay exact.
    parts.push(
      `<rect x="${x.toFixed(1)}" y="${barY}" width="${Math.max(w - 2, 1).toFixed(1)}" height="${barH}" rx="4" fill="${colour}"/>`
    );

    const share = ((segment.value / total) * 100).toFixed(w / plotW < 0.05 ? 1 : 0);
    // Labels for slivers go below the bar with a leader, so they never overlap the neighbour.
    if (w > 84) {
      parts.push(
        `<text x="${(x + w / 2).toFixed(1)}" y="${barY + 25}" font-size="11.5" font-weight="600" text-anchor="middle" fill="${theme.surface}">${esc(segment.name)}</text>`
      );
      parts.push(
        `<text x="${(x + w / 2).toFixed(1)}" y="${barY + barH + 18}" font-size="11" text-anchor="middle" fill="${theme.textSecondary}">${esc(segment.value)} µs · ${share} %</text>`
      );
    } else {
      // A sliver at either end would have its centred label run off the canvas, so the label is
      // anchored to the side it sits on while the leader line stays on the segment itself.
      const anchorX = x + w / 2;
      const nearLeft = anchorX < 60;
      const nearRight = anchorX > width - 60;
      const textX = nearLeft ? padL : nearRight ? width - padL : anchorX;
      const anchor = nearLeft ? "start" : nearRight ? "end" : "middle";
      parts.push(
        `<line x1="${anchorX.toFixed(1)}" y1="${barY + barH}" x2="${anchorX.toFixed(1)}" y2="${barY + barH + 12}" stroke="${theme.grid}" stroke-width="2"/>`
      );
      parts.push(
        `<text x="${textX.toFixed(1)}" y="${barY + barH + 26}" font-size="11" text-anchor="${anchor}" fill="${theme.textSecondary}">${esc(segment.name)}</text>`
      );
      parts.push(
        `<text x="${textX.toFixed(1)}" y="${barY + barH + 40}" font-size="11" text-anchor="${anchor}" fill="${theme.textSecondary}">${esc(segment.value)} µs · ${share} %</text>`
      );
    }
    x += w;
  });

  parts.push(
    `<text x="${padL}" y="${height - 18}" font-size="10.5" fill="${theme.textSecondary}">${esc(footnote)}</text>`
  );
  parts.push("</svg>");
  return parts.join("\n");
}

/**
 * Ratios around a baseline of 1.0. A ratio is the only honest form here: the absolute times differ
 * by an order of magnitude across the thread counts, so a shared linear axis would flatten the
 * single-threaded pair into two indistinguishable slivers and hide that Falco loses that one.
 * Bars grow right when Falco is ahead and left when it is behind, which is what the two hues say.
 */
function ratioBars({ title, subtitle, rows, footnote }, theme) {
  const padL = 100;
  const padR = 118;
  const padT = 78;
  const padB = 58;
  const rowH = 30;
  const gap = 14;
  const plotW = 470;
  const width = padL + plotW + padR;
  const height = padT + rows.length * (rowH + gap) - gap + padB;

  const maxRatio = Math.max(...rows.map((r) => r.ratio));
  // The gap left of the baseline has to fit a whole "1.14x slower" label, not just the bar, or it
  // collides with the row label on its left.
  const leftRoom = 112;
  const zeroX = padL + leftRoom;
  const scale = (plotW - leftRoom) / (maxRatio - 1);

  const parts = [];
  parts.push(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" font-family="${FONT}">`
  );
  parts.push(`<rect width="${width}" height="${height}" fill="${theme.surface}"/>`);
  parts.push(
    `<text x="20" y="27" font-size="14" font-weight="600" fill="${theme.textPrimary}">${esc(title)}</text>`
  );
  parts.push(
    `<text x="20" y="46" font-size="11.5" fill="${theme.textSecondary}">${esc(subtitle)}</text>`
  );

  const legend = [
    { name: "Falco faster", colour: theme.series[0] },
    { name: "Minestom faster", colour: theme.series[1] },
  ];
  let lx = 20;
  legend.forEach((entry) => {
    parts.push(`<rect x="${lx}" y="56" width="9" height="9" rx="2" fill="${entry.colour}"/>`);
    parts.push(
      `<text x="${lx + 14}" y="64" font-size="11.5" fill="${theme.textSecondary}">${esc(entry.name)}</text>`
    );
    lx += 14 + entry.name.length * 6.6 + 18;
  });

  const plotTop = padT - 10;
  const plotBottom = height - padB + 4;
  parts.push(
    `<line x1="${zeroX}" y1="${plotTop}" x2="${zeroX}" y2="${plotBottom}" stroke="${theme.grid}" stroke-width="2"/>`
  );
  parts.push(
    `<text x="${zeroX}" y="${plotBottom + 16}" font-size="10.5" text-anchor="middle" fill="${theme.textSecondary}">1.0× — level</text>`
  );

  rows.forEach((row, i) => {
    const y = padT + i * (rowH + gap);
    parts.push(
      `<text x="${padL - 12}" y="${y + rowH / 2 + 4}" font-size="11.5" text-anchor="end" fill="${theme.textPrimary}">${esc(row.label)}</text>`
    );

    const faster = row.ratio >= 1;
    const w = faster ? (row.ratio - 1) * scale : (1 / row.ratio - 1) * scale;
    const x = faster ? zeroX : zeroX - Math.max(w, 2);
    parts.push(
      `<rect x="${x.toFixed(1)}" y="${y}" width="${Math.max(w, 2).toFixed(1)}" height="${rowH}" rx="4" fill="${faster ? theme.series[0] : theme.series[1]}"/>`
    );

    const labelX = faster ? x + Math.max(w, 2) + 8 : x - 8;
    const anchor = faster ? "start" : "end";
    const text = faster
      ? `${row.ratio.toFixed(2)}× faster`
      : `${(1 / row.ratio).toFixed(2)}× slower`;
    parts.push(
      `<text x="${labelX.toFixed(1)}" y="${y + rowH / 2 + 4}" font-size="11.5" font-weight="600" text-anchor="${anchor}" fill="${theme.textPrimary}">${esc(text)}</text>`
    );
  });

  parts.push(
    `<text x="20" y="${height - 14}" font-size="10.5" fill="${theme.textSecondary}">${esc(footnote)}</text>`
  );
  parts.push("</svg>");
  return parts.join("\n");
}

const CHARTS = {
  "light-engine": (theme) =>
    groupedBars(
      {
        title: "Light engine against the one Minestom ships with",
        subtitle: "LightEngineComparisonBenchmark, one section, µs/op — shorter is better",
        series: ["Falco", "Minestom"],
        categories: [
          "1 source, 0 % solid",
          "1 source, 30 % solid",
          "8 sources, 0 % solid",
          "8 sources, 30 % solid",
          "64 sources, 0 % solid",
          "64 sources, 30 % solid",
        ],
        rows: [
          [44.5, 49.4],
          [39.3, 62.0],
          [98.3, 121.1],
          [119.3, 204.2],
          [109.2, 126.5],
          [122.6, 206.6],
        ],
        decimals: 1,
        footnote: "Both engines produce byte-identical output; a test asserts that on every build.",
      },
      theme
    ),

  "light-engine-mixed": (theme) =>
    groupedBars(
      {
        title: "Light engine with sources of mixed brightness",
        subtitle: "Sources cycle through glowstone, lantern, torch, redstone torch and magma block, µs/op",
        series: ["Falco", "Minestom"],
        categories: [
          "8 sources, 0 % solid",
          "8 sources, 30 % solid",
          "64 sources, 0 % solid",
          "64 sources, 30 % solid",
        ],
        rows: [
          [118.97, 126.54],
          [116.5, 201.46],
          [150.42, 162.2],
          [149.42, 252.26],
        ],
        decimals: 2,
        footnote: "Mixed brightness costs Falco about 33 % against uniform sources — the honest case, and it still leads.",
      },
      theme
    ),

  "loader-contention": (theme) =>
    ratioBars(
      {
        title: "Reading a chunk, as threads are added",
        subtitle: "RegionFileComparisonBenchmark, 200 distinct block states, Minestom time ÷ Falco time",
        rows: [
          { label: "1 thread", ratio: 1060.0 / 1203.3 },
          { label: "2 threads", ratio: 2200.4 / 1181.0 },
          { label: "4 threads", ratio: 11020.8 / 1377.6 },
        ],
        footnote:
          "Single-threaded Falco is behind. The lock granularity only pays once threads compete — which is the case a server is in.",
      },
      theme
    ),

  "save-stages": (theme) =>
    stackedShare(
      {
        title: "Where the time goes when saving a chunk",
        subtitle: "ChunkSaveStageBenchmark, 24 sections, 200 block states — 4 138 µs in total",
        segments: [
          { name: "Snapshot", value: 64, locked: true },
          { name: "Codec", value: 1356, locked: false },
          { name: "zlib compression", value: 2701, locked: false },
          { name: "Transfer", value: 17, locked: true },
        ],
        footnote:
          "About 97 % of a save runs outside any lock. That is the whole design claim, as a number.",
      },
      theme
    ),
};

mkdirSync(HERE, { recursive: true });

for (const [name, build] of Object.entries(CHARTS)) {
  for (const theme of Object.values(THEMES)) {
    const file = `${HERE}/${name}-${theme.suffix}.svg`;
    writeFileSync(file, build(theme) + "\n");
    console.log(`wrote ${file}`);
  }
}
