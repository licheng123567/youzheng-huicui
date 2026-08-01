import { test, expect } from './fixtures/test'
import { authJson, openCaseByAcctNo, switchRole } from './helpers'

test('@lifecycle import -> dispatch -> accept -> assign -> follow-up -> repay -> reconcile', async ({ page }) => {
  test.setTimeout(90_000)
  const runId = `E2E-${new Date().toISOString().replace(/\D/g, '').slice(0, 14)}-${Math.random().toString(36).slice(2, 7)}`
  const acctNo = `${runId}-ACCT`
  const phone = `139${String(Date.now()).slice(-8)}`

  await switchRole(page, 'SA')
  const projects = (await authJson(page, 'GET', '/v1/projects?page=1&size=200')) as any
  const project = projects.items.find((item: any) => item.name === '翠湖一期') ?? projects.items[0]
  expect(project).toBeTruthy()
  await authJson(page, 'POST', '/v1/batches/import', {
    projectId: String(project.id),
    commInRate: 0.3,
    rows: [
      {
        acctNo,
        ownerName: `合成业主-${runId}`,
        phone,
        room: `合成房号-${runId}`,
        dueCents: 100_000,
        arrearPeriod: '2026-01~2026-06',
      },
    ],
  })
  const imported = (await authJson(
    page,
    'GET',
    `/v1/cases?q=${encodeURIComponent(acctNo)}&page=1&size=20`,
  )) as any
  expect(imported.items).toHaveLength(1)
  const created = imported.items[0]
  const batch = (await authJson(page, 'GET', `/v1/batches/${created.batchId}`)) as any
  expect(batch.code).toBeTruthy()
  const providers = (await authJson(
    page,
    'GET',
    '/v1/orgs?type=PROVIDER&status=ACTIVE&page=1&size=50',
  )) as any
  const provider = providers.items.find((item: any) => item.name === '捷信催收')
  expect(provider).toBeTruthy()
  await authJson(page, 'PUT', `/v1/batches/${created.batchId}/commission-rates`, {
    commInRate: 0.3,
    payOutRate: 0.2,
  })
  await authJson(page, 'POST', `/v1/batches/${created.batchId}/dispatch`, {
    mode: 'WHOLE',
    providerId: String(provider.id),
    payOutRate: 0.2,
  })

  await switchRole(page, 'VL')
  await authJson(page, 'POST', `/v1/cases/${created.id}/accept`)
  const members = (await authJson(page, 'GET', '/v1/members?page=1&size=100')) as any
  const collector = members.items.find((item: any) => item.username === 'jx_co1')
  expect(collector).toBeTruthy()
  await authJson(page, 'POST', `/v1/cases/${created.id}/assign`, {
    collectorId: String(collector.id),
  })

  await switchRole(page, 'CO')
  await openCaseByAcctNo(page, acctNo)
  await page.getByRole('button', { name: '写跟进' }).click()
  const follow = page.getByRole('dialog').filter({ hasText: '写跟进记录' })
  await follow.getByPlaceholder(/记录本次跟进情况/).fill(runId)
  await follow.getByRole('button', { name: '提交', exact: true }).click()
  await expect(follow).toBeHidden()

  await switchRole(page, 'PC')
  await openCaseByAcctNo(page, acctNo)
  await page.locator('.dtabs .t').filter({ hasText: '沟通记录' }).click()
  await expect(page.getByText(runId).first()).toBeVisible()
  await page.getByRole('button', { name: '标线下回款' }).click()
  const repay = page.getByRole('dialog').filter({ hasText: '标线下回款' })
  await repay.locator('input[type="number"]').fill('12.34')
  await repay.locator('input[type="date"]').fill(new Date().toISOString().slice(0, 10))
  await repay.locator('select').selectOption('BANK_TRANSFER')
  await repay.getByPlaceholder(/凭证说明/).fill(runId)
  await repay.getByRole('button', { name: '提交', exact: true }).click()
  await expect(repay).toBeHidden()

  await switchRole(page, 'SA')
  const lines = (await authJson(
    page,
    'GET',
    `/v1/batches/${created.batchId}/repay-lines?page=1&size=100`,
  )) as any
  expect(
    lines.items.some(
      (item: any) => item.caseId === String(created.id) && item.amountCents === 1234,
    ),
  ).toBe(true)
  await page.goto('/settlement')
  const settlementRow = page.locator('table tbody tr').filter({ hasText: batch.code }).first()
  await expect(settlementRow).toContainText('¥12.34')
})
