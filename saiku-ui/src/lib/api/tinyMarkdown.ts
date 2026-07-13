/*
 * Minimal markdown → HTML renderer for the AI Ask drawer's insight bubbles.
 *
 * Scope is intentionally narrow: handles what Claude actually emits in
 * emit_insight responses — headings, bold/italic, paragraphs, bullets,
 * inline code, and links. Anything fancier (tables, code blocks, images,
 * nested lists) falls through as plain text — acceptable because the
 * insight-tool system prompt asks the model to keep formatting tight.
 *
 * Output is HTML-safe: every node from the source gets escaped first; only
 * the structural tags we emit (h4, p, ul, li, strong, em, code, a) appear
 * un-escaped. No third-party markdown lib in the bundle.
 */

const ESCAPE_REPLACEMENTS: Record<string, string> = {
  "&": "&amp;",
  "<": "&lt;",
  ">": "&gt;",
  '"': "&quot;",
  "'": "&#39;",
};

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (ch) => ESCAPE_REPLACEMENTS[ch] ?? ch);
}

/**
 * Inline-format a single line of escaped text: **bold**, *italic*, `code`,
 * and [text](url). Applied AFTER escapeHtml so we can't accidentally
 * generate dangerous HTML from user-controlled input.
 */
function formatInline(escaped: string): string {
  let out = escaped;
  // **bold** — eager, non-greedy
  out = out.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
  // *italic* — same, but match only when surrounded by word boundaries so
  // we don't eat bullet asterisks that survived the line-prefix strip.
  out = out.replace(/(^|[\s(])\*([^*\s][^*]*?)\*(?=[\s).,!?]|$)/g, "$1<em>$2</em>");
  // `inline code`
  out = out.replace(/`([^`]+)`/g, "<code>$1</code>");
  // [text](url) — http(s) only, anything else is plain text
  out = out.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
  return out;
}

export function renderTinyMarkdown(src: string): string {
  if (!src) return "";
  const lines = src.replace(/\r\n/g, "\n").split("\n");
  const out: string[] = [];
  let para: string[] = [];
  let bullets: string[] = [];

  const flushPara = () => {
    if (para.length) {
      out.push(`<p>${formatInline(escapeHtml(para.join(" ")))}</p>`);
      para = [];
    }
  };
  const flushBullets = () => {
    if (bullets.length) {
      const items = bullets.map((b) => `<li>${formatInline(escapeHtml(b))}</li>`).join("");
      out.push(`<ul>${items}</ul>`);
      bullets = [];
    }
  };

  for (const raw of lines) {
    const line = raw.replace(/\s+$/, "");
    // Headings — # … #### map to <h3>..<h6>. We never emit <h1>/<h2> so the
    // drawer's own UI headings stay dominant in the visual hierarchy.
    const heading = /^(#{1,6})\s+(.*)$/.exec(line);
    if (heading) {
      flushBullets();
      flushPara();
      const level = Math.min(6, Math.max(3, heading[1].length + 2));
      out.push(`<h${level}>${formatInline(escapeHtml(heading[2]))}</h${level}>`);
      continue;
    }
    // Bullets: `- foo` or `* foo`. `1. foo` ordered lists fall through to
    // plain text — the LLM almost never uses them in insight bubbles.
    const bullet = /^\s*[-*]\s+(.*)$/.exec(line);
    if (bullet) {
      flushPara();
      bullets.push(bullet[1]);
      continue;
    }
    if (line === "") {
      flushBullets();
      flushPara();
      continue;
    }
    flushBullets();
    para.push(line);
  }
  flushBullets();
  flushPara();
  return out.join("\n");
}
