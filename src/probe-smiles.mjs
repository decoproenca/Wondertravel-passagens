import { chromium } from "playwright";
import { mkdir, writeFile } from "node:fs/promises";

const search = {
  from: process.env.FROM ?? "GRU",
  to: process.env.TO ?? "BPS",
  departureDate: process.env.DEPARTURE_DATE ?? "2026-10-09",
  numAdults: Number(process.env.NUM_ADULTS ?? 2),
  numChildren: Number(process.env.NUM_CHILDREN ?? 2),
  numInfants: 0,
  cabin: "ALL",
};

if (search.numAdults + search.numChildren !== 4) {
  throw new Error("A prova deve consultar os quatro passageiros juntos.");
}

const query = new URLSearchParams(
  Object.entries(search).map(([key, value]) => [key, String(value)]),
);
const searchUrl = `https://www.smiles.com.br/passagens-aereas?${query}`;
const outputDir = "artifacts";
await mkdir(outputDir, { recursive: true });

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({
  locale: "pt-BR",
  timezoneId: "America/Sao_Paulo",
  viewport: { width: 1440, height: 1000 },
});

const candidateResponses = [];
page.on("response", async (response) => {
  const contentType = response.headers()["content-type"] ?? "";
  if (!contentType.includes("json")) return;

  try {
    const body = await response.json();
    const serialized = JSON.stringify(body);
    if (/mile|milha|fare|flight|offer/i.test(serialized)) {
      const parsedUrl = new URL(response.url());
      candidateResponses.push({
        status: response.status(),
        url: parsedUrl.origin + parsedUrl.pathname,
        body,
      });
    }
  } catch {
    // Algumas respostas declaram JSON, mas chegam vazias ou incompletas.
  }
});

let status = "unknown";
let error = null;
try {
  const response = await page.goto(searchUrl, {
    waitUntil: "domcontentloaded",
    timeout: 90_000,
  });
  await page.waitForTimeout(30_000);
  status = response?.status() === 200 ? "page_loaded" : "page_unexpected_status";
} catch (caught) {
  status = "navigation_failed";
  error = caught instanceof Error ? caught.message : String(caught);
}

const pageText = (await page.locator("body").innerText().catch(() => ""))
  .replace(/\s+/g, " ")
  .slice(0, 20_000);

await page.screenshot({
  path: `${outputDir}/smiles-probe.png`,
  fullPage: true,
}).catch(() => {});

const result = {
  checkedAt: new Date().toISOString(),
  status,
  error,
  search,
  finalUrl: page.url(),
  title: await page.title().catch(() => ""),
  pageText,
  candidateResponses,
  validation: {
    automatedPriceAlertEnabled: false,
    reason:
      "Uma execução real precisa confirmar preço por passageiro, tarifa Clube e quatro assentos antes de ativar alertas.",
  },
};

await writeFile(
  `${outputDir}/smiles-probe.json`,
  JSON.stringify(result, null, 2),
);
console.log(
  JSON.stringify({
    status,
    title: result.title,
    candidateResponses: candidateResponses.length,
  }),
);

await browser.close();

if (status === "navigation_failed") process.exitCode = 1;
