import { test as base, expect, type Page, type Route } from '@playwright/test';
import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

// The ordinary mocked-browser suite keeps its current behavior. The opt-in
// coverage command records one raw V8 report per test so parallel workers do
// not race to update a shared file.
const test = base.extend({
	page: async ({ page }, use, testInfo) => {
		if (!process.env.SAIKU_COVERAGE) {
			await use(page);
			return;
		}
		await page.coverage.startJSCoverage({ resetOnNavigation: false });
		try {
			await use(page);
		} finally {
			const entries = await page.coverage.stopJSCoverage();
			const directory = join(process.cwd(), 'coverage', 'browser', 'raw');
			await mkdir(directory, { recursive: true });
			const id = createHash('sha256')
				.update(`${testInfo.testId}:${testInfo.retry}:${testInfo.workerIndex}`)
				.digest('hex');
			await writeFile(join(directory, `${id}.json`), JSON.stringify(entries));
		}
	}
});

export { test, expect, type Page, type Route };
