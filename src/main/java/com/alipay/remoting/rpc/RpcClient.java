/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.remoting.rpc;

import com.alipay.remoting.AbstractBoltClient;
import com.alipay.remoting.Connection;
import com.alipay.remoting.ConnectionEventHandler;
import com.alipay.remoting.ConnectionEventListener;
import com.alipay.remoting.ConnectionEventProcessor;
import com.alipay.remoting.ConnectionEventType;
import com.alipay.remoting.ConnectionMonitorStrategy;
import com.alipay.remoting.ConnectionSelectStrategy;
import com.alipay.remoting.DefaultClientConnectionManager;
import com.alipay.remoting.DefaultConnectionManager;
import com.alipay.remoting.DefaultConnectionMonitor;
import com.alipay.remoting.InvokeCallback;
import com.alipay.remoting.InvokeContext;
import com.alipay.remoting.LifeCycleException;
import com.alipay.remoting.RandomSelectStrategy;
import com.alipay.remoting.ReconnectManager;
import com.alipay.remoting.Reconnector;
import com.alipay.remoting.RemotingAddressParser;
import com.alipay.remoting.ScheduledDisconnectStrategy;
import com.alipay.remoting.Url;
import com.alipay.remoting.config.BoltGenericOption;
import com.alipay.remoting.config.switches.GlobalSwitch;
import com.alipay.remoting.exception.RemotingException;
import com.alipay.remoting.log.BoltLoggerFactory;
import com.alipay.remoting.rpc.protocol.UserProcessor;
import com.alipay.remoting.rpc.protocol.UserProcessorRegisterHelper;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client for Rpc.
 *
 * @author jiangping
 * @version $Id: RpcClient.java, v 0.1 2015-9-23 PM4:03:28 tao Exp $
 */
public class RpcClient extends AbstractBoltClient {

    private static final Logger logger = BoltLoggerFactory.getLogger("RpcRemoting");

    private final RpcTaskScanner taskScanner;
    private final ConcurrentHashMap<String, UserProcessor<?>> userProcessors;
    private final ConnectionEventHandler connectionEventHandler;
    private final ConnectionEventListener connectionEventListener;
    // used in RpcClientAdapter (bolt-tr-adapter)
    protected RpcRemoting rpcRemoting;
    private DefaultClientConnectionManager connectionManager;
    private Reconnector reconnectManager;
    private RemotingAddressParser addressParser;
    private DefaultConnectionMonitor connectionMonitor;
    private ConnectionMonitorStrategy monitorStrategy;

    public RpcClient() {
        this.taskScanner = new RpcTaskScanner();
        this.userProcessors = new ConcurrentHashMap<>();
        this.connectionEventHandler = new RpcConnectionEventHandler(switches());
        this.connectionEventListener = new ConnectionEventListener();
    }

    /**
     * Please use {@link RpcClient#startup()} instead
     */
    @Deprecated
    public void init() {
        startup();
    }

    /**
     * Shutdown.
     * <p>
     * Notice:<br>
     * <li>Rpc client can not be used any more after shutdown.
     * <li>If you need, you should destroy it, and instantiate another one.
     */
    @Override
    public void shutdown() {
        super.shutdown();

        this.connectionManager.shutdown();
        logger.warn("Close all connections from client side!");
        this.taskScanner.shutdown();
        logger.warn("Rpc client shutdown!");
        if (reconnectManager != null) {
            reconnectManager.shutdown();
        }
        if (connectionMonitor != null) {
            connectionMonitor.shutdown();
        }
    }

    @Override
    public void startup() throws LifeCycleException {
        super.startup();

        if (this.addressParser == null) {
            this.addressParser = new RpcAddressParser();
        }

        ConnectionSelectStrategy connectionSelectStrategy = option(BoltGenericOption.CONNECTION_SELECT_STRATEGY);
        if (connectionSelectStrategy == null) {
            connectionSelectStrategy = new RandomSelectStrategy(switches());
        }
        // 连接管理器
        this.connectionManager = new DefaultClientConnectionManager(connectionSelectStrategy,
                new RpcConnectionFactory(userProcessors, this), connectionEventHandler,
                connectionEventListener, switches());
        this.connectionManager.setAddressParser(this.addressParser);
        this.connectionManager.startup();
        this.rpcRemoting = new RpcClientRemoting(new RpcCommandFactory(), this.addressParser, this.connectionManager);
        this.taskScanner.add(this.connectionManager);
        this.taskScanner.startup();

        if (switches().isOn(GlobalSwitch.CONN_MONITOR_SWITCH)) {
            if (monitorStrategy == null) {
                connectionMonitor = new DefaultConnectionMonitor(new ScheduledDisconnectStrategy(), this.connectionManager);
            } else {
                connectionMonitor = new DefaultConnectionMonitor(monitorStrategy, this.connectionManager);
            }
            connectionMonitor.startup();
            logger.warn("Switch on connection monitor");
        }
        if (switches().isOn(GlobalSwitch.CONN_RECONNECT_SWITCH)) {
            reconnectManager = new ReconnectManager(connectionManager);
            reconnectManager.startup();

            connectionEventHandler.setReconnector(reconnectManager);
            logger.warn("Switch on reconnect manager");
        }
    }

    @Override
    public void oneway(final String address, final Object request) throws RemotingException,
            InterruptedException {
        this.rpcRemoting.oneway(address, request, null);
    }

    @Override
    public void oneway(final String address, final Object request, final InvokeContext invokeContext)
            throws RemotingException, InterruptedException {
        this.rpcRemoting.oneway(address, request, invokeContext);
    }

    @Override
    public void oneway(final Url url, final Object request) throws RemotingException,
            InterruptedException {
        this.rpcRemoting.oneway(url, request, null);
    }

    @Override
    public void oneway(final Url url, final Object request, final InvokeContext invokeContext)
            throws RemotingException,
            InterruptedException {
        this.rpcRemoting.oneway(url, request, invokeContext);
    }

    @Override
    public void oneway(final Connection conn, final Object request) throws RemotingException {
        this.rpcRemoting.oneway(conn, request, null);
    }

    @Override
    public void oneway(final Connection conn, final Object request,
                       final InvokeContext invokeContext) throws RemotingException {
        this.rpcRemoting.oneway(conn, request, invokeContext);
    }

    @Override
    public Object invokeSync(final String address, final Object request, final int timeoutMillis)
            throws RemotingException, InterruptedException {
        return this.rpcRemoting.invokeSync(address, request, null, timeoutMillis);
    }

    @Override
    public Object invokeSync(final String address, final Object request,
                             final InvokeContext invokeContext, final int timeoutMillis)
            throws RemotingException, InterruptedException {
        return this.rpcRemoting.invokeSync(address, request, invokeContext, timeoutMillis);
    }

    @Override
    public Object invokeSync(final Url url, final Object request, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        return this.invokeSync(url, request, null, timeoutMillis);
    }

    @Override
    public Object invokeSync(final Url url, final Object request,
                             final InvokeContext invokeContext, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        return this.rpcRemoting.invokeSync(url, request, invokeContext, timeoutMillis);
    }

    @Override
    public Object invokeSync(final Connection conn, final Object request, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        return this.rpcRemoting.invokeSync(conn, request, null, timeoutMillis);
    }

    @Override
    public Object invokeSync(final Connection conn, final Object request,
                             final InvokeContext invokeContext, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        return this.rpcRemoting.invokeSync(conn, request, invokeContext, timeoutMillis);
    }

    @Override
    public RpcResponseFuture invokeWithFuture(final String address, final Object request,
                                              final int timeoutMillis) throws RemotingException,
            InterruptedException {
        return this.rpcRemoting.invokeWithFuture(address, request, null, timeoutMillis);
    }

    @Override
    public RpcResponseFuture invokeWithFuture(final String address, final Object request,
                                              final InvokeContext invokeContext,
                                              final int timeoutMillis) throws RemotingException,
            InterruptedException {
        return this.rpcRemoting.invokeWithFuture(address, request, invokeContext, timeoutMillis);
    }

    @Override
    public RpcResponseFuture invokeWithFuture(final Url url, final Object request,
                                              final int timeoutMillis) throws RemotingException,
            InterruptedException {
        return this.rpcRemoting.invokeWithFuture(url, request, null, timeoutMillis);
    }

    @Override
    public RpcResponseFuture invokeWithFuture(final Url url, final Object request,
                                              final InvokeContext invokeContext,
                                              final int timeoutMillis) throws RemotingException,
            InterruptedException {
        return this.rpcRemoting.invokeWithFuture(url, request, invokeContext, timeoutMillis);
    }

    @Override
    public RpcResponseFuture invokeWithFuture(final Connection conn, final Object request,
                                              int timeoutMillis) throws RemotingException {
        return this.rpcRemoting.invokeWithFuture(conn, request, null, timeoutMillis);
    }

    @Override
    public RpcResponseFuture invokeWithFuture(final Connection conn, final Object request,
                                              final InvokeContext invokeContext, int timeoutMillis)
            throws RemotingException {
        return this.rpcRemoting.invokeWithFuture(conn, request, invokeContext, timeoutMillis);
    }

    @Override
    public void invokeWithCallback(final String address, final Object request,
                                   final InvokeCallback invokeCallback, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        this.rpcRemoting.invokeWithCallback(address, request, null, invokeCallback, timeoutMillis);
    }

    @Override
    public void invokeWithCallback(final String address, final Object request,
                                   final InvokeContext invokeContext,
                                   final InvokeCallback invokeCallback, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        this.rpcRemoting.invokeWithCallback(address, request, invokeContext, invokeCallback,
                timeoutMillis);
    }

    @Override
    public void invokeWithCallback(final Url url, final Object request,
                                   final InvokeCallback invokeCallback, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        this.rpcRemoting.invokeWithCallback(url, request, null, invokeCallback, timeoutMillis);
    }

    @Override
    public void invokeWithCallback(final Url url, final Object request,
                                   final InvokeContext invokeContext,
                                   final InvokeCallback invokeCallback, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        this.rpcRemoting.invokeWithCallback(url, request, invokeContext, invokeCallback,
                timeoutMillis);
    }

    @Override
    public void invokeWithCallback(final Connection conn, final Object request,
                                   final InvokeCallback invokeCallback, final int timeoutMillis)
            throws RemotingException {
        this.rpcRemoting.invokeWithCallback(conn, request, null, invokeCallback, timeoutMillis);
    }

    @Override
    public void invokeWithCallback(final Connection conn, final Object request,
                                   final InvokeContext invokeContext,
                                   final InvokeCallback invokeCallback, final int timeoutMillis)
            throws RemotingException {
        this.rpcRemoting.invokeWithCallback(conn, request, invokeContext, invokeCallback,
                timeoutMillis);
    }

    @Override
    public void addConnectionEventProcessor(ConnectionEventType type,
                                            ConnectionEventProcessor processor) {
        this.connectionEventListener.addConnectionEventProcessor(type, processor);
    }

    @Override
    public void registerUserProcessor(UserProcessor<?> processor) {
        UserProcessorRegisterHelper.registerUserProcessor(processor, this.userProcessors);
    }

    @Override
    public Connection createStandaloneConnection(String ip, int port, int connectTimeout)
            throws RemotingException {
        return this.connectionManager.create(ip, port, connectTimeout);
    }

    @Override
    public Connection createStandaloneConnection(String address, int connectTimeout)
            throws RemotingException {
        return this.connectionManager.create(address, connectTimeout);
    }

    @Override
    public void closeStandaloneConnection(Connection conn) {
        if (null != conn) {
            conn.close();
        }
    }

    @Override
    public Connection getConnection(String address, int connectTimeout) throws RemotingException,
            InterruptedException {
        Url url = this.addressParser.parse(address);
        return this.getConnection(url, connectTimeout);
    }

    @Override
    public Connection getConnection(Url url, int connectTimeout) throws RemotingException,
            InterruptedException {
        url.setConnectTimeout(connectTimeout);
        return this.connectionManager.getAndCreateIfAbsent(url);
    }

    @Override
    public Map<String, List<Connection>> getAllManagedConnections() {
        return this.connectionManager.getAll();
    }

    @Override
    public boolean checkConnection(String address) {
        Url url = this.addressParser.parse(address);
        Connection conn = this.connectionManager.get(url.getUniqueKey());
        try {
            this.connectionManager.check(conn);
        } catch (Exception e) {
            logger.warn("check failed. connection: {}", conn, e);
            return false;
        }
        return true;
    }

    /**
     * Close all connections of a address
     *
     * @param addr
     */
    public void closeConnection(String addr) {
        Url url = this.addressParser.parse(addr);
        if (switches().isOn(GlobalSwitch.CONN_RECONNECT_SWITCH) && reconnectManager != null) {
            reconnectManager.disableReconnect(url);
        }
        this.connectionManager.remove(url.getUniqueKey());
    }

    @Override
    public void closeConnection(Url url) {
        if (switches().isOn(GlobalSwitch.CONN_RECONNECT_SWITCH) && reconnectManager != null) {
            reconnectManager.disableReconnect(url);
        }
        this.connectionManager.remove(url.getUniqueKey());
    }

    @Override
    public void enableConnHeartbeat(String address) {
        Url url = this.addressParser.parse(address);
        this.enableConnHeartbeat(url);
    }

    @Override
    public void enableConnHeartbeat(Url url) {
        if (null != url) {
            this.connectionManager.enableHeartbeat(this.connectionManager.get(url.getUniqueKey()));
        }
    }

    @Override
    public void disableConnHeartbeat(String address) {
        Url url = this.addressParser.parse(address);
        this.disableConnHeartbeat(url);
    }

    @Override
    public void disableConnHeartbeat(Url url) {
        if (null != url) {
            this.connectionManager.disableHeartbeat(this.connectionManager.get(url.getUniqueKey()));
        }
    }

    @Override
    public void enableReconnectSwitch() {
        this.switches().turnOn(GlobalSwitch.CONN_RECONNECT_SWITCH);
    }

    @Override
    public void disableReconnectSwith() {
        this.switches().turnOff(GlobalSwitch.CONN_RECONNECT_SWITCH);
    }

    @Override
    public boolean isReconnectSwitchOn() {
        return this.switches().isOn(GlobalSwitch.CONN_RECONNECT_SWITCH);
    }

    @Override
    public void enableConnectionMonitorSwitch() {
        this.switches().turnOn(GlobalSwitch.CONN_MONITOR_SWITCH);
    }

    @Override
    public void disableConnectionMonitorSwitch() {
        this.switches().turnOff(GlobalSwitch.CONN_MONITOR_SWITCH);
    }

    @Override
    public boolean isConnectionMonitorSwitchOn() {
        return this.switches().isOn(GlobalSwitch.CONN_MONITOR_SWITCH);
    }

    @Override
    public DefaultConnectionManager getConnectionManager() {
        return this.connectionManager;
    }

    @Override
    public RemotingAddressParser getAddressParser() {
        return this.addressParser;
    }

    @Override
    public void setAddressParser(RemotingAddressParser addressParser) {
        this.addressParser = addressParser;
    }

    @Override
    public void setMonitorStrategy(ConnectionMonitorStrategy monitorStrategy) {
        this.monitorStrategy = monitorStrategy;
    }
}
