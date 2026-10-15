import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;

/** Standalone test fixture: no Spring, MySQL, Redis, or production configuration. */
public class LauncherProbe {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv("SERVER_PORT"));
        try (ServerSocket server = new ServerSocket(port, 10, InetAddress.getByName("127.0.0.1"))) {
            if (!"quiet".equals(System.getenv("LAUNCHER_PROBE_MODE"))) {
                Thread.sleep(1000);
                System.out.println("Tomcat started on port " + server.getLocalPort() + " (http)");
                System.out.println("Started LauncherProbe in 1.0 seconds");
            }
            Thread.sleep(Long.MAX_VALUE);
        } catch (BindException ex) {
            System.out.println("Web server failed to start. Port " + port + " was already in use.");
            System.exit(1);
        }
    }
}
