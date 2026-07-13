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
 * saiku ossie-export --in saiku-home/data/Pharma.xml --out pharma.ossie.yaml
 *
 * # Or pipe on stdin / stdout for scripting.
 * cat saiku-home/data/Pharma.xml | saiku ossie-export > pharma.ossie.yaml
 * }</pre>
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
