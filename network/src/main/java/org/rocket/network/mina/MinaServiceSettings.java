package org.rocket.network.mina;

import org.apache.mina.core.filterchain.DefaultIoFilterChainBuilder;
import org.apache.mina.transport.socket.SocketSessionConfig;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;

public class MinaServiceSettings {
    public final DefaultIoFilterChainBuilder filter;
    public final SocketSessionConfig sessionConfig;

    public MinaServiceSettings(NioSocketAcceptor server){
        this.filter = server.getFilterChain();
        this.sessionConfig = server.getSessionConfig();
    }
}
