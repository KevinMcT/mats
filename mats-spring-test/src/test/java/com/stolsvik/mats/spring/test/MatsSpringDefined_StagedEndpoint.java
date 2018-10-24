package com.stolsvik.mats.spring.test;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import com.stolsvik.mats.MatsEndpoint;
import com.stolsvik.mats.MatsEndpoint.EndpointConfig;
import com.stolsvik.mats.MatsInitiator;
import com.stolsvik.mats.spring.Dto;
import com.stolsvik.mats.spring.MatsMapping;
import com.stolsvik.mats.spring.MatsStaged;
import com.stolsvik.mats.spring.MatsSimpleTestContext;
import com.stolsvik.mats.spring.Sto;
import com.stolsvik.mats.test.MatsTestLatch;
import com.stolsvik.mats.test.MatsTestLatch.Result;

/**
 * Basic test of the {@link MatsStaged @MatsStaged} annotation, both without and with {@link EndpointConfig} in the
 * setup method, and also testing the default-to-Void for state STO and reply-DTO.
 *
 * @author Endre Stølsvik - 2016-08-07 - http://endre.stolsvik.com
 */
@RunWith(SpringRunner.class)
@MatsSimpleTestContext
public class MatsSpringDefined_StagedEndpoint {
    public static final String ENDPOINT_ID = "mats.spring.MatsSpringDefined_MultiStageEndpoint";
    public static final String TERMINATOR = ".TERMINATOR";
    public static final String MULTI = ".Multi";
    public static final String MULTI_WITH_CONFIG = ".MultiWithConfig";
    public static final String SINGLE_VOID_VOID = ".SingleVoidVoid";
    public static final String LEAF = ".Leaf";

    @Configuration
    static class MultipleMappingsConfiguration {

        /**
         * Sets up a multi-staged endpoint using the @MatsStaged facility.
         */
        @MatsStaged(endpointId = ENDPOINT_ID + MULTI, state = SpringTestStateTO.class, reply = SpringTestDataTO.class)
        public void springMatsStagedEndpoint(MatsEndpoint<SpringTestDataTO, SpringTestStateTO> ep) {
            ep.stage(SpringTestDataTO.class, (context, sto, dto) -> {
                Assert.assertEquals(new SpringTestStateTO(0, null), sto);
                sto.number = Integer.MAX_VALUE;
                sto.string = "some state";
                context.request(ENDPOINT_ID + LEAF, dto);
            });
            ep.stage(SpringTestDataTO.class, (context, sto, dto) -> {
                Assert.assertEquals(new SpringTestStateTO(Integer.MAX_VALUE, "some state"), sto);
                sto.number = Integer.MIN_VALUE;
                sto.string = "new state";
                context.next(new SpringTestDataTO(dto.number * 3, dto.string + ":Nexted"));
            });
            ep.lastStage(SpringTestDataTO.class, (context, sto, dto) -> {
                Assert.assertEquals(new SpringTestStateTO(Integer.MIN_VALUE, "new state"), sto);
                return new SpringTestDataTO(dto.number * 5, dto.string + ":FromStaged");
            });
        }

        /**
         * Sets up a multi-stage endpoint, where the EndpointConfig is supplied directly to the annotated method.
         */
        @MatsStaged(endpointId = ENDPOINT_ID
                + MULTI_WITH_CONFIG, state = SpringTestStateTO.class, reply = SpringTestDataTO.class)
        public void springMatsStagedEndpointWithConfig(EndpointConfig<SpringTestDataTO, SpringTestStateTO> config,
                MatsEndpoint<SpringTestDataTO, SpringTestStateTO> ep) {
            // Just invoke something on the config instance to check that it is sane
            Assert.assertEquals(1, config.getConcurrency());
            // Set up the stages
            ep.stage(SpringTestDataTO.class, (context, sto, dto) -> {
                Assert.assertEquals(new SpringTestStateTO(0, null), sto);
                sto.number = Integer.MAX_VALUE;
                sto.string = "some state";
                context.request(ENDPOINT_ID + LEAF, dto);
            });
            ep.stage(SpringTestDataTO.class, (context, sto, dto) -> {
                Assert.assertEquals(new SpringTestStateTO(Integer.MAX_VALUE, "some state"), sto);
                sto.number = Integer.MIN_VALUE;
                sto.string = "new state";
                context.next(new SpringTestDataTO(dto.number * 7, dto.string + ":Nexted"));
            });
            ep.lastStage(SpringTestDataTO.class, (context, sto, dto) -> {
                Assert.assertEquals(new SpringTestStateTO(Integer.MIN_VALUE, "new state"), sto);
                return new SpringTestDataTO(dto.number * 11, dto.string + ":FromStagedWithConfig");
            });
        }

        /**
         * Sets up a Terminator-style endpoint using Staged, where both the state STO and reply DTO defaults to Void.
         */
        @MatsStaged(endpointId = ENDPOINT_ID + SINGLE_VOID_VOID)
        public void springMatsStagedEndpointWithVoidStateAndVoidReply(MatsEndpoint<Void, Void> ep,
                EndpointConfig<Void, Void> config) {
            // Just invoke something on the config instance to check that it is sane
            Assert.assertEquals(1, config.getConcurrency());
            // Set up the stages
            ep.stage(SpringTestDataTO.class, (context, sto, dto) -> {
                // Void state class should lead to null state.
                Assert.assertNull(sto);
                // Resolve directly
                _latch.resolve(sto, new SpringTestDataTO(dto.number * 17, dto.string + ":FromSingleVoidVoid"));
            });
            // Must invoke .start(), since we're not invoking lastStage.
            ep.start();
        }

        /**
         * Single-staged endpoint which is requested by the Staged Endpoint.
         */
        @MatsMapping(endpointId = ENDPOINT_ID + LEAF)
        public SpringTestDataTO springMatsSingleEndpoint_Dto(@Dto SpringTestDataTO msg) {
            return new SpringTestDataTO(msg.number * 2, msg.string + ":FromLeaf");
        }

        @Inject
        private MatsTestLatch _latch;

        /**
         * Terminator that receives the reply from the Staged Endpoint, resolving the test-Latch.
         */
        @MatsMapping(endpointId = ENDPOINT_ID + TERMINATOR)
        public void springMatsSingleEndpoint_Dto(@Dto SpringTestDataTO msg, @Sto SpringTestStateTO sto) {
            _latch.resolve(sto, msg);
        }
    }

    @Inject
    private MatsInitiator _matsInitiator;

    @Inject
    private MatsTestLatch _latch;

    @Test
    public void testStaged() {
        SpringTestDataTO dto = new SpringTestDataTO(13, "Request");
        SpringTestStateTO sto = new SpringTestStateTO(5, "two");
        _matsInitiator.initiateUnchecked(init -> {
            init.traceId("test_trace_id:" + Math.random())
                    .from(MatsSpringDefined_MultipleMappingsTest.class.getSimpleName())
                    .to(ENDPOINT_ID + MULTI)
                    .replyTo(ENDPOINT_ID + TERMINATOR, sto)
                    .request(dto);
        });

        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new SpringTestDataTO(dto.number * 2 * 3 * 5, dto.string + ":FromLeaf:Nexted:FromStaged"),
                result.getData());
    }

    @Test
    public void testStagedWithConfig() {
        SpringTestDataTO dto = new SpringTestDataTO(27, "Request-config");
        SpringTestStateTO sto = new SpringTestStateTO(9, "nine");
        _matsInitiator.initiateUnchecked(init -> {
            init.traceId("test_trace_id:" + Math.random())
                    .from(MatsSpringDefined_MultipleMappingsTest.class.getSimpleName())
                    .to(ENDPOINT_ID + MULTI_WITH_CONFIG)
                    .replyTo(ENDPOINT_ID + TERMINATOR, sto)
                    .request(dto);
        });

        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new SpringTestDataTO(dto.number * 2 * 7 * 11,
                dto.string + ":FromLeaf:Nexted:FromStagedWithConfig"), result.getData());
    }

    @Test
    public void testSingleUsingStagedAndVoidVoid() {
        SpringTestDataTO dto = new SpringTestDataTO(42, "SingleVoidVoid");
        _matsInitiator.initiateUnchecked(init -> {
            init.traceId("test_trace_id:" + Math.random())
                    .from(MatsSpringDefined_MultipleMappingsTest.class.getSimpleName())
                    .to(ENDPOINT_ID + SINGLE_VOID_VOID)
                    .send(dto);
        });

        Result<Void, SpringTestDataTO> result = _latch.waitForResult();

        Assert.assertNull(result.getState()); // Void state class should lead to null state.
        Assert.assertEquals(new SpringTestDataTO(dto.number * 17,
                dto.string + ":FromSingleVoidVoid"), result.getData());
    }
}
