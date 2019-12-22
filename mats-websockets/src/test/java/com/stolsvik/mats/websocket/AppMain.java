package com.stolsvik.mats.websocket;

import java.io.IOException;
import java.net.URL;
import java.security.Principal;
import java.util.Collections;
import java.util.function.Function;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpoint;

import com.stolsvik.mats.impl.jms.JmsMatsFactory;
import com.stolsvik.mats.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import com.stolsvik.mats.serial.json.MatsSerializer_DefaultJson;
import com.stolsvik.mats.util_activemq.MatsLocalVmActiveMq;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle.AbstractLifeCycleListener;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stolsvik.mats.MatsFactory;
import com.stolsvik.mats.websocket.MatsSocketServer.MatsSocketEndpoint;
import com.stolsvik.mats.websocket.impl.ClusterStoreAndForward;
import com.stolsvik.mats.websocket.impl.ClusterStoreAndForward_SQL;
import com.stolsvik.mats.websocket.impl.DefaultMatsSocketServer;

import ch.qos.logback.core.CoreConstants;

/**
 * @author Endre Stølsvik 2019-11-21 21:07 - http://stolsvik.com/, endre@stolsvik.com
 */
public class AppMain {

    private static final String CONTEXT_ATTRIBUTE_PORTNUMBER = "ServerPortNumber";

    private static final String COMMON_AMQ_NAME = "CommonAMQ";

    private static final Logger log = LoggerFactory.getLogger(AppMain.class);

    @WebListener
    public static class SCL_Endre implements ServletContextListener {

        private MatsSocketServer _matsSocketServer;
        private MatsFactory _matsFactory;
        private MatsLocalVmActiveMq _commonAmq;

        @Override
        public void contextInitialized(ServletContextEvent sce) {
            log.info("EndreXY contextInitialized: Test 1 2 3: " + sce);

            log.info("ServletContext: " + sce.getServletContext());

            // :: H2 DataBase
            JdbcDataSource h2Ds = new JdbcDataSource();
            h2Ds.setURL("jdbc:h2:~/temp/matsproject_dev_h2database/matssocket_dev;AUTO_SERVER=TRUE");
            JdbcConnectionPool dataSource = JdbcConnectionPool.create(h2Ds);

            // :: ActiveMQ and MatsFactory
            ActiveMQConnectionFactory connectionFactory = MatsLocalVmActiveMq.createConnectionFactory(COMMON_AMQ_NAME);
            MatsSerializer_DefaultJson matsSerializer = new MatsSerializer_DefaultJson();
            _matsFactory = JmsMatsFactory.createMatsFactory_JmsOnlyTransactions(
                    this.getClass().getSimpleName(), "*testing*",
                    new JmsMatsJmsSessionHandler_Pooling((s) -> connectionFactory.createConnection()),
                    matsSerializer);

            Integer portNumber = (Integer) sce.getServletContext().getAttribute(CONTEXT_ATTRIBUTE_PORTNUMBER);
            _matsFactory.getFactoryConfig().setConcurrency(1);
            _matsFactory.getFactoryConfig().setName("MF_Server_" + portNumber);
            _matsFactory.getFactoryConfig().setNodename("EndreBox_" + portNumber);

            // :: Test MatsEndpoint
            _matsFactory.single("Test.single", MatsDataTO.class, MatsDataTO.class, (processContext, incomingDto) -> {
                return new MatsDataTO(incomingDto.number, incomingDto.string + ":FromSimple", incomingDto.multiplier);
            });

            // :: Create MatsSocketServer
            // Cluster-stuff for the MatsSocketServer
            // ClusterStoreAndForward_DummySingleNode csaf = new ClusterStoreAndForward_DummySingleNode(matsFactory
            // .getFactoryConfig().getNodename());
            ClusterStoreAndForward_SQL csaf = ClusterStoreAndForward_SQL.create(dataSource, _matsFactory
                    .getFactoryConfig().getNodename());
            // Create the MatsSocketServer
            _matsSocketServer = getMatsSocketServer(sce, _matsFactory, csaf);
            sce.getServletContext().setAttribute(MatsSocketServer.class.getName(), _matsSocketServer);
            MatsSocketServer matsSocketServer = (MatsSocketServer) sce.getServletContext().getAttribute(
                    MatsSocketServer.class.getName());
            log.info("EndreXY: servletContext MatsSocketServer:" + matsSocketServer);

            // .. stick in an Authentication plugin
            Function<String, Principal> authToPrincipalFunction = authHeader -> {
                log.info("Resolving Authorization header to principal for header [" + authHeader + "].");
                long expires = Long.parseLong(authHeader.substring(authHeader.indexOf(':') + 1));
                if (expires < System.currentTimeMillis()) {
                    throw new IllegalStateException("This DummyAuth is too old.");
                }
                return new Principal() {
                    @Override
                    public String getName() {
                        return "Mr. Dummy Auth";
                    }

                    @Override
                    public String toString() {
                        return "DummyPrincipal:" + authHeader;
                    }
                };
            };
            _matsSocketServer.setAuthorizationToPrincipalFunction(authToPrincipalFunction);

            // :: MatsSocketEndpoint
            MatsSocketEndpoint<MatsSocketRequestDto, MatsDataTO, MatsDataTO, MatsSocketReplyDto> matsSocketEndpoint = _matsSocketServer
                    .matsSocketEndpoint("Test.single",
                            MatsSocketRequestDto.class, MatsDataTO.class, MatsDataTO.class, MatsSocketReplyDto.class,
                            (ctx, principal, msIncoming) -> {
                                log.info("Got MatsSocket request on MatsSocket EndpointId: "
                                        + ctx.getMatsSocketEndpointId());
                                log.info(" \\- Authorization: " + ctx.getAuthorization());
                                log.info(" \\- Principal:     " + ctx.getPrincipal());
                                log.info(" \\- Message:       " + msIncoming);
                                ctx.forwardCustom(new MatsDataTO(msIncoming.number, msIncoming.string),
                                        msg -> {
                                            msg.to(ctx.getMatsSocketEndpointId())
                                                    .interactive()
                                                    .nonPersistent()
                                                    .setTraceProperty("requestTimestamp", msIncoming.requestTimestamp);
                                        });
                            });
            matsSocketEndpoint.replyAdapter((ctx, matsReply) -> {
                log.info("Adapting message: " + matsReply);
                return new MatsSocketReplyDto(matsReply.string.length(), matsReply.number,
                        ctx.getMatsContext().getTraceProperty("requestTimestamp", Long.class));
            });
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce) {
            log.info("EndreXY contextDestroyed: Test 1 2 3: " + sce);
            _matsSocketServer.shutdown();
            _matsFactory.stop(1000);
            _commonAmq.close();
        }
    }

    private static MatsSocketServer getMatsSocketServer(ServletContextEvent sce, MatsFactory matsFactory,
            ClusterStoreAndForward clusterStoreAndForward) {
        Object serverContainerAttrib = sce.getServletContext().getAttribute(ServerContainer.class.getName());
        if (!(serverContainerAttrib instanceof ServerContainer)) {
            throw new AssertionError("Did not find '" + ServerContainer.class.getName() + "' object"
                    + " in ServletContext, but [" + serverContainerAttrib + "].");
        }

        ServerContainer wsServerContainer = (ServerContainer) serverContainerAttrib;
        return DefaultMatsSocketServer.createMatsSocketServer(
                wsServerContainer, matsFactory, clusterStoreAndForward);
    }

    @WebServlet("/test")
    public static class TestServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().println("Testing Servlet");
        }
    }

    public static String id(Object x) {
        return x.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(x));
    }

    @ServerEndpoint("/ws/json2")
    public static class TestWebSocket {
        @OnOpen
        public void myOnOpen(Session session, EndpointConfig endpointConfig) {
            log.info("WebSocket opened, session:" + session.getId() + ", endpointConfig:" + endpointConfig + ", this:"
                    + id(this));
        }

        @OnMessage
        public void myOnMessage(Session session, String txt) {
            log.info("WebSocket received message:" + txt + ", session:" + session.getId() + ", this:" + id(this));
        }

        @OnClose
        public void myOnClose(Session session, CloseReason reason) {
            log.info("WebSocket @OnClose, session:" + session.getId() + ", reason:" + reason.getReasonPhrase()
                    + ", this:" + id(this));
        }

        @OnError
        public void myOnError(Session session, Throwable t) {
            log.info("WebSocket @OnError, session:" + session.getId() + ", this:" + id(this), t);
        }
    }

    public static Server createServer(int port) {
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        webAppContext.setBaseResource(Resource.newClassPathResource("webapp"));
        webAppContext.setThrowUnavailableOnStartupException(true);

        webAppContext.getServletContext().setAttribute(CONTEXT_ATTRIBUTE_PORTNUMBER, port);

        // Override the default configurations, stripping down and adding AnnotationConfiguration.
        // https://www.eclipse.org/jetty/documentation/9.4.x/configuring-webapps.html
        // Note: The default resides in WebAppContext.DEFAULT_CONFIGURATION_CLASSES
        webAppContext.setConfigurations(new Configuration[] {
                // new WebInfConfiguration(),
                new WebXmlConfiguration(), // Evidently adds the DefaultServlet, as otherwise no read of "/webapp/"
                // new MetaInfConfiguration(),
                // new FragmentConfiguration(),
                new AnnotationConfiguration() // Adds Servlet annotation processing.
        });

        // :: Get Jetty to Scan project classes too: https://stackoverflow.com/a/26220672/39334
        // Find "this" location for current classes
        URL classes = AppMain.class.getProtectionDomain().getCodeSource().getLocation();
        // Set this location to be scanned.
        webAppContext.getMetaData().setWebInfClassesDirs(Collections.singletonList(Resource.newResource(classes)));

        Server server = new Server(port);

        // Add StatisticsHandler
        StatisticsHandler stats = new StatisticsHandler();
        stats.setHandler(webAppContext);
        server.setHandler(stats);

        // Add a Jetty Lifecycle Listener to cleanly shut down the MatsSocketServer.
        server.addLifeCycleListener(new AbstractLifeCycleListener() {
            @Override
            public void lifeCycleStopping(LifeCycle event) {
                log.info("XXXX lifeCycleStopping for " + port + ", event:" + event + ", context:" + webAppContext);
                log.info("  test.elg: "+webAppContext.getServletContext().getAttribute("test.elg"));
                MatsSocketServer matsSocketServer = (MatsSocketServer) webAppContext.getServletContext().getAttribute(MatsSocketServer.class
                        .getName());
                log.info("MatsSocketServer instance:" + matsSocketServer);
                matsSocketServer.shutdown();
            }
        });

        server.setStopTimeout(1000);
        server.setStopAtShutdown(true);
        return server;
    }

    public static void main(String... args) throws Exception {
        // Turn off LogBack's absurd SCI
        System.setProperty(CoreConstants.DISABLE_SERVLET_CONTAINER_INITIALIZER_KEY, "true");

        // Create common AMQ
        MatsLocalVmActiveMq inVmActiveMq = MatsLocalVmActiveMq.createInVmActiveMq(COMMON_AMQ_NAME);

        Server server1 = createServer(8080);
        Server server2 = createServer(8081);

        log.info("######### Starting server 1");
        server1.start();
        log.info("######### Starting server 2");
        server2.start();

        server1.join();
        server2.join();
    }
}
