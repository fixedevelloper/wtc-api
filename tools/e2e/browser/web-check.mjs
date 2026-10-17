// Parcours du back-office dans un vrai navigateur (Chrome installé sur la machine) : connexion avec double authentification,
// puis chaque écran, en relevant les erreurs de la console et les appels d'API en échec. Captures dans .local/e2e/shots/.
//   Prérequis : tools/e2e/start.sh, tools/e2e/smoke.py (données), tools/e2e/web.sh
import { chromium } from 'playwright'
import { execFileSync } from 'node:child_process'
import { mkdirSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '../../..')
const shots = resolve(root, '.local/e2e/shots')
mkdirSync(shots, { recursive: true })
const base = process.env.E2E_WEB_URL ?? 'http://localhost:3000'
const state = JSON.parse(readFileSync(resolve(root, '.local/e2e/state.json'), 'utf8'))
const secret = readFileSync(resolve(root, '.local/e2e/admin.secret'), 'utf8').trim()
const totp = () => execFileSync('python3', [resolve(root, 'tools/e2e/totp.py'), secret]).toString().trim()

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })

const problems = []
let current = 'connexion'
page.on('console', (m) => {
  if (m.type() === 'error' && !/favicon|Download the React DevTools|hydrat|Failed to load resource/i.test(m.text())) problems.push(`[${current}] console : ${m.text().slice(0, 200)}`)
})
page.on('pageerror', (e) => problems.push(`[${current}] exception : ${e.message.slice(0, 200)}`))
page.on('response', async (r) => {
  const u = r.url()
  // Attendus : le 401 du premier appel de connexion (le code de double authentification est alors demandé) et les recharges
  // (routes /top-ups pas encore implémentées côté API : phase 7, documentation Pay-In de Wace attendue).
  const expected = u.includes('/api/auth/callback/credentials') || u.includes('/api/auth/session') || u.includes('/api/admin/top-ups')
  if (u.includes('/api/') && r.status() >= 400 && !expected) {
    problems.push(`[${current}] ${r.request().method()} ${u.replace(/^https?:\/\/[^/]+/, '')} -> ${r.status()}`)
  }
})

const results = []
const step = async (name, fn) => {
  current = name
  try {
    await fn()
    results.push([true, name])
  } catch (e) {
    results.push([false, `${name} : ${String(e.message).split('\n')[0]}`])
    await page.screenshot({ path: `${shots}/ECHEC-${name.replace(/\W+/g, '-')}.png` }).catch(() => {})
  }
}
const shot = (n) => page.screenshot({ path: `${shots}/${n}.png`, fullPage: true })
const visit = async (path, expectText, name) =>
  step(name, async () => {
    await page.goto(base + path, { waitUntil: 'networkidle' })
    await page.getByText(expectText, { exact: false }).first().waitFor({ timeout: 15000 })
    await shot(name.replace(/\W+/g, '-'))
  })

// -------------------------------------------------------------- connexion avec double authentification
await step('connexion : mot de passe', async () => {
  await page.goto(base + '/auth/login', { waitUntil: 'networkidle' })
  await page.fill('#email', state.admin[0])
  await page.fill('#password', state.admin[1])
  await page.getByRole('button', { name: /se connecter/i }).click()
  await page.getByText('code à 6 chiffres', { exact: false }).first().waitFor({ timeout: 15000 })
})
await step('connexion : code de la double authentification', async () => {
  // un code déjà utilisé dans la fenêtre de 30 s est refusé : on attend une fenêtre neuve
  await page.waitForTimeout((30 - (Date.now() / 1000) % 30 + 1) * 1000)
  await page.fill('#code', totp())
  await page.getByRole('button', { name: /valider/i }).click()
  await page.waitForURL(/dashboard/, { timeout: 20000 })
  await shot('00-tableau-de-bord')
})

// -------------------------------------------------------------- écrans
await visit('/transactions', 'WTC-2026-', 'transferts : liste')
await step('transferts : détail', async () => {
  await page.goto(base + '/transactions', { waitUntil: 'networkidle' })
  await page.locator('tbody a[href^="/transactions/"]').first().click()
  await page.getByText('Historique', { exact: false }).first().waitFor({ timeout: 15000 })
  await page.getByText('Payé', { exact: false }).first().waitFor({ timeout: 5000 })
  await shot('transferts-detail')
})
await visit('/customers', 'Awa', 'clients : liste')
await step('clients : détail, identité et portefeuille', async () => {
  await page.goto(`${base}/customers/${state.customerId}`, { waitUntil: 'networkidle' })
  await page.getByText('Pièces d\'identité', { exact: false }).first().waitFor({ timeout: 15000 })
  await page.getByText('XAF', { exact: false }).first().waitFor({ timeout: 5000 })
  await shot('clients-detail')
})
await visit('/senders', 'Ndiaye', 'expéditeurs : liste')
await visit('/beneficiaries', 'Okoko', 'bénéficiaires : liste')
await step('bénéficiaire : fiche (numéro masqué)', async () => {
  await page.goto(base + '/beneficiaries', { waitUntil: 'networkidle' })
  await page.getByText('Détails et modification', { exact: false }).first().click().catch(() => {})
  await page.locator('tbody tr').first().locator('button, .dropdown-toggle').first().click()
  await page.getByRole('link', { name: /détails et modification/i }).first().click()
  await page.getByText('Destinations de paiement', { exact: false }).first().waitFor({ timeout: 15000 })
  const html = await page.content()
  if (html.includes('998877')) throw new Error('numéro de pièce en clair !')
  await shot('beneficiaire-fiche')
})
await visit('/adjustments', 'Dotation essai', 'ajustements de solde')
await visit('/recharges', 'Recharges', 'recharges')
await visit('/grilletarifaires', 'Grille tarifaire', 'barème de frais')
await visit('/zones', 'Zones tarifaires', 'zones')
await visit('/countries', 'République du Congo', 'pays')
await visit('/countries/banks/CG', 'Banque Congolaise', 'pays : banques')
await visit('/manage-token', 'Jetons web', 'jetons web')
await visit('/employes', 'support@wtc.local', 'personnel')
await visit('/audits', 'TRANSFER', "journal d'audit")
await visit('/connexions', 'SUCCESS', 'connexions')
await step('saisie d’un transfert pour un client', async () => {
  await page.goto(base + '/transactions/add-transaction', { waitUntil: 'networkidle' })
  await page.getByPlaceholder(/Rechercher un client/i).fill('awa')
  await page.getByText('Awa', { exact: false }).first().click()
  const selects = page.locator('select')
  await selects.nth(0).selectOption({ index: 1 }) // expéditeur
  await selects.nth(1).selectOption({ index: 1 }) // bénéficiaire
  await page.waitForTimeout(800)
  await selects.nth(2).selectOption({ index: 1 }) // destination
  await page.getByPlaceholder('10000').fill('10000')
  await page.getByText('Total', { exact: false }).first().waitFor({ timeout: 15000 }) // devis du serveur affiché
  await selects.nth(3).selectOption({ index: 1 })
  await selects.nth(4).selectOption({ index: 1 })
  await selects.nth(5).selectOption({ index: 1 })
  await shot('saisie-transfert')
  await page.getByRole('button', { name: /envoyer le transfert/i }).click()
  await page.waitForURL(/transactions\/[0-9a-f-]{36}/, { timeout: 20000 })
  await shot('saisie-transfert-resultat')
})
await step('déconnexion', async () => {
  await page.goto(base + '/auth/logout', { waitUntil: 'networkidle' })
})

await browser.close()
console.log('\nParcours du back-office :')
for (const [ok, name] of results) console.log(`  ${ok ? '✔' : '✘'} ${name}`)
if (problems.length) {
  console.log('\nProblèmes relevés (console / API) :')
  for (const p of [...new Set(problems)]) console.log('  - ' + p)
}
console.log(`\nCaptures : ${shots}`)
process.exit(results.some(([ok]) => !ok) || problems.length ? 1 : 0)
