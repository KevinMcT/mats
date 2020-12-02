package com.stolsvik.mats.spring.test.infrastructure;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import com.stolsvik.mats.spring.EnableMats;
import com.stolsvik.mats.spring.MatsMapping;
import com.stolsvik.mats.spring.test.MatsTestContext;
import com.stolsvik.mats.spring.test.matsmappings.SpringTestDataTO;
import com.stolsvik.mats.util.MatsFuturizer;
import com.stolsvik.mats.util.MatsFuturizer.Reply;

/**
 * Simplest test, employing Mats' {@link MatsTestContext @MatsTestContext} and SpringConfig.
 */
@RunWith(SpringRunner.class)
@MatsTestContext
public class Test_X_MatsTestContext {
    @Configuration
    @EnableMats
    static class TestConfiguration {
        @MatsMapping("Test.endpoint")
        SpringTestDataTO multiplyEndpoint(SpringTestDataTO msg) {
            return new SpringTestDataTO(msg.number * 2, msg.string + msg.string);
        }
    }

    @Inject
    private MatsFuturizer _matsFuturizer;

    @Test
    public void doTest() throws ExecutionException, InterruptedException, TimeoutException {
        SpringTestDataTO msg = new SpringTestDataTO(42, "Førtito");
        CompletableFuture<Reply<SpringTestDataTO>> replyFuture = _matsFuturizer
                .futurizeNonessential("traceId", "FromTest", "Test.endpoint", SpringTestDataTO.class, msg);

        SpringTestDataTO reply = replyFuture.get(2, TimeUnit.SECONDS).reply;

        Assert.assertEquals(msg.number * 2, reply.number, 0d);
        Assert.assertEquals(msg.string + msg.string, reply.string);
    }
}
