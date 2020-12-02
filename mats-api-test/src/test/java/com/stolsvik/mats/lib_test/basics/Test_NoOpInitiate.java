package com.stolsvik.mats.lib_test.basics;

import org.junit.ClassRule;
import org.junit.Test;

import com.stolsvik.mats.test.junit.Rule_Mats;

/**
 * Tests that it is OK to NOT do anything in initiate.
 *
 * @author Endre Stølsvik 2020-03-01 23:31 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_NoOpInitiate {
    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    @Test
    public void doTest() {
        MATS.getMatsInitiator().initiateUnchecked(init -> {
            /* no-op: Not sending/requesting/doing anything .. */
        });
    }
}
