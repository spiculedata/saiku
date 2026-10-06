// The image-tag contract between the docker workflow and the preview host.
// Ported from spiculedata/saiku-cloud .github/scripts/preview-images.mjs
// (source commit in docs/decisions/ci-preview-environments.md, provenance table).
//
// A leaf module: no I/O, no imports, so the guard (which builds every host
// command), the lifecycle (which decides) and the driver (which waits) share one
// grammar.
//
// What CI publishes for a same-repo PR build (docker.yml): `pr-<n>` and the
// first SHA_TAG_LENGTH hex of the PR head sha, to ghcr.io/spiculedata/saiku.
//
//   per-PR tag   the first SHA_TAG_LENGTH hex of the PR head sha. It names one
//                commit exactly; `pr-<n>` moves on every push and could still
//                point at the previous commit while the new build is running.
//
// Differences from the cloud: saiku is ONE image (the launcher fat JAR), so there
// is no component enum, no changed-files path rules and no `develop` fallback. A
// preview of a PR whose image does not exist is not a preview of that PR, so the
// driver waits for the image and then fails rather than substituting another one.
//
// Nothing PR-authored (title, branch, file names) ever becomes part of an image
// reference: a reference is registry constant + repository constant + a tag that
// matched the strict grammar below.

export const IMAGE_REGISTRY = 'ghcr.io/spiculedata';
export const IMAGE_REPOSITORY = 'saiku';
/** Must equal the short-sha length docker.yml tags PR builds with (a test pins it). */
export const SHA_TAG_LENGTH = 7;

/**
 * `on.pull_request.paths` of docker.yml: a PR that changes none of these publishes NO
 * image ("Docs-only PRs do not build one"), so waiting for one would only hold the host
 * lock for nothing. A test pins this list to the workflow file.
 */
export const IMAGE_BUILD_PATHS = Object.freeze([
  'pom.xml',
  '**/pom.xml',
  'saiku-*/**',
  'lib/**',
  'Dockerfile',
  'docker/**',
  '.github/workflows/docker.yml',
]);

const HEAD_SHA_RE = /^[0-9a-f]{40}$/;
const SHA_TAG_RE = new RegExp(`^[0-9a-f]{${SHA_TAG_LENGTH}}$`);

/** The per-PR image tag for a head commit. */
export function prImageTag(sha) {
  if (typeof sha !== 'string' || !HEAD_SHA_RE.test(sha)) {
    throw new Error(`invalid commit sha: ${JSON.stringify(sha)}`);
  }
  return sha.slice(0, SHA_TAG_LENGTH);
}

/** A tag may reach a command line only if it is a per-SHA tag. */
export function assertImageTag(tag) {
  if (typeof tag !== 'string' || !SHA_TAG_RE.test(tag)) {
    throw new Error(`invalid image tag: ${JSON.stringify(tag)}`);
  }
  return tag;
}

export const imageRef = (tag) => `${IMAGE_REGISTRY}/${IMAGE_REPOSITORY}:${assertImageTag(tag)}`;

/**
 * Display value for an image kept in a registry file on the box: anything
 * outside the grammar is dropped, never repaired.
 */
export function describeImage(image) {
  if (!image || typeof image !== 'object') return null;
  try {
    return { tag: assertImageTag(image.tag) };
  } catch {
    return null;
  }
}

/** The subset of GitHub's `paths` glob syntax docker.yml uses. */
function matchesRule(file, rule) {
  if (rule === '**/pom.xml') return file === 'pom.xml' || file.endsWith('/pom.xml');
  if (rule === 'saiku-*/**') return /^saiku-[^/]+\/./.test(file);
  if (rule.endsWith('/**')) return file.startsWith(rule.slice(0, -2));
  return file === rule;
}

/**
 * Would docker.yml build (and push) an image for a PR with these changed files? Entries
 * that are not strings are ignored; a non-array is "nothing changed". File names are data
 * matched against the rules above, never interpolated anywhere.
 */
export function buildsImage(files) {
  const list = Array.isArray(files) ? files.filter((f) => typeof f === 'string') : [];
  return list.some((file) => IMAGE_BUILD_PATHS.some((rule) => matchesRule(file, rule)));
}
