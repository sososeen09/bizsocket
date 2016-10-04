package com.dx168.bizsocket.core;

import com.dx168.bizsocket.core.signal.AbstractSerialContext;
import com.dx168.bizsocket.core.signal.SerialSignal;
import com.dx168.bizsocket.tcp.ConnectionListener;
import com.dx168.bizsocket.tcp.Packet;
import com.dx168.bizsocket.tcp.PacketListener;
import com.dx168.bizsocket.tcp.SocketConnection;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Created by tong on 16/3/7.
 */
public class RequestQueue implements PacketListener,ConnectionListener {
    protected final List<RequestContext> requestContextList = Collections.synchronizedList(new ArrayList<RequestContext>());
    private static final List<SerialSignal> SERIAL_SIGNAL_LIST = new ArrayList<SerialSignal>();
    private final List<AbstractSerialContext> mSerialContexts = new CopyOnWriteArrayList();

    protected final AbstractBizSocket bizSocket;
    private ResponseHandler globalNotifyHandler;

    public RequestQueue(AbstractBizSocket bizSocket) {
        this.bizSocket = bizSocket;

        bizSocket.getSocketConnection().addPacketListener(this);
        bizSocket.getSocketConnection().addConnectionListener(this);
    }

    public void addRequestContext(final RequestContext context) {
        if (context == null) {
            return;
        }
        prepareContext(context);
        boolean existed = requestContextList.contains(context);
        if (!existed) {
            if ((context.getFlags() & RequestContext.FLAG_CLEAR_QUOTE) != 0) {
                removeRequestContexts(requestContextList);
            }
            if ((context.getFlags() & RequestContext.FLAG_NOT_SUPPORT_REPEAT) != 0) {
                Collection contexts = getRequestContext(new Filter() {
                    @Override
                    public boolean filter(RequestContext ctx) {
                        return ctx.getRequestCommand() == context.getRequestCommand();
                    }
                });

                if (!contexts.isEmpty()) {
                    //
                    return;
                }
            }
            if ((context.getFlags() & RequestContext.FLAG_JUMP_QUOTE) != 0) {
                requestContextList.add(0,context);
            }
            else {
                requestContextList.add(context);
            }
            if ((context.getFlags() & RequestContext.FLAG_REQUEST) != 0) {
                sendRequest(context);
            }
        }
    }

    public void sendRequest(RequestContext context) {
        if ((context.getFlags() & RequestContext.FLAG_CHECK_CONNECT_STATUS) == 0
                || ((context.getFlags() & RequestContext.FLAG_CHECK_CONNECT_STATUS) != 0 && bizSocket.isConnected())) {
            //Logger.e("connected , send request ...");
            //已连接发送请求
            if (sendPacket(context.getRequestPacket())) {
                context.setFlags(context.getFlags() | RequestContext.FLAG_REQUEST_ALREADY_SEND);
                onPacketSend(context);

                if (context.getResponseHandler() == null) {
                    removeRequestContext(context);
                }
            }
            else {
                //TODO

            }
        }
        else {
            //等待连接成功后发送
            //Logger.e("connect closed ,wait ... ");
        }
    }

    public boolean sendPacket(Packet requestPacket) {
        if (bizSocket.getSocketConnection() != null) {
            bizSocket.getSocketConnection().sendPacket(requestPacket);
            return false;
        }

        return true;
    }

    public void onPacketSend(RequestContext context) {
        SerialSignal serialSignal = getSerialSignal(context.getRequestCommand());
        //判断是否是串行入口命令
        if (serialSignal != null) {
            AbstractSerialContext serialContext = getSerialContext(context);
            if (serialContext == null) {
                try {
                    serialContext = buildSerialContext(serialSignal,context);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                //Logger.d(TAG, "build serial context: " + serialContext);
                mSerialContexts.add(serialContext);
            } else {
                //Logger.d(TAG, "repeat request: " + serialContext);
            }
            return;
        }

        removeExpiredSerialContexts();
    }

    //获取串行context
    private AbstractSerialContext getSerialContext(RequestContext context) {
        Packet packet = context.getRequestPacket();
        if (packet != null) {
            for (AbstractSerialContext serialContext : mSerialContexts) {
                if (serialContext.getSerialSignal().getEntranceCommand() == context.getRequestCommand()
                        && serialContext.getRequestPacketId() != null
                        && serialContext.getRequestPacketId().equals(packet.getPacketID())) {
                    return serialContext;
                }
            }
        }
        //Logger.d(TAG, mSerialContexts.toString());
        return null;
    }

    //创建串行context
    public AbstractSerialContext buildSerialContext(SerialSignal serialSignal, RequestContext context) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException {
        Class<? extends AbstractSerialContext> serialContexType = serialSignal.getSerialContextType();
        AbstractSerialContext serialContex = serialContexType.getConstructor(SerialSignal.class,RequestContext.class).newInstance(serialSignal,context);
        return serialContex;
    }

    //移除过期的上线文
    private void removeExpiredSerialContexts() {
        List<AbstractSerialContext> preDelList = new ArrayList<AbstractSerialContext>();
        for (AbstractSerialContext serialContext : mSerialContexts) {
            if (serialContext.isExpired()) {
                preDelList.add(serialContext);
            }
        }

        mSerialContexts.removeAll(preDelList);

        //Log.e(TAG, "serialContexts remove: " + preDelList);
    }

    /**
     * 根据入口命令获取串行信号
     *
     * @param entranceCommand
     * @return
     */
    private SerialSignal getSerialSignal(Integer entranceCommand) {
        if (entranceCommand != null) {
            for (SerialSignal signal : SERIAL_SIGNAL_LIST) {
                if (signal.getEntranceCommand() == entranceCommand) {
                    return signal;
                }
            }
        }
        return null;
    }

    //获取串行上下文
    private AbstractSerialContext getSerialContext(Packet responsePacket) {
        for (int i = 0; i < mSerialContexts.size(); i++) {
            AbstractSerialContext serialContext = mSerialContexts.get(i);
            if (serialContext != null && serialContext.shouldProcess(this,responsePacket)) {
                return serialContext;
            }
        }
        return null;
    }

    /**
     * 加入队列前准备上下文
     * @param requestContext
     */
    private void prepareContext(final RequestContext requestContext) {
        if ((requestContext.getFlags() & RequestContext.FLAG_REQUEST) == 0) {
            throw new IllegalStateException("Invalid request context!");
        }
        requestContext.setOnRequestTimeoutListener(new RequestContext.OnRequestTimeoutListener() {
            @Override
            public void onRequestTimeout(RequestContext context) {
                //请求超时
                RequestTimeoutException exception = new RequestTimeoutException("网络异常，请检查网络连接");
                context.sendFailureMessage(context.getRequestCommand(), exception);
                removeRequestContext(context);
            }
        });
    }

    public void removeRequestContext(final RequestContext context) {
        removeRequestContexts(new ArrayList<RequestContext>(){{add(context);}});
    }

    public void removeRequestContexts(Collection<RequestContext> requestContexts) {
        if (requestContexts == null) {
            return;
        }
        for (RequestContext context : requestContexts) {
            context.onRemoveFromQuoue();
        }
        requestContextList.removeAll(requestContexts);
    }

    public Collection<RequestContext> getRequestContext(Filter filter) {
        if (filter == null) {
            throw new RuntimeException("filter can not be null");
        }
        List<RequestContext> resultList = new ArrayList<RequestContext>();
        for (int i = 0;i < requestContextList.size();i++) {
            RequestContext context = requestContextList.get(i);
            if (filter.filter(context)) {
                resultList.add(context);
            }
        }
        return resultList;
    }

    /**
     * 分发数据包
     * @param responsePacket
     */
    public void dispatchPacket(final Packet responsePacket) {
        final int command = responsePacket.getCommand();
        final String packetID = responsePacket.getPacketID() == null ? "" : responsePacket.getPacketID();
        Collection<RequestContext> relativeContexts = getRequestContext(new Filter() {
            @Override
            public boolean filter(RequestContext context) {
                return command == context.getRequestCommand()
                        && packetID.equals(context.getRequestPacket().getPacketID());
            }
        });

        for (RequestContext context : relativeContexts) {
            context.sendSuccessMessage(command,null,context.getAttachInfo(),responsePacket);
        }
        removeRequestContexts(relativeContexts);

        if (globalNotifyHandler != null) {
            globalNotifyHandler.sendSuccessMessage(command,null,null,responsePacket);
        }
    }

    /**
     * 执行队列中的所有请求
     */
    public void executeAllRequestContext() {
        //Logger.d("执行队列中的所有请求");
        Collection<RequestContext> prepareExecuteList = getRequestContext(new Filter() {
            @Override
            public boolean filter(RequestContext context) {
                //获取没有被发送出去的请求
                return (context.getFlags() & RequestContext.FLAG_REQUEST) != 0
                        && (context.getFlags() & RequestContext.FLAG_REQUEST_ALREADY_SEND) == 0;
            }
        });
        for (RequestContext context : prepareExecuteList) {
            sendRequest(context);
        }
    }

    @Override
    public void connected(SocketConnection connection) {
        executeAllRequestContext();
    }

    @Override
    public void connectionClosed() {

    }

    @Override
    public void connectionClosedOnError(Exception e) {

    }

    @Override
    public void reconnectingIn(int seconds) {

    }

    @Override
    public void onSendSuccessful(Packet packet) {

    }

    public boolean prepareDispatchPacket(Packet packet) {
        AbstractSerialContext serialContext = getSerialContext(packet);
        if (serialContext != null) {
           // Logger.d(TAG, "about serial packet: " + packet);
            Packet processPacket = serialContext.processPacket(this,packet);
            if (processPacket == null) {
                return false;
            }

            boolean result = mSerialContexts.remove(serialContext);
            if (result) {
                //Log.e(TAG, "serialContext remove: " + serialContext);
            }
        }
        return true;
    }


    @Override
    public void processPacket(Packet packet) {
        if (prepareDispatchPacket(packet)) {
            dispatchPacket(packet);
        }
    }

    public AbstractBizSocket getBizSocket() {
        return bizSocket;
    }

    public void setGlobalNotifyHandler(ResponseHandler globalNotifyHandler) {
        this.globalNotifyHandler = globalNotifyHandler;
    }

    public interface Filter {
        boolean filter(RequestContext context);
    }
}