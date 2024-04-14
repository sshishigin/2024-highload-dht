package ru.vk.itmo.test.shishiginstepan.server;

import one.nio.http.*;
import one.nio.server.AcceptorConfig;
import org.apache.log4j.Logger;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.shishiginstepan.dao.EntryWithTimestamp;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Server extends HttpServer {
    private final DistributedDao dao;
    private static final String INNER_REQUEST_HEADER = "X-inner-request: ";
    private static final String TIMESTAMP_HEADER = "X-timestamp: ";


    private final Logger logger = Logger.getLogger("lsm-db-server");

    private final ExecutorService executor;
    private static final Duration defaultTimeout = Duration.of(200, ChronoUnit.MILLIS);
    private static final ZoneId ServerZoneId = ZoneId.of("+0");

    private static final ThreadFactory threadFactory = new ThreadFactory() {
        private final AtomicInteger workerNamingCounter = new AtomicInteger(0);

        @Override
        public Thread newThread(@Nonnull Runnable r) {
            return new Thread(r, "lsm-db-worker-" + workerNamingCounter.getAndIncrement());
        }
    };

    public Server(ServiceConfig config, DistributedDao dao) throws IOException {
        super(configFromServiceConfig(config));
        this.dao = dao;
        //TODO подумать какое значение будет разумным
        BlockingQueue<Runnable> requestQueue = new ArrayBlockingQueue<>(100);
        int processors = Runtime.getRuntime().availableProcessors();
        this.executor = new ThreadPoolExecutor(
                processors,
                processors,
                32,
                TimeUnit.SECONDS,
                requestQueue,
                threadFactory
        );
    }

    private static HttpServerConfig configFromServiceConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.reusePort = true;
        acceptorConfig.port = serviceConfig.selfPort();

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        LocalDateTime requestExpirationDate = LocalDateTime.now(ServerZoneId).plus(defaultTimeout);
        try {
            executor.execute(() -> {
                try {
                    handleRequestWithExceptions(request, session, requestExpirationDate);
                } catch (IOException exceptionHandlingException) {
                    logger.error(exceptionHandlingException.initCause(exceptionHandlingException));
                    session.scheduleClose();
                }
            });
        } catch (RejectedExecutionException e) {
            logger.error(e);
            session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
        }
    }

    private void handleRequestWithExceptions(Request request,
                                             HttpSession session,
                                             LocalDateTime requestExpirationDate) throws IOException {
        try {
            handleRequestInOtherThread(request, session, requestExpirationDate);
        } catch (IOException e) {
            logger.error(e);
            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        } catch (HttpException | DistributedDao.ClusterLimitExceeded e) {
            logger.error(e);
            session.sendError(Response.BAD_REQUEST, null);
        } catch (DistributedDao.NoConsensus e) {
            logger.error(e);
            session.sendResponse(new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY));
        }
    }

    private void handleRequestInOtherThread(
            Request request,
            HttpSession session,
            LocalDateTime requestExpirationDate
    ) throws IOException, HttpException {
        if (LocalDateTime.now(ServerZoneId).isAfter(requestExpirationDate)) {
            session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
        } else {
            MemorySegment key = MemorySegment.ofArray(request.getParameter("id=").getBytes(StandardCharsets.UTF_8));
            String rawAck = request.getParameter("ack=");
            String rawFrom = request.getParameter("from=");
            Integer ack = rawAck == null ? 0 : Integer.parseInt(rawAck);
            Integer from = rawFrom == null ? 0 : Integer.parseInt(rawFrom);

            switch (request.getMethod()) {
                case Request.METHOD_GET -> {
                    if (request.getHeader(INNER_REQUEST_HEADER) == null) {
                        dao.getByQuorum(key, ack, from, session);
                    } else {
                        EntryWithTimestamp<MemorySegment> entry = dao.getLocal(key);
                        Response response;
                        if (entry.value()==null) {
                            response = new Response(Response.NOT_FOUND, Response.EMPTY);
                            response.addHeader(TIMESTAMP_HEADER+entry.timestamp());
                            session.sendResponse(
                                   response
                            );
                            return;
                        }
                        response = new Response(Response.OK,entry.value().toArray(ValueLayout.JAVA_BYTE));
                        response.addHeader(TIMESTAMP_HEADER+entry.timestamp());
                        session.sendResponse(response);
                    }
                }
                case Request.METHOD_PUT -> {
                    if (request.getHeader(INNER_REQUEST_HEADER) == null) {
                        EntryWithTimestamp<MemorySegment> entry = new EntryWithTimestamp<>(key, MemorySegment.ofArray(request.getBody()), System.currentTimeMillis());
                        dao.upsertByQuorum(entry, ack, from, session);
                    } else {
                        Long timestamp = Long.parseLong(request.getHeader(TIMESTAMP_HEADER));
                        EntryWithTimestamp<MemorySegment> entry = new EntryWithTimestamp<>(key, MemorySegment.ofArray(request.getBody()), timestamp);
                        dao.upsertLocal(entry);
                        session.sendResponse(new Response(Response.CREATED, Response.EMPTY));
                    }
                }
                case Request.METHOD_DELETE -> {
                    if (request.getHeader(INNER_REQUEST_HEADER) == null) {
                        EntryWithTimestamp<MemorySegment> entry = new EntryWithTimestamp<>(key, null, System.currentTimeMillis());
                        dao.upsertByQuorum(entry, ack, from, session);
                    } else {
                        Long timestamp = Long.parseLong(request.getHeader(TIMESTAMP_HEADER));
                        EntryWithTimestamp<MemorySegment> entry = new EntryWithTimestamp<>(key, null, timestamp);
                        dao.upsertLocal(entry);
                        session.sendResponse(new Response(Response.CREATED, Response.EMPTY));
                    }
                }
                default -> session.sendResponse(notAllowed());
            }
        }
    }


    public Response notAllowed() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }

    @Override
    public synchronized void stop() {
        super.stop();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    logger.error("Pool did not terminate");
                }
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
