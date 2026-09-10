package com.myharness.codex.support;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Disposable loopback-only server; never loads application.yml, a user's data directory, or database credentials. */
public final class IsolatedMysql implements AutoCloseable {
    private final Path root;
    private final Process server;
    private final DriverManagerDataSource dataSource;

    private IsolatedMysql(Path root, Process server, String url) {
        this.root = root;
        this.server = server;
        dataSource = new DriverManagerDataSource(url + "/harness?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", "root", "");
    }

    public static IsolatedMysql start() throws Exception {
        Path executable = executable();
        Path target = Path.of("target").toAbsolutePath().normalize();
        Files.createDirectories(target);
        Path root = Files.createTempDirectory(target, "isolated-email-mysql-").toAbsolutePath().normalize();
        Path data = root.resolve("data");
        Files.createDirectories(data);
        Path grantFile = root.resolve("loopback-user.sql");
        Files.writeString(grantFile, "CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '';\n"
                + "GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;\n");
        int port;
        try (ServerSocket probe = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { port = probe.getLocalPort(); }
        List<String> common = new ArrayList<>(List.of(executable.toString(), "--no-defaults", "--basedir=" + executable.getParent().getParent(),
                "--datadir=" + data, "--log-error=" + root.resolve("server.log")));
        // MySQL on Windows otherwise launches a monitoring process and a separate child server.
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) common.add("--no-monitor");
        Process initializer = null;
        Process server = null;
        try {
            List<String> initialize = new ArrayList<>(common);
            initialize.add("--initialize-insecure");
            initializer = new ProcessBuilder(initialize).redirectErrorStream(true).redirectOutput(root.resolve("initialize.log").toFile()).start();
            if (!initializer.waitFor(60, TimeUnit.SECONDS) || initializer.exitValue() != 0) {
                throw new IllegalStateException("Could not initialize isolated test MySQL; see " + root.resolve("server.log"));
            }
            List<String> launch = new ArrayList<>(common);
            launch.addAll(List.of("--port=" + port, "--bind-address=127.0.0.1", "--mysqlx=0", "--skip-name-resolve",
                    "--default-time-zone=+00:00", "--innodb-buffer-pool-size=32M", "--max-connections=30",
                    "--init-file=" + grantFile, "--pid-file=" + root.resolve("server.pid")));
            server = new ProcessBuilder(launch).redirectErrorStream(true).redirectOutput(root.resolve("console.log").toFile()).start();
            String baseUrl = "jdbc:mysql://127.0.0.1:" + port;
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            Exception last = null;
            while (System.nanoTime() < deadline && server.isAlive()) {
                try (Connection connection = DriverManager.getConnection(baseUrl + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", "root", "")) {
                    connection.createStatement().execute("CREATE DATABASE harness CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    Files.deleteIfExists(grantFile);
                    return new IsolatedMysql(root, server, baseUrl);
                } catch (Exception ex) { last = ex; Thread.sleep(100); }
            }
            throw new IllegalStateException("Isolated test MySQL did not become ready", last);
        } catch (Exception ex) {
            if (initializer != null) stopProcess(initializer);
            if (server != null) stopProcess(server);
            try { removeOwnedDirectory(root); } catch (Exception cleanup) { ex.addSuppressed(cleanup); }
            throw ex;
        }
    }

    public DataSource dataSource() { return dataSource; }

    public void execute(String... statements) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
    }

    public void applyResource(String path) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(path));
        }
    }

    public void createTablesFromSchema(String... names) throws Exception {
        String schema = new ClassPathResource("db/schema.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        for (String name : names) {
            var matcher = Pattern.compile("(?is)CREATE TABLE IF NOT EXISTS `?" + Pattern.quote(name)
                    + "`?\\s*\\(.*?\\n\\) ENGINE[^;]*;").matcher(schema);
            if (!matcher.find()) throw new IllegalStateException("Cannot find final installation table: " + name);
            execute(matcher.group());
        }
    }

    private static Path executable() {
        String file = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "mysqld.exe" : "mysqld";
        for (String directory : System.getenv().getOrDefault("PATH", "").split(Pattern.quote(java.io.File.pathSeparator))) {
            Path candidate = Path.of(directory).resolve(file).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
        }
        throw new IllegalStateException("Install MySQL 8 and add mysqld to PATH to run isolated MySQL tests");
    }

    @Override public void close() throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        } catch (java.sql.SQLException ignored) { /* MySQL can close this connection as shutdown starts. */ }
        if (!server.waitFor(10, TimeUnit.SECONDS)) stopProcess(server);
        removeOwnedDirectory(root);
    }

    private static void stopProcess(Process process) throws InterruptedException {
        List<ProcessHandle> children = process.descendants().toList();
        for (ProcessHandle child : children) child.destroyForcibly();
        if (process.isAlive()) process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
        for (ProcessHandle child : children) {
            try { child.onExit().get(10, TimeUnit.SECONDS); } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {}
        }
    }

    private static void removeOwnedDirectory(Path directory) throws IOException {
        Path target = Path.of("target").toAbsolutePath().normalize();
        Path resolved = directory.toAbsolutePath().normalize();
        if (!resolved.startsWith(target) || !resolved.getFileName().toString().startsWith("isolated-email-mysql-")) {
            throw new IllegalStateException("Refusing to clean a directory outside the isolated test workspace");
        }
        if (!Files.exists(resolved)) return;
        IOException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try (var entries = Files.walk(resolved)) {
                for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
                return;
            } catch (IOException ex) {
                last = ex;
                try { Thread.sleep(100); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }
        throw last;
    }
}
