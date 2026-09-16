import { chromium } from "playwright";
import { mkdir, writeFile } from "node:fs/promises";

const search = {
  from: process.env.FROM ?? "GRU",
  to: process.env.TO ?? "BPS",
  departureDate: process.env.DEPARTURE_DATE ?? "2026-10-09",
  returnDate: process.env.RETURN_DATE ?? "2026-10-17",
  numAdults: 2,
  numChildren: 2,
};

const airportNames = {
  GRU: "Guarulhos",
  CGH: "Congonhas",
  VCP: "Viracopos",
  BPS: "Porto Seguro",
};

const dateLabels = {
  "2026-10-09": /Choose sexta-feira, 9 de outubro de 2026/,
  "2026-10-17": /Choose sábado, 17 de outubro de 2026/,
};

if (!airportNames[search.from] || !airportNames[search.to]) {
  throw new Error("Aeroporto não configurado para esta prova.");
}
if (!dateLabels[search.departureDate] || !dateLabels[search.returnDate]) {
  throw new Error("Data não configurada para esta prova.");
}

const outputDir = "artifacts";
await mkdir(outputDir, { recursive: true });

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({
  locale: "pt-BR",
  timezoneId: "America/Sao_Paulo",
  viewport: { width: 1440, height: 1000 },
});

const candidateResponses = [];
const relevantRequests = [];
const failedRequests = [];
page.on("request", (request) => {
  const url = request.url();
  if (/flight|availability|fare|offer|graphql|search/i.test(url)) {
    const parsedUrl = new URL(url);
    const safeUrl = parsedUrl.hostname === "api-air-flightsearch-blue.smiles.com.br"
      ? url
      : parsedUrl.origin + parsedUrl.pathname;
    relevantRequests.push({
      method: request.method(),
      url: safeUrl,
    });
  }
});
page.on("requestfailed", (request) => {
  const url = request.url();
  if (/flight|availability|fare|offer|graphql|search/i.test(url)) {
    const parsedUrl = new URL(url);
    failedRequests.push({
      method: request.method(),
      url: parsedUrl.origin + parsedUrl.pathname,
      failure: request.failure()?.errorText ?? "unknown",
    });
  }
});
page.on("response", async (response) => {
  const url = response.url();
  if (!/flight|availability|fare|offer/i.test(url)) return;
  const parsedUrl = new URL(url);
  const record = {
    status: response.status(),
    url: parsedUrl.origin + parsedUrl.pathname,
  };
  try {
    const body = await response.json();
    const serialized = JSON.stringify(body);
    record.body = serialized.length <= 250_000
      ? body
      : { truncated: true, size: serialized.length };
  } catch {
    record.body = null;
  }
  candidateResponses.push(record);
});

async function selectAirport(fieldName, code) {
  const field = page.getByRole("textbox", { name: fieldName });
  await field.fill(airportNames[code]);
  const option = page.getByRole("button", {
    name: new RegExp(`${airportNames[code]}.*${code}`, "i"),
  });
  await option.waitFor({ state: "visible", timeout: 15_000 });
  await option.click();
}

let status = "unknown";
let error = null;

try {
  await page.goto("https://www.smiles.com.br/portal/passagens", {
    waitUntil: "domcontentloaded",
    timeout: 90_000,
  });

  const rejectCookies = page.getByRole("button", { name: "Rejeitar todos" });
  if (await rejectCookies.isVisible().catch(() => false)) {
    await rejectCookies.click();
  }

  await selectAirport("Origem", search.from);
  await selectAirport("Destino", search.to);

  await page.getByRole("button", { name: /1 pessoa adulta/ }).click();
  await page.locator("#btn_addAdultPerson").click();
  await page.locator("#btn_addChildren").click();
  await page.locator("#btn_addChildren").click();
  await page.locator("#btn_confirmPassagers").click();

  await page.getByRole("textbox", { name: "Ida" }).click();
  await page.getByRole("button", {
    name: dateLabels[search.departureDate],
  }).click();
  await page.getByRole("button", {
    name: dateLabels[search.returnDate],
  }).click();
  await page.getByRole("button", { name: "Confirmar", exact: true }).click();

  await Promise.all([
    page.waitForURL(/mfe\/emissao-passagem/, { timeout: 90_000 }),
    page.getByRole("button", { name: "Buscar voos" }).click(),
  ]);

  await page.waitForTimeout(75_000);
  const bodyText = await page.locator("body").innerText();
  status = /Aguarde enquanto buscamos os melhores voos/i.test(bodyText)
    ? "results_not_loaded"
    : "results_page_loaded";
} catch (caught) {
  status = "interaction_failed";
  error = caught instanceof Error ? caught.message : String(caught);
}

const pageText = (await page.locator("body").innerText().catch(() => ""))
  .replace(/\s+/g, " ")
  .slice(0, 30_000);

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
  relevantRequests,
  failedRequests,
  candidateResponses,
  validation: {
    automatedPriceAlertEnabled: false,
    reason:
      status === "results_page_loaded"
        ? "As tarifas ainda precisam ser validadas antes dos alertas."
        : "A página de resultados não entregou tarifas utilizáveis.",
  },
};

await writeFile(
  `${outputDir}/smiles-probe.json`,
  JSON.stringify(result, null, 2),
);

console.log(JSON.stringify({
  status,
  finalUrl: result.finalUrl,
  relevantRequests: relevantRequests.length,
  failedRequests: failedRequests.length,
  candidateResponses: candidateResponses.length,
}));

await browser.close();

if (status !== "results_page_loaded") process.exitCode = 1;
