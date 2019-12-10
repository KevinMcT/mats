package com.stolsvik.mats.websocket.impl;

import java.util.List;
import java.util.Optional;

/**
 * MatsSockets forwards requests from WebSocket-connected clients to a Mats Endpoint, and must get the reply back to the
 * client. The WebSocket is "static" in that it is a fixed connection to one specific node (as long as the connection is
 * up), which means that we need to get the message back to the same node that fired off the request. We could have used
 * the same logic as with MatsFuturizer (i.e. the "last jump" uses a node-specific Topic), but that was deemed too
 * feeble: We want reliable messaging all the way to the client. We want to be able to handle that the client looses the
 * connection (e.g. sitting on a train which drives through a tunnel), and thus will reconnect. He might not come back
 * to the same server as last time. We also want to be able to reboot any server (typically deploy of new code) at any
 * time, but this obviously kills all WebSockets that are attached to it. To be able to ensure reliable messaging for
 * MatsSockets, we need to employ a "store and forward" logic: When the reply comes in, which as with standard Mats
 * logic can happen on any node in the cluster of nodes handling this Endpoint Stage, the reply message is temporarily
 * stored in some reliable storage. We then look up which node that currently holds the MatsSession, and notify it about
 * new messages for that session. This node gets the notice, finds the now local MatsSession, and forwards the message.
 * Note that it is possible that the node getting the reply, and the node which holds the WebSocket/MatsSession, is the
 * same node, in which case it is a local forward.
 * <p />
 * Each node has his own instance of this class, connected to the same backing datastore.
 * <p />
 * It is assumed that the consumption of messages for a session is done single threaded, on one node only. That is, only
 * one thread on one node will actually {@link #getMessagesForSession(String, int)} get messages}, and, more
 * importantly, {@link #messagesComplete(String, List) register dem as completed}. Wrt. multiple nodes, this argument
 * still holds, since only one node can hold a MatsSocket Session. I believe it is possible to construct a bad async
 * situation here (connect to one node, authenticate, get SessionId, immediately disconnect and perform reconnect, and
 * do this until the current {@link ClusterStoreAndForward} has the wrong idea of which node holds the Session) but this
 * should at most result in the client screwing up for himself (not getting messages), and a Session is not registered
 * until the client has authenticated. Such a situation will also resolve if the client again performs a non-malicious
 * reconnect. It is the server that constructs and holds SessionIds: A client cannot itself force the server side to
 * create a Session or SessionId - it can only reconnect to an existing SessionId that it was given earlier.
 */
public interface ClusterStoreAndForward {
    /**
     * Start the {@link ClusterStoreAndForward}, perform DB preparations and migrations.
     */
    void boot();

    /**
     * Registers a Session home to this node - only one node can ever be home, so implicitly any old is deleted.
     *
     * @param matsSocketSessionId
     */
    void registerSessionAtThisNode(String matsSocketSessionId);

    /**
     * Deregisters a Session home when a WebSocket is closed. This node's nodename is taken into account, so that if it
     * has changed async, it will not deregister a new session home.
     *
     * @param matsSocketSessionId
     */
    void deregisterSessionFromThisNode(String matsSocketSessionId);

    /**
     * Invoked when the client explicitly tells us that he closed this session, CLOSE_SESSION. Throws
     * {@link IllegalStateException} if this node is not the home of the session.
     *
     * @param matsSocketSessionId
     *            the MatsSocketSessionId that should be closed.
     */
    void terminateSession(String matsSocketSessionId) throws IllegalStateException;

    /**
     * @param matsSocketSessionId
     *            the MatsSocketSessionId for which to find current session home.
     * @return the nodename of current node holding MatsSocket Session, or empty if none.
     */
    Optional<String> getCurrentNodeForSession(String matsSocketSessionId);

    /**
     * Shall be invoked on some kind of schedule (e.g. every 5 minute) by node to inform {@link ClusterStoreAndForward}
     * about Sessions' liveliness. Sessions that aren't live, will be scavenged after some time, e.g. 24 hours.
     *
     * @param matsSocketSessionIds
     */
    void notifySessionLiveliness(List<String> matsSocketSessionIds);

    /**
     * Stores the message for the Session, returning the nodename for the node holding the session, if any. If the
     * session is timed out, the message won't be stored (i.e. dumped on the floor) and the return value is empty.
     *
     * @param matsSocketSessionId
     *            the matsSocketSessionId that the message is meant for.
     * @param traceId
     *            the server-side traceId for this message.
     * @param type
     *            the type of the reply, currently "REPLY" or "ERROR".
     * @param message
     *            the JSON-serialized MatsSocket <b>Envelope</b>.
     * @return the nodename of the node holding the WebSocket Session, or empty if the Session is not connected.
     */
    Optional<String> storeMessageForSession(String matsSocketSessionId, String traceId, String type,
            String message);

    /**
     * Fetch a set of messages, up to 'maxNumberOfMessages'.
     *
     * @param matsSocketSessionId
     *            the matsSocketSessionId that the message is meant for.
     * @param maxNumberOfMessages
     *            the maximum number of messages to fetch.
     * @return a list of json encoded messages destined for the WebSocket.
     */
    List<StoredMessage> getMessagesForSession(String matsSocketSessionId, int maxNumberOfMessages);

    /**
     * States that the messages are delivered, or overran their delivery attempts. Will typically delete the message.
     *
     * @param matsSocketSessionId
     *            the matsSocketSessionId that the messageIds refers to.
     * @param messageIds
     *            which messages are complete.
     */
    void messagesComplete(String matsSocketSessionId, List<Long> messageIds);

    /**
     * Notches the 'deliveryAttempt' one up for the specified messages.
     *
     * @param matsSocketSessionId
     *            the matsSocketSessionId that the messageIds refers to.
     * @param messageIds
     *            which messages failed delivery.
     */
    void messagesFailedDelivery(String matsSocketSessionId, List<Long> messageIds);

    interface StoredMessage {
        long getId();

        int getDeliveryAttempt();

        long getStoredTimestamp();

        String getType();

        String getTraceId();

        String getEnvelopeJson();
    }
}