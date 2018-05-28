package com.stolsvik.mats;

import java.io.Closeable;
import java.util.UUID;

import org.slf4j.MDC;

import com.stolsvik.mats.MatsEndpoint.ProcessContext;
import com.stolsvik.mats.MatsEndpoint.ProcessTerminatorLambda;

/**
 * Provides a way to get a {@link MatsInitiate} instance "from the outside" of MATS, i.e. from a synchronous context. On
 * this instance, you invoke {@link #initiate(InitiateLambda)}, where the lambda will provide you with the necessary
 * {@link MatsInitiate} instance.
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public interface MatsInitiator extends Closeable {

    /**
     * Initiates a new message (request or invocation) out to an endpoint.
     *
     * @param lambda
     *            provides the {@link MatsInitiate} instance on which to create the message to be sent.
     */
    void initiate(InitiateLambda lambda);

    @FunctionalInterface
    interface InitiateLambda {
        void initiate(MatsInitiate msg);
    }

    /**
     * Closes any underlying backend resource.
     */
    @Override
    void close();

    /**
     * You must have access to an instance of this interface to initiate a MATS process.
     * <p>
     * To initiate a message "from the outside", i.e. from synchronous application code, get it by invoking
     * {@link MatsFactory#createInitiator()}, and then {@link MatsInitiator#initiate(InitiateLambda)} on that.
     * <p>
     * To initiate a new message "from the inside", i.e. while already inside a {@link MatsStage processing stage} of an
     * endpoint, get it by invoking {@link ProcessContext#initiate(InitiateLambda)}.
     *
     * @author Endre Stølsvik - 2015-07-11 - http://endre.stolsvik.com
     */
    interface MatsInitiate {
        /**
         * Sets (or appends with a "|" in case of {@link ProcessContext#initiate(InitiateLambda) initiation within a
         * stage}) the supplied <i>Trace Id</i>, which is solely used for logging and debugging purposes. It should be
         * unique, at least to a degree where it is <u>very</u> unlikely that you will have two identical traceIds
         * within a couple of years.
         * <p>
         * Since this is very important when doing distributed and asynchronous architectures, it is mandatory.
         * <p>
         * The traceId follows a MATS processing from the initiation until it is finished, usually in a Terminator.
         * <p>
         * It is highly suggested to use small, dense, information rich Trace Ids. Sticking in an UUID as Trace Id
         * certainly fulfils the uniqueness-requirement, but it is a crappy solution, as it by itself does not give any
         * hint of source, cause, relevant entities, or goal. <i>(It isn't even dense for the uniqueness an UUID gives,
         * which also is way above the required uniqueness unless you handle billions of such messages per minute. A
         * random alphanum (a-z,0-9) and much smaller string would give plenty enough uniqueness).</i> The following
         * would be a much better Trace Id, which follows some scheme that could be system wide:
         * "Web.placeOrder[cid:43512][cart:xa4ru5285fej]qz7apy9". From this example TraceId we could infer that it
         * originated at the <i>Web system</i>, it regards <i>Placing an order</i> for <i>Customer Id 43512</i>, it
         * regards the <i>Shopping Cart Id xa4ru5285fej</i>, and it contains some uniqueness ('qz7apy9') generated at
         * the initiating, so that even if the customer managed to click three times on the "place order" button for
         * the same cart, you would still be able to separate the resulting three different Mats call flows.
         * <p>
         * (For the default implementation "JMS Mats", the Trace Id is set on the {@link MDC} of the SLF4J logging
         * system, using the key "traceId". Since this implementation logs a few lines per handled message, in addition
         * to any log lines you emit yourself, you will, by collecting the log lines in a common log system (e.g. the
         * ELK stack), be able to very easily follow the processing trace through all the services the call flow
         * passed.)
         *
         * @param traceId
         *            some world-unique Id, preferably set all the way back when some actual person performed some event
         *            (e.g. in a "new order" situation, the Id would best be set when the user clicked the "place order"
         *            button - or maybe even derived from the event when he first initiated the shopping cart - or maybe
         *            even when he started the session. The point is that when using e.g. Kibana or Splunk to track
         *            events that led some some outcome, a robust, versatile and information-rich track/trace Id makes
         *            wonders).
         * @return the {@link MatsInitiate} for chaining.
         */
        MatsInitiate traceId(String traceId);

        /**
         * <b>Debugging feature:</b> Hint to the underlying implementation to which level of call and state history the
         * underlying protocol should retain.
         * <p>
         * <b>This is solely meant for debugging.</b> The resulting kept trace would typically be visible in a
         * "toString()" of the {@link ProcessContext}.
         *
         * @return the {@link MatsInitiate} for chaining.
         */
        MatsInitiate keepTrace(KeepTrace keepTrace);

        /**
         * <b>Enable unreliable, but fast, messaging!</b> Hint to the underlying implementation that it does not matter
         * that much if this message is lost. The implication is that the messages that this flow consist of are
         * unreliable - typically, if the MQ broker is restarted, any outstanding "non persistent" messages are lost.
         * (Also, some backends will loose the Dead Letter Queue (DLQ) functionality when this is used, where a ordinary
         * persistent message would be DLQed if it failed to be delivered to an endpoint. This can severely impact
         * monitoring and to a degree debugging.)
         * <p>
         * This is only usable for "pure GET"-style requests <i>without any state changes along the flow</i>, i.e.
         * "AccountService.getBalances", for display to an end user. If such a message is lost, the world won't go
         * under.
         * <p>
         * The upshot here is that non-persistent messaging typically is blazingly fast, as the messages will not have
         * to (transactionally) be stored in non-volatile storage. It is therefore wise to actually employ this feature
         * where it makes sense.
         *
         * @return the {@link MatsInitiate} for chaining.
         */
        MatsInitiate nonPersistent();

        /**
         * <b>Prioritize this message!</b> Hint to the underlying implementation that a human is actually waiting for
         * the result of a request, and that the flow therefore should be prioritized. This status will be kept through
         * the entire flow, so that all messages in the flow are prioritized. This makes it possible to use the same
         * "AccountService.getBalances" service both for the Web Application that the user facing GUI are employing, and
         * the batch processing of a ton of orders. Without such a feature, the interactive usage could be backlogged by
         * the batch process, while if the interactive flag is set, it will bypass the backlog of "ordinary" messages.
         * <p>
         * This implies that MATS defines two levels of prioritization: "Ordinary" and "Interactive". Most processing
         * should employ the default, i.e. "Ordinary", while places where <i><u>a human is actually waiting for the
         * reply</u></i> should employ the fast-lane, i.e. "Interactive". It is important here to not abuse this
         * feature, or else it will loose its value: If any batches are going to slow, nothing will be gained by setting
         * the interactive flag - instead use higher parallelism, by increasing {@link MatsConfig#setConcurrency(int)
         * concurrency} or the number of nodes running the problematic endpoint or stage (or just code it to be
         * faster!).
         * <p>
         * It will often make sense to set both this flag, and the {@link #nonPersistent()}, at the same time. E.g. when
         * you need to show the account balance for a customer: It both needs to skip past any bulk/batch set of such
         * requests (since a human is literally waiting for the result), but it is also a "pure GET"-style request, not
         * altering state whatsoever, so it can also be set to non-persistent.
         *
         * @return the {@link MatsInitiate} for chaining.
         */
        MatsInitiate interactive();

        /**
         * Sets the fictive originating/initiating "endpointId" - only used for tracing/debugging. If this message is
         * initiated from within a stage, i.e. by use of {@link ProcessContext#initiate(InitiateLambda)}, the 'from'
         * property is already set to the stageId of the currently processing Stage, but it can be overridden if
         * desired.
         * <p>
         * A typical value that would be of use when debugging a call trace is something following a structure like
         * <code>"OrderService.initiator.processOrder"</code>.
         *
         * @param initiatorId
         *            a fictive "endpointId" representing the "initiating endpoint" - only used for tracing/debugging. A
         *            typical value that would be of use when debugging a call trace is something following a structure
         *            like <code>"OrderService.initiator.processOrder"</code>.
         * @return the {@link MatsInitiate} for chaining.
         */
        MatsInitiate from(String initiatorId);

        /**
         * Sets which MATS Endpoint this message should go.
         *
         * @param endpointId
         *            to which MATS Endpoint this message should go.
         * @return the {@link MatsInitiate} for chaining.
         */
        MatsInitiate to(String endpointId);

        /**
         * Specified which MATS Endpoint the reply of the invoked Endpoint should go to.
         *
         * @param endpointId
         *            which MATS Endpoint the reply of the invoked Endpoint should go to.
         * @param replySto
         *            the object that should be provided as STO to the service which get the reply.
         * @return the {@link MatsInitiate} for chaining.
         */
        MatsInitiate replyTo(String endpointId, Object replySto);

        /**
         * A "pub-sub" variant of {@link #replyTo(String, Object) replyTo}, where the reply will go to the specified
         * endpointId which must be a
         * {@link MatsFactory#subscriptionTerminator(String, Class, Class, ProcessTerminatorLambda)
         * SubscriptionTerminator}.
         *
         * @param endpointId
         *            which MATS Endpoint the reply of the invoked Endpoint should go to.
         * @param replySto
         *            the object that should be provided as STO to the service which get the reply.
         * @return the {@link MatsInitiate} for chaining.
         */
        MatsInitiate replyToSubscription(String endpointId, Object replySto);

        /**
         * Adds a property that will "stick" with the call flow from this call on out. Read more on
         * {@link ProcessContext#setTraceProperty(String, Object)}.
         *
         * @param propertyName
         *            the name of the property
         * @param propertyValue
         *            the value of the property, which will be serialized using the active MATS serializer.
         * @see ProcessContext#setTraceProperty(String, Object)
         * @see ProcessContext#getTraceProperty(String, Class)
         */
        MatsInitiate setTraceProperty(String propertyName, Object propertyValue);

        /**
         * Adds a binary payload to the endpoint, e.g. a PDF document.
         *
         * @param key
         *            the key on which this is set. A typical logic is to just use an {@link UUID} as key, and then
         *            reference the payload key in the Request DTO.
         * @param payload
         *            the byte array.
         * @return the {@link MatsInitiate} for chaining.
         */
        MatsInitiate addBytes(String key, byte[] payload);

        /**
         * Adds a String payload to the endpoint, e.g. a XML document.
         * <p>
         * The rationale for having this is to not have to encode a largish string document inside the JSON structure
         * that carries the Request DTO.
         *
         * @param key
         *            the key on which this is set. A typical logic is to just use an {@link UUID} as key, and then
         *            reference the payload key in the Request DTO.
         * @param payload
         *            the string.
         * @return the {@link MatsInitiate} for chaining.
         */
        MatsInitiate addString(String key, String payload);

        /**
         * <i>The standard request initiation method</i>: All of from, to and replyTo must be set. A message is sent to
         * a service, and the reply from that service will come to the specified reply endpointId, typically a
         * terminator.
         *
         * @param requestDto
         *            the object which the endpoint will get as its incoming DTO (Data Transfer Object).
         */
        void request(Object requestDto);

        /**
         * <b>Variation of the request initiation method</b>, where the incoming state is sent along.
         * <p>
         * <b>This only makes sense if the same code base "owns" both the initiation code and the endpoint to which this
         * message is sent.</b> It is mostly here for completeness, since it is <i>possible</i> to send state along with
         * the message, but if employed between different services, it violates the premise that MATS is built on: State
         * is private to the stages of a multi-stage endpoint, and the Request and Reply DTOs are the public interface.
         *
         * @param requestDto
         *            the object which the target endpoint will get as its incoming DTO (Data Transfer Object).
         * @param initialTargetSto
         *            the object which the target endpoint will get as its STO (State Transfer Object).
         */
        void request(Object requestDto, Object initialTargetSto);

        /**
         * Sends a message to an endpoint, without expecting any reply ("fire-and-forget"). The 'reply' parameter must
         * not be set.
         * <p>
         * Note that the difference between request and invoke is only that replyTo is not set for invoke, otherwise the
         * mechanism is exactly the same.
         *
         * @param messageDto
         *            the object which the target endpoint will get as its incoming DTO (Data Transfer Object).
         */
        void send(Object messageDto);

        /**
         * <b>Variation of the {@link #send(Object)} method</b>, where the incoming state is sent along.
         * <p>
         * <b>This only makes sense if the same code base "owns" both the initiation code and the endpoint to which this
         * message is sent.</b> It is mostly here for completeness, since it is <i>possible</i> to send state along with
         * the message, but if employed between different services, it violates the premise that MATS is built on: State
         * is private to the stages of a multi-stage endpoint, and the Request and Reply DTOs are the public interface.
         *
         * @param messageDto
         *            the object which the target endpoint will get as its incoming DTO (Data Transfer Object).
         * @param initialTargetSto
         *            the object which the target endpoint will get as its STO (State Transfer Object).
         */
        void send(Object messageDto, Object initialTargetSto);

        /**
         * Sends a message to a
         * {@link MatsFactory#subscriptionTerminator(String, Class, Class, MatsEndpoint.ProcessTerminatorLambda)
         * SubscriptionTerminator}, employing the publish/subscribe pattern instead of message queues (topic in JMS
         * terms). <b>This means that all of the live servers that are listening to this endpointId will receive the
         * message, and if there are no live servers, then no one will receive it.</b>
         * <p>
         * The concurrency of a SubscriptionTerminator is always 1, as it only makes sense for there being only one
         * receiver per server - otherwise it would just mean that all of the active listeners on one server would get
         * the message, per semantics of the pub/sub.
         * <p>
         * It is only possible to publish to SubscriptionTerminators as employing publish/subscribe for multi-stage
         * services makes no sense.
         *
         * @param messageDto
         *            the object which the target endpoint will get as its incoming DTO (Data Transfer Object).
         */
        void publish(Object messageDto);

        /**
         * <b>Variation of the {@link #publish(Object)} method</b>, where the incoming state is sent along.
         * <p>
         * <b>This only makes sense if the same code base "owns" both the initiation code and the endpoint to which this
         * message is sent.</b> The possibility to send state along with the request makes most sense with the publish
         * method: A SubscriptionTerminator is often paired with a Terminator, where the Terminator receives the
         * message, typically a reply from a requested service, along with the state that was sent in the initiation.
         * The terminator does any "needs to be guaranteed to be performed" state changes to e.g. database, and then
         * passes the incoming message - <b>along with the same state it received</b> - on to a SubscriptionTerminator.
         * The SubscriptionTerminator performs any updates of any connected GUI clients, or for any other local states,
         * e.g. invalidation of caches, <i>on all live servers listening to that endpoint</i>, the point being that if
         * no servers are live at that moment, no one will process that message - but at the same time, there is
         * obviously no GUI clients connected, nor are there are local state in form of caches that needs to be
         * invalidated.
         *
         * @param messageDto
         *            the object which the target endpoint will get as its incoming DTO (Data Transfer Object).
         * @param initialTargetSto
         *            the object which the target endpoint will get as its STO (State Transfer Object).
         */
        void publish(Object messageDto, Object initialTargetSto);
    }

    /**
     * A hint to the underlying implementation of how much historic debugging information for the call flow should be
     * retained in the underlying protocol.
     */
    enum KeepTrace {
        /**
         * Keep all history for request and reply DTOs, and all history for state STOs.
         * <p>
         * All calls with data and state should be kept, which e.g means that at the Terminator, all request and reply
         * DTOs, and all STOs (with their changing values between each stage of a multi-stage endpoint) will be present
         * in the underlying protocol.
         */
        FULL,

        /**
         * <b>Default</b>: Nulls out Data for other than current call while still keeping the meta-info for the call
         * history, and condenses State to a pure stack.
         * <p>
         * This is a compromise between FULL and MINIMAL, where the DTOs and STOs except for the ones needed in the
         * stack, are "nulled out", while the call trace itself (with metadata) is still present, which e.g. means that
         * at the Terminator, you will know all endpoints and stages that the call flow traversed, but not the data or
         * state except for what is pertinent for the Terminator.
         */
        COMPACT,

        /**
         * Only keep the current call, and condenses State to a pure stack.
         * <p>
         * Keep <b>zero</b> history, where only the current call, and only the STOs needed for the current stack, are
         * present. This e.g. means that at the Terminator, no Calls nor DTOs and STOs except for the current incoming
         * to the Terminator will be present in the underlying protocol.
         */
        MINIMAL
    }
}
