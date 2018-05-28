package com.stolsvik.mats.lib_test.basics;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.stolsvik.mats.MatsEndpoint;
import com.stolsvik.mats.MatsInitiator.KeepTrace;
import com.stolsvik.mats.lib_test.DataTO;
import com.stolsvik.mats.lib_test.MatsBasicTest;
import com.stolsvik.mats.lib_test.StateTO;
import com.stolsvik.mats.test.MatsTestLatch.Result;

/**
 * Very similar to {@link Test_MultiLevelMultiStage}, but calls the "MidService" and "LeafService" multiple times. The
 * reason is to assert that the state-keeping and stack frames works as expected, i.e. state is null upon 0th stage
 * invocation (the endpoint itself), and gets its previous stage's state for subsequent stages. (Implicitly also that a
 * subsequent invocation of the same service doesn't accidentally get the last state from a previous invocation of the
 * same service, i.e. that the state vs. stack frame resolution works).
 * <p>
 * Basically same setup as {@link Test_MultiLevelMultiStage}, but more invocations, ref. the ASCII-artsy invocation
 * chart.
 * <p>
 * ASCII-artsy, it looks like this:
 * <p>
 * 
 * <pre>
 * [Initiator]              - init request
 *     [Master S0 - init]   - request
 *         [Mid S0 - init]  - request
 *             [Leaf]       - reply
 *         [Mid S1 - last]  - reply
 *     [Master S1]          - request
 *         [Mid S0 - init]  - request
 *             [Leaf]       - reply
 *         [Mid S1 - last]  - reply
 *     [Master S2]          - request
 *         [Leaf]           - reply
 *     [Master S3]          - request
 *         [Leaf]           - reply
 *     [Master S4]          - request
 *         [Mid S0 - init]  - request
 *             [Leaf]       - reply
 *         [Mid S1 - last]  - reply
 *     [Master S5]          - request
 *         [Mid S0 - init]  - request
 *             [Leaf]       - reply
 *         [Mid S1 - last]  - reply
 *     [Master S6 - last]   - reply
 * [Terminator]
 * </pre>
 *
 * @author Endre Stølsvik - 2018-04-22 - http://endre.stolsvik.com
 */
public class Test_ComplexLargeMultiStage extends MatsBasicTest {
    @Before
    public void setupLeafService() {
        matsRule.getMatsFactory().single(SERVICE + ".Leaf", DataTO.class, DataTO.class,
                (context, dto) -> {
                    log.debug("LEAF SERVICE MatsTrace:\n" + context.toString());
                    // Use the 'multiplier' in the request to formulate the reply.. I.e. multiply the number..!
                    return new DataTO(dto.number * dto.multiplier, dto.string + ":FromLeafService");
                });
    }

    @Before
    public void setupMidMultiStagedService() {
        MatsEndpoint<StateTO, DataTO> ep = matsRule.getMatsFactory().staged(SERVICE + ".Mid", StateTO.class,
                DataTO.class);
        ep.stage(DataTO.class, (context, dto, sto) -> {
            log.info("Incoming message for MidService: DTO:[" + dto + "], STO:[" + sto + "], context:\n" + context);
            Assert.assertEquals(new StateTO(0, 0), sto);
            // Store the multiplier in state, so that we can use it when replying in the next (last) stage.
            sto.number1 = dto.multiplier;
            // Add an important number to state..!
            sto.number2 = Math.PI;
            context.request(SERVICE + ".Leaf", new DataTO(dto.number, dto.string + ":LeafCall", 2));
        });
        ep.lastStage(DataTO.class, (context, dto, sto) -> {
            // Only assert number2, as number1 is differing between calls (it is the multiplier for MidService).
            Assert.assertEquals(Math.PI, sto.number2, 0d);
            // Use the 'multiplier' in the request to formulate the reply.. I.e. multiply the number..!
            return new DataTO(dto.number * sto.number1, dto.string + ":FromMidService");
        });
    }

    @Before
    public void setupMasterMultiStagedService() {
        MatsEndpoint<StateTO, DataTO> ep = matsRule.getMatsFactory().staged(SERVICE, StateTO.class, DataTO.class);
        ep.stage(DataTO.class, (context, dto, sto) -> {
            Assert.assertEquals(new StateTO(0, 0), sto);
            sto.number1 = Integer.MAX_VALUE;
            sto.number2 = Math.E;
            context.request(SERVICE + ".Mid", new DataTO(dto.number, dto.string + ":MidCall1", 3));
        });
        ep.stage(DataTO.class, (context, dto, sto) -> {
            Assert.assertEquals(new StateTO(Integer.MAX_VALUE, Math.E), sto);
            sto.number1 = Integer.MIN_VALUE;
            sto.number2 = Math.E * 2;
            context.request(SERVICE + ".Mid", new DataTO(dto.number, dto.string + ":MidCall2", 7));
        });
        ep.stage(DataTO.class, (context, dto, sto) -> {
            Assert.assertEquals(new StateTO(Integer.MIN_VALUE, Math.E * 2), sto);
            sto.number1 = Integer.MIN_VALUE / 2;
            sto.number2 = Math.E / 2;
            context.request(SERVICE + ".Leaf", new DataTO(dto.number, dto.string + ":LeafCall1", 4));
        });
        ep.stage(DataTO.class, (context, dto, sto) -> {
            Assert.assertEquals(new StateTO(Integer.MIN_VALUE / 2, Math.E / 2), sto);
            sto.number1 = Integer.MIN_VALUE / 4;
            sto.number2 = Math.E / 4;
            context.request(SERVICE + ".Leaf", new DataTO(dto.number, dto.string + ":LeafCall2", 6));
        });
        ep.stage(DataTO.class, (context, dto, sto) -> {
            Assert.assertEquals(new StateTO(Integer.MIN_VALUE / 4, Math.E / 4), sto);
            sto.number1 = Integer.MAX_VALUE / 2;
            sto.number2 = Math.PI / 2;
            context.request(SERVICE + ".Mid", new DataTO(dto.number, dto.string + ":MidCall3", 8));
        });
        ep.stage(DataTO.class, (context, dto, sto) -> {
            Assert.assertEquals(new StateTO(Integer.MAX_VALUE / 2, Math.PI / 2), sto);
            sto.number1 = Integer.MAX_VALUE / 4;
            sto.number2 = Math.PI / 4;
            context.request(SERVICE + ".Mid", new DataTO(dto.number, dto.string + ":MidCall4", 9));
        });
        ep.lastStage(DataTO.class, (context, dto, sto) -> {
            Assert.assertEquals(new StateTO(Integer.MAX_VALUE / 4, Math.PI / 4), sto);
            return new DataTO(dto.number * 5, dto.string + ":FromMasterService");
        });
    }

    @Before
    public void setupTerminator() {
        matsRule.getMatsFactory().terminator(TERMINATOR, DataTO.class, StateTO.class,
                (context, dto, sto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    matsTestLatch.resolve(dto, sto);
                });
    }

    @Test
    public void testWithKeepTraceMINIMAL() {
        doTest(KeepTrace.MINIMAL);
    }

    @Test
    public void testWithKeepTraceCOMPACT() {
        doTest(KeepTrace.COMPACT);
    }

    @Test
    public void testWithKeepTraceFULL() {
        doTest(KeepTrace.FULL);
    }

    private void doTest(KeepTrace keepTrace) {
        StateTO sto = new StateTO(420, 420.024);
        DataTO dto = new DataTO(42, "TheAnswer");
        matsRule.getMatsFactory().createInitiator().initiate(
                (msg) -> msg.traceId(randomId())
                        .keepTrace(keepTrace)
                        .from(INITIATOR)
                        .to(SERVICE)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // Wait synchronously for terminator to finish.
        Result<DataTO, StateTO> result = matsTestLatch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number
                * 3 * 2
                * 7 * 2
                * 4
                * 6
                * 8 * 2
                * 9 * 2
                * 5, dto.string
                + ":MidCall1" + ":LeafCall" + ":FromLeafService" + ":FromMidService"
                + ":MidCall2" + ":LeafCall" + ":FromLeafService" + ":FromMidService"
                + ":LeafCall1" + ":FromLeafService"
                + ":LeafCall2" + ":FromLeafService"
                + ":MidCall3" + ":LeafCall" + ":FromLeafService" + ":FromMidService"
                + ":MidCall4" + ":LeafCall" + ":FromLeafService" + ":FromMidService"
                + ":FromMasterService"), result.getData());
    }
}
