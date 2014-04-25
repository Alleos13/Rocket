package org.rocket.network.mina;

import com.google.common.collect.ImmutableSet;
import net.engio.mbassy.IPublicationErrorHandler;
import net.engio.mbassy.PublicationError;
import net.engio.mbassy.bus.IMessageBus;
import org.apache.mina.core.filterchain.IoFilterAdapter;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.AttributeKey;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;
import org.fungsi.concurrent.Timer;
import org.fungsi.concurrent.Timers;
import org.rocket.Service;
import org.rocket.ServiceContext;
import org.rocket.network.NetworkCommand;
import org.rocket.network.NetworkService;
import org.rocket.network.event.*;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.google.common.base.Throwables.propagate;

public class MinaService<C extends MinaClient> implements NetworkService<C>, IPublicationErrorHandler {

    private final BiFunction<IoSession, MinaService<C>, C> clientFactory;
    private final IMessageBus<NetworkEvent<C>, ?> eventBus;
    private final ScheduledExecutorService scheduler;
    private final Logger logger;
    private final Set<C> clients = new HashSet<>();

    private final NioSocketAcceptor server;

    public MinaService(BiFunction<IoSession, MinaService<C>, C> clientFactory, IMessageBus<NetworkEvent<C>, ?> eventBus, Consumer<MinaServiceSettings> initializer, SocketAddress localAddr, ScheduledExecutorService scheduler, Logger logger) {
        this.clientFactory = clientFactory;
        this.eventBus = eventBus;
        this.scheduler = scheduler;
        this.logger = logger;
        this.eventBus.addErrorHandler(this);

        this.server = new NioSocketAcceptor();
        server.setDefaultLocalAddress(localAddr);

        MinaServiceSettings settings = new MinaServiceSettings(server);
        settings.filters.addLast(Clients.class.getName(), new Clients());
        initializer.accept(settings);
        server.setHandler(new Dispatch());
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    public Timer newTimer() {
        return Timers.wrap(getScheduler());
    }

    @Override
    public int getActualConnectedClients() {
        return clients.size();
    }

    @Override
    public int getMaxConnectedClients() {
        return server.getStatistics().getLargestManagedSessionCount();
    }

    @Override
    public ImmutableSet<C> getClients() {
        return ImmutableSet.copyOf(clients);
    }

    @Override
    public NetworkCommand broadcast(Stream<C> clients, Object o) {
        return new BroadcastCommand(clients.map(it -> it.session), o, this::newTimer);
    }

    @Override
    public IMessageBus<NetworkEvent<C>, ?> getEventBus() {
        return eventBus;
    }

    @Override
    public Optional<Class<? extends Service>> dependsOn() {
        return Optional.empty();
    }

    @Override
    public void start(ServiceContext ctx) {
        try {
            logger.debug("starting...");
            server.bind();
            logger.info("started");
        }catch(IOException e){
            propagate(e);
        }
    }

    @Override
    public void stop(ServiceContext ctx) {
        logger.debug("stopping...");
        server.unbind();
        server.dispose(true);
        logger.info("stopped");
    }

    @Override
    public void handleError(PublicationError error) {
        Throwable cause = error.getCause();

        if (cause instanceof Error) {
            throw (Error) cause; // an error should not be catched
        }

        @SuppressWarnings("unchecked")
        C client = ((NetworkEvent<C>) error.getPublishedObject()).getClient();

        eventBus.post(new RecoverEvent<>(client, cause)).now();
        logger.error("unhandled exception", cause);
    }

    final AttributeKey ATTR = new AttributeKey(MinaClient.class, MinaService.class.getName() + "$Clients.ATTR." + this.hashCode());

    class Clients extends IoFilterAdapter {

        @Override
        public void sessionCreated(NextFilter nextFilter, IoSession session){
            C client = clientFactory.apply(session, MinaService.this);
            clients.add(client);
            session.setAttribute(ATTR, client);

            nextFilter.sessionCreated(session);
        }

        @Override
        public void filterClose(NextFilter nextFilter, IoSession session){
            @SuppressWarnings("unchecked")
            C client = (C) session.getAttribute(ATTR);
            clients.remove(client);

            nextFilter.filterClose(session);
        }
    }

    class Dispatch extends IoHandlerAdapter {
        @Override
        public void sessionOpened(IoSession session) throws Exception {
            @SuppressWarnings("unchecked")
            C client = (C) session.getAttribute(ATTR);
            eventBus.post(new ConnectEvent<>(client)).now();
        }

        @Override
        public void sessionClosed(IoSession session) throws Exception {
            @SuppressWarnings("unchecked")
            C client = (C) session.removeAttribute(ATTR);
            eventBus.post(new DisconnectEvent<>(client)).now();
        }

        @Override
        public void messageReceived(IoSession session, Object msg) throws Exception {
            @SuppressWarnings("unchecked")
            C client = (C) session.getAttribute(ATTR);
            eventBus.post(new ReceiveEvent<>(client, msg)).now();
        }

        @Override
        public void exceptionCaught(IoSession session, Throwable cause) throws Exception {
            @SuppressWarnings("unchecked")
            C client = (C) session.getAttribute(ATTR);
            eventBus.post(new RecoverEvent<>(client, cause)).now();
        }
    }
}
