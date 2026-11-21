// Acceptance spec for spiculedata/saiku#2462: the Docker image moves onto Chainguard's
// digest-pinned JRE with no shell in the final stage. The container behaviour itself (boot,
// healthcheck, PNG render) is exercised by hand and by the launcher unit tests; this spec
// pins the structure of the image definition and the CI wiring so it cannot silently regress.
// Red on base (Temurin + bash entrypoint), green on the change.

import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => (existsSync(join(root, p)) ? readFileSync(join(root, p), 'utf8') : '');
const failures = [];
const claim = (id, what, holds) => {
  if (!holds) failures.push(`${id}: ${what}`);
};

const stripComments = (s) =>
  s
    .split('\n')
    .filter((l) => !/^\s*#/.test(l))
    .join('\n');

const dockerfile = stripComments(read('Dockerfile'));
const stages = [...dockerfile.matchAll(/^\s*FROM\s+(\S+)(?:\s+AS\s+(\S+))?/gim)].map((m) => ({ from: m[1], alias: m[2] }));
const froms = stages.filter((st) => !stages.some((o) => o.alias === st.from)).map((st) => st.from); // images, not stage aliases
// The final stage may name an earlier stage (`FROM jre`); resolve it to the image behind it.
const lastFrom = stages[stages.length - 1]?.from ?? '';
const finalFrom = stages.find((st) => st.alias === lastFrom)?.from ?? lastFrom;

// C1
claim('C1', 'final stage is the concepttocloud.com jre:openjdk-21', /^cgr\.dev\/concepttocloud\.com\/jre:openjdk-21@sha256:[0-9a-f]{64}$/.test(finalFrom));
claim('C1', 'every FROM is pinned by sha256 digest', froms.length > 0 && froms.every((f) => /@sha256:[0-9a-f]{64}$/.test(f)));

// C2
claim('C2', 'docker/saiku-entrypoint is gone', !existsSync(join(root, 'docker', 'saiku-entrypoint')));
const lastStage = dockerfile.slice(dockerfile.lastIndexOf('FROM '));
claim('C2', 'ENTRYPOINT is exec-form java ... -jar /app/saiku.jar', /^\s*ENTRYPOINT\s+\["[^"]*java"[^\]]*"-jar",\s*"\/app\/saiku\.jar"\]/m.test(lastStage));
claim('C2', 'CMD is ["serve"]', /^\s*CMD\s+\["serve"\]/m.test(lastStage));
claim('C2', 'final stage never uses a shell-form RUN', !/^\s*RUN\s/m.test(lastStage));
claim('C2', 'HEALTHCHECK is exec-form and does not use curl', /^\s*HEALTHCHECK\b[^\n]*\n?[^\n]*CMD\s+\[/m.test(lastStage) && !/curl/.test(lastStage));
claim('C2', 'uid/gid 10001 kept', /10001/.test(lastStage) && /USER\s+10001/.test(lastStage));
claim('C2', 'SAIKU_HOME, JAVA_TOOL_OPTIONS and EXPOSE 8080 kept', /ENV\s+SAIKU_HOME=/.test(lastStage) && /ENV\s+JAVA_TOOL_OPTIONS=/.test(lastStage) && /EXPOSE\s+8080/.test(lastStage));

// C3 / C4
const launcherDir = 'saiku-launcher/src/main/java/org/saiku/launcher';
const preflight = read(`${launcherDir}/ContainerPreflight.java`);
claim('C3', 'ContainerPreflight exists', preflight.includes('class ContainerPreflight'));
claim('C3', 'serve calls the pre-flight', /ContainerPreflight\./.test(read(`${launcherDir}/SaikuLauncher.java`)));
claim('C3', 'ContainerPreflightTest ships', existsSync(join(root, 'saiku-launcher/src/test/java/org/saiku/launcher/ContainerPreflightTest.java')));
claim('C4', 'HealthcheckCommand exists and is registered', read(`${launcherDir}/HealthcheckCommand.java`).includes('name = "healthcheck"') && /HealthcheckCommand\.class/.test(read(`${launcherDir}/SaikuLauncher.java`)));
claim('C4', 'HealthcheckCommandTest ships', existsSync(join(root, 'saiku-launcher/src/test/java/org/saiku/launcher/HealthcheckCommandTest.java')));
claim('C4', 'HEALTHCHECK invokes the healthcheck subcommand', /HEALTHCHECK[\s\S]*CMD\s+\[[^\]]*"healthcheck"/.test(lastStage));

// C5
for (const wf of ['docker.yml', 'release.yml']) {
  const y = read(`.github/workflows/${wf}`);
  const login = y.search(/registry:\s*cgr\.dev/);
  const build = y.search(/docker\/build-push-action@/);
  claim('C5', `${wf} logs in to cgr.dev before building`, login >= 0 && build > login);
  claim('C5', `${wf} uses CHAINGUARD_IDENTITY_ID and CHAINGUARD_TOKEN`, y.includes('secrets.CHAINGUARD_IDENTITY_ID') && y.includes('secrets.CHAINGUARD_TOKEN'));
}
claim('C5', 'docker.yml skips the image build when the Chainguard secrets are absent', /HAVE_CHAINGUARD|chainguard\.outputs\.available/.test(read('.github/workflows/docker.yml')));

// C6
const evalDoc = read('docs/docker-chainguard-evaluation.md');
claim('C6', 'evaluation doc records the adoption', /adopted/i.test(evalDoc) && /30[- ]day/i.test(evalDoc));

if (failures.length) {
  console.error(`acceptance/2462: ${failures.length} claim(s) do not hold:`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log('acceptance/2462: all claims hold');
