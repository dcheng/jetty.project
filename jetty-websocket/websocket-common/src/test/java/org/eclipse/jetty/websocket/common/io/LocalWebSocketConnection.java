//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common.io;

import java.net.InetSocketAddress;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.junit.rules.TestName;

public class LocalWebSocketConnection implements LogicalConnection, IncomingFrames
{
    private static final Logger LOG = Log.getLogger(LocalWebSocketConnection.class);
    private final String id;
    private WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
    private boolean open = false;
    private IncomingFrames incoming;
    private IOState ioState = new IOState();

    public LocalWebSocketConnection()
    {
        this("anon");
    }

    public LocalWebSocketConnection(String id)
    {
        this.id = id;
    }

    public LocalWebSocketConnection(TestName testname)
    {
        this.id = testname.getMethodName();
    }

    @Override
    public void close()
    {
        open = false;
    }

    @Override
    public void close(int statusCode, String reason)
    {
        open = false;
    }

    @Override
    public void disconnect()
    {
        open = false;
    }

    public IncomingFrames getIncoming()
    {
        return incoming;
    }

    @Override
    public IOState getIOState()
    {
        return ioState;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return null;
    }

    @Override
    public long getMaxIdleTimeout()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return null;
    }

    @Override
    public WebSocketSession getSession()
    {
        return null;
    }

    @Override
    public void incomingError(WebSocketException e)
    {
        incoming.incomingError(e);
    }

    @Override
    public void incomingFrame(Frame frame)
    {
        incoming.incomingFrame(frame);
    }

    @Override
    public boolean isOpen()
    {
        return open;
    }

    @Override
    public boolean isReading()
    {
        return false;
    }

    public void onOpen() {
        LOG.debug("onOpen()");
        open = true;
    }

    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback)
    {
    }

    @Override
    public void resume()
    {
    }

    @Override
    public void setMaxIdleTimeout(long ms)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void setNextIncomingFrames(IncomingFrames incoming)
    {
        this.incoming = incoming;
    }

    public void setPolicy(WebSocketPolicy policy)
    {
        this.policy = policy;
    }

    @Override
    public void setSession(WebSocketSession session)
    {
    }

    @Override
    public SuspendToken suspend()
    {
        return null;
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]",LocalWebSocketConnection.class.getSimpleName(),id);
    }
}
