package com.stolsvik.mats.websocket;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.stolsvik.mats.websocket.MatsSocketServer.MessageType;

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
 * same node, in which case it eventually results in a local forward.
 * <p />
 * Each node has his own instance of this class, connected to the same backing datastore.
 * <p />
 * It is assumed that the consumption of messages for a session is done single threaded, on one node only. That is, only
 * one thread on one node will actually {@link #getMessagesFromOutbox(String, int)} get messages}, and, more
 * importantly, {@link #outboxMessagesComplete(String, Collection) register them as completed}. Wrt. multiple nodes,
 * this argument still holds, since only one node can hold a MatsSocketSession - the one that has the actual WebSocket
 * connection. I believe it is possible to construct a bad async situation here (connect to one node, authenticate, get
 * SessionId, immediately disconnect and perform reconnect, and do this until the current {@link ClusterStoreAndForward}
 * has the wrong idea of which node holds the Session) but this should at most result in the client screwing up for
 * himself (not getting messages). A Session is not registered until the client has authenticated, so this will never
 * lead to information leakage to other users. Such a situation will also resolve if the client again performs a
 * non-malicious reconnect. It is the server that constructs and holds SessionIds: A client cannot itself force the
 * server side to create a Session or SessionId - it can only reconnect to an existing SessionId that it was given
 * earlier.
 *
 * @author Endre Stølsvik 2019-12-07 23:29 - http://stolsvik.com/, endre@stolsvik.com
 */
public interface ClusterStoreAndForward {
    /**
     * Start the {@link ClusterStoreAndForward}, perform DB preparations and migrations.
     */
    void boot();

    // ---------- Session management ----------

    /**
     * Registers a Session home to this node - only one node can ever be home, so any old is deleted. When
     * "re-registering" a session, it is asserted that the provided 'userId' is the same UserId as originally registered
     * - a {@link WrongUserException} is thrown if this does not match.
     *
     * @param matsSocketSessionId
     *            the SessionId for this connection.
     * @param userId
     *            the UserId, as provided by {@link AuthenticationPlugin}, that own this MatsSocketSessionId
     * @param connectionId
     *            an id that is unique for this specific WebSocket Session (i.e. TCP Connection), so that if it closes,
     *            a new registration will not be deregistered by the old MatsSocketSession realizing that it is closed
     *            and then invoking {@link #deregisterSessionFromThisNode(String, String)}
     * @param clientLibAndVersions
     *            the Client Library and Versions + runtime information for the Client.
     * @param appName
     *            the AppName of the accessing Client app
     * @param appVersion
     *            the AppVersion of the accessing Client app
     * @throws WrongUserException
     *             if the userId provided does not match the original userId that created the session.
     * @throws DataAccessException
     *             if problems with underlying data store.
     * @return the created timestamp - which is either "now" if this is the first register, or if it a "reconnect", when
     *         this MatsSocketSession was initially created
     */
    long registerSessionAtThisNode(String matsSocketSessionId, String userId, String connectionId,
            String clientLibAndVersions, String appName, String appVersion)
            throws WrongUserException, DataAccessException;

    /**
     * @param matsSocketSessionId
     *            the MatsSocketSessionId for which to find current session home.
     * @return the current node holding MatsSocket Session, or empty if none.
     */
    Optional<CurrentNode> getCurrentRegisteredNodeForSession(String matsSocketSessionId) throws DataAccessException;

    /**
     * Shall be invoked on some kind of schedule (e.g. every 5 minute) by node to inform {@link ClusterStoreAndForward}
     * about Sessions' liveliness. Sessions that aren't live, will be scavenged after some time, e.g. 24 hours.
     *
     * @param matsSocketSessionIds
     */
    void notifySessionLiveliness(Collection<String> matsSocketSessionIds) throws DataAccessException;

    /**
     * @param matsSocketSessionId
     *            the MatsSocketSessionId for which to check whether there is a session
     * @return whether a CSAF Session exists - NOTE: Not whether it is currently registered!
     */
    boolean isSessionExists(String matsSocketSessionId) throws DataAccessException;

    /**
     * Deregisters a Session home when a WebSocket is closed. This node's nodename and this WebSocket Session's
     * ConnectionId is taken into account, so that if it has changed async, it will not deregister a new session home
     * (which can potentially be on the same node the 'connectionId' parameter).
     *
     * @param matsSocketSessionId
     *            the MatsSocketSessionId for which to deregister the specific WebSocket Session's ConnectionId.
     * @param connectionId
     *            an id that is unique for this specific WebSocket Session (i.e. TCP Connection), so that if it closes,
     *            a new registration will not be deregistered by the old MatsSocketSession realizing that it is closed
     *            and then invoking {@link #deregisterSessionFromThisNode(String, String)}
     */
    void deregisterSessionFromThisNode(String matsSocketSessionId, String connectionId)
            throws DataAccessException;

    /**
     * Invoked when the client explicitly tells us that he closed this session, CLOSE_SESSION. This deletes the session
     * instance, and any messages in queue for it. No new incoming REPLYs, SENDs or RECEIVEs for this SessionId will be
     * sent anywhere. (Notice that the implementation might still store any new outboxed messages, not checking that the
     * session actually exists. But this SessionId will never be reused (exception without extreme "luck"), and the
     * implementation will periodically purge such non-session-attached outbox messages).
     *
     * @param matsSocketSessionId
     *            the MatsSocketSessionId that should be closed.
     */
    void closeSession(String matsSocketSessionId) throws DataAccessException;

    // ---------- Inbox ----------

    /**
     * Stores the incoming message Id, to avoid double delivery. If the messageId already exists, a
     * {@link ClientMessageIdAlreadyExistsException} will be raised.
     *
     * @param matsSocketSessionId
     *            the MatsSocketSessionId for which to store the incoming message id.
     * @param clientMessageId
     *            the client's message Id for the incoming message.
     * @throws ClientMessageIdAlreadyExistsException
     *             if this messageId already existed for this SessionId.
     */
    void storeMessageIdInInbox(String matsSocketSessionId, String clientMessageId)
            throws ClientMessageIdAlreadyExistsException, DataAccessException;

    void updateMessageInInbox(String matsSocketSessionId, String clientMessageId, String messageJson,
            byte[] messageBinary) throws DataAccessException;

    StoredInMessage getMessageFromInbox(String matsSocketSessionId, String clientMessageId) throws DataAccessException;

    /**
     * Deletes the incoming message Ids, as we've established that the client will never try to send this particular
     * message again.
     *
     * @param matsSocketSessionId
     *            the MatsSocketSessionId for which to delete the incoming message id.
     * @param clientMessageIds
     *            the client's message Ids for the incoming messages to delete.
     */
    void deleteMessageIdsFromInbox(String matsSocketSessionId, Collection<String> clientMessageIds)
            throws DataAccessException;

    // ---------- Outbox ----------

    /**
     * Stores the message for the Session, returning the nodename for the node holding the session, if any. If the
     * session is closed/timed out, the message won't be stored (i.e. dumped on the floor) and the return value is
     * empty. The ServerMessageId is set by the CSAF, and available when {@link #getMessagesFromOutbox(String, int)
     * getting messages}.
     *
     * @param matsSocketSessionId
     *            the matsSocketSessionId that the message is meant for.
     * @param serverMessageId
     *            unique id for outbox'ed message within the MatsSocketSessionId. Must be long enough that it is
     *            <b><i>extremely</i></b> unlikely that is collides with another message within the same
     *            MatsSocketSessionId - but do remember that there will at any one time be pretty few messages in the
     *            outbox for a given MatsSocketSession.
     * @param clientMessageId
     *            For Client Replies to requests from Server-to-Client, this is the Client's Message Id, which we need
     *            to send back in the message-received acknowledgement.
     * @param traceId
     *            the server-side traceId for this message.
     * @param type
     *            the type of the reply, currently "REPLY".
     * @param envelope
     *            the JSON-serialized MatsSocket Envelope <b>- without the 'msg' field set</b>.
     * @param messageJson
     *            the JSON-serialized message - the piece that sits in the 'msg' field of the Envelope.
     * @param messageBinary
     *            the binary part of an outgoing message.
     * @return the current node holding MatsSocket Session, or empty if none.
     */
    Optional<CurrentNode> storeMessageInOutbox(String matsSocketSessionId, String serverMessageId,
            String clientMessageId, String traceId,
            MessageType type, String envelope, String messageJson, byte[] messageBinary)
            throws DataAccessException;

    /**
     * Fetch a set of messages, up to 'maxNumberOfMessages' - but do not include messages that have been attempted
     * delivered already (marked with {@link #outboxMessagesAttemptedDelivery(String, Collection)}). If
     * {@link #outboxMessagesUnmarkAttemptedDelivery(String)} is invoked, that mark will be unset.
     *
     * @param matsSocketSessionId
     *            the matsSocketSessionId that the message is meant for.
     * @param maxNumberOfMessages
     *            the maximum number of messages to fetch.
     * @return a list of json encoded messages destined for the WebSocket.
     */
    List<StoredOutMessage> getMessagesFromOutbox(String matsSocketSessionId, int maxNumberOfMessages)
            throws DataAccessException;

    /**
     * Marks the specified messages as attempted delivered and notches the {@link StoredOutMessage#getDeliveryCount()}
     * one up. If {@link #outboxMessagesUnmarkAttemptedDelivery(String)} is invoked (typically on reconnect), the mark
     * will be unset, but the delivery count will stay in place - this is to be able to abort delivery attempts if there
     * is something wrong with the message.
     *
     * @param matsSocketSessionId
     *            the matsSocketSessionId that the serverMessageIds refers to.
     * @param serverMessageIds
     *            which messages failed delivery.
     */
    void outboxMessagesAttemptedDelivery(String matsSocketSessionId, Collection<String> serverMessageIds)
            throws DataAccessException;

    /**
     * When this method is invoked, {@link #getMessagesFromOutbox(String, int)} will again return messages that has
     * previously been marked as attempted delivered with {@link #outboxMessagesAttemptedDelivery(String, Collection)}.
     * Notice that the {@link StoredOutMessage#getDeliveryCount()} will not be reset, but the
     * {@link StoredOutMessage#getAttemptTimestamp()} will now again return null.
     *
     * @param matsSocketSessionId
     *            the matsSocketSessionId whose messages now shall be attempted.
     */
    void outboxMessagesUnmarkAttemptedDelivery(String matsSocketSessionId) throws DataAccessException;

    /**
     * States that the messages are delivered. Will typically delete the message.
     *
     * @param matsSocketSessionId
     *            the matsSocketSessionId that the serverMessageIds refers to.
     * @param serverMessageIds
     *            which messages are complete.
     */
    void outboxMessagesComplete(String matsSocketSessionId, Collection<String> serverMessageIds)
            throws DataAccessException;

    /**
     * States that the messages overran the accepted number of delivery attempts.
     *
     * @param matsSocketSessionId
     *            the matsSocketSessionId that the serverMessageIds refers to.
     * @param serverMessageIds
     *            which messages should be DLQed.
     */
    void outboxMessagesDeadLetterQueue(String matsSocketSessionId, Collection<String> serverMessageIds)
            throws DataAccessException;

    // ---------- "Request box" ----------

    void storeRequestCorrelation(String matsSocketSessionId, String serverMessageId, long requestTimestamp,
            String replyTerminatorId, String correlationString, byte[] correlationBinary) throws DataAccessException;

    Optional<RequestCorrelation> getAndDeleteRequestCorrelation(String matsSocketSessionId, String serverMessageId)
            throws DataAccessException;

    // ---------- Exceptions and DTOs ----------

    /**
     * Thrown from {@link #registerSessionAtThisNode(String, String, String, String, String, String)} if the userId does
     * not match the original userId that created this session.
     */
    class WrongUserException extends Exception {
        public WrongUserException(String message) {
            super(message);
        }
    }

    /**
     * Thrown if the operation resulted in a Unique Constraint situation. Relevant for
     * {@link #storeMessageIdInInbox(String, String)}.
     */
    class ClientMessageIdAlreadyExistsException extends Exception {
        public ClientMessageIdAlreadyExistsException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * If having problems accessing the underlying common data store.
     */
    class DataAccessException extends Exception {
        public DataAccessException(String message) {
            super(message);
        }

        public DataAccessException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    interface RequestCorrelation {
        String getMatsSocketSessionId();

        String getServerMessageId();

        long getRequestTimestamp();

        String getReplyTerminatorId();

        String getCorrelationString();

        byte[] getCorrelationBinary();
    }

    class SimpleRequestCorrelation implements RequestCorrelation {
        private final String _matsSocketSessionId;
        private final String _serverMessageId;
        private final long _requestTimestamp;
        private final String _replyTerminatorId;
        private final String _correlationString;
        private final byte[] _correlationBinary;

        public SimpleRequestCorrelation(String matsSocketSessionId, String serverMessageId, long requestTimestamp,
                String replyTerminatorId, String correlationString, byte[] correlationBinary) {
            _matsSocketSessionId = matsSocketSessionId;
            _serverMessageId = serverMessageId;
            _requestTimestamp = requestTimestamp;
            _correlationString = correlationString;
            _correlationBinary = correlationBinary;
            _replyTerminatorId = replyTerminatorId;
        }

        @Override
        public String getMatsSocketSessionId() {
            return _matsSocketSessionId;
        }

        public String getServerMessageId() {
            return _serverMessageId;
        }

        @Override
        public long getRequestTimestamp() {
            return _requestTimestamp;
        }

        @Override
        public String getReplyTerminatorId() {
            return _replyTerminatorId;
        }

        @Override
        public String getCorrelationString() {
            return _correlationString;
        }

        @Override
        public byte[] getCorrelationBinary() {
            return _correlationBinary;
        }
    }

    interface CurrentNode {
        String getNodename();

        String getConnectionId();
    }

    class SimpleCurrentNode implements CurrentNode {
        private final String nodename;
        private final String connectionId;

        public SimpleCurrentNode(String nodename, String connectionId) {
            this.nodename = nodename;
            this.connectionId = connectionId;
        }

        @Override
        public String getNodename() {
            return nodename;
        }

        @Override
        public String getConnectionId() {
            return connectionId;
        }
    }

    interface CsafSession {
        String getSessionId();

        String getUserId();

        String getNodename();

        String getConnectionId();

        long getCreatedTimestamp();

        long getLivelinessTimestamp();
    }

    class SimpleCsafSession implements CsafSession {
        private final String sessionId;
        private final String userId;
        private final String nodename;
        private final String connectionId;
        private final long createdTimestamp;
        private final long livelinessTimestamp;

        public SimpleCsafSession(String sessionId, String userId, String nodename, String connectionId,
                long createdTimestamp,
                long livelinessTimestamp) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.nodename = nodename;
            this.connectionId = connectionId;
            this.createdTimestamp = createdTimestamp;
            this.livelinessTimestamp = livelinessTimestamp;
        }

        @Override
        public String getSessionId() {
            return sessionId;
        }

        @Override
        public String getUserId() {
            return userId;
        }

        @Override
        public String getNodename() {
            return nodename;
        }

        @Override
        public String getConnectionId() {
            return connectionId;
        }

        @Override
        public long getCreatedTimestamp() {
            return createdTimestamp;
        }

        @Override
        public long getLivelinessTimestamp() {
            return livelinessTimestamp;
        }
    }

    interface StoredInMessage {
        String getMatsSocketSessionId();

        String getClientMessageId();

        long getStoredTimestamp();

        Optional<String> getFullEnvelope();

        Optional<byte[]> getMessageBinary();
    }

    class SimpleStoredInMessage implements StoredInMessage {
        private final String matsSocketSessionId;
        private final String clientMessageId;
        private final long storedTimeStamp;
        private final String fullEnvelope;
        private final byte[] messageBinary;

        public SimpleStoredInMessage(String matsSocketSessionId, String clientMessageId, long storedTimeStamp,
                String fullEnvelope, byte[] messageBinary) {
            this.matsSocketSessionId = matsSocketSessionId;
            this.clientMessageId = clientMessageId;
            this.storedTimeStamp = storedTimeStamp;
            this.fullEnvelope = fullEnvelope;
            this.messageBinary = messageBinary;
        }

        @Override
        public String getMatsSocketSessionId() {
            return matsSocketSessionId;
        }

        @Override
        public String getClientMessageId() {
            return clientMessageId;
        }

        @Override
        public long getStoredTimestamp() {
            return storedTimeStamp;
        }

        @Override
        public Optional<String> getFullEnvelope() {
            return Optional.ofNullable(fullEnvelope);
        }

        @Override
        public Optional<byte[]> getMessageBinary() {
            return Optional.ofNullable(messageBinary);
        }
    }

    interface StoredOutMessage {
        String getMatsSocketSessionId();

        String getServerMessageId();

        Optional<String> getClientMessageId();

        long getStoredTimestamp();

        Optional<Long> getAttemptTimestamp();

        int getDeliveryCount();

        String getTraceId();

        MessageType getType();

        String getEnvelope();

        String getMessageText();

        byte[] getMessageBinary();
    }

    class SimpleStoredOutMessage implements StoredOutMessage {
        private final String _matsSocketSessionId;
        private final String _serverMessageId;
        private final String _clientMessageId;
        private final long _storedTimestamp;
        private final Long _attemptTimestamp;
        private final int _deliveryCount;

        private final String _traceId;

        private final MessageType _type;
        private final String _envelope;
        private final String _messageText;
        private final byte[] _messageBinary;

        public SimpleStoredOutMessage(String matsSocketSessionId, String serverMessageId,
                String clientMessageId,
                long storedTimestamp, Long attemptTimestamp, int deliveryCount, String traceId,
                MessageType type, String envelope, String messageText, byte[] messageBinary) {
            _matsSocketSessionId = matsSocketSessionId;
            _serverMessageId = serverMessageId;
            _clientMessageId = clientMessageId;
            _storedTimestamp = storedTimestamp;
            _attemptTimestamp = attemptTimestamp;
            _deliveryCount = deliveryCount;
            _traceId = traceId;

            _type = type;
            _envelope = envelope;
            _messageText = messageText;
            _messageBinary = messageBinary;
        }

        @Override
        public String getMatsSocketSessionId() {
            return _matsSocketSessionId;
        }

        @Override
        public String getServerMessageId() {
            return _serverMessageId;
        }

        @Override
        public Optional<String> getClientMessageId() {
            return Optional.ofNullable(_clientMessageId);
        }

        @Override
        public long getStoredTimestamp() {
            return _storedTimestamp;
        }

        @Override
        public Optional<Long> getAttemptTimestamp() {
            return Optional.ofNullable(_attemptTimestamp);
        }

        @Override
        public int getDeliveryCount() {
            return _deliveryCount;
        }

        @Override
        public String getTraceId() {
            return _traceId;
        }

        @Override
        public MessageType getType() {
            return _type;
        }

        @Override
        public String getEnvelope() {
            return _envelope;
        }

        @Override
        public String getMessageText() {
            return _messageText;
        }

        @Override
        public byte[] getMessageBinary() {
            return _messageBinary;
        }
    }
}
