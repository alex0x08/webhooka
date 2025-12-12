package com.Ox08.serious.experiments.webhooka;

import jakarta.servlet.*;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import org.apache.catalina.connector.*;
import org.jspecify.annotations.NonNull;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.context.ServletComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Webhooka service
 *
 * @author 0x08
 * @since 1.0
 */
@SpringBootApplication
@ServletComponentScan
@EnableScheduling
public class WebhookaApp {

    @Value("${app.webhooka.internal.http.port}")
    private int internalPort;

    private static final Logger LOG = LoggerFactory.getLogger("WEBHOOKA");

    @Bean
    public WebServerFactoryCustomizer<@NonNull TomcatServletWebServerFactory> tomcatCustomizer() {
        return (TomcatServletWebServerFactory factory) -> {
            // also listen on http
            final Connector connector = new Connector();
            connector.setPort(internalPort);
            
            factory.addAdditionalConnectors(connector);
        };
    }


    public static void main(String[] args) {
        LOG.info("Starting..");
        final SpringApplication sa = new SpringApplication(WebhookaApp.class);
        sa.setLogStartupInfo(false);
        sa.setBannerMode(Banner.Mode.OFF);
        sa.run(args);
    }


    @Component
    class WebhookaApiAccessFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain fc)
                throws IOException, ServletException {

            if (request instanceof HttpServletRequest req && response instanceof HttpServletResponse res) {
                if (req.getRequestURI().startsWith(req.getContextPath() + "/internal")
                        && request.getServerPort() != internalPort) {
                    LOG.warn("access to internal API from public denied.");
                    res.sendError(404);
                    return;
                }

                if (req.getRequestURI().startsWith(req.getContextPath() + "/api/hooks/public")
                        && request.getServerPort() == internalPort) {
                    LOG.warn("access to public API from internal denied.");
                    res.sendError(404);
                    return;
                }
            }

            fc.doFilter(request, response);
        }
    }

    @Service
    public static class ConnectionCheck {
        private boolean alive; // current connection state
        private long lastCheck; // last time checked (millis)
        @Value("${app.webhooka.connectExpireMin}")
        private int connectExpireMin;

        @Scheduled(initialDelay = 0, fixedDelay = 5000)
        public void dropCheck() {
            if (alive && System.currentTimeMillis() - lastCheck > 1000L * 60 * connectExpireMin) {
                alive = false;
                LOG.debug("internal connection lost");
            }
        }

        public synchronized void markAlive() {
            this.lastCheck = System.currentTimeMillis();
            if (!alive) {
                this.alive = true;
                LOG.debug("internal connection established");
            }
        }

        public boolean isNotAlive() {
            return !alive;
        }
    }

    /**
     * Сервлет для внутреннего API, вызываемого со стороны ERP
     */
    @WebServlet(urlPatterns = "/internal/api/hooks/*")
    public static class InternalWebhooksServlet extends HttpServlet {

        @Value("${app.webhooka.apiToken}")
        private String apiToken; // токен авторизации
        @Autowired
        private MessagesStore ms;
        @Autowired
        private ConnectionCheck cc;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws IOException {
            final String authToken = req.getHeader("X-Webhooka-Auth");
            if (authToken == null || authToken.isBlank()) {
                LOG.warn("No authentication token");
                resp.setStatus(403);
                return;
            }
            if (!apiToken.equals(authToken)) {
                LOG.warn("Invalid authentication token: {}", authToken);
                resp.setStatus(403);
                return;
            }

            // обязательно помечаем активность подключения со стороны ERP
            cc.markAlive();
            resp.setStatus(200);
            resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
            resp.setCharacterEncoding(StandardCharsets.UTF_8);
            final List<MessagesStore.InputMessage> pending = ms.getPendingMessages();
            if (pending.isEmpty()) {
                resp.flushBuffer();
                return;
            }
            final StringBuilder sb = new StringBuilder();
            for (MessagesStore.InputMessage m : pending) {
                // append ',' for elements after first
                if (!sb.isEmpty())
                    sb.append('\n');


                // assume message body is json object
                sb.append(m.messageBody());
            }
            sb.append('\n');
            if (LOG.isDebugEnabled())
                LOG.debug("transferred message, sz: {}", sb.length());

            resp.getWriter().write(sb.toString());
            resp.flushBuffer();
        }
    }

    /**
     * Сервлет для входящих сообщений - тот самый вебхук.
     */
    @WebServlet(urlPatterns = "/api/hooks/public/*")
    public static class WebhooksServlet extends HttpServlet {

        @Autowired
        private MessagesStore ms;
        @Autowired
        private ConnectionCheck cc;

        @Override
        protected void doHead(HttpServletRequest req, HttpServletResponse resp) {
            // если подключение к ERP живое - отдаем статус 200, если нет, то 500,
            // для того чтобы запустить повторную отправку со стороны внешних систем
            resp.setStatus(cc.isNotAlive() ? 500 : 200);
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
            // не используется со стороны Авито, только для тестов
            resp.setStatus(cc.isNotAlive() ? 500 : 200);
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws IOException {
            if (cc.isNotAlive()) {
                resp.setStatus(500);
                return;
            }
            final String requestBody = req.getReader().lines().collect(Collectors.joining());
            if (requestBody.isBlank()) {
                resp.setStatus(200);
                return;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("got webhook message: '{}'", requestBody);

            // повторная проверка на подключение к ERP
            if (cc.isNotAlive()) {
                resp.setStatus(500);
                return;
            }
            // если буфер не переполнен - сохраняем, если нет - возвращаем статус 500 и
            // тем самым запускаем повторный прием
            resp.setStatus(!ms.addMessage(requestBody) ? 500 : 200);

        }
    }
}
