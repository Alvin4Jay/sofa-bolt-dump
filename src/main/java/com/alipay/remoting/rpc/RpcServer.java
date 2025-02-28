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

import com.alipay.remoting.AbstractRemotingServer;
import com.alipay.remoting.CommandCode;
import com.alipay.remoting.Connection;
import com.alipay.remoting.ConnectionEventHandler;
import com.alipay.remoting.ConnectionEventListener;
import com.alipay.remoting.ConnectionEventProcessor;
import com.alipay.remoting.ConnectionEventType;
import com.alipay.remoting.ConnectionSelectStrategy;
import com.alipay.remoting.DefaultConnectionManager;
import com.alipay.remoting.DefaultServerConnectionManager;
import com.alipay.remoting.InvokeCallback;
import com.alipay.remoting.InvokeContext;
import com.alipay.remoting.NamedThreadFactory;
import com.alipay.remoting.ProtocolCode;
import com.alipay.remoting.ProtocolManager;
import com.alipay.remoting.RandomSelectStrategy;
import com.alipay.remoting.RemotingAddressParser;
import com.alipay.remoting.RemotingProcessor;
import com.alipay.remoting.RemotingServer;
import com.alipay.remoting.ServerIdleHandler;
import com.alipay.remoting.Url;
import com.alipay.remoting.codec.Codec;
import com.alipay.remoting.config.ConfigManager;
import com.alipay.remoting.config.switches.GlobalSwitch;
import com.alipay.remoting.exception.RemotingException;
import com.alipay.remoting.log.BoltLoggerFactory;
import com.alipay.remoting.rpc.protocol.UserProcessor;
import com.alipay.remoting.rpc.protocol.UserProcessorRegisterHelper;
import com.alipay.remoting.util.NettyEventLoopUtil;
import com.alipay.remoting.util.RemotingUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Server for Rpc.
 * <p>
 * Usage:
 * You can initialize RpcServer with one of the three constructors:
 * {@link #RpcServer(int)}, {@link #RpcServer(int, boolean)}, {@link #RpcServer(int, boolean, boolean)}
 * Then call start() to start a rpc server, and call stop() to stop a rpc server.
 * <p>
 * Notice:
 * Once rpc server has been stopped, it can never be start again. You should init another instance of RpcServer to use.
 *
 * @author jiangping
 * @version $Id: RpcServer.java, v 0.1 2015-8-31 PM5:22:22 tao Exp $
 */
public class RpcServer extends AbstractRemotingServer {

    /** logger */
    private static final Logger logger = BoltLoggerFactory.getLogger("RpcRemoting");
    /**
     * worker event loop group. Reuse I/O worker threads between rpc servers.
     */
    private static final EventLoopGroup workerGroup = NettyEventLoopUtil
            .newEventLoopGroup(Runtime.getRuntime().availableProcessors() * 2, // doubt 为什么是这个线程数
                    new NamedThreadFactory("Rpc-netty-server-worker", true));

    static {
        if (workerGroup instanceof NioEventLoopGroup) { // 设置io事件与task处理时间比例
            ((NioEventLoopGroup) workerGroup).setIoRatio(ConfigManager.netty_io_ratio());
        } else if (workerGroup instanceof EpollEventLoopGroup) {
            ((EpollEventLoopGroup) workerGroup).setIoRatio(ConfigManager.netty_io_ratio());
        }
    }

    /**
     * boss event loop group, boss group should not be daemon, need shutdown manually 实例级别 // doubt 为什么不能使用daemon
     */
    private final EventLoopGroup bossGroup = NettyEventLoopUtil.newEventLoopGroup(1,
                    new NamedThreadFactory("Rpc-netty-server-boss", false));

    /** rpc remoting--RpcServerRemoting */
    protected RpcRemoting rpcRemoting;

    /** server bootstrap */
    private ServerBootstrap bootstrap;

    /** channelFuture */
    private ChannelFuture channelFuture;

    /** connection event handler */
    private ConnectionEventHandler connectionEventHandler;

    /** connection event listener */
    private ConnectionEventListener connectionEventListener = new ConnectionEventListener();

    /** user processors of rpc server */
    private ConcurrentHashMap<String, UserProcessor<?>> userProcessors = new ConcurrentHashMap<String, UserProcessor<?>>(4);

    /** address parser to get custom args */
    private RemotingAddressParser addressParser;

    /** connection manager 可能为null*/
    private DefaultServerConnectionManager connectionManager;

    /** rpc codec */
    private Codec codec = new RpcCodec();

    /**
     * Construct a rpc server. <br>
     * <p>
     * Note:<br>
     * You can only use invoke methods with params {@link Connection}, for example {@link #invokeSync(Connection, Object, int)} <br>
     * Otherwise {@link UnsupportedOperationException} will be thrown.
     */
    public RpcServer(int port) {
        this(port, false);
    }

    /**
     * Construct a rpc server. <br>
     * <p>
     * Note:<br>
     * You can only use invoke methods with params {@link Connection}, for example {@link #invokeSync(Connection, Object, int)} <br>
     * Otherwise {@link UnsupportedOperationException} will be thrown.
     */
    public RpcServer(String ip, int port) {
        this(ip, port, false);
    }

    /**
     * Construct a rpc server. <br>
     *
     * <ul>
     * <li>You can enable connection management feature by specify @param manageConnection true.</li>
     * <ul>
     * <li>When connection management feature enabled, you can use all invoke methods with params {@link String}, {@link Url}, {@link Connection} methods.</li>
     * <li>When connection management feature disabled, you can only use invoke methods with params {@link Connection}, otherwise {@link UnsupportedOperationException} will be thrown.</li>
     * </ul>
     * </ul>
     *
     * @param port             listened port
     * @param manageConnection true to enable connection management feature
     */
    public RpcServer(int port, boolean manageConnection) {
        super(port);
        /* server connection management feature enabled or not, default value false, means disabled. */
        if (manageConnection) {
            this.switches().turnOn(GlobalSwitch.SERVER_MANAGE_CONNECTION_SWITCH);
        }
    }

    /**
     * Construct a rpc server. <br>
     *
     * <ul>
     * <li>You can enable connection management feature by specify @param manageConnection true.</li>
     * <ul>
     * <li>When connection management feature enabled, you can use all invoke methods with params {@link String}, {@link Url}, {@link Connection} methods.</li>
     * <li>When connection management feature disabled, you can only use invoke methods with params {@link Connection}, otherwise {@link UnsupportedOperationException} will be thrown.</li>
     * </ul>
     * </ul>
     *
     * @param port             listened port
     * @param manageConnection true to enable connection management feature
     */
    public RpcServer(String ip, int port, boolean manageConnection) {
        super(ip, port);
        /* server connection management feature enabled or not, default value false, means disabled. */
        if (manageConnection) {
            this.switches().turnOn(GlobalSwitch.SERVER_MANAGE_CONNECTION_SWITCH);
        }
    }

    /**
     * Construct a rpc server. <br>
     * <p>
     * You can construct a rpc server with synchronous or asynchronous stop strategy by {@param syncStop}.
     *
     * @param port             listened port
     * @param manageConnection manage connection
     * @param syncStop         true to enable stop in synchronous way
     */
    public RpcServer(int port, boolean manageConnection, boolean syncStop) {
        this(port, manageConnection);
        if (syncStop) {
            this.switches().turnOn(GlobalSwitch.SERVER_SYNC_STOP);
        }
    }

    @Override
    protected void doInit() {
        if (this.addressParser == null) {
            this.addressParser = new RpcAddressParser(); // 地址解析器
        }
        if (this.switches().isOn(GlobalSwitch.SERVER_MANAGE_CONNECTION_SWITCH)) {
            // in server side, do not care the connection service state, so use null instead of global switch
            ConnectionSelectStrategy connectionSelectStrategy = new RandomSelectStrategy(null);
            this.connectionManager = new DefaultServerConnectionManager(connectionSelectStrategy);
            this.connectionManager.startup();

            this.connectionEventHandler = new RpcConnectionEventHandler(switches()); // 连接事件处理器
            this.connectionEventHandler.setConnectionManager(this.connectionManager); // 设置连接管理器
            this.connectionEventHandler.setConnectionEventListener(this.connectionEventListener);  // 设置连接事件监听
        } else {
            this.connectionEventHandler = new ConnectionEventHandler(switches());
            this.connectionEventHandler.setConnectionEventListener(this.connectionEventListener);
        }
        initRpcRemoting();
        this.bootstrap = new ServerBootstrap();
        this.bootstrap.group(bossGroup, workerGroup)
                .channel(NettyEventLoopUtil.getServerSocketChannelClass()) // 获取ServerSocketChannel
                .option(ChannelOption.SO_BACKLOG, ConfigManager.tcp_so_backlog()) // 1024
                .option(ChannelOption.SO_REUSEADDR, ConfigManager.tcp_so_reuseaddr()) // true
                .childOption(ChannelOption.TCP_NODELAY, ConfigManager.tcp_nodelay()) // true
                .childOption(ChannelOption.SO_KEEPALIVE, ConfigManager.tcp_so_keepalive()); // true

        // set write buffer water mark
        initWriteBufferWaterMark(); // 设置channel写缓冲区high/low water mark

        // init byte buf allocator
        if (ConfigManager.netty_buffer_pooled()) { // buffer池化与否
            this.bootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        } else {
            this.bootstrap.option(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT)
                    .childOption(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT);
        }

        // enable trigger mode for epoll if need
        NettyEventLoopUtil.enableTriggeredMode(bootstrap); // 设置epoll触发模式

        final boolean idleSwitch = ConfigManager.tcp_idle_switch(); // 是否空闲检测
        final int idleTime = ConfigManager.tcp_server_idle(); // 空闲时间
        final ChannelHandler serverIdleHandler = new ServerIdleHandler(); // 服务端空闲检测
        final RpcHandler rpcHandler = new RpcHandler(true, this.userProcessors);
        this.bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {

            @Override
            protected void initChannel(SocketChannel channel) {
                System.out.println("init channel");
                ChannelPipeline pipeline = channel.pipeline();
                pipeline.addLast("decoder", codec.newDecoder());
                pipeline.addLast("encoder", codec.newEncoder());
                if (idleSwitch) { // 空闲检测
                    pipeline.addLast("idleStateHandler",
                            new IdleStateHandler(0, 0, idleTime, TimeUnit.MILLISECONDS));
                    pipeline.addLast("serverIdleHandler", serverIdleHandler);
                }
                pipeline.addLast("connectionEventHandler", connectionEventHandler);
                pipeline.addLast("handler", rpcHandler);
                createConnection(channel);
            }

            /**
             * create connection operation<br>
             * <ul>
             * <li>If flag manageConnection be true, use {@link DefaultConnectionManager} to add a new connection,
             * meanwhile bind it with the channel.</li>
             * <li>If flag manageConnection be false, just create a new connection and bind it with the channel.</li>
             * </ul>
             */
            private void createConnection(SocketChannel channel) {
                Url url = addressParser.parse(RemotingUtil.parseRemoteAddress(channel));
                if (switches().isOn(GlobalSwitch.SERVER_MANAGE_CONNECTION_SWITCH)) { // 服务端开启连接管理
                    connectionManager.add(new Connection(channel, url), url.getUniqueKey()); // 创建连接并加入连接池
                } else {
                    new Connection(channel, url); // channel绑定Connection
                }
                channel.pipeline().fireUserEventTriggered(ConnectionEventType.CONNECT);
            }
        });
    }

    @Override
    protected boolean doStart() throws InterruptedException {
        this.channelFuture = this.bootstrap.bind(new InetSocketAddress(ip(), port())).sync(); // 同步
        return this.channelFuture.isSuccess();
    }

    /**
     * Notice: only {@link GlobalSwitch#SERVER_MANAGE_CONNECTION_SWITCH} switch on, will close all connections.
     *
     * @see AbstractRemotingServer#doStop()
     */
    @Override
    protected boolean doStop() {
        if (null != this.channelFuture) {
            this.channelFuture.channel().close(); // 关闭channel
        }
        if (this.switches().isOn(GlobalSwitch.SERVER_SYNC_STOP)) { // 同步停止
            this.bossGroup.shutdownGracefully().awaitUninterruptibly();
        } else {
            this.bossGroup.shutdownGracefully();
        }
        if (this.switches().isOn(GlobalSwitch.SERVER_MANAGE_CONNECTION_SWITCH) && null != this.connectionManager) {
            this.connectionManager.shutdown(); // 关闭connection
            logger.warn("Close all connections from server side!");
        }
        logger.warn("Rpc Server stopped!");
        return true;
    }

    /** init rpc remoting */
    protected void initRpcRemoting() {
        this.rpcRemoting = new RpcServerRemoting(new RpcCommandFactory(), this.addressParser,
                this.connectionManager);
    }

    /**
     * @see RemotingServer#registerProcessor(byte, com.alipay.remoting.CommandCode, com.alipay.remoting.RemotingProcessor)
     */
    @Override
    public void registerProcessor(byte protocolCode, CommandCode cmd, RemotingProcessor<?> processor) {
        ProtocolManager.getProtocol(ProtocolCode.fromBytes(protocolCode)).getCommandHandler()
                .registerProcessor(cmd, processor);
    }

    /**
     * @see RemotingServer#registerDefaultExecutor(byte, ExecutorService)
     */
    @Override
    public void registerDefaultExecutor(byte protocolCode, ExecutorService executor) {
        ProtocolManager.getProtocol(ProtocolCode.fromBytes(protocolCode)).getCommandHandler()
                .registerDefaultExecutor(executor);
    }

    /**
     * Add processor to process connection event.
     *
     * @param type      connection event type
     * @param processor connection event processor
     */
    public void addConnectionEventProcessor(ConnectionEventType type,
                                            ConnectionEventProcessor processor) {
        this.connectionEventListener.addConnectionEventProcessor(type, processor);
    }

    /**
     * Use UserProcessorRegisterHelper{@link UserProcessorRegisterHelper} to help register user processor for server side.
     *
     * @see AbstractRemotingServer#registerUserProcessor(com.alipay.remoting.rpc.protocol.UserProcessor)
     */
    @Override
    public void registerUserProcessor(UserProcessor<?> processor) {
        UserProcessorRegisterHelper.registerUserProcessor(processor, this.userProcessors);
    }

    /**
     * One way invocation using a string address, address format example - 127.0.0.1:12200?key1=value1&key2=value2 <br>
     * <p>
     * Notice:<br>
     * <ol>
     * <li><b>DO NOT modify the request object concurrently when this method is called.</b></li>
     * <li>When do invocation, use the string address to find a available client connection, if none then throw exception</li>
     * <li>Unlike rpc client, address arguments takes no effect here, for rpc server will not create connection.</li>
     * </ol>
     *
     * @param addr
     * @param request
     * @throws RemotingException
     * @throws InterruptedException
     */
    public void oneway(final String addr, final Object request) throws RemotingException,
            InterruptedException {
        check();
        this.rpcRemoting.oneway(addr, request, null);
    }

    /**
     * One way invocation with a {@link InvokeContext}, common api notice please see {@link #oneway(String, Object)}
     *
     * @param addr
     * @param request
     * @param invokeContext
     * @throws RemotingException
     * @throws InterruptedException
     */
    public void oneway(final String addr, final Object request, final InvokeContext invokeContext)
            throws RemotingException,
            InterruptedException {
        check();
        this.rpcRemoting.oneway(addr, request, invokeContext);
    }

    /**
     * One way invocation using a parsed {@link Url} <br>
     * <p>
     * Notice:<br>
     * <ol>
     * <li><b>DO NOT modify the request object concurrently when this method is called.</b></li>
     * <li>When do invocation, use the parsed {@link Url} to find a available client connection, if none then throw exception</li>
     * </ol>
     *
     * @param url
     * @param request
     * @throws RemotingException
     * @throws InterruptedException
     */
    public void oneway(final Url url, final Object request) throws RemotingException,
            InterruptedException {
        check();
        this.rpcRemoting.oneway(url, request, null);
    }

    /**
     * One way invocation with a {@link InvokeContext}, common api notice please see {@link #oneway(Url, Object)}
     *
     * @param url
     * @param request
     * @param invokeContext
     * @throws RemotingException
     * @throws InterruptedException
     */
    public void oneway(final Url url, final Object request, final InvokeContext invokeContext)
            throws RemotingException,
            InterruptedException {
        check();
        this.rpcRemoting.oneway(url, request, invokeContext);
    }

    /**
     * One way invocation using a {@link Connection} <br>
     * <p>
     * Notice:<br>
     * <b>DO NOT modify the request object concurrently when this method is called.</b>
     *
     * @param conn
     * @param request
     * @throws RemotingException
     */
    public void oneway(final Connection conn, final Object request) throws RemotingException {
        this.rpcRemoting.oneway(conn, request, null);
    }

    /**
     * One way invocation with a {@link InvokeContext}, common api notice please see {@link #oneway(Connection, Object)}
     *
     * @param conn
     * @param request
     * @param invokeContext
     * @throws RemotingException
     */
    public void oneway(final Connection conn, final Object request,
                       final InvokeContext invokeContext) throws RemotingException {
        this.rpcRemoting.oneway(conn, request, invokeContext);
    }

    /**
     * Synchronous invocation using a string address, address format example - 127.0.0.1:12200?key1=value1&key2=value2 <br>
     * <p>
     * Notice:<br>
     * <ol>
     * <li><b>DO NOT modify the request object concurrently when this method is called.</b></li>
     * <li>When do invocation, use the string address to find a available client connection, if none then throw exception</li>
     * <li>Unlike rpc client, address arguments takes no effect here, for rpc server will not create connection.</li>
     * </ol>
     *
     * @param addr
     * @param request
     * @param timeoutMillis
     * @return Object
     * @throws RemotingException
     * @throws InterruptedException
     */
    public Object invokeSync(final String addr, final Object request, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        check();
        return this.rpcRemoting.invokeSync(addr, request, null, timeoutMillis);
    }

    /**
     * Synchronous invocation with a {@link InvokeContext}, common api notice please see {@link #invokeSync(String, Object, int)}
     *
     * @param addr
     * @param request
     * @param invokeContext
     * @param timeoutMillis
     * @return
     * @throws RemotingException
     * @throws InterruptedException
     */
    public Object invokeSync(final String addr, final Object request,
                             final InvokeContext invokeContext, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        check();
        return this.rpcRemoting.invokeSync(addr, request, invokeContext, timeoutMillis);
    }

    /**
     * Synchronous invocation using a parsed {@link Url} <br>
     * <p>
     * Notice:<br>
     * <ol>
     * <li><b>DO NOT modify the request object concurrently when this method is called.</b></li>
     * <li>When do invocation, use the parsed {@link Url} to find a available client connection, if none then throw exception</li>
     * </ol>
     *
     * @param url
     * @param request
     * @param timeoutMillis
     * @return Object
     * @throws RemotingException
     * @throws InterruptedException
     */
    public Object invokeSync(Url url, Object request, int timeoutMillis) throws RemotingException,
            InterruptedException {
        check();
        return this.rpcRemoting.invokeSync(url, request, null, timeoutMillis);
    }

    /**
     * Synchronous invocation with a {@link InvokeContext}, common api notice please see {@link #invokeSync(Url, Object, int)}
     *
     * @param url
     * @param request
     * @param invokeContext
     * @param timeoutMillis
     * @return
     * @throws RemotingException
     * @throws InterruptedException
     */
    public Object invokeSync(final Url url, final Object request,
                             final InvokeContext invokeContext, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        check();
        return this.rpcRemoting.invokeSync(url, request, invokeContext, timeoutMillis);
    }

    /**
     * Synchronous invocation using a {@link Connection} <br>
     * <p>
     * Notice:<br>
     * <b>DO NOT modify the request object concurrently when this method is called.</b>
     *
     * @param conn
     * @param request
     * @param timeoutMillis
     * @return Object
     * @throws RemotingException
     * @throws InterruptedException
     */
    public Object invokeSync(final Connection conn, final Object request, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        return this.rpcRemoting.invokeSync(conn, request, null, timeoutMillis);
    }

    /**
     * Synchronous invocation with a {@link InvokeContext}, common api notice please see {@link #invokeSync(Connection, Object, int)}
     *
     * @param conn
     * @param request
     * @param invokeContext
     * @param timeoutMillis
     * @return
     * @throws RemotingException
     * @throws InterruptedException
     */
    public Object invokeSync(final Connection conn, final Object request,
                             final InvokeContext invokeContext, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        return this.rpcRemoting.invokeSync(conn, request, invokeContext, timeoutMillis);
    }

    /**
     * Future invocation using a string address, address format example - 127.0.0.1:12200?key1=value1&key2=value2 <br>
     * You can get result use the returned {@link RpcResponseFuture}.
     * <p>
     * Notice:<br>
     * <ol>
     * <li><b>DO NOT modify the request object concurrently when this method is called.</b></li>
     * <li>When do invocation, use the string address to find a available client connection, if none then throw exception</li>
     * <li>Unlike rpc client, address arguments takes no effect here, for rpc server will not create connection.</li>
     * </ol>
     *
     * @param addr
     * @param request
     * @param timeoutMillis
     * @return RpcResponseFuture
     * @throws RemotingException
     * @throws InterruptedException
     */
    public RpcResponseFuture invokeWithFuture(final String addr, final Object request,
                                              final int timeoutMillis) throws RemotingException,
            InterruptedException {
        check();
        return this.rpcRemoting.invokeWithFuture(addr, request, null, timeoutMillis);
    }

    /**
     * Future invocation with a {@link InvokeContext}, common api notice please see {@link #invokeWithFuture(String, Object, int)}
     *
     * @param addr
     * @param request
     * @param invokeContext
     * @param timeoutMillis
     * @return
     * @throws RemotingException
     * @throws InterruptedException
     */
    public RpcResponseFuture invokeWithFuture(final String addr, final Object request,
                                              final InvokeContext invokeContext,
                                              final int timeoutMillis) throws RemotingException,
            InterruptedException {
        check();
        return this.rpcRemoting.invokeWithFuture(addr, request, invokeContext, timeoutMillis);
    }

    /**
     * Future invocation using a parsed {@link Url} <br>
     * You can get result use the returned {@link RpcResponseFuture}.
     * <p>
     * Notice:<br>
     * <ol>
     * <li><b>DO NOT modify the request object concurrently when this method is called.</b></li>
     * <li>When do invocation, use the parsed {@link Url} to find a available client connection, if none then throw exception</li>
     * </ol>
     *
     * @param url
     * @param request
     * @param timeoutMillis
     * @return RpcResponseFuture
     * @throws RemotingException
     * @throws InterruptedException
     */
    public RpcResponseFuture invokeWithFuture(final Url url, final Object request,
                                              final int timeoutMillis) throws RemotingException,
            InterruptedException {
        check();
        return this.rpcRemoting.invokeWithFuture(url, request, null, timeoutMillis);
    }

    /**
     * Future invocation with a {@link InvokeContext}, common api notice please see {@link #invokeWithFuture(Url, Object, int)}
     *
     * @param url
     * @param request
     * @param invokeContext
     * @param timeoutMillis
     * @return
     * @throws RemotingException
     * @throws InterruptedException
     */
    public RpcResponseFuture invokeWithFuture(final Url url, final Object request,
                                              final InvokeContext invokeContext,
                                              final int timeoutMillis) throws RemotingException,
            InterruptedException {
        check();
        return this.rpcRemoting.invokeWithFuture(url, request, invokeContext, timeoutMillis);
    }

    /**
     * Future invocation using a {@link Connection} <br>
     * You can get result use the returned {@link RpcResponseFuture}.
     * <p>
     * Notice:<br>
     * <b>DO NOT modify the request object concurrently when this method is called.</b>
     *
     * @param conn
     * @param request
     * @param timeoutMillis
     * @return
     * @throws RemotingException
     */
    public RpcResponseFuture invokeWithFuture(final Connection conn, final Object request,
                                              final int timeoutMillis) throws RemotingException {

        return this.rpcRemoting.invokeWithFuture(conn, request, null, timeoutMillis);
    }

    /**
     * Future invocation with a {@link InvokeContext}, common api notice please see {@link #invokeWithFuture(Connection, Object, int)}
     *
     * @param conn
     * @param request
     * @param invokeContext
     * @param timeoutMillis
     * @return
     * @throws RemotingException
     */
    public RpcResponseFuture invokeWithFuture(final Connection conn, final Object request,
                                              final InvokeContext invokeContext,
                                              final int timeoutMillis) throws RemotingException {

        return this.rpcRemoting.invokeWithFuture(conn, request, invokeContext, timeoutMillis);
    }

    /**
     * Callback invocation using a string address, address format example - 127.0.0.1:12200?key1=value1&key2=value2 <br>
     * You can specify an implementation of {@link InvokeCallback} to get the result.
     * <p>
     * Notice:<br>
     * <ol>
     * <li><b>DO NOT modify the request object concurrently when this method is called.</b></li>
     * <li>When do invocation, use the string address to find a available client connection, if none then throw exception</li>
     * <li>Unlike rpc client, address arguments takes no effect here, for rpc server will not create connection.</li>
     * </ol>
     *
     * @param addr
     * @param request
     * @param invokeCallback
     * @param timeoutMillis
     * @throws RemotingException
     * @throws InterruptedException
     */
    public void invokeWithCallback(final String addr, final Object request,
                                   final InvokeCallback invokeCallback, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        check();
        this.rpcRemoting.invokeWithCallback(addr, request, null, invokeCallback, timeoutMillis);
    }

    /**
     * Callback invocation with a {@link InvokeContext}, common api notice please see {@link #invokeWithCallback(String, Object, InvokeCallback, int)}
     *
     * @param addr
     * @param request
     * @param invokeContext
     * @param invokeCallback
     * @param timeoutMillis
     * @throws RemotingException
     * @throws InterruptedException
     */
    public void invokeWithCallback(final String addr, final Object request,
                                   final InvokeContext invokeContext,
                                   final InvokeCallback invokeCallback, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        check();
        this.rpcRemoting.invokeWithCallback(addr, request, invokeContext, invokeCallback,
                timeoutMillis);
    }

    /**
     * Callback invocation using a parsed {@link Url} <br>
     * You can specify an implementation of {@link InvokeCallback} to get the result.
     * <p>
     * Notice:<br>
     * <ol>
     * <li><b>DO NOT modify the request object concurrently when this method is called.</b></li>
     * <li>When do invocation, use the parsed {@link Url} to find a available client connection, if none then throw exception</li>
     * </ol>
     *
     * @param url
     * @param request
     * @param invokeCallback
     * @param timeoutMillis
     * @throws RemotingException
     * @throws InterruptedException
     */
    public void invokeWithCallback(final Url url, final Object request,
                                   final InvokeCallback invokeCallback, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        check();
        this.rpcRemoting.invokeWithCallback(url, request, null, invokeCallback, timeoutMillis);
    }

    /**
     * Callback invocation with a {@link InvokeContext}, common api notice please see {@link #invokeWithCallback(Url, Object, InvokeCallback, int)}
     *
     * @param url
     * @param request
     * @param invokeContext
     * @param invokeCallback
     * @param timeoutMillis
     * @throws RemotingException
     * @throws InterruptedException
     */
    public void invokeWithCallback(final Url url, final Object request,
                                   final InvokeContext invokeContext,
                                   final InvokeCallback invokeCallback, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        check();
        this.rpcRemoting.invokeWithCallback(url, request, invokeContext, invokeCallback,
                timeoutMillis);
    }

    /**
     * Callback invocation using a {@link Connection} <br>
     * You can specify an implementation of {@link InvokeCallback} to get the result.
     * <p>
     * Notice:<br>
     * <b>DO NOT modify the request object concurrently when this method is called.</b>
     *
     * @param conn
     * @param request
     * @param invokeCallback
     * @param timeoutMillis
     * @throws RemotingException
     */
    public void invokeWithCallback(final Connection conn, final Object request,
                                   final InvokeCallback invokeCallback, final int timeoutMillis)
            throws RemotingException {
        this.rpcRemoting.invokeWithCallback(conn, request, null, invokeCallback, timeoutMillis);
    }

    /**
     * Callback invocation with a {@link InvokeContext}, common api notice please see {@link #invokeWithCallback(Connection, Object, InvokeCallback, int)}
     *
     * @param conn
     * @param request
     * @param invokeCallback
     * @param timeoutMillis
     * @throws RemotingException
     */
    public void invokeWithCallback(final Connection conn, final Object request,
                                   final InvokeContext invokeContext,
                                   final InvokeCallback invokeCallback, final int timeoutMillis)
            throws RemotingException {
        this.rpcRemoting.invokeWithCallback(conn, request, invokeContext, invokeCallback,
                timeoutMillis);
    }

    /**
     * check whether a client address connected
     *
     * @param remoteAddr
     * @return
     */
    public boolean isConnected(String remoteAddr) {
        Url url = this.rpcRemoting.addressParser.parse(remoteAddr);
        return this.isConnected(url);
    }

    /**
     * check whether a {@link Url} connected
     *
     * @param url
     * @return
     */
    public boolean isConnected(Url url) {
        Connection conn = this.rpcRemoting.connectionManager.get(url.getUniqueKey());
        if (null != conn) {
            return conn.isFine();
        }
        return false;
    }

    /**
     * check whether connection manage feature enabled
     */
    private void check() {
        if (!this.switches().isOn(GlobalSwitch.SERVER_MANAGE_CONNECTION_SWITCH)) {
            throw new UnsupportedOperationException(
                    "Please enable connection manage feature of Rpc Server before call this method! See comments in constructor RpcServer(int port, boolean manageConnection) to find how to enable!");
        }
    }

    /**
     * init netty write buffer water mark
     */
    private void initWriteBufferWaterMark() {
        int lowWaterMark = this.netty_buffer_low_watermark();
        int highWaterMark = this.netty_buffer_high_watermark();
        if (lowWaterMark > highWaterMark) {
            throw new IllegalArgumentException(
                    String.format(
                            "[server side] bolt netty high water mark {%s} should not be smaller than low water mark {%s} bytes)",
                            highWaterMark, lowWaterMark));
        } else {
            logger.warn("[server side] bolt netty low water mark is {} bytes, high water mark is {} bytes",
                    lowWaterMark, highWaterMark);
        }
        this.bootstrap.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(
                lowWaterMark, highWaterMark));
    }

    // ~~~ getter and setter

    /**
     * Getter method for property <tt>addressParser</tt>.
     *
     * @return property value of addressParser
     */
    public RemotingAddressParser getAddressParser() {
        return this.addressParser;
    }

    /**
     * Setter method for property <tt>addressParser</tt>.
     *
     * @param addressParser value to be assigned to property addressParser
     */
    public void setAddressParser(RemotingAddressParser addressParser) {
        this.addressParser = addressParser;
    }

    /**
     * Getter method for property <tt>connectionManager</tt>.
     *
     * @return property value of connectionManager
     */
    public DefaultConnectionManager getConnectionManager() {
        return connectionManager;
    }
}
