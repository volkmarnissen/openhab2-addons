/**
 * Copyright (c) 2014-2016 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.kodi.internal.protocol;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * KodiClientSocket implements the low level communication to kodi through websocket. Usually this communication is done
 * through port 9090
 *
 * @author Paul Frank
 *
 */
public class KodiClientSocket {
    private static final Logger logger = LoggerFactory.getLogger(KodiClientSocket.class);

    private final ScheduledExecutorService scheduler;
    private static final int REQUEST_TIMEOUT_MS = 60000;

    private CountDownLatch commandLatch = null;
    private JsonObject commandResponse = null;
    private int nextMessageId = 1;

    private boolean connected = false;

    private final JsonParser parser = new JsonParser();
    private final Gson mapper = new Gson();
    private URI uri;
    private Session session;
    private WebSocketClient client;

    private final KodiClientSocketEventListener eventHandler;

    public KodiClientSocket(KodiClientSocketEventListener eventHandler, URI uri, ScheduledExecutorService scheduler) {
        this.eventHandler = eventHandler;
        this.uri = uri;
        client = new WebSocketClient();
        this.scheduler = scheduler;
    }

    /**
     * Attempts to create a connection to the kodi host and begin listening
     * for updates over the async http web socket
     *
     * @throws Exception
     */
    public synchronized void open() throws Exception {
        if (isConnected()) {
            logger.warn("connect: connection is already open");
        }
        if (!client.isStarted()) {
            client.start();
        }
        KodiWebSocketListener socket = new KodiWebSocketListener();
        ClientUpgradeRequest request = new ClientUpgradeRequest();

        client.connect(socket, uri, request);
    }

    /***
     * Close this connection to the kodi instance
     */
    public void close() {
        // if there is an old web socket then clean up and destroy
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                logger.error("Exception during closing the websocket {}", e.getMessage(), e);
            }
            session = null;
        }
        try {
            client.stop();
        } catch (Exception e) {
            logger.error("Exception during closing the websocket {}", e.getMessage(), e);
        }
    }

    public boolean isConnected() {
        if (session == null || !session.isOpen()) {
            return false;
        }

        return connected;
    }

    @WebSocket
    public class KodiWebSocketListener {

        @OnWebSocketConnect
        public void onConnect(Session wssession) {
            logger.debug("Connected to server");
            session = wssession;
            connected = true;
            if (eventHandler != null) {
                scheduler.submit(new Runnable() {

                    @Override
                    public void run() {
                        try {
                            eventHandler.onConnectionOpened();
                        } catch (Exception e) {
                            logger.error("Error handling onConnectionOpened() {}", e.getMessage(), e);
                        }

                    }
                });

            }
        }

        @OnWebSocketMessage
        public void onMessage(String message) {
            logger.debug("Message received from server: {}", message);
            final JsonObject json = parser.parse(message).getAsJsonObject();
            if (json.has("id")) {
                logger.debug("Response received from server:" + json.toString());
                int messageId = json.get("id").getAsInt();
                if (messageId == nextMessageId - 1) {
                    commandResponse = json;
                    commandLatch.countDown();
                }
            } else {
                logger.debug("Event received from server: {}", json.toString());
                try {
                    if (eventHandler != null) {
                        scheduler.submit(new Runnable() {

                            @Override
                            public void run() {
                                try {
                                    eventHandler.handleEvent(json);
                                } catch (Exception e) {
                                    logger.error("Error handling event {} player state change message: {}", json,
                                            e.getMessage(), e);
                                }

                            }
                        });

                    }
                } catch (Exception e) {
                    logger.error("Error handling player state change message", e);
                }
            }
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason) {
            session = null;
            connected = false;
            logger.debug("Closing a WebSocket due to {}", reason);
            scheduler.submit(new Runnable() {

                @Override
                public void run() {
                    try {
                        eventHandler.onConnectionClosed();
                    } catch (Exception e) {
                        logger.error("Error handling onConnectionClosed()", e);
                    }
                }
            });
        }
    }

    private void sendMessage(String str) throws Exception {
        if (isConnected()) {
            logger.debug("send message: {}", str);
            session.getRemote().sendString(str);
        } else {
            throw new Exception("socket not initialized");
        }
    }

    public JsonElement callMethod(String methodName) {
        return callMethod(methodName, null);
    }

    public synchronized JsonElement callMethod(String methodName, JsonObject params) {
        try {
            JsonObject payloadObject = new JsonObject();
            payloadObject.addProperty("jsonrpc", "2.0");
            payloadObject.addProperty("id", nextMessageId);
            payloadObject.addProperty("method", methodName);

            if (params != null) {
                payloadObject.add("params", params);
            }

            String message = mapper.toJson(payloadObject);

            commandLatch = new CountDownLatch(1);
            commandResponse = null;
            nextMessageId++;

            sendMessage(message);
            if (commandLatch.await(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                logger.debug("callMethod returns {}", commandResponse.toString());
                return commandResponse.get("result");
            } else {
                logger.error("Timeout during callMethod({}, {})", methodName, params != null ? params.toString() : "");
                return null;
            }
        } catch (Exception e) {
            logger.error("Error during callMethod", e);
            return null;
        }
    }
}
