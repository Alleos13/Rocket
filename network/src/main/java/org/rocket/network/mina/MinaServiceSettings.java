package org.rocket.network.mina;

import org.apache.mina.core.filterchain.DefaultIoFilterChainBuilder;
import org.apache.mina.transport.socket.SocketSessionConfig;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;

public class MinaServiceSettings {
    public final DefaultIoFilterChainBuilder filters;
    public final SocketSessionConfig session;

    public MinaServiceSettings(NioSocketAcceptor server){
        this.filters = server.getFilterChain();
        this.session = server.getSessionConfig();
    }
}
