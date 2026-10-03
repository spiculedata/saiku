/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The runnable entry point for a model diff, shared by every caller (saiku#1434).
 *
 * <p>Two callers, one implementation — deliberately:
 *
 * <ul>
 *   <li>{@code saiku model diff} in the launcher, whose picocli subcommand is a thin wrapper
 *       around {@link #run}.
 *   <li>The {@code model-diff} GitHub workflow, which cannot afford to build the launcher's fat
 *       JAR (that pulls the whole SvelteKit bundle) just to run one check, and invokes
 *       {@link #main} against the compiled {@code saiku-service} classes instead.
 * </ul>
 *
 * <p>Because both go through here, a CI comment and a local run are byte-identical — a reviewer
 * reading one can reproduce the other exactly.
 *
 * <p><b>Exit codes</b> (the CI signal): {@code 0} nothing broken, {@code 1} broken references
 * found, {@code 2} input unreadable or unparseable, {@code 3} the two sides are different model
 * formats.
 */
public final class ModelDiffTool {

    /** Outcome of a run, for callers that want the report rather than the rendered output. */
    public record Result(int exitCode, ModelDiffReport report) {}

    /** Everything a run needs. Built by the CLI or parsed from {@code main}'s argv. */
    public static class Options {
        public Path before;
        public Path after;
        /** Git refs to read {@code before} (index 0) and {@code after} (index 1) from. */
        public List<String> fromGit = new ArrayList<>();

        public Path repository;
        public boolean json;
        public boolean failOnBroken = true;

        public String refAt(int index) {
            if (fromGit == null || fromGit.isEmpty()) {
                return null;
            }
            return fromGit.get(Math.min(index, fromGit.size() - 1));
        }
    }

    private final ModelDiffService service;
    private GitBlobReader git = new ProcessGitBlobReader();

    public ModelDiffTool() {
        this(new ModelDiffService());
    }

    public ModelDiffTool(ModelDiffService service) {
        this.service = service;
    }

    /** Swappable so callers (and tests) can serve blobs without a git repository on disk. */
    public void setGitBlobReader(GitBlobReader reader) {
        this.git = reader;
    }

    /** Reads a path out of a git ref. */
    public interface GitBlobReader {
        String show(String ref, Path path);
    }

    /** The real reader: {@code git show <ref>:<path>}, with no shell in between. */
    public static class ProcessGitBlobReader implements GitBlobReader {
        @Override
        public String show(String ref, Path path) {
            // Ref and path are separate argv elements, never a shell string, so a ref like
            // "; rm -rf /" is a git error rather than a command.
            try {
                ProcessBuilder builder = new ProcessBuilder("git", "show", ref + ":" + path);
                Process process = builder.start();
                String body = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exit = process.waitFor();
                if (exit != 0) {
                    throw new ModelDiffException(
                            ModelDiffException.Reason.MALFORMED,
                            "git show " + ref + ":" + path + " failed (exit " + exit + ")");
                }
                return body;
            } catch (IOException e) {
                throw new ModelDiffException(
                        ModelDiffException.Reason.MALFORMED, "could not run git: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelDiffException(ModelDiffException.Reason.MALFORMED, "interrupted reading " + ref, e);
            }
        }
    }

    public Result run(Options options, PrintStream out, PrintStream err) {
        if (options.before == null) {
            err.println("model diff: --before is required.");
            return new Result(2, null);
        }
        Path after = options.after == null ? options.before : options.after;
        // A side named together with --from-git lives inside a git ref, not necessarily on disk;
        // a side without one must be a readable file.
        if (options.refAt(0) == null && !Files.isRegularFile(options.before)) {
            err.println("model diff: --before '" + options.before + "' is not a readable file.");
            return new Result(2, null);
        }
        if (options.refAt(1) == null && !Files.isRegularFile(after)) {
            err.println("model diff: --after '" + after + "' is not a readable file.");
            return new Result(2, null);
        }
        try {
            ModelSnapshot beforeModel =
                    service.parse(read(options.before, options.refAt(0)), label(options.before, options.refAt(0)));
            ModelSnapshot afterModel = service.parse(read(after, options.refAt(1)), label(after, options.refAt(1)));
            ModelDiffReport report = service.diff(beforeModel, afterModel, options.repository);
            out.print(options.json ? toJson(report) : report.markdown());
            boolean broken = !report.isClean();
            int exit = (options.failOnBroken && broken) ? 1 : 0;
            return new Result(exit, report);
        } catch (ModelDiffException e) {
            err.println("model diff: " + e.getMessage());
            return new Result(e.reason() == ModelDiffException.Reason.CROSS_FORMAT ? 3 : 2, null);
        }
    }

    private String read(Path path, String ref) {
        if (ref == null) {
            try {
                return Files.readString(path);
            } catch (IOException e) {
                throw new ModelDiffException(
                        ModelDiffException.Reason.MALFORMED, "could not read '" + path + "': " + e.getMessage(), e);
            }
        }
        return git.show(ref, path);
    }

    private static String label(Path path, String ref) {
        return ref == null ? path.getFileName().toString() : ref + ":" + path;
    }

    public static String toJson(ModelDiffReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"format\": \"").append(report.format().name()).append("\",\n");
        sb.append("  \"before\": \"").append(escape(report.beforeName())).append("\",\n");
        sb.append("  \"after\": \"").append(escape(report.afterName())).append("\",\n");
        sb.append("  \"clean\": ").append(report.isClean()).append(",\n");
        sb.append("  \"breaking\": ").append(report.hasBreakingChanges()).append(",\n");
        sb.append("  \"filesScanned\": ").append(report.filesScanned()).append(",\n");
        sb.append("  \"changes\": [");
        for (int i = 0; i < report.changes().size(); i++) {
            ModelChange change = report.changes().get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    {\"type\": \"")
                    .append(change.type())
                    .append("\", \"kind\": \"")
                    .append(change.kind())
                    .append("\", \"cube\": \"")
                    .append(escape(change.cube()))
                    .append("\", \"path\": \"")
                    .append(escape(change.path()))
                    .append("\", \"from\": \"")
                    .append(escape(change.fromName()))
                    .append("\", \"to\": \"")
                    .append(escape(change.toName()))
                    .append("\"}");
        }
        sb.append(report.changes().isEmpty() ? "]" : "\n  ],\n");
        sb.append("  \"brokenReferences\": [");
        for (int i = 0; i < report.brokenReferences().size(); i++) {
            BrokenReference reference = report.brokenReferences().get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    {\"file\": \"")
                    .append(escape(reference.file()))
                    .append("\", \"location\": \"")
                    .append(escape(reference.location()))
                    .append("\", \"reference\": \"")
                    .append(escape(reference.mdxForm()))
                    .append("\", \"reason\": \"")
                    .append(escape(reference.reason()))
                    .append("\"}");
        }
        sb.append(report.brokenReferences().isEmpty() ? "]\n" : "\n  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /**
     * {@code java -cp <saiku-service classes> org.saiku.service.schema.diff.ModelDiffTool …}
     *
     * <p>Argv mirrors the CLI: {@code --before} / {@code --after} (paths inside the ref, or on
     * disk), {@code --from-git} (repeatable: first for before, second for after),
     * {@code --repository}, {@code --json}, {@code --no-fail-on-broken}.
     */
    public static void main(String[] args) {
        Options options = new Options();
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--before", "-b" -> options.before = Paths.get(value(args, ++i, arg));
                case "--after", "-a" -> options.after = Paths.get(value(args, ++i, arg));
                case "--repository", "-r" -> options.repository = Paths.get(value(args, ++i, arg));
                case "--from-git" -> refs.add(value(args, ++i, arg));
                case "--json" -> options.json = true;
                case "--no-fail-on-broken" -> options.failOnBroken = false;
                case "--fail-on-broken" -> options.failOnBroken = true;
                default -> {
                    System.err.println("model diff: unknown option '" + arg + "'");
                    printUsage(System.err);
                    System.exit(2);
                }
            }
        }
        options.fromGit = refs;
        System.exit(new ModelDiffTool().run(options, System.out, System.err).exitCode());
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }

    private static void printUsage(PrintStream err) {
        err.println("usage: model diff --before=<model> --after=<model>"
                + " [--from-git=<ref>] [--repository=<dir>] [--json] [--no-fail-on-broken]");
    }
}
