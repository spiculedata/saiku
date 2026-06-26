#!/usr/bin/env node
/**
 * CSS-to-Tailwind converter for saiku-ui's BEM-style scoped component CSS.
 *
 * For each input .svelte file:
 *   1. Parses the <style> block
 *   2. For each `.selector { prop: value; ... }` rule, tries to convert
 *      every property to a Tailwind utility class
 *   3. If 100% of the rule's properties convert, replaces the selector
 *      throughout the template with the Tailwind class string AND drops
 *      the rule from <style>
 *   4. Anything that doesn't fully convert stays in <style> verbatim
 *
 * Conservative by design: misses something → that rule stays. False
 * positives are worse than false negatives because the latter leaves
 * the existing CSS in place (no visual regression).
 *
 * Run: node scripts/css-to-tw.mjs <file1.svelte> [file2 ...]
 * Or: node scripts/css-to-tw.mjs --auto (sweeps files with <style> blocks)
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { argv } from 'node:process';

const SPACE_MAP = {
  '0': '0',
  '1px': 'px',
  '2px': '0.5',
  '4px': '1',
  '6px': '1.5',
  '8px': '2',
  '10px': '2.5',
  '12px': '3',
  '14px': '3.5',
  '16px': '4',
  '20px': '5',
  '24px': '6',
  '28px': '7',
  '32px': '8',
  '36px': '9',
  '40px': '10',
  '44px': '11',
  '48px': '12',
  '56px': '14',
  '64px': '16',
  '0.25rem': '1',
  '0.5rem': '2',
  '0.75rem': '3',
  '1rem': '4',
  '1.25rem': '5',
  '1.5rem': '6',
  '1.75rem': '7',
  '2rem': '8',
  '2.5rem': '10',
  '3rem': '12',
  'var(--space-1)': '1',
  'var(--space-2)': '2',
  'var(--space-3)': '3',
  'var(--space-4)': '4',
  'var(--space-5)': '6',
  'var(--space-6)': '8',
  'var(--space-8)': '12',
};

const COLOR_MAP = {
  'var(--fg)': 'fg',
  'var(--fg-muted)': 'fg-muted',
  'var(--fg-subtle)': 'fg-subtle',
  'var(--bg)': 'bg',
  'var(--bg-muted)': 'bg-muted',
  'var(--bg-subtle)': 'bg-subtle',
  'var(--bg-hover)': 'bg-hover',
  'var(--bg-overlay)': 'bg-overlay',
  'var(--border)': 'border',
  'var(--border-strong)': 'border-strong',
  'var(--accent)': 'accent',
  'var(--accent-hover)': 'accent-hover',
  'var(--accent-fg)': 'accent-foreground',
  'var(--accent-soft)': 'accent-soft',
  'var(--accent-strong)': 'accent-strong',
  'var(--success)': 'success',
  'var(--success-soft)': 'success-soft',
  'var(--success-strong)': 'success-strong',
  'var(--warning)': 'warning',
  'var(--warning-soft)': 'warning-soft',
  'var(--warning-strong)': 'warning-strong',
  'var(--danger)': 'danger',
  'var(--danger-soft)': 'danger-soft',
  'var(--danger-strong)': 'danger-strong',
};

const RADIUS_MAP = {
  'var(--radius-sm)': 'sm',
  'var(--radius-md)': 'md',
  'var(--radius-lg)': 'lg',
  '0': 'none',
  '4px': 'sm',
  '8px': 'md',
  '12px': 'lg',
};

const FS_MAP = {
  'var(--fs-xs)': 'xs',
  'var(--fs-sm)': 'sm',
  'var(--fs-md)': 'base',
  'var(--fs-lg)': 'lg',
  'var(--fs-xl)': 'xl',
  'var(--fs-2xl)': '2xl',
  '11px': 'xs',
  '12px': 'xs',
  '13px': 'sm',
  '14px': 'sm',
  '16px': 'base',
  '18px': 'lg',
  '20px': 'xl',
  '24px': '2xl',
  '0.6875rem': '[11px]',
  '0.75rem': 'xs',
  '0.8125rem': 'sm',
  '0.875rem': 'sm',
  '1rem': 'base',
  '1.125rem': 'lg',
  '1.25rem': 'xl',
  '1.5rem': '2xl',
};

const WEIGHT_MAP = {
  'var(--weight-normal)': 'normal',
  'var(--weight-medium)': 'medium',
  'var(--weight-semibold)': 'semibold',
  'var(--weight-bold)': 'bold',
  '400': 'normal',
  '500': 'medium',
  '600': 'semibold',
  '700': 'bold',
};

function convertProp(prop, value) {
  const v = value.trim().replace(/;$/, '');
  switch (prop) {
    case 'display':
      if (v === 'flex') return ['flex'];
      if (v === 'inline-flex') return ['inline-flex'];
      if (v === 'grid') return ['grid'];
      if (v === 'inline-grid') return ['inline-grid'];
      if (v === 'block') return ['block'];
      if (v === 'inline-block') return ['inline-block'];
      if (v === 'inline') return ['inline'];
      if (v === 'none') return ['hidden'];
      return null;
    case 'flex-direction':
      if (v === 'column') return ['flex-col'];
      if (v === 'row') return ['flex-row'];
      if (v === 'column-reverse') return ['flex-col-reverse'];
      if (v === 'row-reverse') return ['flex-row-reverse'];
      return null;
    case 'flex-wrap':
      if (v === 'wrap') return ['flex-wrap'];
      if (v === 'nowrap') return ['flex-nowrap'];
      return null;
    case 'align-items':
      if (v === 'center') return ['items-center'];
      if (v === 'flex-start') return ['items-start'];
      if (v === 'flex-end') return ['items-end'];
      if (v === 'stretch') return ['items-stretch'];
      if (v === 'baseline') return ['items-baseline'];
      return null;
    case 'justify-content':
      if (v === 'center') return ['justify-center'];
      if (v === 'flex-start') return ['justify-start'];
      if (v === 'flex-end') return ['justify-end'];
      if (v === 'space-between') return ['justify-between'];
      if (v === 'space-around') return ['justify-around'];
      if (v === 'space-evenly') return ['justify-evenly'];
      return null;
    case 'gap': {
      if (SPACE_MAP[v]) return [`gap-${SPACE_MAP[v]}`];
      return null;
    }
    case 'padding': {
      const parts = v.split(/\s+/);
      if (parts.length === 1) {
        const u = SPACE_MAP[parts[0]];
        if (u !== undefined) return [`p-${u}`];
      } else if (parts.length === 2) {
        const py = SPACE_MAP[parts[0]];
        const px = SPACE_MAP[parts[1]];
        if (py !== undefined && px !== undefined) return [`py-${py}`, `px-${px}`];
      } else if (parts.length === 4) {
        const t = SPACE_MAP[parts[0]];
        const r = SPACE_MAP[parts[1]];
        const b = SPACE_MAP[parts[2]];
        const l = SPACE_MAP[parts[3]];
        if ([t, r, b, l].every((x) => x !== undefined))
          return [`pt-${t}`, `pr-${r}`, `pb-${b}`, `pl-${l}`];
      }
      return null;
    }
    case 'padding-top':
    case 'padding-right':
    case 'padding-bottom':
    case 'padding-left': {
      const side = prop.split('-')[1][0];
      const u = SPACE_MAP[v];
      if (u !== undefined) return [`p${side}-${u}`];
      return null;
    }
    case 'margin': {
      const parts = v.split(/\s+/);
      if (parts.length === 1) {
        if (v === '0') return ['m-0'];
        const u = SPACE_MAP[parts[0]];
        if (u !== undefined) return [`m-${u}`];
        if (v === 'auto') return ['m-auto'];
      }
      return null;
    }
    case 'margin-top':
    case 'margin-right':
    case 'margin-bottom':
    case 'margin-left': {
      const side = prop.split('-')[1][0];
      if (v === '0') return [`m${side}-0`];
      if (v === 'auto') return [`m${side}-auto`];
      const u = SPACE_MAP[v];
      if (u !== undefined) return [`m${side}-${u}`];
      return null;
    }
    case 'width':
      if (v === '100%') return ['w-full'];
      if (v === 'auto') return ['w-auto'];
      if (v === '100vw') return ['w-screen'];
      if (/^\d+px$/.test(v)) return [`w-[${v}]`];
      if (/^\d+%$/.test(v)) return [`w-[${v}]`];
      return null;
    case 'height':
      if (v === '100%') return ['h-full'];
      if (v === 'auto') return ['h-auto'];
      if (v === '100vh') return ['h-screen'];
      if (/^\d+px$/.test(v)) return [`h-[${v}]`];
      if (/^\d+%$/.test(v)) return [`h-[${v}]`];
      return null;
    case 'min-width':
      if (v === '0') return ['min-w-0'];
      if (/^\d+px$/.test(v)) return [`min-w-[${v}]`];
      return null;
    case 'min-height':
      if (v === '0') return ['min-h-0'];
      if (/^\d+px$/.test(v)) return [`min-h-[${v}]`];
      return null;
    case 'max-width':
      if (/^\d+px$/.test(v)) return [`max-w-[${v}]`];
      return null;
    case 'max-height':
      if (/^\d+px$/.test(v)) return [`max-h-[${v}]`];
      return null;
    case 'overflow':
      if (v === 'hidden') return ['overflow-hidden'];
      if (v === 'auto') return ['overflow-auto'];
      if (v === 'scroll') return ['overflow-scroll'];
      if (v === 'visible') return ['overflow-visible'];
      return null;
    case 'overflow-x':
      if (v === 'auto') return ['overflow-x-auto'];
      if (v === 'hidden') return ['overflow-x-hidden'];
      return null;
    case 'overflow-y':
      if (v === 'auto') return ['overflow-y-auto'];
      if (v === 'hidden') return ['overflow-y-hidden'];
      return null;
    case 'border':
      if (v === '0') return ['border-0'];
      if (v === 'none') return ['border-0'];
      if (v === '1px solid var(--border)') return ['border', 'border-border'];
      if (v === '1px solid var(--border-strong)') return ['border', 'border-border-strong'];
      return null;
    case 'border-top':
    case 'border-right':
    case 'border-bottom':
    case 'border-left': {
      const side = prop.split('-')[1][0];
      if (v === '0') return [`border-${side}-0`];
      if (v === '1px solid var(--border)') return [`border-${side}`, 'border-border'];
      return null;
    }
    case 'border-radius':
      if (RADIUS_MAP[v]) return [`rounded-${RADIUS_MAP[v]}`];
      return null;
    case 'background':
    case 'background-color': {
      if (COLOR_MAP[v]) return [`bg-${COLOR_MAP[v]}`];
      if (v === 'transparent') return ['bg-transparent'];
      if (v === 'none') return ['bg-none'];
      return null;
    }
    case 'color': {
      if (COLOR_MAP[v]) return [`text-${COLOR_MAP[v]}`];
      if (v === 'inherit') return ['text-inherit'];
      return null;
    }
    case 'font-size': {
      if (FS_MAP[v]) return [`text-${FS_MAP[v]}`];
      return null;
    }
    case 'font-weight': {
      if (WEIGHT_MAP[v]) return [`font-${WEIGHT_MAP[v]}`];
      return null;
    }
    case 'text-align':
      if (v === 'left') return ['text-left'];
      if (v === 'right') return ['text-right'];
      if (v === 'center') return ['text-center'];
      return null;
    case 'cursor':
      if (v === 'pointer') return ['cursor-pointer'];
      if (v === 'default') return ['cursor-default'];
      if (v === 'not-allowed') return ['cursor-not-allowed'];
      if (v === 'grab') return ['cursor-grab'];
      return null;
    case 'position':
      if (v === 'relative') return ['relative'];
      if (v === 'absolute') return ['absolute'];
      if (v === 'fixed') return ['fixed'];
      if (v === 'sticky') return ['sticky'];
      return null;
    case 'flex':
      if (v === '1') return ['flex-1'];
      if (v === '0 0 auto') return ['flex-none'];
      if (v === 'none') return ['flex-none'];
      return null;
    case 'opacity':
      if (/^0\.\d+$/.test(v)) {
        const n = Math.round(parseFloat(v) * 100);
        return [`opacity-${n}`];
      }
      if (v === '0') return ['opacity-0'];
      if (v === '1') return ['opacity-100'];
      return null;
    case 'list-style':
      if (v === 'none') return ['list-none'];
      return null;
    case 'text-decoration':
      if (v === 'none') return ['no-underline'];
      if (v === 'underline') return ['underline'];
      return null;
    case 'white-space':
      if (v === 'nowrap') return ['whitespace-nowrap'];
      if (v === 'pre') return ['whitespace-pre'];
      if (v === 'pre-wrap') return ['whitespace-pre-wrap'];
      return null;
    case 'pointer-events':
      if (v === 'none') return ['pointer-events-none'];
      if (v === 'auto') return ['pointer-events-auto'];
      return null;
    case 'user-select':
      if (v === 'none') return ['select-none'];
      if (v === 'text') return ['select-text'];
      return null;
    case 'box-sizing':
      if (v === 'border-box') return ['box-border'];
      if (v === 'content-box') return ['box-content'];
      return null;
    case 'line-height':
      if (v === '1') return ['leading-none'];
      if (v === '1.25') return ['leading-tight'];
      if (v === '1.5') return ['leading-normal'];
      if (v === 'var(--lh-tight)') return ['leading-tight'];
      if (v === 'var(--lh-normal)') return ['leading-normal'];
      return null;
    case 'text-overflow':
      if (v === 'ellipsis') return ['text-ellipsis'];
      if (v === 'clip') return ['text-clip'];
      return null;
    case 'word-break':
      if (v === 'break-all') return ['break-all'];
      if (v === 'break-word') return ['break-words'];
      return null;
    case 'overflow-wrap':
      if (v === 'break-word') return ['break-words'];
      if (v === 'anywhere') return ['break-words'];
      return null;
    case 'text-transform':
      if (v === 'uppercase') return ['uppercase'];
      if (v === 'lowercase') return ['lowercase'];
      if (v === 'capitalize') return ['capitalize'];
      if (v === 'none') return ['normal-case'];
      return null;
    case 'visibility':
      if (v === 'hidden') return ['invisible'];
      if (v === 'visible') return ['visible'];
      return null;
    case 'z-index':
      if (/^-?\d+$/.test(v)) return [`z-[${v}]`];
      return null;
    case 'top':
    case 'right':
    case 'bottom':
    case 'left':
      if (v === '0') return [`${prop}-0`];
      if (v === 'auto') return [`${prop}-auto`];
      if (/^\d+px$/.test(v)) return [`${prop}-[${v}]`];
      return null;
    case 'inset':
      if (v === '0') return ['inset-0'];
      if (v === 'auto') return ['inset-auto'];
      return null;
    case 'object-fit':
      if (v === 'contain') return ['object-contain'];
      if (v === 'cover') return ['object-cover'];
      if (v === 'fill') return ['object-fill'];
      if (v === 'none') return ['object-none'];
      return null;
    case 'flex-shrink':
      if (v === '0') return ['shrink-0'];
      if (v === '1') return ['shrink'];
      return null;
    case 'flex-grow':
      if (v === '0') return ['grow-0'];
      if (v === '1') return ['grow'];
      return null;
    default:
      return null;
  }
}

/**
 * Parse a CSS rule body into [property, value] pairs.
 * Conservative — doesn't handle nested braces, comments inside the rule, etc.
 */
function parseProps(body) {
  const pairs = [];
  for (const decl of body.split(';')) {
    const trimmed = decl.trim();
    if (!trimmed) continue;
    const colon = trimmed.indexOf(':');
    if (colon < 0) return null; // malformed; bail
    const prop = trimmed.slice(0, colon).trim();
    const value = trimmed.slice(colon + 1).trim();
    pairs.push([prop, value]);
  }
  return pairs;
}

/**
 * Convert a single CSS rule body to a Tailwind class list.
 * Returns null if any property is unconvertible — caller leaves the rule alone.
 */
function convertRule(body) {
  const pairs = parseProps(body);
  if (!pairs) return null;
  const classes = [];
  for (const [prop, value] of pairs) {
    const tw = convertProp(prop, value);
    if (!tw) return null;
    classes.push(...tw);
  }
  return classes;
}

function processFile(fp) {
  const original = readFileSync(fp, 'utf8');
  const styleMatch = original.match(/<style>([\s\S]*?)<\/style>/);
  if (!styleMatch) return { changed: false };
  const style = styleMatch[1];

  // Find every simple rule: `.foo { ... }` (no nested, no media queries, no
  // commas, no pseudo-classes/elements). Anything fancier stays untouched.
  const ruleRe = /^\s*\.([\w-]+)\s*\{([^{}]*)\}/gm;
  const replacements = []; // [className, tailwindClasses]
  let newStyle = style;
  let m;
  while ((m = ruleRe.exec(style))) {
    const className = m[1];
    const body = m[2];
    const tw = convertRule(body);
    if (!tw || tw.length === 0) continue;
    // Skip if the class appears in a complex selector elsewhere in the
    // style block (parent .foo .bar, .foo:hover, .foo + .bar, etc.).
    const complexRe = new RegExp(`\\.${className}[^{,\\s]|\\.${className}\\s+[.\\w]|\\.${className}\\s*[,:+>~]`);
    if (complexRe.test(style)) continue;
    replacements.push([className, tw.join(' ')]);
    // Drop this rule from style block
    newStyle = newStyle.replace(m[0], '');
  }

  if (replacements.length === 0) return { changed: false };

  // Apply replacements to the template: class="...foo..." → class="...{tw}..."
  let body = original;
  for (const [cls, tw] of replacements) {
    // class="something foo something" or class={'foo'} or :class="..."
    // Replace `class="...foo..."` patterns: split by spaces, swap, rejoin
    body = body.replace(/class="([^"]*)"/g, (full, classes) => {
      const tokens = classes.split(/\s+/);
      const idx = tokens.indexOf(cls);
      if (idx < 0) return full;
      tokens.splice(idx, 1, ...tw.split(/\s+/));
      return `class="${tokens.filter(Boolean).join(' ')}"`;
    });
  }

  // Replace the <style> block (or drop it if empty after trim)
  const newStyleTrimmed = newStyle.replace(/^\s*\n/gm, '').trim();
  if (newStyleTrimmed === '') {
    body = body.replace(/<style>[\s\S]*?<\/style>\s*/, '');
  } else {
    body = body.replace(/<style>[\s\S]*?<\/style>/, `<style>\n${newStyleTrimmed}\n</style>`);
  }

  if (body === original) return { changed: false };
  writeFileSync(fp, body);
  return { changed: true, replaced: replacements.length };
}

const files = argv.slice(2);
let totalChanged = 0;
let totalReplaced = 0;
for (const fp of files) {
  try {
    const r = processFile(fp);
    if (r.changed) {
      totalChanged++;
      totalReplaced += r.replaced;
      console.log(`✓ ${fp}: ${r.replaced} rules converted`);
    }
  } catch (e) {
    console.error(`✗ ${fp}: ${e.message}`);
  }
}
console.log(`\nTotal: ${totalChanged} files changed, ${totalReplaced} rules converted`);
