// Credential hand-off for a preview environment.
// Ported from spiculedata/saiku-cloud .github/scripts/preview-creds.mjs
// (spiculedata/saiku-cloud#1381).
//
// A validation job (a trusted base-branch workflow with tailnet + preview SSH
// access) fetches the PR's credentials with
//
//   preview-ctl.mjs creds --pr <n> --host ssh --out <file> [--github-output]
//
// Rules this module enforces, each with a test:
//   * Only the CREDENTIAL_KEYS allow-list is ever read off the host
//     (cmds.readCreds greps for exactly those), and every value is validated
//     against a strict character set, so a value can never smuggle a newline
//     into $GITHUB_OUTPUT or a second key into the dotenv file.
//   * `::add-mask::` is emitted for every secret BEFORE anything is written.
//   * Secrets go only to the --out file (0600) and/or $GITHUB_OUTPUT. They are
//     never printed, never put in the step summary, never in an error message.
//
// Why a file / step output and not a job output: GitHub drops a job output whose
// value contains a registered secret and masks it in logs; artifacts are readable
// by anyone with repo read access. So the consumer must be a LATER STEP IN THE
// SAME JOB, reading the 0600 file or `steps.<id>.outputs.*`.

import { appendFileSync, chmodSync, writeFileSync } from 'node:fs';

import { CREDENTIAL_KEYS } from './preview-guard.mjs';

/** Keys whose values are secrets; everything else is routing information. */
export const SECRET_KEYS = ['SAIKU_ADMIN_PASSWORD'];

/** env-file key -> name the consumer sees. */
const OUTPUT_NAMES = {
  ORIGIN: 'PREVIEW_BASE_URL',
  PREVIEW_ADMIN_USER: 'PREVIEW_ADMIN_USER',
  SAIKU_ADMIN_PASSWORD: 'PREVIEW_ADMIN_PASSWORD',
};

const VALUE_RE = /^[A-Za-z0-9][A-Za-z0-9_.@:/-]{0,255}$/;

/**
 * Parse the grep output (KEY=VALUE lines) into a validated map.
 * Errors name the KEY, never the value.
 */
export function parseCredentials(text) {
  const found = {};
  for (const line of String(text).split('\n')) {
    if (!line.trim()) continue;
    const at = line.indexOf('=');
    const key = at === -1 ? '' : line.slice(0, at);
    if (!CREDENTIAL_KEYS.includes(key)) throw new Error('unexpected line in the credential output');
    const value = line.slice(at + 1).trim();
    if (!VALUE_RE.test(value)) throw new Error(`${key} has an unexpected shape`);
    found[key] = value;
  }
  const missing = CREDENTIAL_KEYS.filter((k) => !found[k]);
  if (missing.length) throw new Error(`the env file lacks: ${missing.join(', ')}`);
  return found;
}

/** The consumer-facing record. */
export function toOutputs(found) {
  const outputs = {};
  for (const [key, name] of Object.entries(OUTPUT_NAMES)) outputs[name] = found[key];
  return outputs;
}

export const maskCommands = (found) => SECRET_KEYS.map((k) => `::add-mask::${found[k]}`);

const dotenv = (outputs) => `${Object.entries(outputs).map(([k, v]) => `${k}=${v}`).join('\n')}\n`;

/**
 * Mask first, then write. `io.out` receives only the add-mask commands.
 * `fs` is injectable so a test can prove the ordering.
 */
export function deliverCredentials(
  found,
  { outFile, githubOutputFile, io, fs = { writeFileSync, appendFileSync, chmodSync } },
) {
  if (!outFile && !githubOutputFile) {
    throw new Error('creds needs --out <file> and/or --github-output; it never prints credentials');
  }
  for (const line of maskCommands(found)) io.out(`${line}\n`);
  const outputs = toOutputs(found);
  if (outFile) {
    fs.writeFileSync(outFile, dotenv(outputs), { mode: 0o600 });
    fs.chmodSync(outFile, 0o600);
  }
  if (githubOutputFile) fs.appendFileSync(githubOutputFile, dotenv(outputs));
  return { names: Object.keys(outputs), wroteFile: Boolean(outFile), wroteGithubOutput: Boolean(githubOutputFile) };
}

/** Defence in depth for anything logged from a host: mask password-shaped strings. */
export const redactSecrets = (text) =>
  String(text)
    .replace(/prevpw_[0-9a-f]+/g, '***')
    .replace(/(SAIKU_ADMIN_PASSWORD=)\S+/g, '$1***');
