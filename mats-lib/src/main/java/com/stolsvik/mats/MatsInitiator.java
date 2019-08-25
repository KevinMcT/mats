package com.stolsvik.mats;

import java.io.Closeable;
import java.util.Optional;

import org.slf4j.MDC;

import com.stolsvik.mats.MatsEndpoint.DetachedProcessContext;
import com.stolsvik.mats.MatsEndpoint.ProcessContext;
import com.stolsvik.mats.MatsEndpoint.ProcessLambda;
import com.stolsvik.mats.MatsEndpoint.ProcessTerminatorLambda;

/**
 * Provides a way to get a {@link MatsInitiate} instance "from the outside" of MATS, i.e. from a synchronous context. On
 * this instance, you invoke {@link #initiate(InitiateLambda)}, where the lambda will provide you with the necessary
 * {@link MatsInitiate} instance.
 * <p/>
 * <b>Notice: This class is Thread Safe</b> - you are not supposed to make one instance per message initiation, but
 * rather make one (or a few) for the entire application, and use it/them for all your initiation needs.
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public interface MatsInitiator extends Closeable {

    /**
     * @return the name of this <code>MatsInitiator</code>. The {@link MatsFactory#getDefaultInitiator() default
     *         initiator}'s name is 'default'.
     */
    String getName();

    /**
     * Initiates a new message (request or invocation) out to an endpoint.
     *
     * @param lambda
     *            provides the {@link MatsInitiate} instance on which to create the message to be sent.
     * @throws MatsBackendException
     *             if the Mats implementation cannot connect to the underlying message broker, or are having problems
     *             interacting with it.
     * @throws MatsMessageSendException
     *             if the Mats implementation cannot send the messages after it has executed the initiation lambda and
     *             committed external resources - please read the JavaDoc of that class.
     */
    void initiate(InitiateLambda lambda) throws MatsBackendException, MatsMessageSendException;

    /**
     * Initiates a new message (request or invocation) out to an endpoint - where the two error conditions are raised as
     * unchecked exceptions (But please understand the implications of {@link MatsMessageSendRuntimeException}).
     *
     * @param lambda
     *            provides the {@link MatsInitiate} instance on which to create the message to be sent.
     * @throws MatsBackendRuntimeException
     *             if the Mats implementation cannot connect to the underlying message broker, or are having problems
     *             interacting with it.
     * @throws MatsMessageSendRuntimeException
     *             if the Mats implementation cannot send the messages after it has executed the initiation lambda and
     *             committed external resources - please read the JavaDoc of {@link MatsMessageSendException}.
     */
    void initiateUnchecked(InitiateLambda lambda) throws MatsBackendRuntimeException,
            MatsMessageSendRuntimeException;

    /**
     * Will be thrown by the {@link MatsInitiator#initiate(InitiateLambda)}-method if it is not possible at this time to
     * establish a connection to the underlying messaging system (e.g. to ActiveMQ if used in JMS implementation with
     * ActiveMQ as JMS Message Broker), or if there was any other kind of problems interacting with the it. Do note that
     * in all cases of this exception, any other external resource (typically database) will not have been committed -
     * unlike if you get the {@link MatsMessageSendException}.
     */
    class MatsBackendException extends Exception {
        public MatsBackendException(String message) {
            super(message);
        }

        public MatsBackendException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Will be thrown by the {@link MatsInitiator#initiate(InitiateLambda)}-method if Mats fails to send the messages
     * after the {@link InitiateLambda} has been run, any external resource (typically DB) has been committed, <b>and
     * then</b> some situation occurs that makes it impossible to send out messages. <i>(Some developers might recognize
     * this as the <i>"VERY BAD!-initiation"</i> situation)</i>.
     * <p/>
     * This is a rare, but unfortunate situation, but which is hard to guard completely against, in particular in the
     * "Best Effort 1-Phase Commit" paradigm that the current Mats implementations runs on. What it means, is that if
     * you e.g. in the initiate-lambda did some "job allocation" logic on a table in a database, and based on that
     * allocation sent out e.g. 5 messages, the job <i>allocation</i> will now have happened, but the <i>messages have
     * not actually been sent</i>. The result is that in the database, you will see those jobs as processed
     * (semantically "started processing"), but in reality the downstream endpoints never started working on them since
     * the message was not actually sent out.
     * <p/>
     * This situation can to a degree be alleviated if you catch this exception, and then use a <i>compensating
     * transaction</i> to de-allocate the jobs in the database again. However, since bad things often happen in
     * clusters, you might not be able to do the de-allocation either (due to the database becoming inaccessible at the
     * same instant - e.g. the reason that the messages could not be set was that the network cable became unplugged, or
     * that this node actually lost power at that instant). A way to at least catch when this happens, is to employ a
     * state machine to the job allocation logic: First pick jobs for this node by setting the state column of
     * job-entries whose state is "UNPROCESSED" to some status like "ALLOCATED" (along with a column of which node
     * allocated them (i.e. "hostname" of this node) and a column for timestamp of when they were allocated). In the
     * initiator, you pick the jobs that was allocated to this node, set the status to "SENT" and send the outgoing
     * messages. Finally, in the terminator endpoint (which you specify in the initiation), you set the status to
     * "DONE". Then you make a health check: Assuming that in normal conditions such jobs should always be processed in
     * seconds, you make a health check that scans the table for rows which have been in the "ALLOCATED" or "SENT"
     * status for e.g. 15 minutes: Such rows are very suspicious, and should be checked up by humans. Sitting in
     * "ALLOCATED" status would imply that the node that allocated the job went down (and has not (yet) come back up)
     * before it managed to initiate the messages, while sitting in "SENT" would imply that the message had started, but
     * not gotten through the processing: Either that message flow sits in a downstream Dead Letter Queue due to some
     * error, or you ended up in the situation explained here: The database commit went through, but the messages was
     * not sent.
     * <p/>
     * Please note that this should, in a somewhat stable operations environment, happen extremely seldom: What needs to
     * occur for this to happen, is that in the sliver of time between the commit of the database and the commit of the
     * message broker, this node crashes, the network is lost, or the message broker goes down. Given that a check for
     * broker liveliness is performed right before the database commit, that time span is very tight. But to make the
     * most robust systems that can monitor themselves, you should consider employing a state machine handling as
     * outlined above. You might never see that health check trip, but now you can at least sleep without thinking about
     * that 1 billion dollar order that was never processed.
     * <p/>
     * PS: Best effort 1PC: Two transactions are opened: one for the message broker, and one for the database. The
     * business logic and possibly database reads and changes are performed. The database is committed first, as that
     * has many more failure scenarios than the message systems, e.g. data or code problems giving integrity constraint
     * violations, and spurious stuff like MS SQL's deadlock victim, etc. Then the message queue is committed, as the
     * only reason for the message broker to not handle a commit is basically that you've had infrastructure problems
     * like connectivity issues or that the broker has crashed.
     * <p/>
     * Notice that it has been decided to not let this exception extend the {@link MatsBackendException}, even though it
     * is definitely a backend problem. The reason is that it in all situations where {@code MatsBackendException} is
     * raised, the other resources have not been committed yet, as opposed to situations where
     * {@code MatsMessageSendException} is raised. Luckily, in this time and age, we have multi-exception catch blocks
     * if you want to handle both the same.
     */
    class MatsMessageSendException extends Exception {
        public MatsMessageSendException(String message) {
            super(message);
        }

        public MatsMessageSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Unchecked variant of the {@link MatsBackendException}, thrown from the {@link #initiateUnchecked(InitiateLambda)}
     * variant of initiate().
     */
    class MatsBackendRuntimeException extends RuntimeException {
        public MatsBackendRuntimeException(String message) {
            super(message);
        }

        public MatsBackendRuntimeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Unchecked variant of the {@link MatsMessageSendException}, thrown from the
     * {@link #initiateUnchecked(InitiateLambda)} variant of initiate().
     */
    class MatsMessageSendRuntimeException extends RuntimeException {
        public MatsMessageSendRuntimeException(String message) {
            super(message);
        }

        public MatsMessageSendRuntimeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

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
     * <p/>
     * To initiate a message "from the outside", i.e. from synchronous application code, get it by invoking
     * {@link MatsFactory#getDefaultInitiator()}, and then {@link MatsInitiator#initiate(InitiateLambda)} on that.
     * <p/>
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
         * <p/>
         * Since this is very important when doing distributed and asynchronous architectures, it is mandatory.
         * <p/>
         * The traceId follows a MATS processing from the initiation until it is finished, usually in a Terminator.
         * <p/>
         * <b>It is strongly recommended to use small, dense, information rich Trace Ids.</b> Sticking in an UUID as
         * Trace Id certainly fulfils the uniqueness-requirement, but it is a crappy solution, as it by itself does not
         * give any hint of source, cause, relevant entities, or goal. <i>(It isn't even dense for the uniqueness an
         * UUID gives, which also is way above the required uniqueness unless you handle billions of such messages per
         * minute. A random alphanum (a-z,0-9) string of much smaller length would give plenty enough uniqueness).</i>
         * The following would be a much better Trace Id than a random UUID, which follows some scheme that could be
         * system wide: "Web.placeOrder[cid:43512][cart:xa4ru5285fej]qz7apy9". From this example TraceId we could infer
         * that it originated at the <i>Web system</i>, it regards <i>Placing an order</i> for <i>Customer Id 43512</i>,
         * it regards the <i>Shopping Cart Id xa4ru5285fej</i>, and it contains some uniqueness ('qz7apy9') generated at
         * the initiating, so that even if the customer managed to click three times on the "place order" button for the
         * same cart, you would still be able to separate the resulting three different Mats call flows.
         * <p/>
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
         * <p/>
         * <b>This is solely meant for debugging.</b> The resulting kept trace would typically be visible in a
         * "toString()" of the {@link ProcessContext} - or in an external (e.g. Brokerside) debugging/tracing system.
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
         * monitoring (as you don't get a build-up of DLQs when things goes wrong - only log lines) and to a degree
         * debugging (since you don't have the DLQ'ed messages to look at).)
         * <p/>
         * <b>This is only usable for "pure GET"-style requests <i>without <u>any</u> state changes along the flow</i>,
         * i.e. "AccountService.getBalances", for display to an end user.</b> If such a message is lost, the world won't
         * go under.
         * <p/>
         * The upshot here is that non-persistent messaging typically is blazingly fast and is way less resource
         * demanding, as the messages will not have to (transactionally) be stored in non-volatile storage. It is
         * therefore wise to actually employ this feature where it makes sense (which, again, is <i>only</i> relevant
         * for side-effect free GET-style requests).
         *
         * @return the {@link MatsInitiate} for chaining.
         */
        MatsInitiate nonPersistent();

        /**
         * <b>Prioritize this message & flow!</b> Hint to the underlying implementation that a human is actually waiting
         * for the result of a request, and that the flow therefore should be prioritized. This status will be kept
         * through the entire flow, so that all messages in the flow are prioritized. This makes it possible to use the
         * same "AccountService.getBalances" service both for the Web Application that the user facing GUI are
         * employing, and the batch processing of a ton of orders. Without such a feature, the interactive usage could
         * be backlogged by the batch process, while if the interactive flag is set, it will bypass the backlog of
         * "ordinary" messages.
         * <p/>
         * This implies that MATS defines two levels of prioritization: "Ordinary" and "Interactive". Most processing
         * should employ the default, i.e. "Ordinary", while places where <i><u>a human is actually waiting for the
         * reply</u></i> should employ the fast-lane, i.e. "Interactive". It is important here to not abuse this
         * feature, or else it will loose its value: If batches are going too slow, nothing will be gained by setting
         * the interactive flag except destroying the entire point of this feature. Instead use higher parallelism: By
         * increasing {@link MatsConfig#setConcurrency(int) concurrency}, or the number of nodes, running the
         * problematic endpoint or stage; increase the speed and/or throughput of external systems like the database; or
         * somehow just code the whole thing to be faster!
         * <p/>
         * It will often make sense to set both this flag, and the {@link #nonPersistent()}, at the same time. E.g. when
         * you need to show the account balance for a customer: It both needs to skip past any bulk/batch set of such
         * requests (since a human is literally waiting for the result), but it is also a "pure GET"-style request, not
         * altering state whatsoever, so it can also be set to non-persistent.
         *
         * @return the {@link MatsInitiate} for chaining.
         */
        MatsInitiate interactive();

        /**
         * Sets the Time To Live for this message. If the message gets this old before being delivered to the receiving
         * Mats endpoint, it will be deleted and never delivered.
         * <p/>
         * This functionality often makes sense for messages that are <b>both</b> {@link #interactive() interactive} and
         * {@link #nonPersistent() non-persistent}: Such messages shall only be "getters" free of any side effects (i.e.
         * no state is changed by the entire message flow), and where a human is actively waiting for the reply. If
         * there is a situation where such messages aren't consumed due to the receiving service having problems, it
         * does not make sense to use processing resources to handle a massive stack of these messages when the
         * consumption is restored an hour later, as e.g. the synchronously waiting HTTP call has timed out, and the
         * waiting human is probably long gone anyway.
         * <p/>
         * <b>Notice on use:</b> This should <b>NOT</b> be employed for message flows where any stage might change any
         * state, i.e. message flows with side effects (Think "PUT", "POST" and "DELETE"-style messages) - which also
         * should <b>NOT</b> employ {@link #nonPersistent() non-persistent} messaging. The rationale is that such
         * messages should never just cease to exists, for any reason - they should have both guaranteed delivery and
         * execution. You should also never use this to handle any business logic, e.g. some kind of order time-out
         * where an order is only valid until 21:00, or something like this. This both because of the <i>"Note on
         * implementation"</i> below, and that the entire facility of "time to live" is optional both for Mats and for
         * the underlying message queue system.
         * <p/>
         * <b>Notice on implementation:</b> If the message is a part of a multi-message flow, which most Mats
         * initiations pretty much invariably is (a request consists of a request-message and a reply-message), this TTL
         * will be set afresh on every new message in the flow, possibly with the amount of time taken in the processing
         * of the stage deducted. <u>However, the time that the message waited in queue will not be deducted.</u> The
         * effective TTL of the flow might therefore be a multiple of what is set here. An example: The TTL of an
         * initiation is set to 5000 ms. The request message stays 4 seconds in queue, before being received and
         * processed, where the processing took 100 ms. The reply-message will thus have its TTL set to 4900 ms: 5000 ms
         * TTL - 100 ms for processing. The reply message stays 4 seconds in queue before being received. The total
         * "time in flight" has now been 8.1 seconds, and there was still 900 ms left of the reply-message's TTL. The
         * rationale for not deducting queue-time on the subsequent message is that there is no easy way to get the
         * "queue time" which does not involve taking the difference between two timestamps, but in a multi-server
         * architecture there is a clear possibility of clock skews between different services, even instances of the
         * same service. You could then deduce a too high queue time, deducting a too high value from the
         * reply-message's TTL, and effectively time out the full message flow too early. However, for the intended use
         * case - to hinder build-up of messages that will nevertheless be valueless when the answer is received since
         * the interactively waiting human is long gone - this is no big problem.
         * 
         * @param millis
         *            the number of milliseconds before this message is timed out and thus will never be delivered - 0
         *            means "live forever", and this is the default.
         * @return the {@link MatsInitiate} for chaining.
         */
        MatsInitiate timeToLive(long millis);

        /**
         * Sets the fictive originating/initiating free-form "endpointId" - only used for tracing/debugging. If this
         * message is initiated from within a stage, i.e. by use of {@link ProcessContext#initiate(InitiateLambda)}, the
         * 'from' property is already set to the stageId of the currently processing Stage, but it can be
         * <b>overridden</b> if desired.
         * <p/>
         * A typical value that would be of use when debugging a call trace is something following a structure like
         * <code>"OrderService.initiator.processOrder"</code>.
         * <p/>
         * <b>NOTE:</b> This is only used for tracing/debugging, <b>and is free-form</b>. If the initiation is based on
         * a HTTP call, e.g. a REST endpoint, it is suggested to add the URL, e.g.
         * <code>"OrderService.initiator.processOrder:orders/place_order?cartId=12345"</code>
         *
         * @param initiatorId
         *            a fictive, free-form "endpointId" representing the "initiating endpoint" - only used for
         *            tracing/debugging. A typical value that would be of use when debugging a call trace is something
         *            following a structure like <code>"OrderService.initiator.processOrder"</code>, or if the
         *            initiation is based on a HTTP call, e.g. a REST endpoint, it is suggested to add the URL, e.g.
         *            <code>"OrderService.initiator.processOrder:orders/place_order?cartId=12345"</code>
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
         * Adds a binary payload to the outgoing request message, e.g. a PDF document.
         * <p/>
         * The rationale for having this is to not have to encode a largish byte array inside the JSON structure that
         * carries the Request DTO - byte arrays represent very badly in JSON.
         * <p/>
         * Note: The byte array is not compressed (as might happen with the DTO), so if the payload is large, you might
         * want to consider compressing it before attaching it (and will then have to decompress it on the receiving
         * side).
         *
         * @param key
         *            the key on which to store the byte array payload. The receiver will have to use this key to get
         *            the payload out again, so either it will be a specific key that the sender and receiver agree
         *            upon, or you could generate a random key, and reference this key as a field in the Request DTO.
         * @param payload
         *            the byte array.
         * @return the {@link MatsInitiate} for chaining.
         * @see #addString(String, String)
         */
        MatsInitiate addBytes(String key, byte[] payload);

        /**
         * Adds a String payload to the outgoing request message, e.g. a XML, JSON or CSV document.
         * <p/>
         * The rationale for having this is to not have to encode a largish string document inside the JSON structure
         * that carries the Request DTO.
         * <p/>
         * Note: The String payload is not compressed (as might happen with the DTO), so if the payload is large, you
         * might want to consider compressing it before attaching it and instead use the
         * {@link #addBytes(String, byte[]) addBytes(..)} method (and will then have to decompress it on the receiving
         * side).
         *
         * @param key
         *            the key on which to store the String payload. The receiver will have to use this key to get the
         *            payload out again, so either it will be a specific key that the sender and receiver agree upon, or
         *            you could generate a random key, and reference this key as a field in the Request DTO.
         * @param payload
         *            the string.
         * @return the {@link MatsInitiate} for chaining.
         * @see #addBytes(String, byte[])
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
        MessageReference request(Object requestDto);

        /**
         * <b>Variation of the request initiation method</b>, where the incoming state is sent along.
         * <p/>
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
        MessageReference request(Object requestDto, Object initialTargetSto);

        /**
         * Sends a message to an endpoint, without expecting any reply ("fire-and-forget"). The 'reply' parameter must
         * not be set.
         * <p/>
         * Note that the difference between request and invoke is only that replyTo is not set for invoke, otherwise the
         * mechanism is exactly the same.
         *
         * @param messageDto
         *            the object which the target endpoint will get as its incoming DTO (Data Transfer Object).
         */
        MessageReference send(Object messageDto);

        /**
         * <b>Variation of the {@link #send(Object)} method</b>, where the incoming state is sent along.
         * <p/>
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
        MessageReference send(Object messageDto, Object initialTargetSto);

        /**
         * Sends a message to a
         * {@link MatsFactory#subscriptionTerminator(String, Class, Class, MatsEndpoint.ProcessTerminatorLambda)
         * SubscriptionTerminator}, employing the publish/subscribe pattern instead of message queues (topic in JMS
         * terms). <b>This means that all of the live servers that are listening to this endpointId will receive the
         * message, and if there are no live servers, then no one will receive it.</b>
         * <p/>
         * The concurrency of a SubscriptionTerminator is always 1, as it only makes sense for there being only one
         * receiver per server - otherwise it would just mean that all of the active listeners on one server would get
         * the message, per semantics of the pub/sub.
         * <p/>
         * It is only possible to publish to SubscriptionTerminators as employing publish/subscribe for multi-stage
         * services makes no sense.
         *
         * @param messageDto
         *            the object which the target endpoint will get as its incoming DTO (Data Transfer Object).
         */
        MessageReference publish(Object messageDto);

        /**
         * <b>Variation of the {@link #publish(Object)} method</b>, where the incoming state is sent along.
         * <p/>
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
        MessageReference publish(Object messageDto, Object initialTargetSto);

        /**
         * Unstashes a Mats Flow that have been previously {@link ProcessContext#stash() stashed}. To be able to
         * deserialize the stashed bytes to instances provided to the supplied {@link ProcessLambda}, you need to
         * provide the classes of the original stage's Reply, State and Incoming objects.
         *
         * @param stash
         *            the stashed bytes which now should be unstashed
         * @param replyClass
         *            the class which the original stage originally would reply with.
         * @param stateClass
         *            the class which used for state in the original stage (endpoint) - or Void.class if none.
         * @param incomingClass
         *            the class which the original stage gets as incoming DTO.
         * @param lambda
         *            the stage lambda which should now be executed instead of the original stage lambda where stash was
         *            invoked.
         * @param <R>
         *            type of the ReplyClass
         * @param <S>
         *            type of the StateClass
         * @param <I>
         *            type of the IncomingClass
         */
        <R, S, I> void unstash(byte[] stash,
                Class<R> replyClass,
                Class<S> stateClass,
                Class<I> incomingClass,
                ProcessLambda<R, S, I> lambda);

        /**
         * Provides a way to get hold of (optional) attributes/objects from the Mats implementation, either specific to
         * the Mats implementation in use, or configured into this instance of the Mats implementation. Mirrors the same
         * method at {@link ProcessContext#getAttribute(Class, String...)}.
         * <p/>
         * Mandatory: If the Mats implementation has a transactional SQL Connection, it shall be available by
         * <code>'context.getAttribute(Connection.class)'</code>.
         *
         * @param type
         *            The expected type of the attribute
         * @param name
         *            The (optional) (hierarchical) name(s) of the attribute.
         * @param <T>
         *            The type of the attribute.
         * @return Optional of the attribute in question, the optionality pointing out that it depends on the Mats
         *         implementation or configuration whether it is available.
         */
        <T> Optional<T> getAttribute(Class<T> type, String... name);
    }

    /**
     * Reference information about the outgoing message.
     */
    interface MessageReference {
        /**
         * @return the globally unique Mats MessageId of the outgoing message - which will be available on the incoming
         *         side as {@link DetachedProcessContext#getMatsMessageId()} (where it could also be used to catch
         *         double deliveries, as it shall be utterly unique to this particular sent message). You could
         *         conceivably store this along with the order row in the database or something like this, i.e. "this is
         *         the Id of the particular Mats Message that sent this order on its way".
         */
        String getMatsMessageId();
    }

    /**
     * A hint to the underlying implementation of how much historic debugging information for the call flow should be
     * retained in the underlying protocol.
     */
    enum KeepTrace {
        /**
         * Keep all history for request and reply DTOs, and all history for state STOs.
         * <p/>
         * All calls with data and state should be kept, which e.g means that at the Terminator, all request and reply
         * DTOs, and all STOs (with their changing values between each stage of a multi-stage endpoint) will be present
         * in the underlying protocol.
         */
        FULL,

        /**
         * <b>Default</b>: Nulls out Data for other than current call while still keeping the meta-info for the call
         * history, and condenses State to a pure stack.
         * <p/>
         * This is a compromise between FULL and MINIMAL, where the DTOs and STOs except for the ones needed in the
         * stack, are "nulled out", while the call trace itself (with metadata) is still present, which e.g. means that
         * at the Terminator, you will know all endpoints and stages that the call flow traversed, but not the data or
         * state except for what is pertinent for the Terminator.
         */
        COMPACT,

        /**
         * Only keep the current call, and condenses State to a pure stack.
         * <p/>
         * Keep <b>zero</b> history, where only the current call, and only the STOs needed for the current stack, are
         * present. This e.g. means that at the Terminator, no Calls nor DTOs and STOs except for the current incoming
         * to the Terminator will be present in the underlying protocol.
         */
        MINIMAL
    }
}
