package com.stolsvik.mats.impl.jms;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.stolsvik.mats.MatsEndpoint.MatsRefuseMessageException;
import com.stolsvik.mats.MatsEndpoint.ProcessContext;
import com.stolsvik.mats.MatsEndpoint.ProcessLambda;
import com.stolsvik.mats.MatsInitiator.KeepTrace;
import com.stolsvik.mats.MatsInitiator.MatsInitiate;
import com.stolsvik.mats.MatsInitiator.MessageReference;
import com.stolsvik.mats.impl.jms.JmsMatsInitiator.MessageReferenceImpl;
import com.stolsvik.mats.impl.jms.JmsMatsProcessContext.DoAfterCommitRunnableHolder;
import com.stolsvik.mats.serial.MatsSerializer;
import com.stolsvik.mats.serial.MatsSerializer.DeserializedMatsTrace;
import com.stolsvik.mats.serial.MatsTrace;
import com.stolsvik.mats.serial.MatsTrace.Call;
import com.stolsvik.mats.serial.MatsTrace.Call.MessagingModel;
import com.stolsvik.mats.serial.MatsTrace.KeepMatsTrace;

/**
 * The JMS implementation of {@link MatsInitiate}.
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 * @author Endre Stølsvik - 2020-01-17, extracted from {@link JmsMatsInitiator} - http://endre.stolsvik.com
 */
class JmsMatsInitiate<Z> implements MatsInitiate, JmsMatsStatics {
    private static final Logger log = LoggerFactory.getLogger(JmsMatsInitiate.class);

    private final JmsMatsFactory<Z> _parentFactory;
    private final List<JmsMatsMessage<Z>> _messagesToSend;
    private final JmsMatsMessageContext _jmsMatsMessageContext;
    private final DoAfterCommitRunnableHolder _doAfterCommitRunnableHolder;

    JmsMatsInitiate(JmsMatsFactory<Z> parentFactory, List<JmsMatsMessage<Z>> messagesToSend,
            JmsMatsMessageContext jmsMatsMessageContext,
            DoAfterCommitRunnableHolder doAfterCommitRunnableHolder) {
        _parentFactory = parentFactory;
        _messagesToSend = messagesToSend;
        _jmsMatsMessageContext = jmsMatsMessageContext;
        _doAfterCommitRunnableHolder = doAfterCommitRunnableHolder;

        reset();
    }

    private MatsTrace<Z> _existingMatsTrace;
    private Map<String, Object> _tracePropertiesSetSoFarInStage;

    JmsMatsInitiate(JmsMatsFactory<Z> parentFactory, List<JmsMatsMessage<Z>> messagesToSend,
            JmsMatsMessageContext jmsMatsMessageContext,
            DoAfterCommitRunnableHolder doAfterCommitRunnableHolder,
            MatsTrace<Z> existingMatsTrace, Map<String, Object> tracePropertiesSetSoFarInStage) {
        _parentFactory = parentFactory;
        _messagesToSend = messagesToSend;
        _jmsMatsMessageContext = jmsMatsMessageContext;
        _doAfterCommitRunnableHolder = doAfterCommitRunnableHolder;

        _existingMatsTrace = existingMatsTrace;
        _tracePropertiesSetSoFarInStage = tracePropertiesSetSoFarInStage;

        reset();
    }

    private String _traceId;
    private KeepMatsTrace _keepTrace;
    private boolean _nonPersistent;
    private boolean _interactive;
    private long _timeToLive;
    private boolean _noAudit;
    private String _from;
    private String _to;
    private String _replyTo;
    private boolean _replyToSubscription;
    private Object _replySto;
    private final LinkedHashMap<String, Object> _props = new LinkedHashMap<>();
    private final LinkedHashMap<String, byte[]> _binaries = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> _strings = new LinkedHashMap<>();

    private void reset() {
        // ?: Is this a initiation from within a Stage? (Not via a MatsInitiator "from the outside")
        if (_existingMatsTrace != null) {
            // -> Yes, initiation within a Stage.
            // Set the initial traceId - any setting of TraceId is appended.
            _traceId = _existingMatsTrace.getTraceId();
            // Set the initial from (initiatorId), which is the current processing stage
            _from = _existingMatsTrace.getCurrentCall().getTo().getId();

            // Copy over the properties which so far has been set in the stage (before this message is initiated).
            // (This is a reset() function, thus we must clear the map in case this is message #x, x>1).
            _props.clear();
            _props.putAll(_tracePropertiesSetSoFarInStage);
        }
        else {
            // -> No, this is an initiation from MatsInitiator, i.e. "from the outside".
            _traceId = null;
            _from = null;
            _props.clear();
        }

        // :: Set defaults
        // _traceId is set above.
        _keepTrace = KeepMatsTrace.FULL;
        _nonPersistent = false;
        _interactive = false;
        _timeToLive = 0;
        // _from is set above
        _to = null;
        _replyTo = null;
        _replyToSubscription = false;
        _replySto = null;
        // _props is cleared above
        _binaries.clear();
        _strings.clear();
    }

    @Override
    public MatsInitiate traceId(String traceId) {
        // ?: If we're an initiation from within a stage, append the traceId to the existing traceId, else set.
        _traceId = (_existingMatsTrace != null ? _existingMatsTrace.getTraceId() + '|' + traceId : traceId);
        // Also set this on the MDC so that we have it on log lines if it crashes within the initiation lambda
        // NOTICE: The MDC will always be reset to the existing, or overwritten with new, after initiation lambda
        // is finished, so this will not trash the traceId from an existing context.
        MDC.put(MDC_TRACE_ID, traceId);
        return this;
    }

    @Override
    public MatsInitiate keepTrace(KeepTrace keepTrace) {
        if (keepTrace == KeepTrace.MINIMAL) {
            _keepTrace = KeepMatsTrace.MINIMAL;
        }
        else if (keepTrace == KeepTrace.COMPACT) {
            _keepTrace = KeepMatsTrace.COMPACT;
        }
        else if (keepTrace == KeepTrace.FULL) {
            _keepTrace = KeepMatsTrace.FULL;
        }
        else {
            throw new IllegalArgumentException("Unknown KeepTrace enum [" + keepTrace + "].");
        }
        return this;
    }

    @Override
    public MatsInitiate nonPersistent() {
        nonPersistent(0);
        return this;
    }

    @Override
    public MatsInitiate nonPersistent(long timeToLiveMillis) {
        if (timeToLiveMillis < 0) {
            throw new IllegalArgumentException("timeToLive must be > 0");
        }
        _nonPersistent = true;
        _timeToLive = timeToLiveMillis;
        return this;
    }

    @Override
    public MatsInitiate interactive() {
        _interactive = true;
        return this;
    }

    @Override
    @Deprecated
    public MatsInitiate timeToLive(long timeToLiveMillis) {
        if (timeToLiveMillis < 0) {
            throw new IllegalArgumentException("timeToLive must be > 0");
        }
        _timeToLive = timeToLiveMillis;
        return this;
    }

    @Override
    public MatsInitiate noAudit() {
        _noAudit = true;
        return this;
    }

    @Override
    public MatsInitiate from(String initiatorId) {
        _from = initiatorId;
        return this;
    }

    @Override
    public MatsInitiate to(String endpointId) {
        _to = endpointId;
        return this;
    }

    @Override
    public MatsInitiate replyTo(String endpointId, Object replySto) {
        _replyTo = endpointId;
        _replySto = replySto;
        _replyToSubscription = false;
        return this;
    }

    @Override
    public MatsInitiate replyToSubscription(String endpointId, Object replySto) {
        _replyTo = endpointId;
        _replySto = replySto;
        _replyToSubscription = true;
        return this;
    }

    @Override
    public MatsInitiate setTraceProperty(String propertyName, Object propertyValue) {
        _props.put(propertyName, propertyValue);
        return this;
    }

    @Override
    public MatsInitiate addBytes(String key, byte[] payload) {
        _binaries.put(key, payload);
        return this;
    }

    @Override
    public MatsInitiate addString(String key, String payload) {
        _strings.put(key, payload);
        return this;
    }

    @Override
    public MessageReference request(Object requestDto) {
        return request(requestDto, null);
    }

    @Override
    public MessageReference request(Object requestDto, Object initialTargetSto) {
        long nanosStart = System.nanoTime();
        String msg = "All of 'traceId', 'from', 'to' and 'replyTo' must be set when request(..)";
        checkCommon(msg);
        if (_replyTo == null) {
            throw new NullPointerException(msg + ": Missing 'replyTo'.");
        }
        MatsSerializer<Z> ser = _parentFactory.getMatsSerializer();
        long now = System.currentTimeMillis();
        MatsTrace<Z> matsTrace = createMatsTrace(ser, now)
                .addRequestCall(_from, _to, MessagingModel.QUEUE,
                        _replyTo, (_replyToSubscription ? MessagingModel.TOPIC : MessagingModel.QUEUE),
                        ser.serializeObject(requestDto),
                        ser.serializeObject(_replySto),
                        ser.serializeObject(initialTargetSto));
        addDebugInfoToCurrentCall(now, matsTrace);

        copyOverAnyExistingTraceProperties(matsTrace);

        // Produce the new REQUEST JmsMatsMessage to send
        JmsMatsMessage<Z> request = produceJmsMatsMessage(log, nanosStart, _parentFactory.getMatsSerializer(),
                matsTrace, _props, _binaries, _strings, "new REQUEST",
                _parentFactory.getFactoryConfig().getName());
        _messagesToSend.add(request);

        // Reset, in preparation for more messages
        reset();

        return new MessageReferenceImpl(matsTrace.getCurrentCall().getMatsMessageId());
    }

    private MatsTrace<Z> createMatsTrace(MatsSerializer<Z> ser, long now) {
        String flowId = createFlowId(now);
        String debugInfo = _keepTrace != KeepMatsTrace.MINIMAL
                ? getInvocationPoint()
                : null;
        return ser.createNewMatsTrace(_traceId, flowId, _keepTrace, _nonPersistent, _interactive,
                _timeToLive, _noAudit)
                // NOTE! We set "from" both on the MatsTrace, AND on the initial Call, so that you can have the
                // origin of the flow even though it is in KeepTrace.MINIMAL mode.
                .withDebugInfo(_parentFactory.getFactoryConfig().getAppName(),
                        _parentFactory.getFactoryConfig().getAppVersion(),
                        _parentFactory.getFactoryConfig().getNodename(), _from, now, debugInfo);
    }

    private void addDebugInfoToCurrentCall(long now, MatsTrace<Z> matsTrace) {
        Call<Z> currentCall = matsTrace.getCurrentCall();

        currentCall.setDebugInfo(_parentFactory.getFactoryConfig().getAppName(),
                _parentFactory.getFactoryConfig().getAppVersion(),
                _parentFactory.getFactoryConfig().getNodename(), now,
                createMatsMessageId(matsTrace.getFlowId(), now, now, matsTrace.getCallNumber()),
                "#init#");
    }

    @Override
    public MessageReference send(Object messageDto) {
        return send(messageDto, null);
    }

    @Override
    public MessageReference send(Object messageDto, Object initialTargetSto) {
        long nanosStart = System.nanoTime();
        checkCommon("All of 'traceId', 'from' and 'to' must be set when send(..)");
        MatsSerializer<Z> ser = _parentFactory.getMatsSerializer();
        long now = System.currentTimeMillis();
        MatsTrace<Z> matsTrace = createMatsTrace(ser, now)
                .addSendCall(_from, _to, MessagingModel.QUEUE,
                        ser.serializeObject(messageDto), ser.serializeObject(initialTargetSto));
        addDebugInfoToCurrentCall(now, matsTrace);

        copyOverAnyExistingTraceProperties(matsTrace);

        // Produce the new SEND JmsMatsMessage to send
        JmsMatsMessage<Z> send = produceJmsMatsMessage(log, nanosStart, _parentFactory.getMatsSerializer(),
                matsTrace, _props, _binaries, _strings, "new SEND",
                _parentFactory.getFactoryConfig().getName());
        _messagesToSend.add(send);

        // Reset, in preparation for more messages
        reset();

        return new MessageReferenceImpl(matsTrace.getCurrentCall().getMatsMessageId());
    }

    @Override
    public MessageReference publish(Object messageDto) {
        return publish(messageDto, null);
    }

    @Override
    public MessageReference publish(Object messageDto, Object initialTargetSto) {
        long nanosStart = System.nanoTime();
        checkCommon("All of 'traceId', 'from' and 'to' must be set when publish(..)");
        MatsSerializer<Z> ser = _parentFactory.getMatsSerializer();
        long now = System.currentTimeMillis();
        MatsTrace<Z> matsTrace = createMatsTrace(ser, now)
                .addSendCall(_from, _to, MessagingModel.TOPIC,
                        ser.serializeObject(messageDto), ser.serializeObject(initialTargetSto));
        addDebugInfoToCurrentCall(now, matsTrace);

        copyOverAnyExistingTraceProperties(matsTrace);

        // Produce the new PUBLISH JmsMatsMessage to send
        JmsMatsMessage<Z> publish = produceJmsMatsMessage(log, nanosStart, _parentFactory.getMatsSerializer(),
                matsTrace, _props, _binaries, _strings, "new PUBLISH",
                _parentFactory.getFactoryConfig().getName());
        _messagesToSend.add(publish);

        // Reset, in preparation for more messages
        reset();

        return new MessageReferenceImpl(matsTrace.getCurrentCall().getMatsMessageId());
    }

    @Override
    public <R, S, I> void unstash(byte[] stash,
            Class<R> replyClass, Class<S> stateClass, Class<I> incomingClass,
            ProcessLambda<R, S, I> lambda) {

        long nanosStart = System.nanoTime();

        if (stash == null) {
            throw new NullPointerException("byte[] stash");
        }

        // :: Validate that this is a "MATSjmts" v.1 stash.
        validateByte(stash, 0, 77);
        validateByte(stash, 1, 65);
        validateByte(stash, 2, 84);
        validateByte(stash, 3, 83);
        validateByte(stash, 4, 106);
        validateByte(stash, 5, 109);
        validateByte(stash, 6, 116);
        validateByte(stash, 7, 115);
        validateByte(stash, 8, 1);

        // ----- Validated ok. Could have thrown in a checksum, but if foot-shooting is your thing, then go ahead.

        // ::: Get the annoying metadata

        // How many such fields are there. The idea is that we can add more fields in later revisions, and
        // just have older versions "jump over" the ones it does not know.
        int howManyZeros = stash[9];

        // :: Find zeros (field delimiters) - UTF-8 does not have zeros: https://stackoverflow.com/a/6907327/39334
        int zstartEndpointId = findZero(stash, 10); // Should currently be right there, at pos#10.
        int zstartStageId = findZero(stash, zstartEndpointId + 1);
        int zstartNextStageId = findZero(stash, zstartStageId + 1);
        int zstartMatsTraceMeta = findZero(stash, zstartNextStageId + 1);
        int zstartSystemMessageId = findZero(stash, zstartMatsTraceMeta + 1);
        // :: Here we'll jump over fields that we do not know, to be able to add more metadata in later revisions.
        int zstartMatsTrace = zstartSystemMessageId;
        for (int i = 5; i < howManyZeros; i++) {
            zstartMatsTrace = findZero(stash, zstartMatsTrace + 1);
        }

        // :: Metadata
        // :EndpointId
        String endpointId = new String(stash, zstartEndpointId + 1, zstartStageId - zstartEndpointId - 1,
                StandardCharsets.UTF_8);
        // :StageId
        String stageId = new String(stash, zstartStageId + 1, zstartNextStageId - zstartStageId - 1,
                StandardCharsets.UTF_8);
        // :NextStageId
        // If nextStageId == the special "no next stage" string, then null. Else get it.
        String nextStageId = (zstartMatsTraceMeta - zstartNextStageId
                - 1) == JmsMatsProcessContext.NO_NEXT_STAGE.length &&
                stash[zstartNextStageId + 1] == JmsMatsProcessContext.NO_NEXT_STAGE[0]
                        ? null
                        : new String(stash, zstartNextStageId + 1,
                                zstartMatsTraceMeta - zstartNextStageId - 1, StandardCharsets.UTF_8);
        // :MatsTrace Meta
        String matsTraceMeta = new String(stash, zstartMatsTraceMeta + 1,
                zstartSystemMessageId - zstartMatsTraceMeta - 1, StandardCharsets.UTF_8);
        // :MessageId
        String messageId = new String(stash, zstartSystemMessageId + 1,
                zstartMatsTrace - zstartSystemMessageId - 1, StandardCharsets.UTF_8);

        // :Actual MatsTrace:
        MatsSerializer<Z> matsSerializer = _parentFactory.getMatsSerializer();
        DeserializedMatsTrace<Z> deserializedMatsTrace = matsSerializer
                .deserializeMatsTrace(stash, zstartMatsTrace + 1,
                        stash.length - zstartMatsTrace - 1, matsTraceMeta);
        MatsTrace<Z> matsTrace = deserializedMatsTrace.getMatsTrace();

        // :: Current State: If null, make an empty object instead, unless Void, which is null.
        S currentSto = handleIncomingState(matsSerializer, stateClass, matsTrace.getCurrentState());

        // :: Current Call, incoming Message DTO
        Call<Z> currentCall = matsTrace.getCurrentCall();
        I incomingDto = handleIncomingMessageMatsObject(matsSerializer, incomingClass, currentCall.getData());

        double millisDeserializing = (System.nanoTime() - nanosStart) / 1_000_000d;

        log.info(LOG_PREFIX + "Unstashing message from [" + stash.length + " B] stash, R:[" + replyClass
                .getSimpleName() + "], S:[" + stateClass.getSimpleName() + "], I:[" + incomingClass.getSimpleName()
                + "]. From StageId:[" + matsTrace.getCurrentCall().getFrom() + "], This StageId:[" + stageId
                + "], NextStageId:[" + nextStageId + "] - deserializing took ["
                + millisDeserializing + " ms]");

        LinkedHashMap<String, Object> outgoingProps = new LinkedHashMap<>();
        Supplier<MatsInitiate> initiateSupplier = () -> new JmsMatsInitiate<>(_parentFactory,
                _messagesToSend, _jmsMatsMessageContext, _doAfterCommitRunnableHolder,
                matsTrace, outgoingProps);

        _parentFactory.setCurrentThreadLocalMatsDemarcation(initiateSupplier);

        // :: Invoke the process lambda (the actual user code).
        try {
            JmsMatsProcessContext<R, S, Z> processContext = new JmsMatsProcessContext<>(
                    _parentFactory,
                    endpointId,
                    stageId,
                    messageId,
                    nextStageId,
                    stash, zstartMatsTrace + 1, stash.length - zstartMatsTrace - 1,
                    matsTraceMeta, matsTrace,
                    currentSto, initiateSupplier,
                    new LinkedHashMap<>(), new LinkedHashMap<>(),
                    _messagesToSend, _jmsMatsMessageContext,
                    outgoingProps,
                    _doAfterCommitRunnableHolder);

            JmsMatsContextLocalCallback.bindResource(ProcessContext.class, processContext);

            lambda.process(processContext, currentSto, incomingDto);
        }
        catch (MatsRefuseMessageException e) {
            throw new IllegalStateException("Cannot throw MatsRefuseMessageException when unstash()'ing!"
                    + " You should have done that when you first received the message, before"
                    + " stash()'ing it.", e);
        }
        finally {
            _parentFactory.clearCurrentThreadLocalMatsDemarcation();
            JmsMatsContextLocalCallback.unbindResource(ProcessContext.class);
        }

        // No need to reset() here, as we've not touched the _from, _to, etc..
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getAttribute(Class<T> type, String... name) {
        // TODO: Way to stick in MatsFactory-configured attributes. Notice: both in ProcessContext and Initiate.
        // ?: Is this a query for SQL Connection, without any names?
        if ((type == Connection.class) && (name.length == 0)) {
            // -> Yes, then it is the default transactional SQL Connection.
            return (Optional<T>) _jmsMatsMessageContext.getSqlConnection();
        }
        return Optional.empty();
    }

    private static void validateByte(byte[] stash, int idx, int value) {
        if (stash[idx] != value) {
            throw new IllegalArgumentException("The stash bytes shall start with ASCII letters 'MATSjmts' and then"
                    + " a byte denoting the version. Index [" + idx + "] should be [" + value
                    + "], but was [" + stash[idx] + "].");
        }
    }

    private static int findZero(byte[] stash, int fromIndex) {
        try {
            int t = fromIndex;
            while (stash[t] != 0) {
                t++;
            }
            return t;
        }
        catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("The stash byte array does not contain the zeros I expected,"
                    + " starting from index [" + fromIndex + "]");
        }
    }

    private void copyOverAnyExistingTraceProperties(MatsTrace<Z> matsTrace) {
        // ?: Do we have an existing MatsTrace (implying that we are being initiated within a Stage)
        if (_existingMatsTrace != null) {
            // -> Yes, so copy over existing Trace Properties
            for (String key : _existingMatsTrace.getTracePropertyKeys()) {
                matsTrace.setTraceProperty(key, _existingMatsTrace.getTraceProperty(key));
            }
        }
    }

    private void checkCommon(String msg) {
        if ((_timeToLive > 0) && (!_nonPersistent)) {
            throw new IllegalStateException("TimeToLive is set [" + _timeToLive
                    + "], but message is not NonPersistent - illegal combination.");
        }
        if (_traceId == null) {
            throw new NullPointerException(msg + ": Missing 'traceId'.");
        }
        if (_from == null) {
            throw new NullPointerException(msg + ": Missing 'from'.");
        }
        if (_to == null) {
            throw new NullPointerException(msg + ": Missing 'to'.");
        }
    }

    @Override
    public String toString() {
        return idThis();
    }
}
