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

package org.eclipse.jetty.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.client.util.OutputStreamContentProvider;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

import static java.nio.file.StandardOpenOption.CREATE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HttpClientStreamTest extends AbstractHttpClientServerTest
{
    public HttpClientStreamTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void testFileUpload() throws Exception
    {
        // Prepare a big file to upload
        Path targetTestsDir = MavenTestingUtils.getTargetTestingDir().toPath();
        Files.createDirectories(targetTestsDir);
        Path upload = Paths.get(targetTestsDir.toString(), "http_client_upload.big");
        try (OutputStream output = Files.newOutputStream(upload, CREATE))
        {
            byte[] kb = new byte[1024];
            for (int i = 0; i < 10 * 1024; ++i)
                output.write(kb);
        }

        start(new EmptyServerHandler());

        final AtomicLong requestTime = new AtomicLong();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .file(upload)
                .onRequestSuccess(new Request.SuccessListener()
                {
                    @Override
                    public void onSuccess(Request request)
                    {
                        requestTime.set(System.nanoTime());
                    }
                })
                .timeout(10, TimeUnit.SECONDS)
                .send();
        long responseTime = System.nanoTime();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(requestTime.get() <= responseTime);

        // Give some time to the server to consume the request content
        // This is just to avoid exception traces in the test output
        Thread.sleep(1000);
    }

    @Test
    public void testDownload() throws Exception
    {
        final byte[] data = new byte[128 * 1024];
        byte value = 1;
        Arrays.fill(data, value);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(data);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        Assert.assertNotNull(input);

        int length = 0;
        while (input.read() == value)
        {
            if (length % 100 == 0)
                Thread.sleep(1);
            ++length;
        }

        Assert.assertEquals(data.length, length);

        Result result = listener.await(5, TimeUnit.SECONDS);
        Assert.assertNotNull(result);
        Assert.assertFalse(result.isFailed());
        Assert.assertSame(response, result.getResponse());
    }

    @Test
    public void testDownloadOfUTF8Content() throws Exception
    {
        final byte[] data = new byte[]{(byte)0xC3, (byte)0xA8}; // UTF-8 representation of &egrave;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(data);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        Assert.assertNotNull(input);

        for (byte b : data)
        {
            int read = input.read();
            assertTrue(read >= 0);
            assertEquals(b & 0xFF, read);
        }

        assertEquals(-1, input.read());

        Result result = listener.await(5, TimeUnit.SECONDS);
        Assert.assertNotNull(result);
        Assert.assertFalse(result.isFailed());
        Assert.assertSame(response, result.getResponse());
    }

    @Test
    public void testDownloadWithFailure() throws Exception
    {
        final byte[] data = new byte[64 * 1024];
        byte value = 1;
        Arrays.fill(data, value);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                // Say we want to send this much...
                response.setContentLength(2 * data.length);
                // ...but write only half...
                response.getOutputStream().write(data);
                // ...then shutdown output
                baseRequest.getHttpChannel().getEndPoint().shutdownOutput();
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        Assert.assertNotNull(input);

        int length = 0;
        try
        {
            length = 0;
            while (input.read() == value)
            {
                if (length % 100 == 0)
                    Thread.sleep(1);
                ++length;
            }
            fail();
        }
        catch (IOException expected)
        {
        }

        Assert.assertEquals(data.length, length);

        Result result = listener.await(5, TimeUnit.SECONDS);
        Assert.assertNotNull(result);
        Assert.assertTrue(result.isFailed());
    }

    @Test(expected = AsynchronousCloseException.class)
    public void testDownloadWithCloseBeforeContent() throws Exception
    {
        final byte[] data = new byte[128 * 1024];
        byte value = 3;
        Arrays.fill(data, value);
        final CountDownLatch latch = new CountDownLatch(1);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.flushBuffer();

                try
                {
                    Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    throw new InterruptedIOException();
                }

                response.getOutputStream().write(data);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        Assert.assertNotNull(input);
        input.close();

        latch.countDown();

        input.read();
    }

    @Test(expected = AsynchronousCloseException.class)
    public void testDownloadWithCloseMiddleOfContent() throws Exception
    {
        final byte[] data1 = new byte[1024];
        final byte[] data2 = new byte[1024];
        final CountDownLatch latch = new CountDownLatch(1);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(data1);
                response.flushBuffer();

                try
                {
                    Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    throw new InterruptedIOException();
                }

                response.getOutputStream().write(data2);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        Assert.assertNotNull(input);

        for (byte b : data1)
            input.read();

        input.close();

        latch.countDown();

        input.read(); // throws
    }

    @Test
    public void testDownloadWithCloseEndOfContent() throws Exception
    {
        final byte[] data = new byte[1024];
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(data);
                response.flushBuffer();
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        Assert.assertNotNull(input);

        for (byte b : data)
            input.read();

        // Read EOF
        Assert.assertEquals(-1, input.read());

        input.close();

        // Must not throw
        Assert.assertEquals(-1, input.read());
    }

    @Slow
    @Test
    public void testUploadWithDeferredContentProviderFromInputStream() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), new ByteArrayOutputStream());
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        try (DeferredContentProvider content = new DeferredContentProvider())
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .content(content)
                    .send(new Response.CompleteListener()
                    {
                        @Override
                        public void onComplete(Result result)
                        {
                            if (result.isSucceeded() && result.getResponse().getStatus() == 200)
                                latch.countDown();
                        }
                    });

            // Make sure we provide the content *after* the request has been "sent".
            Thread.sleep(1000);

            try (ByteArrayInputStream input = new ByteArrayInputStream(new byte[1024]))
            {
                byte[] buffer = new byte[200];
                int read;
                while ((read = input.read(buffer)) >= 0)
                    content.offer(ByteBuffer.wrap(buffer, 0, read));
            }
        }
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testUploadWithDeferredContentProviderRacingWithSend() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        final byte[] data = new byte[512];
        final DeferredContentProvider content = new DeferredContentProvider()
        {
            @Override
            public void setListener(Listener listener)
            {
                super.setListener(listener);
                // Simulate a concurrent call
                offer(ByteBuffer.wrap(data));
                close();
            }
        };

        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .content(content)
                .send(new BufferingResponseListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        if (result.isSucceeded() &&
                                result.getResponse().getStatus() == 200 &&
                                Arrays.equals(data, getContent()))
                            latch.countDown();
                    }
                });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testUploadWithDeferredContentProviderRacingWithIterator() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        final byte[] data = new byte[512];
        final AtomicReference<DeferredContentProvider> contentRef = new AtomicReference<>();
        final DeferredContentProvider content = new DeferredContentProvider()
        {
            @Override
            public Iterator<ByteBuffer> iterator()
            {
                return new Iterator<ByteBuffer>()
                {
                    // Data for the deferred content iterator:
                    // [0] => deferred
                    // [1] => deferred
                    // [2] => data
                    private final byte[][] iteratorData = new byte[3][];
                    private final AtomicInteger index = new AtomicInteger();

                    {
                        iteratorData[0] = null;
                        iteratorData[1] = null;
                        iteratorData[2] = data;
                    }

                    @Override
                    public boolean hasNext()
                    {
                        return index.get() < iteratorData.length;
                    }

                    @Override
                    public ByteBuffer next()
                    {
                        byte[] chunk = iteratorData[index.getAndIncrement()];
                        ByteBuffer result = chunk == null ? null : ByteBuffer.wrap(chunk);
                        if (index.get() == 2)
                        {
                            contentRef.get().offer(result == null ? BufferUtil.EMPTY_BUFFER : result);
                            contentRef.get().close();
                        }
                        return result;
                    }

                    @Override
                    public void remove()
                    {
                    }
                };
            }
        };
        contentRef.set(content);

        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .content(content)
                .send(new BufferingResponseListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        if (result.isSucceeded() &&
                                result.getResponse().getStatus() == 200 &&
                                Arrays.equals(data, getContent()))
                            latch.countDown();
                    }
                });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testUploadWithOutputStream() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        final byte[] data = new byte[512];
        final CountDownLatch latch = new CountDownLatch(1);
        OutputStreamContentProvider content = new OutputStreamContentProvider();
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .content(content)
                .send(new BufferingResponseListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        if (result.isSucceeded() &&
                                result.getResponse().getStatus() == 200 &&
                                Arrays.equals(data, getContent()))
                            latch.countDown();
                    }
                });

        // Make sure we provide the content *after* the request has been "sent".
        Thread.sleep(1000);

        try (OutputStream output = content.getOutputStream())
        {
            output.write(data);
        }

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
