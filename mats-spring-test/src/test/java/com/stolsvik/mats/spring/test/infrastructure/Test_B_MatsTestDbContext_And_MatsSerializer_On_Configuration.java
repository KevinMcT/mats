package com.stolsvik.mats.spring.test.infrastructure;

import javax.inject.Inject;
import javax.sql.DataSource;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import com.stolsvik.mats.MatsFactory;
import com.stolsvik.mats.MatsInitiator;
import com.stolsvik.mats.serial.MatsSerializer;
import com.stolsvik.mats.serial.json.MatsSerializerJson;
import com.stolsvik.mats.spring.test.MatsTestDbContext;
import com.stolsvik.mats.spring.test.MatsTestInfrastructureConfiguration;
import com.stolsvik.mats.test.MatsTestLatch;
import com.stolsvik.mats.util.MatsFuturizer;

/**
 * Same as {@link Test_B_MatsTestDbContext_And_MatsSerializer}, only that the
 * {@link MatsTestDbContext @MatsTestDbContext} now resides on the inner static {@link Configuration @Configuration}
 * class.
 */
@RunWith(SpringRunner.class)
public class Test_B_MatsTestDbContext_And_MatsSerializer_On_Configuration {

    private static MatsSerializer<String> _matsSerializer_Configuration;

    /**
     * Create a {@link MatsSerializer}, which shall be picked up by {@link MatsTestInfrastructureConfiguration}.
     */
    @Configuration
    @MatsTestDbContext
    protected static class TestConfiguration {
        @Bean
        public MatsSerializer<String> matsSerializer() {
            // Make specific MatsSerializer (in Spring Context, which should be picked up).
            _matsSerializer_Configuration = MatsSerializerJson.create();
            return _matsSerializer_Configuration;
        }
    }

    // The Mats Test Infrastructure, with DataSource

    @Inject
    private MatsFactory _matsFactory;

    @Inject
    private MatsInitiator _matsInitiator;

    @Inject
    private MatsFuturizer _matsFuturizer;

    @Inject
    private MatsTestLatch _matsTestLatch;

    @Inject
    private DataSource _dataSource;

    // From @Configuration

    @Inject
    private MatsSerializer<String> _matsSerializer;

    @Test
    public void assertMatsInfrastructureInjected() {
        Assert.assertNotNull("MatsFactory should be in Spring context", _matsFactory);
        Assert.assertNotNull("MatsInitiator should be in Spring context", _matsInitiator);
        Assert.assertNotNull("MatsFuturizer should be in Spring context", _matsFuturizer);
        Assert.assertNotNull("MatsTestLatch should be in Spring context", _matsTestLatch);
    }

    @Test
    public void assertDataSourceInjected() {
        Assert.assertNotNull("DataSource should be in Spring context", _dataSource);
    }

    @Test
    public void assert_Specific_MatsSerializer_was_used_to_make_MatsFactory() {
        // Check that the injected MatsSerializer it is the one that should have been constructed in the Configuration
        Assert.assertSame(_matsSerializer_Configuration, _matsSerializer);

        Common.assertSameMatsSerializerInMatsFactory(_matsFactory, _matsSerializer);
    }

    @Test
    public void assert_Same_DataSource_was_used_to_make_MatsFactory() {
        // NOTE: The injected MatsFactory will be wrapped by TestSpringMatsFactoryProvider.

        Common.assertSameDataSourceInMatsFactory(_matsFactory, _dataSource);
    }
}
