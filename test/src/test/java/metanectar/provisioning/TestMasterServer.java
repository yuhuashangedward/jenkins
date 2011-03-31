package metanectar.provisioning;

import com.cloudbees.commons.metanectar.agent.Agent;
import com.cloudbees.commons.metanectar.agent.AgentStatusListener;
import com.cloudbees.commons.metanectar.agent.MetaNectarAgentProtocol;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import hudson.remoting.Channel;
import org.apache.commons.codec.binary.Base64;
import org.jenkinsci.main.modules.instance_identity.InstanceIdentity;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Emulate a master, for the purposes of establishing a connection between the Master and MetaNectar.
 *
 * @author Paul Sandoz
 */
public class TestMasterServer {
    private static final Logger LOGGER = Logger.getLogger(TestMasterServer.class.getName());

    final URL metaNectarEndpoint;

    final String organization;

    final URL endpoint;

    final InstanceIdentity id;

    final Agent agent;

    class Client extends MetaNectarAgentProtocol.Listener {
        Channel channel;

        @Override
        public URL getOurURL() throws IOException {
            return endpoint;
        }

        @Override
        public void onConnectingTo(URL address, X509Certificate identity) throws GeneralSecurityException, IOException {
            LOGGER.info("Connecting to: " + address);
        }

        @Override
        public void onConnectedTo(Channel channel, X509Certificate identity) throws IOException {
            LOGGER.info("Connected: " + channel);

            this.channel = channel;
            try {
                channel.join();
            } catch (InterruptedException ex) {
                LOGGER.log(Level.WARNING, "Channel was interrupted", ex);
            }
        }

        @Override
        public void onRefusal(MetaNectarAgentProtocol.GracefulConnectionRefusalException e) throws Exception {
            // Don't re-throw so that the agent keeps tr-trying
            LOGGER.log(Level.WARNING, "Server refused connection", e);
        }

        @Override
        public void onError(Exception e) throws Exception {
            LOGGER.log(Level.SEVERE, "Error connecting to server", e);
        }
    }

    class Resolver implements Agent.ConnectionResolver {
        public InetSocketAddress resolve() throws IOException {
            HttpURLConnection c = (HttpURLConnection)metaNectarEndpoint.openConnection();
            int port = c.getHeaderFieldInt("X-MetaNectar-Port", 0);

            return new InetSocketAddress(metaNectarEndpoint.getHost(), port);
        }
    }

    public TestMasterServer(URL metaNectarEndpoint, String organization) throws IOException {

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", new HttpHandler() {
            public void handle(HttpExchange httpExchange) throws IOException {
                Headers responseHeaders = httpExchange.getResponseHeaders();
                responseHeaders.add("X-Jenkins", "");

                responseHeaders.add("X-Instance-Identity", new String(Base64.encodeBase64(id.getPublic().getEncoded())));
                httpExchange.sendResponseHeaders(200, 0);
                httpExchange.close();
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        this.endpoint = new URL("http", "localhost", server.getAddress().getPort(), "");
        this.id = getId();
        this.metaNectarEndpoint = metaNectarEndpoint;
        this.organization = organization;

        MetaNectarAgentProtocol.Outbound p = new MetaNectarAgentProtocol.Outbound(
                MetaNectarAgentProtocol.getInstanceIdentityCertificate(id, endpoint.toExternalForm()),
                id.getPrivate(),
                new Client(),
                null);

        this.agent = new Agent(new AgentStatusListener.LoggerListener(LOGGER), new Resolver(), p);
    }


    // TODO copied from InstanceIdentity
    private InstanceIdentity getId() throws IOException {
        final KeyPair keys = generateKey();
        return new InstanceIdentity(File.createTempFile("key", null)) {
            public RSAPublicKey getPublic() {
                return (RSAPublicKey)keys.getPublic();
            }

            public RSAPrivateKey getPrivate() {
                return (RSAPrivateKey)keys.getPrivate();
            }
        };
    }

    // TODO copied from InstanceIdentity
    public KeyPair generateKey() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048,new SecureRandom()); // going beyond 2048 requires crypto extension
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e); // RSA algorithm should be always there
        }
    }

    public void setRetryInterval(int retryInterval) {
        agent.setRetryInterval(retryInterval);
    }

    public URL start() {
        new Thread(agent).start();
        return endpoint;
    }
}