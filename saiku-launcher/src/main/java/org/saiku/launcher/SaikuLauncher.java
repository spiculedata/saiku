package org.saiku.launcher;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Callable;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "saiku",
        mixinStandardHelpOptions = true,
        version = "saiku 3.17",
        description = "Saiku OLAP server.",
        subcommands = {SaikuLauncher.ServeCommand.class})
public class SaikuLauncher implements Callable<Integer> {

    public static void main(String[] args) {
        int code = new CommandLine(new SaikuLauncher()).execute(args.length == 0 ? new String[] {"serve"} : args);
        if (code != 0) {
            System.exit(code);
        }
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(name = "serve", description = "Start the Saiku web server.")
    public static class ServeCommand implements Callable<Integer> {

        @Option(
                names = {"-p", "--port"},
                description = "HTTP port (default 8080).",
                defaultValue = "8080")
        int port;

        @Option(
                names = {"-h", "--host"},
                description = "Bind host (default 0.0.0.0).",
                defaultValue = "0.0.0.0")
        String host;

        @Option(
                names = {"--context"},
                description = "Context path (default /).",
                defaultValue = "/")
        String contextPath;

        @Option(
                names = {"--home"},
                description = "Saiku home directory (data, repository, logs). Default: ./saiku-home")
        Path home;

        @Override
        public Integer call() throws Exception {
            Path saikuHome = (home != null ? home : Paths.get("saiku-home")).toAbsolutePath();
            Files.createDirectories(saikuHome.resolve("data"));
            Files.createDirectories(saikuHome.resolve("repository").resolve("data"));
            Files.createDirectories(saikuHome.resolve("logs"));
            Files.createDirectories(saikuHome.resolve("plugins"));
            System.setProperty("saiku.home", saikuHome.toString());
            System.out.println("Saiku home: " + saikuHome);

            Path warPath = extractWar();

            Server server = new Server();
            ServerConnector connector = new ServerConnector(server);
            connector.setHost(host);
            connector.setPort(port);
            server.addConnector(connector);

            WebAppContext webapp = new WebAppContext();
            webapp.setContextPath(contextPath);
            webapp.setWar(warPath.toString());
            webapp.setExtractWAR(true);
            server.setHandler(webapp);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.stop();
                } catch (Exception ignored) {
                }
            }));

            server.start();
            System.out.println("Saiku ready at http://" + host + ":" + port + contextPath);
            server.join();
            return 0;
        }

        private Path extractWar() throws Exception {
            Path tmp = Files.createTempFile("saiku-", ".war");
            tmp.toFile().deleteOnExit();
            try (InputStream in = SaikuLauncher.class.getResourceAsStream("/webapp/saiku.war")) {
                if (in == null) {
                    Path local = Paths.get("saiku.war");
                    if (Files.exists(local)) {
                        return local.toAbsolutePath();
                    }
                    throw new IllegalStateException(
                            "Bundled WAR not found on classpath at /webapp/saiku.war and no saiku.war in cwd.");
                }
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            return tmp;
        }
    }
}
