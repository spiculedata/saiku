/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import bi.saiku.ossie.OssieYamlWriter;
import bi.saiku.ossie.model.OssieDocument;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import org.saiku.service.schema.ossie.MondrianToOssieConverter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code saiku ossie-export} — convert a Mondrian XML schema into Apache Ossie YAML.
 *
 * <p>Reads a Mondrian XML file (default: stdin) via {@link MondrianToOssieConverter}, serialises
 * the resulting {@link OssieDocument} through {@link OssieYamlWriter}, and writes it to the
 * requested output (default: stdout). Any cubes that couldn't be mapped (Mondrian 4 MG shape,
 * virtual cubes — see the converter's javadoc for the deliberate-non-goals list) get reported to
 * stderr so operators can spot the gap without the YAML itself carrying schema-invalid stubs.
 *
 * <p>Examples:
 *
 * <pre>{@code
 * # Convert a schema on disk to YAML on disk.
 * saiku ossie-export --in my-classic3-schema.xml --out my.ossie.yaml
 *
 * # Or pipe on stdin / stdout for scripting.
 * cat my-classic3-schema.xml | saiku ossie-export > my.ossie.yaml
 * }</pre>
 *
 * <p><b>Exit codes:</b> {@code 0} converted at least one cube; {@code 2} input unreadable;
 * {@code 3} output unwritable; {@code 4} every cube was skipped, so the YAML is an empty model.
 *
 * <p><b>Mondrian 4 is not supported yet</b>, and that is the shape every schema Saiku ships uses
 * (FoodMart4, Bank, Pharma all declare {@code <MeasureGroup>}). Pointing this command at one of
 * them writes {@code semantic_model: []} and exits 4. The examples above deliberately no longer
 * name a shipped schema — the previous ones used {@code saiku-home/data/Pharma.xml}, so the
 * documented worked example produced nothing (saiku#1808).
 */
@Command(
        name = "ossie-export",
        description = "Convert a Mondrian XML schema into Apache Ossie YAML.",
        mixinStandardHelpOptions = true)
public class OssieExportCommand implements Callable<Integer> {

    @Option(
            names = {"-i", "--in"},
            description = "Input Mondrian schema XML. Defaults to stdin when absent.")
    Path in;

    @Option(
            names = {"-o", "--out"},
            description = "Output Ossie YAML file. Defaults to stdout when absent.")
    Path out;

    @Override
    public Integer call() throws Exception {
        MondrianToOssieConverter converter = new MondrianToOssieConverter();
        OssieDocument doc;
        try (InputStream input = openInput()) {
            doc = converter.convert(input);
        } catch (IOException e) {
            System.err.println("ossie-export: failed to read input: " + e.getMessage());
            return 2;
        }

        try (Writer output = openOutput()) {
            new OssieYamlWriter().write(doc, output);
        } catch (IOException e) {
            System.err.println("ossie-export: failed to write output: " + e.getMessage());
            return 3;
        }

        // Report on skipped cubes so the operator knows nothing was silently dropped.
        if (!converter.getSkippedCubes().isEmpty()) {
            System.err.println(
                    "ossie-export: skipped " + converter.getSkippedCubes().size()
                            + " cube(s) that the first-cut converter doesn't yet recognise "
                            + "(Mondrian 4 <MeasureGroup>/virtual cube shape — tracked on the parent epic):");
            for (String name : converter.getSkippedCubes()) {
                System.err.println("  - " + name);
            }
        }
        try (PrintWriter tty = new PrintWriter(System.err, true)) {
            tty.println("ossie-export: wrote " + doc.getSemanticModel().size() + " semantic model(s) to "
                    + (out == null ? "stdout" : out.toString()));
        }

        // saiku#1808: converting NOTHING is a failure, and must not exit 0.
        //
        // The documented usage is a pipeline — `cat schema.xml | saiku ossie-export > out.yaml` —
        // where stderr is routinely discarded and the exit code is the only signal the caller
        // reads. Every MDX schema Saiku ships uses the Mondrian 4 MeasureGroup shape this
        // converter skips, so the common case was: empty `semantic_model: []` on stdout, an
        // explanation on stderr nobody sees, and success. A build step consuming that YAML would
        // carry on with a model containing no datasets and no metrics.
        if (doc.getSemanticModel().isEmpty() && !converter.getSkippedCubes().isEmpty()) {
            System.err.println("ossie-export: no cube could be converted — the output is an empty model.");
            return 4;
        }
        return 0;
    }

    private InputStream openInput() throws IOException {
        if (in == null) {
            // Wrapping System.in in a filter that DOESN'T close the underlying stream avoids
            // slamming the terminal shut when the try-with-resources body finishes.
            return new java.io.FilterInputStream(System.in) {
                @Override
                public void close() {}
            };
        }
        Path resolved = in.isAbsolute()
                ? in
                : Paths.get(System.getProperty("user.dir")).resolve(in).normalize();
        if (!Files.isReadable(resolved)) {
            throw new IOException("Input schema not readable: " + resolved);
        }
        return new FileInputStream(resolved.toFile());
    }

    private Writer openOutput() throws IOException {
        OutputStream stream;
        if (out == null) {
            stream = new java.io.FilterOutputStream(System.out) {
                @Override
                public void close() throws IOException {
                    flush();
                    // Don't close stdout — keeps the launcher process healthy if the CLI is one
                    // step in a shell pipeline.
                }
            };
        } else {
            stream = Files.newOutputStream(out);
        }
        return new OutputStreamWriter(stream, StandardCharsets.UTF_8);
    }
}
