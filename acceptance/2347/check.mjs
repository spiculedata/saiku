// Compiles the real SvgSecurity class (it has no dependencies) and exercises it.
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const src = join(root, 'saiku-core/saiku-web/src/main/java/org/saiku/web/svg/SvgSecurity.java');
const out = mkdtempSync(join(tmpdir(), 'svgsec-'));
execFileSync('javac', ['-d', out, src], { stdio: 'inherit' });

const runner = join(out, 'Check.java');
writeFileSync(
  runner,
  `import org.saiku.web.svg.SvgSecurity;
public class Check {
  static boolean rejects(String s) {
    try { SvgSecurity.requireSafe(s); return false; } catch (IllegalArgumentException e) { return true; }
  }
  public static void main(String[] a) {
    int bad = 0;
    String[] mustReject = {
      "<svg><style>@import \\"http://evil/x.css\\";</style></svg>",
      "<svg><style>@import 'http://evil/x.css';</style></svg>",
      "<svg><style>@import \\"file:///etc/passwd\\";</style></svg>",
      "<svg><style>@IMPORT   \\"//evil/x.css\\";</style></svg>",
      "<svg><style>@import url(http://evil/x.css);</style></svg>",
      "<?xml-stylesheet href=\\"http://evil/x.css\\" type=\\"text/css\\"?><svg/>"
    };
    for (String s : mustReject) if (!rejects(s)) { System.err.println("C1/C2 not rejected: " + s); bad++; }
    String ok = "<svg xmlns=\\"http://www.w3.org/2000/svg\\"><style>.a{fill:red}</style><path d=\\"M0 0\\"/></svg>";
    if (rejects(ok)) { System.err.println("C3 legit SVG rejected"); bad++; }
    System.exit(bad == 0 ? 0 : 1);
  }
}`
);
try {
  execFileSync('java', ['-cp', out, runner], { stdio: 'inherit' });
} catch {
  process.exit(1);
}
process.stdout.write('SvgSecurity rejects @import and xml-stylesheet, accepts self-contained SVG\n');
