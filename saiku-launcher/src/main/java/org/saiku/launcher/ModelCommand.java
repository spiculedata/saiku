/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.saiku.service.schema.diff.ModelDiffTool;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code saiku model} — the semantic-model review tooling (saiku#1434).
 *
 * <p>One subcommand today, {@code diff}: the PR-review UX for a schema change. This class is
 * deliberately a thin picocli shell — all of the behaviour lives in
 * {@link ModelDiffTool}, which the {@code model-diff} GitHub workflow also runs. A reviewer
 * reading a CI comment can therefore reproduce it exactly with the CLI.
 *
 * <pre>{@code
 * # Two files on disk, scanning a Saiku home for the queries they would break.
 * saiku model diff --before FoodMart4.xml --after FoodMart4.proposed.xml \
 *   --repository ./saiku-home/repository/data
 *
 * # Straight off a pull request: baseline from the base branch, proposal from the head.
 * saiku model diff --from-git origin/development --from-git HEAD \
 *   --before saiku-launcher/src/main/resources/seed/FoodMart4.xml \
 *   --after   saiku-launcher/src/main/resources/seed/FoodMart4.xml \
 *   --repository ./saiku-home/repository/data
 * }</pre>
 *
 * <p>Exit codes are the CI signal: {@code 0} nothing broken, {@code 1} broken references found,
 * {@code 2} the input could not be read or parsed, {@code 3} the two sides are different model
 * formats.
 */
@Command(
        name = "model",
        description = "Semantic-model diff and content validation.",
        mixinStandardHelpOptions = true,
        subcommands = {ModelCommand.DiffCommand.class})
public class ModelCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        // Invoked bare: picocli prints this subcommand's usage, so there is nothing to do.
        return 0;
    }

    @Command(
            name = "diff",
            description = "Diff two semantic models and list the saved queries, dashboards and apps they break.",
            mixinStandardHelpOptions = true)
    public static class DiffCommand implements Callable<Integer> {

        @Option(
                names = {"-b", "--before"},
                description = "Path to the baseline model (Mondrian XML or Ossie YAML).")
        Path before;

        @Option(
                names = {"-a", "--after"},
                description = "Path to the proposed model. Defaults to --before when omitted.")
        Path after;

        @Option(
                names = {"--from-git"},
                description = "Git ref to read --before / --after from. Repeat once for the baseline "
                        + "and once for the proposal, e.g. --from-git origin/development --from-git HEAD.")
        List<String> fromGit = new ArrayList<>();

        @Option(
                names = {"-r", "--repository"},
                description = "Repository directory to scan for saved queries, dashboards and apps.")
        Path repository;

        @Option(
                names = {"--json"},
                description = "Emit the report as JSON instead of Markdown.")
        boolean json;

        @Option(
                names = {"--no-fail-on-broken"},
                description = "Always exit 0, e.g. when generating a report for a human to read "
                        + "rather than gating a build.")
        boolean noFailOnBroken;

        // Package-private and non-final so the test can inject a GitBlobReader and exercise the
        // --from-git path without a repository on disk.
        ModelDiffTool tool = new ModelDiffTool();

        @Override
        public Integer call() {
            ModelDiffTool.Options options = new ModelDiffTool.Options();
            options.before = before;
            options.after = after;
            options.fromGit = fromGit == null ? List.of() : fromGit;
            options.repository = repository;
            options.json = json;
            options.failOnBroken = !noFailOnBroken;
            return tool.run(options, System.out, System.err).exitCode();
        }
    }
}
