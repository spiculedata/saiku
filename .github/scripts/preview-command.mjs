#!/usr/bin/env node
// `/preview` PR comment gate.
// Ported from spiculedata/saiku-cloud .github/scripts/preview-command.mjs
// (spiculedata/saiku-cloud#1379); no functional change.
//
// Runs in preview-command.yml from the DEFAULT branch. It decides whether an
// issue_comment is a collaborator asking for a preview and, if so, writes the
// minimal event preview-ctl consumes. It reads the comment body only to match
// the exact command, and builds the event from API data: a number, a validated
// login, label names, a 40-hex sha and two repo names. The PR title, branch and
// body never enter the event file.
//
//   preview-command.mjs --github-event payload.json --out event.json
//   (env: GITHUB_REPOSITORY, GITHUB_OUTPUT, GH_TOKEN)

import { spawnSync } from 'node:child_process';
import { appendFileSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

import { collaboratorHasWrite, isLogin } from './preview-activity.mjs';
import { assertPrNumber, assertSha } from './preview-guard.mjs';

const REPO_RE = /^[A-Za-z0-9_.-]{1,100}\/[A-Za-z0-9_.-]{1,100}$/;
const COMMAND_RE = /^\/preview(?: (restart))?$/i;

const REPLIES = {
  closed: 'This pull request is closed, so it has no preview environment. Nothing was done.',
  fork: 'Preview environments are not created for forks. Nothing was done.',
  forbidden: 'Only repository collaborators with write access can use `/preview`. Nothing was done.',
};

/** First line only, exactly `/preview` or `/preview restart`. */
export function parseCommand(body) {
  if (typeof body !== 'string') return null;
  const m = COMMAND_RE.exec(body.split('\n')[0].trim());
  return m ? { restart: Boolean(m[1]) } : null;
}

const refuse = (reason, reply) => ({ proceed: false, reason, ...(reply ? { reply } : {}) });

/**
 * @param {{payload: object, repo: string, gh: (path: string) => object}} args
 *   `gh` GETs an API path and returns parsed JSON (throws on failure).
 */
export function prepareCommand({ payload, repo, gh }) {
  if (payload?.action !== 'created') return refuse('not a new comment');
  if (!payload.issue?.pull_request) return refuse('comment is on an issue, not a pull request');
  const command = parseCommand(payload.comment?.body);
  if (!command) return refuse('not a /preview command');

  const login = payload.sender?.login;
  if (!isLogin(login)) return refuse('implausible sender login');
  if (!REPO_RE.test(repo ?? '')) return refuse('invalid repository');

  // Commenter permission first: nothing about the PR is fetched for strangers.
  const allowed = collaboratorHasWrite({
    repo,
    login,
    run: (argv) => {
      try {
        return { status: 0, stdout: toTsv(gh(argv.find((a) => a.startsWith('repos/')))), stderr: '' };
      } catch (err) {
        return { status: 1, stdout: '', stderr: err.message };
      }
    },
  });
  if (!allowed) return refuse('commenter lacks write access', REPLIES.forbidden);

  const number = assertPrNumber(Number(payload.issue.number));
  let pr;
  try {
    pr = gh(`repos/${repo}/pulls/${number}`);
  } catch (err) {
    return refuse(`could not fetch the pull request: ${String(err.message).split('\n')[0]}`);
  }
  if (Number(pr?.number) !== number) return refuse('pull request number mismatch');
  if (String(pr.state).toLowerCase() !== 'open' || pr.merged === true) {
    return refuse('pull request is not open', REPLIES.closed);
  }
  const headRepo = String(pr.head?.repo?.full_name ?? '').toLowerCase();
  if (!headRepo || headRepo !== repo.toLowerCase()) return refuse('fork pull request', REPLIES.fork);

  return {
    proceed: true,
    reason: 'collaborator request',
    event: {
      action: 'requested',
      requested_by: login,
      restart: command.restart,
      pull_request: {
        number,
        state: 'open',
        merged: false,
        user: { login: String(pr.user?.login ?? '') },
        labels: (pr.labels ?? []).map((l) => ({ name: String(l?.name ?? '') })),
        head: { sha: assertSha(pr.head?.sha), repo: { full_name: pr.head.repo.full_name } },
        base: { repo: { full_name: String(pr.base?.repo?.full_name ?? '') } },
      },
    },
  };
}

const toTsv = (permissionResponse) =>
  `${permissionResponse?.permission ?? ''}\t${permissionResponse?.role_name ?? ''}`;

/* -------------------------------------------------------------------- cli */

function ghGet(path) {
  const r = spawnSync('gh', ['api', path], { encoding: 'utf8' });
  if (r.status !== 0) throw new Error(`gh api ${path.split('/').slice(0, 4).join('/')} failed`);
  return JSON.parse(r.stdout);
}

function ghPost(kind, id, { repo, text }) {
  const args =
    kind === 'react'
      ? ['api', '-X', 'POST', `repos/${repo}/issues/comments/${id}/reactions`, '-f', 'content=eyes']
      : ['api', '-X', 'POST', `repos/${repo}/issues/${id}/comments`, '--input', '-'];
  const r = spawnSync('gh', args, {
    input: kind === 'reply' ? JSON.stringify({ body: text }) : undefined,
    encoding: 'utf8',
  });
  if (r.status !== 0) process.stderr.write(`::warning::could not ${kind} on #${id}\n`);
}

export function main(argv, env = process.env, io = {}) {
  const flags = {};
  for (let i = 0; i < argv.length; i += 2) flags[argv[i].replace(/^--/, '')] = argv[i + 1];
  const repo = env.GITHUB_REPOSITORY;
  const payload = JSON.parse(readFileSync(flags['github-event'], 'utf8'));
  const result = prepareCommand({ payload, repo, gh: io.gh ?? ghGet });
  const post = io.post ?? ((kind, id, text) => ghPost(kind, id, { repo, text }));

  if (result.proceed) {
    writeFileSync(flags.out, `${JSON.stringify(result.event, null, 2)}\n`);
    post('react', Number(payload.comment.id));
  } else if (result.reply) {
    post('reply', Number(payload.issue.number), result.reply);
  }
  process.stdout.write(`/preview: ${result.proceed ? 'proceeding' : 'ignored'} (${result.reason})\n`);
  if (env.GITHUB_OUTPUT) appendFileSync(env.GITHUB_OUTPUT, `proceed=${result.proceed}\n`);
  return 0;
}

const isEntrypoint = (() => {
  try {
    return import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href;
  } catch {
    return false;
  }
})();

if (isEntrypoint) {
  try {
    process.exit(main(process.argv.slice(2)));
  } catch (err) {
    process.stderr.write(`::error::${String(err.message).split('\n')[0]}\n`);
    process.exit(1);
  }
}
